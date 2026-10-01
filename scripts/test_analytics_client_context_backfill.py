import datetime as dt
import unittest
import io
from unittest.mock import Mock
from scripts.analytics_client_context_backfill import classify, compatible, proposal, digest, write_day, read_events, reverse_lines, bot_audit, KST

class ClientContextBackfillTest(unittest.TestCase):
    def test_bot_audit_never_guesses_or_overwrites_existing_traffic(self):
        events=[{'id':1,'traffic':'BOT'}, {'id':2,'traffic':'LEGACY'}, {'id':3,'traffic':'UNFLAGGED'}, {'id':4,'traffic':'LEGACY'}]
        plan={1:{'client':'AUTOMATION'},2:{'client':'AUTOMATION'},3:{'client':'BROWSER'}}
        result=bot_audit(events,plan)
        self.assertEqual({'matchedAutomationEvents':2,'alreadyBotEvents':1,'additionalBotCandidates':1,'botFlagsChanged':False},result)
        self.assertEqual('LEGACY',events[1]['traffic'])

    def test_newest_first_and_sql_timestamp_format(self):
        self.assertEqual([b'third',b'second',b'first'],list(reverse_lines(io.BytesIO(b'first\nsecond\nthird\n'))))
        db=Mock();db.query.side_effect=[[0],[]]
        args=Mock(apply=False,start='2026-09-24',end='2026-09-30',max_event_id=42)
        self.assertEqual(([],False),read_events(db,args))
        self.assertIn("'%Y-%m-%dT%H:%i:%s.%fZ'",db.query.call_args.args[0])
    def test_app_and_browser_are_distinct(self):
        self.assertEqual(('KAKAOTALK','Safari'),classify('iPhone AppleWebKit KAKAOTALK/26 Safari/604'))
        self.assertEqual(('LINE','Other'),classify('iPhone AppleWebKit Line/15'))
        self.assertEqual(('BROWSER','Chrome'),classify('iPhone CriOS/140 Safari/604'))
        self.assertEqual(('BROWSER','Firefox'),classify('iPhone FxiOS/140 Safari/604'))
        self.assertEqual(('BROWSER','Edge'),classify('iPhone EdgiOS/140 Safari/604'))
        self.assertEqual(('NAVER_APP','Other'),classify('iPhone NAVER(inapp; search)'))
        self.assertEqual(('AUTOMATION','Chrome'),classify('HeadlessChrome/140 Safari/604'))
        self.assertEqual(('UNKNOWN','Other'),classify('unidentified'))

    def test_exact_kst_time_and_no_unbounded_time_inference(self):
        stamp=dt.datetime(2026,9,30,23,40,tzinfo=KST).timestamp()
        self.assertTrue(compatible({'epoch':stamp},stamp+1))
        self.assertFalse(compatible({'epoch':stamp},stamp+32400))
        self.assertFalse(compatible({'epoch':stamp},stamp+6))

    def test_unmatched_ambiguous_unknown_and_previously_classified_remain_untouched(self):
        events=[{'id':i,'browser':'Safari','evidence':'UNCLASSIFIED'} for i in range(1,6)]
        events[4]['evidence']='REQUEST_UA'
        matches={1:{('KAKAOTALK','Safari')},2:{('LINE','Other'),('BROWSER','Safari')},3:{('UNKNOWN','Other')},5:{('LINE','Other')}}
        result=proposal(events,matches)
        self.assertEqual([1],list(result))
        self.assertEqual('LOG_UA',result[1]['evidence'])
        events[0]['evidence']='LOG_UA'
        self.assertEqual({},proposal(events,matches))

    def test_receipt_and_write_scope(self):
        plan={1:{'client':'LINE','browser':'Other','previousBrowser':'Safari','evidence':'LOG_UA'}}
        self.assertEqual(digest(plan),digest(dict(plan)))
        self.assertNotEqual(digest(plan),digest({}))
        db=Mock();e={'id':1,'visitor':'A'*64}
        write_day(db,'2026-09-30',[(e,plan[1])])
        sql=db.query.call_args.args[0]
        self.assertIn("client_context_evidence='UNCLASSIFIED'",sql)
        self.assertIn("dimension_type IN ('CLIENT_CONTEXT','CLIENT_EVIDENCE','BROWSER')",sql)
        self.assertNotIn('SET source_key',sql)
        self.assertNotIn('SET traffic_class',sql)
        self.assertNotIn('DELETE FROM service_analytics_event',sql)
        self.assertNotIn('service_analytics_daily_summary',sql)
        self.assertTrue(db.query.call_args.kwargs['write'])

if __name__=='__main__': unittest.main()
