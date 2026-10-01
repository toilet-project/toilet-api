"""Bounded host-local UA reconciliation. Dry-run by default; no raw identifiers in output.

Run on the DB host with Docker access. --apply requires the exact dry-run digest and
--max-event-id receipt. Only context/evidence/browser are updated. Source, identity,
sessions, bot flags and metrics are never reclassified. --snapshot emits a private,
re-aliased test snapshot with the proposed changes and never writes production.
"""
import argparse
from collections import Counter, defaultdict
import datetime as dt
import gzip
import hashlib
import hmac
import json
from pathlib import Path
import re
import subprocess
import sys
import time

KST=dt.timezone(dt.timedelta(hours=9))
LINE=re.compile(r'^([^ ]+) \S+ \S+ \[([^\]]+)\] "((?:[^"\\]|\\.)*)" (\d{3}) (?:\d+|-) "(?:[^"\\]|\\.)*" "((?:[^"\\]|\\.)*)"(?: .*)?$')
AUTO=re.compile(r'bot|crawler|spider|headless|preview|(?:^|[\s;(])(?:yeti|ads-naver|blueno|claude-user|chatgpt-user|facebookexternalhit)(?=[/\s;)]|$)')

def reverse_lines(stream):
    """Scan current plain logs newest first without loading the file into memory."""
    position=stream.seek(0,2); remainder=b''
    while position:
        width=min(position,65536);position-=width;stream.seek(position)
        chunks=(stream.read(width)+remainder).split(b'\n')
        remainder=chunks[0]
        if len(remainder)>65536: raise RuntimeError('oversized log line')
        yield from (line for line in reversed(chunks[1:]) if line)
    if remainder: yield remainder

def classify(raw):
    ua=raw.lower()
    browser=next((name for tokens,name in [(['edg/','edgios/','edga/'],'Edge'),(['samsungbrowser'],'Samsung Internet'),
        (['fxios/','firefox/'],'Firefox'),(['crios/','chrome/'],'Chrome'),(['safari/'],'Safari')]
        if any(t in ua for t in tokens)),'Other')
    if AUTO.search(ua): return 'AUTOMATION',browser
    for tokens,name in [(['kakaotalk'],'KAKAOTALK'),(['naver(','naver/'],'NAVER_APP'),(['line/'],'LINE'),
        (['instagram'],'INSTAGRAM'),(['fban/','fbav/'],'FACEBOOK'),(['gsa/'],'GOOGLE_APP'),
        (['; wv)','; wv;'],'ANDROID_WEBVIEW')]:
        if any(t in ua for t in tokens): return name,browser
    if browser!='Other': return 'BROWSER',browser
    if ('iphone' in ua or 'ipad' in ua) and 'applewebkit' in ua: return 'IOS_WEBVIEW',browser
    return 'UNKNOWN',browser

def network(value):
    if '.' in value:
        parts=value.split('.')
        return '.'.join(parts[:3])+'.0/24' if len(parts)==4 else 'unknown'
    parts=value.split(':')
    while parts and not parts[-1]: parts.pop()
    return ':'.join(parts[:4])+'::/64' if len(parts)>=4 else 'unknown'

def compatible(event,log_at):
    # DB DATETIME is KST, decoded explicitly as +09:00. No inferred time shift.
    return -1 <= log_at-event['epoch'] <= 5

def proposal(events,matches):
    result={}
    for event in events:
        choices=matches.get(event['id'],set())
        if event['evidence']!='UNCLASSIFIED' or len(choices)!=1: continue
        context,browser=next(iter(choices))
        if context=='UNKNOWN': continue
        result[event['id']]={'client':context,'browser':browser,'previousBrowser':event['browser'],'evidence':'LOG_UA'}
    return result

def digest(plan):
    return hashlib.sha256(json.dumps(plan,sort_keys=True,separators=(',',':')).encode()).hexdigest()

def bot_audit(events,plan):
    """Report exact UA evidence without guessing from geography, frequency or browser."""
    matched=[e for e in events if plan.get(e['id'],{}).get('client')=='AUTOMATION']
    return {'matchedAutomationEvents':len(matched),
            'alreadyBotEvents':sum(e['traffic']=='BOT' for e in matched),
            'additionalBotCandidates':sum(e['traffic']!='BOT' for e in matched),
            'botFlagsChanged':False}

class Database:
    def __init__(self):
        result=subprocess.run(['docker','inspect','toilet-api'],capture_output=True,text=True,timeout=8)
        if result.returncode: raise RuntimeError('API container unavailable')
        values=dict(v.split('=',1) for v in json.loads(result.stdout)[0]['Config']['Env'] if '=' in v)
        secret=values.get('ANALYTICS_VISITOR_HMAC_SECRET',values.get('JWT_SECRET',''))
        if len(secret)<32: raise RuntimeError('hash configuration unavailable')
        self.key=hashlib.sha256(('service-analytics-v1\n'+secret).encode()).digest()
        self.command=['docker','exec','-i','-e','MYSQL_PWD='+values['SPRING_DB_PASSWORD'],'toilet-mysql','mysql',
            '--protocol=socket','--default-character-set=utf8mb4','-u',values['SPRING_DB_USERNAME'],
            'toilet_db','--batch','--raw','--skip-column-names']
    def query(self,sql,write=False):
        setup="SET SESSION time_zone='+09:00'; SET SESSION MAX_EXECUTION_TIME=8000; SET SESSION innodb_lock_wait_timeout=5; "
        if not write: setup+='SET SESSION TRANSACTION READ ONLY; '
        result=subprocess.run(self.command,input=setup+'START TRANSACTION;\n'+sql+'\n'+('COMMIT;' if write else 'ROLLBACK;'),
            capture_output=True,text=True,timeout=20)
        if result.returncode: raise RuntimeError('bounded database operation failed')
        return [json.loads(s) for s in result.stdout.splitlines() if s.strip()]

def read_events(db,args):
    ready=db.query("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='service_analytics_event' AND column_name IN ('client_context','client_context_evidence');")[0]==2
    if args.apply and not ready: raise RuntimeError('V35 migration is required before apply')
    fields="client_context,'evidence',client_context_evidence" if ready else "'UNKNOWN','evidence','UNCLASSIFIED'"
    # All interpolations are fixed code, validated dates or bounded integers.
    sql="""SELECT JSON_OBJECT('id',event_id,'epoch',UNIX_TIMESTAMP(occurred_at),
        'at',DATE_FORMAT(CONVERT_TZ(occurred_at,'+09:00','+00:00'),'%%Y-%%m-%%dT%%H:%%i:%%s.%%fZ'),
        'date',occurred_date,'name',event_name,'page',page_key,'source',source_key,'channel',channel_key,
        'device',device_type,'os',os_family,'browser',browser_family,'country',country_code,'city',city_name,
        'visitor',HEX(visitor_hash),'session',HEX(session_hash),'seconds',engagement_seconds,
        'bucket',result_count_bucket,'detail',event_detail,'success',success_status,'key',key_event,
        'traffic',traffic_class,'client',%s)
        FROM service_analytics_event WHERE occurred_date BETWEEN '%s' AND '%s' AND event_id<=%d
        ORDER BY event_id LIMIT 50001;"""%(fields,args.start,args.end,args.max_event_id)
    events=db.query(sql)
    if len(events)>50000: raise RuntimeError('event limit exceeded; use a smaller date range')
    return events,ready

def reconcile(db,events,args):
    index=defaultdict(list)
    for e in events:
        if e['evidence']=='UNCLASSIFIED': index[(e['date'],e['visitor'])].append(e)
    matches=defaultdict(set); lines=0; size=0; first=None; last=None; began=time.monotonic(); partial=False
    paths=sorted(Path('/var/log/nginx').glob('access.log*'),key=lambda p:int(re.search(r'access\.log\.(\d+)',p.name)[1]) if re.search(r'access\.log\.(\d+)',p.name) else 0)
    for path in paths:
        if path.is_symlink() or not re.fullmatch(r'access\.log(?:\.\d+)?(?:\.gz)?',path.name): continue
        if path.stat().st_mtime < dt.datetime.combine(dt.date.fromisoformat(args.start),dt.time(),KST).timestamp(): continue
        with (gzip.open(path,'rb') if path.suffix=='.gz' else path.open('rb')) as stream:
            for raw in (stream if path.suffix=='.gz' else reverse_lines(stream)):
                lines+=1;size+=len(raw)
                if size>args.max_bytes or lines>1000000 or time.monotonic()-began>35:
                    partial=True;break
                if len(raw)>65536: continue
                found=LINE.fullmatch(raw.decode('utf8',errors='replace').rstrip('\r\n'))
                if not found: continue
                address,when,request,status,ua=found.groups()
                occurred=dt.datetime.strptime(when,'%d/%b/%Y:%H:%M:%S %z')
                day=occurred.astimezone(KST).date().isoformat()
                if day<args.start and path.suffix!='.gz': break
                if not args.start<=day<=args.end: continue
                first=min(first or occurred,occurred);last=max(last or occurred,occurred)
                parts=request.split(' ')
                if len(parts)!=3 or parts[0]!='POST' or parts[1].split('?',1)[0]!='/api/v1/analytics/events' or not status.startswith('2'): continue
                ua=re.sub(r'\\x([0-9a-fA-F]{2})',lambda m:chr(int(m[1],16)),ua).strip()
                visitor=hmac.new(db.key,(day[:7]+'\n'+network(address)+'\n'+ua).encode(),hashlib.sha256).hexdigest().upper()
                category=classify(ua)
                for event in index.get((day,visitor),[]):
                    if compatible(event,occurred.timestamp()): matches[event['id']].add(category)
        if partial: break
    if partial and args.apply: raise RuntimeError('partial log scan cannot be applied; narrow date range or adjust bounded scan')
    return proposal(events,matches),{'logLines':lines,'logBytes':size,'partialLogScan':partial,
        'logFrom':first.astimezone(KST).isoformat() if first else None,'logTo':last.astimezone(KST).isoformat() if last else None,
        'ambiguousEvents':sum(len(v)>1 for v in matches.values())}

def write_day(db,day,rows):
    # Lock the same dimension rows before changing events, matching batch lock order.
    sql=["DELETE FROM service_analytics_daily_dimension WHERE analytics_date='%s' AND dimension_type IN ('CLIENT_CONTEXT','CLIENT_EVIDENCE','BROWSER');"%day]
    for e,p in rows:
        if not re.fullmatch('[0-9A-F]{64}',e['visitor']): raise RuntimeError('invalid hash')
        # Fixed classifier strings only; no raw UA/URL can enter SQL or the output.
        sql.append("UPDATE service_analytics_event SET client_context='%s',client_context_evidence='LOG_UA',browser_family='%s' WHERE event_id=%d AND occurred_date='%s' AND visitor_hash=UNHEX('%s') AND client_context_evidence='UNCLASSIFIED';"%(p['client'],p['browser'],e['id'],day,e['visitor']))
    for dimension,column,extra in [('CLIENT_CONTEXT','client_context'," AND event_name='session_start'"),('CLIENT_EVIDENCE','client_context_evidence'," AND event_name='session_start'"),('BROWSER','browser_family','')]:
        sql.append("""INSERT INTO service_analytics_daily_dimension(analytics_date,dimension_type,dimension_key,dimension_label,active_users,views,sessions,event_count,key_events,engagement_seconds)
            SELECT occurred_date,'%s',%s,%s,COUNT(DISTINCT visitor_hash),SUM(event_name='page_view'),COUNT(DISTINCT session_hash),COUNT(*),SUM(key_event),SUM(engagement_seconds)
            FROM service_analytics_event WHERE occurred_date='%s' AND traffic_class<>'BOT'%s GROUP BY occurred_date,%s;"""%(dimension,column,column,day,extra,column))
    db.query('\n'.join(sql),write=True)

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--from',dest='start',required=True)
    parser.add_argument('--to',dest='end',required=True)
    parser.add_argument('--max-event-id',type=int,default=9223372036854775807)
    parser.add_argument('--max-bytes',type=int,default=150_000_000)
    parser.add_argument('--apply',action='store_true')
    parser.add_argument('--expected-plan-sha')
    parser.add_argument('--snapshot',action='store_true')
    args=parser.parse_args()
    start,end=dt.date.fromisoformat(args.start),dt.date.fromisoformat(args.end)
    if not 0<=(end-start).days<=34 or args.max_event_id<1 or not 1<=args.max_bytes<=300_000_000: raise RuntimeError('invalid bounds')
    if args.apply and (args.snapshot or not args.expected_plan_sha or args.max_event_id==9223372036854775807): raise RuntimeError('apply needs a bounded dry-run receipt')
    db=Database(); events,ready=read_events(db,args); plan,coverage=reconcile(db,events,args)
    report={'mode':'apply' if args.apply else 'dry-run','schemaReady':ready,'from':args.start,'to':args.end,
        'eventCount':len(events),'maxEventId':max((e['id'] for e in events),default=0),'proposedEvents':len(plan),
        'remainingUnclassified':sum(e['evidence']=='UNCLASSIFIED' and e['id'] not in plan for e in events),
        'planSha':digest(plan),'contexts':dict(Counter(p['client'] for p in plan.values())),
        'botAudit':bot_audit(events,plan),**coverage}
    if args.apply:
        if args.expected_plan_sha!=report['planSha']: raise RuntimeError('dry-run plan changed; inspect a fresh plan')
        by_day=defaultdict(list)
        for e in events:
            if e['id'] in plan: by_day[e['date']].append((e,plan[e['id']]))
        completed=[]
        for day,rows in sorted(by_day.items()):
            write_day(db,day,rows);completed.append(day)
        report['completedDates']=completed
    if args.snapshot:
        print(json.dumps({'kind':'meta','mode':'production-snapshot','capturedAt':dt.datetime.now(dt.timezone.utc).isoformat(),
            'proposedBackfill':True,'productionWrites':False,'reconciliation':report}))
        visitors={};sessions={}
        for ordinal,e in enumerate(events,1):
            if e['id'] in plan: e.update({k:v for k,v in plan[e['id']].items() if k!='previousBrowser'})
            e['v']=visitors.setdefault(e.pop('visitor'),len(visitors)+1)
            e['s']=sessions.setdefault(e.pop('session'),len(sessions)+1)
            e.pop('epoch');e['id']=ordinal;e['kind']='event'
            print(json.dumps(e,ensure_ascii=False,separators=(',',':')))
    else: print(json.dumps(report,ensure_ascii=False))

if __name__=='__main__':
    try: main()
    except Exception as error:
        # Only a fixed allowlist of messages; never expose subprocess commands or credentials.
        safe={'dry-run plan changed; inspect a fresh plan','V35 migration is required before apply',
              'partial log scan cannot be applied; narrow date range or adjust bounded scan'}
        sys.stderr.write((str(error) if str(error) in safe else 'Context reconciliation stopped; no raw data or credentials logged.')+'\n')
        sys.exit(1)
