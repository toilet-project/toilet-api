package com.example.toiletapi.growth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.geupddong.account.LocalMaintenanceLease;
import com.geupddong.growth.GrowthLedger;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Supplier;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Bounded host-only growth operations. The CLI never creates or accepts an administrator token. */
public final class GrowthMaintenanceCli {
    private static final int MAX_BATCH=50;
    private final JdbcTemplate jdbc;
    private final GrowthService growth;
    private final TransactionTemplate read;
    private final TransactionTemplate write;
    @FunctionalInterface interface Lease extends AutoCloseable { @Override void close(); }
    private final Supplier<Lease> lease;

    GrowthMaintenanceCli(DriverManagerDataSource dataSource) {
        this(dataSource,productionLease(new StandardEnvironment()));
    }

    GrowthMaintenanceCli(DriverManagerDataSource dataSource,Supplier<Lease> lease) {
        jdbc=new JdbcTemplate(dataSource);
        growth=GrowthService.offline(jdbc);
        this.lease=lease;
        var manager=new DataSourceTransactionManager(dataSource);
        read=new TransactionTemplate(manager);read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        write=new TransactionTemplate(manager);
        write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public static void main(String[] args) {
        try {
            var env=new StandardEnvironment();
            var dataSource=new DriverManagerDataSource(env.getRequiredProperty("SPRING_DB_URL"),
                    env.getRequiredProperty("SPRING_DB_USERNAME"),env.getRequiredProperty("SPRING_DB_PASSWORD"));
            var properties=new Properties();
            properties.setProperty("connectionTimeZone","+09:00");
            properties.setProperty("forceConnectionTimeZoneToSession","true");
            dataSource.setConnectionProperties(properties);
            Map<String,Object> result=new GrowthMaintenanceCli(dataSource).run(args);
            System.out.println(new ObjectMapper().writeValueAsString(result));
        } catch(Exception failure) {
            String code=failure instanceof GrowthFailure growth ? growth.code() : failure.getClass().getSimpleName();
            System.err.println("GROWTH_MAINTENANCE_FAILED:"+code);
            System.exit(1);
        }
    }

    Map<String,Object> run(String[] args) {
        // The host transition already owns this same POSIX lease while checking policy.
        // This operation is read-only and cannot initialize or award anything.
        if(args.length>0 && "policy-preview".equals(args[0])) return runLocked(args);
        try(Lease ignored=lease.get()) {return runLocked(args);}
    }

    private Map<String,Object> runLocked(String[] args) {
        if(args.length==0) throw new IllegalArgumentException("operation required");
        return switch(args[0]) {
            case "policy-preview" -> {
                requireLength(args,1);
                yield read.execute(tx->policyPreview());
            }
            case "policy-initialize" -> {
                requireLength(args,6);requireApply(args[5]);
                String version=version(args[1]);int targets=positive(args[2],500);
                int regions=positive(args[3],16);String digest=digestArg(args[4]);
                yield write.execute(tx->initialize(version,targets,regions,digest));
            }
            case "batch-preview" -> {
                requireLength(args,3);
                yield read.execute(tx->previewBatch(cursor(args[1]),size(args[2])));
            }
            case "batch-apply" -> {
                requireLength(args,6);requireApply(args[5]);
                String version=version(args[1]);long after=cursor(args[2]);int size=size(args[3]);
                int excluded=nonnegative(args[4],1_000_000);
                yield applyBatch(version,after,size,excluded);
            }
            case "ledger-check" -> {
                requireLength(args,1);
                yield read.execute(tx->ledgerCheck());
            }
            default -> throw new IllegalArgumentException("unknown operation");
        };
    }

    private Map<String,Object> policyPreview() {
        var preview=growth.previewPolicy();
        GrowthLedger.Policy frozen=GrowthLedger.policy(jdbc,preview.version());
        List<GrowthLedger.Target> targets=frozen==null?GrowthLedger.currentTargets(jdbc):frozen.targets();
        var result=new LinkedHashMap<String,Object>();
        result.put("operation","policy-preview");result.put("version",preview.version());
        result.put("initialized",preview.initialized());result.put("targetDistricts",preview.targetDistricts());
        result.put("regions",preview.regions());result.put("targetDigest",targetDigest(targets));
        return result;
    }

    private Map<String,Object> initialize(String version,int expectedTargets,int expectedRegions,String expectedDigest) {
        var before=policyPreview();
        if(!version.equals(before.get("version")) || expectedTargets!=(int)before.get("targetDistricts")
                || expectedRegions!=(int)before.get("regions") || !expectedDigest.equals(before.get("targetDigest")))
            throw new IllegalStateException("GROWTH_POLICY_PREVIEW_CHANGED");
        growth.initializePolicy(true);
        var after=policyPreview();
        if(!Boolean.TRUE.equals(after.get("initialized")) || !expectedDigest.equals(after.get("targetDigest")))
            throw new IllegalStateException("GROWTH_POLICY_FREEZE_CHANGED");
        after.put("operation","policy-initialize");
        return after;
    }

    private record Candidates(List<Long> ids,Long next,boolean hasMore) { }
    private Candidates candidates(long after,int size) {
        List<Long> queried=jdbc.query("SELECT user_id FROM app_user WHERE status='ACTIVE' AND user_id>? ORDER BY user_id LIMIT ?",
                (rs,i)->rs.getLong(1),after,size+1);
        List<Long> ids=List.copyOf(queried.subList(0,Math.min(size,queried.size())));
        return new Candidates(ids,ids.isEmpty()?null:ids.getLast(),queried.size()>size);
    }

    private Map<String,Object> previewBatch(long after,int size) {
        return previewBatch(after,candidates(after,size));
    }

    private Map<String,Object> previewBatch(long after,Candidates page) {
        int linked=0,eligible=0,pending=0;
        long expected=0,current=0,delta=0;
        Map<String,Integer> excluded=new LinkedHashMap<>();
        for(long userId:page.ids()) {
            var preview=growth.previewUser(userId);
            linked+=preview.linkedReviews();eligible+=preview.eligibleReviews();
            pending+=preview.pendingRegionFacilities();expected+=preview.expectedXp();
            current+=preview.currentXp();delta+=preview.deltaXp();
            preview.excludedReasons().forEach((reason,count)->excluded.merge(reason,count,Integer::sum));
        }
        var result=new LinkedHashMap<String,Object>();
        result.put("operation","batch-preview");result.put("policyVersion",growth.previewPolicy().version());
        result.put("afterUserId",Long.toString(after));result.put("nextAfterUserId",page.next()==null?null:page.next().toString());
        result.put("hasMore",page.hasMore());result.put("users",page.ids().size());
        result.put("linkedReviews",linked);result.put("eligibleReviews",eligible);
        result.put("excludedReasons",excluded);result.put("pendingRegionFacilities",pending);
        result.put("expectedXp",expected);result.put("currentXp",current);result.put("deltaXp",delta);
        return result;
    }

    private Map<String,Object> applyBatch(String expectedVersion,long after,int size,int expectedExcluded) {
        GrowthLedger.Policy policy=read.execute(tx->GrowthLedger.policy(jdbc,expectedVersion));
        if(policy==null || !GrowthService.POLICY_VERSION.equals(expectedVersion))
            throw new IllegalStateException("GROWTH_POLICY_NOT_INITIALIZED");
        var page=read.execute(tx->candidates(after,size));
        var planned=read.execute(tx->previewBatch(after,page));
        Map<?,?> reasons=(Map<?,?>)planned.get("excludedReasons");
        int excluded=reasons.values().stream().mapToInt(value->(int)value).sum();
        if(excluded!=expectedExcluded) throw new IllegalStateException("GROWTH_EXCLUSION_COUNT_CHANGED");
        int applied=0,skipped=0,earned=0,revoked=0;
        long totalXp=0;
        for(long userId:page.ids()) {
            try {
                var result=write.execute(tx->growth.backfillUser(userId,true));
                applied++;earned+=result.reconcile().earnedCount();revoked+=result.reconcile().revokedCount();
                totalXp+=result.reconcile().totalXp();
            } catch(GrowthFailure failure) {
                if(failure.status()!=403 && failure.status()!=401) throw failure;
                skipped++; // Account changed state since candidate enumeration.
            }
        }
        var result=new LinkedHashMap<String,Object>();
        result.put("operation","batch-apply");result.put("policyVersion",expectedVersion);
        result.put("afterUserId",Long.toString(after));result.put("nextAfterUserId",page.next()==null?null:page.next().toString());
        result.put("hasMore",page.hasMore());result.put("appliedUsers",applied);result.put("skippedUsers",skipped);
        result.put("earnedAwards",earned);result.put("revokedAwards",revoked);result.put("totalXpForAppliedUsers",totalXp);
        return result;
    }

    private Map<String,Object> ledgerCheck() {
        var result=new LinkedHashMap<String,Object>();
        result.put("operation","ledger-check");
        result.put("accounts",jdbc.queryForObject("SELECT COUNT(*) FROM growth_account",Long.class));
        result.put("events",jdbc.queryForObject("SELECT COUNT(*) FROM growth_xp_event",Long.class));
        result.put("totalXp",jdbc.queryForObject("SELECT COALESCE(SUM(total_xp),0) FROM growth_account",Long.class));
        long balanceMismatches=jdbc.queryForObject("""
                SELECT COUNT(*) FROM growth_account a WHERE a.total_xp <>
                  (SELECT COALESCE(SUM(e.delta_xp),0) FROM growth_xp_event e WHERE e.user_id=a.user_id)
                """,Long.class);
        long missingAccounts=jdbc.queryForObject("""
                SELECT COUNT(DISTINCT e.user_id) FROM growth_xp_event e
                LEFT JOIN growth_account a ON a.user_id=e.user_id WHERE a.user_id IS NULL
                """,Long.class);
        result.put("balanceMismatches",balanceMismatches+missingAccounts);
        result.put("eventUsersWithoutAccount",missingAccounts);
        result.put("activeAwardsWithoutSource",jdbc.queryForObject(
                "SELECT COUNT(*) FROM growth_award WHERE active=TRUE AND award_key IS NULL",Long.class));
        result.put("staleReviewEvidence",jdbc.queryForObject("""
                SELECT COUNT(*) FROM growth_review_evidence e
                LEFT JOIN toilet_review r ON r.review_key=e.review_key
                WHERE r.review_id IS NULL OR r.author_user_id IS NULL OR r.author_user_id<>e.user_id
                  OR r.author_detached=TRUE
                """,Long.class));
        return result;
    }

    private static Supplier<Lease> productionLease(StandardEnvironment env) {
        return ()->{
            if(!"LOCAL".equals(env.getProperty("ERASURE_LEDGER_PROVIDER"))
                    || !env.getProperty("ERASURE_MAINTENANCE_LOCK_ENABLED",Boolean.class,false)
                    || !"/home/luha/geupddong-maintenance".equals(env.getProperty("ERASURE_MAINTENANCE_DIRECTORY")))
                throw new IllegalStateException("GROWTH_MAINTENANCE_LOCK_NOT_CONFIGURED");
            var held=LocalMaintenanceLease.acquire(
                    Path.of("/home/luha/geupddong-maintenance/.maintenance.lock"),1000);
            return held::close;
        };
    }

    private static String targetDigest(List<GrowthLedger.Target> targets) {
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            targets.stream().sorted(java.util.Comparator.comparing(GrowthLedger.Target::code)).forEach(target->{
                String row=target.code()+"\t"+target.sidoCode()+"\t"+target.displayName()+"\n";
                digest.update(row.getBytes(StandardCharsets.UTF_8));
            });
            return HexFormat.of().formatHex(digest.digest());
        } catch(Exception unavailable) {throw new IllegalStateException("GROWTH_DIGEST_UNAVAILABLE",unavailable);}
    }
    private static void requireLength(String[] args,int count) {
        if(args.length!=count) throw new IllegalArgumentException("invalid argument count");
    }
    private static void requireApply(String value) {
        if(!"--apply".equals(value)) throw new IllegalArgumentException("explicit apply flag required");
    }
    private static String version(String value) {
        if(!GrowthService.POLICY_VERSION.equals(value)) throw new IllegalArgumentException("policy version mismatch");
        return value;
    }
    private static String digestArg(String value) {
        if(value==null || !value.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("invalid target digest");
        return value;
    }
    private static int nonnegative(String value,int max) {
        int parsed=Integer.parseInt(value);
        if(parsed<0 || parsed>max) throw new IllegalArgumentException("invalid number");
        return parsed;
    }
    private static int positive(String value,int max) {
        int parsed=nonnegative(value,max);
        if(parsed==0) throw new IllegalArgumentException("invalid number");
        return parsed;
    }
    private static int size(String value) {return positive(value,MAX_BATCH);}
    private static long cursor(String value) {
        long parsed=Long.parseLong(value);
        if(parsed<0)throw new IllegalArgumentException("invalid cursor");
        return parsed;
    }
}
