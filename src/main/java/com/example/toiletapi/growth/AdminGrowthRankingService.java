package com.example.toiletapi.growth;

import com.example.toiletapi.auth.privacy.EmailProtection;
import com.geupddong.growth.GrowthLevelPolicy;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminGrowthRankingService {
    private final AdminGrowthRankingRepository repository;
    private final EmailProtection emailProtection;
    public AdminGrowthRankingService(AdminGrowthRankingRepository repository,EmailProtection emailProtection) {
        this.repository=repository;this.emailProtection=emailProtection;
    }
    @Transactional(readOnly=true)
    public AdminGrowthRankingModels.Page search(AdminGrowthRankingModels.SearchRequest request) {
        if(request==null)throw invalid();
        String keyword=request.keyword()==null?null:request.keyword().strip();
        if(keyword!=null && (keyword.length()>320 || keyword.codePoints().anyMatch(Character::isISOControl)))throw invalid();
        if(keyword!=null && keyword.isEmpty())keyword=null;
        String sort=request.sort()==null?"xp_desc":request.sort();
        int page=request.page()==null?0:request.page(),size=request.size()==null?10:request.size();
        if(!Set.of("xp_desc","xp_asc").contains(sort) || page<0 || page>100000 || size<1 || size>50)throw invalid();
        String hash=emailProtection.searchHash(keyword);
        long total=repository.count(keyword,hash);
        var items=repository.page(keyword,hash,"xp_asc".equals(sort),size,(long)page*size);
        return new AdminGrowthRankingModels.Page(List.copyOf(items),page,size,total,(int)((total+size-1)/size),
                AdminGrowthRankingModels.SCOPE,AdminGrowthRankingModels.EXCLUDED_STATUSES);
    }
    @Transactional(readOnly=true)
    public AdminGrowthRankingModels.Distribution distribution() {
        long[] counts=repository.distribution();var tiers=GrowthLevelPolicy.TIERS;
        var buckets=new ArrayList<AdminGrowthRankingModels.Bucket>();long total=0;
        for(int i=0;i<tiers.size();i++) {
            var tier=tiers.get(i);total+=counts[i];
            buckets.add(new AdminGrowthRankingModels.Bucket(tier.rank(),tier.rankName(),tier.minXp(),
                    i+1<tiers.size()?tiers.get(i+1).minXp()-1:null,counts[i]));
        }
        return new AdminGrowthRankingModels.Distribution(total,List.copyOf(buckets),
                AdminGrowthRankingModels.SCOPE,AdminGrowthRankingModels.EXCLUDED_STATUSES);
    }
    private static IllegalArgumentException invalid() {return new IllegalArgumentException("경험치 조회 조건을 확인해 주세요.");}
}
