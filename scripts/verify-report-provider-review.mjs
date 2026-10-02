// Read back UI decision + exercise coordinate approval against the guarded local fixture only.
import {readFile,writeFile} from 'node:fs/promises'
import {spawnSync} from 'node:child_process'
import {basename,dirname,resolve} from 'node:path'
import assert from 'node:assert/strict'
import {randomUUID} from 'node:crypto'
const [metadataPath,output]=process.argv.slice(2),meta=JSON.parse(await readFile(metadataPath,'utf8'))
assert.equal(basename(dirname(resolve(metadataPath))),`account-retention-mysql-${meta.marker}`)
const jdbc=meta.jdbcUrl.match(/^jdbc:mysql:\/\/127\.0\.0\.1:(\d+)\/(account_retention_test_[a-f0-9]{32})\?/)
assert.ok(jdbc&&Date.parse(meta.expiresAt)>Date.now()&&meta.port>1024)
function sql(query){const result=spawnSync('C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe',['--protocol=TCP','-h','127.0.0.1','-P',jdbc[1],'-u','root','--default-character-set=utf8mb4','--raw','-N','-B',jdbc[2]],{input:query,encoding:'utf8',windowsHide:true});assert.equal(result.status,0,'Fixture query failed');return result.stdout.trim()}
assert.equal(sql('SELECT marker FROM account_retention_fixture_guard.fixture_guard;'),meta.marker)
assert.ok(sql('SELECT @@datadir;').replaceAll('\\','/').includes(`/account-retention-mysql-${meta.marker}/data/`))
async function call(id,body){const response=await fetch(`http://127.0.0.1:${meta.port}/api/admin/v1/reports/${id}${body?'/approve':''}`,{method:body?'POST':'GET',headers:{Authorization:'Bearer '+meta.tokens['3'],...(body?{'Content-Type':'application/json'}:{})},body:body?JSON.stringify(body):undefined,redirect:'error',signal:AbortSignal.timeout(15000)});return {status:response.status,data:await response.json()}}
let newReportId=14,approvalChannel='browser'
if(process.argv[4]==='api'){
  const response=await fetch(`http://127.0.0.1:${meta.port}/api/v1/reports/guest`,{method:'POST',headers:{Origin:'https://preview.geupddong.com','Content-Type':'application/json','Idempotency-Key':randomUUID(),'X-Report-Guest':randomUUID()},body:JSON.stringify({reportType:'NEW_FACILITY',name:'격리 주소 조회 성공 검증',latitude:36.3661532,longitude:127.3150977}),redirect:'error',signal:AbortSignal.timeout(15000)})
  assert.equal(response.status,201);newReportId=(await response.json()).id
  assert.equal((await call(newReportId,{note:'격리 API 주소 조회 성공 검증 · 운영 반영 없음'})).status,200)
  approvalChannel='api'
}
const created=await call(newReportId);assert.equal(created.status,200);assert.equal(created.data.report.reportType,'NEW_FACILITY');assert.equal(created.data.report.status,'APPROVED','Complete the UI confirmation for test report 14 first')
const newId=created.data.report.toiletId;assert.ok(Number.isSafeInteger(newId)&&newId!==53585)
assert.ok(created.data.toilet.roadAddress||created.data.toilet.jibunAddress)
assert.equal(sql(`SELECT COUNT(*) FROM toilet WHERE toilet_id=${newId} AND data_source='USER_REPORT' AND coordinate_source='ADMIN_CONFIRMED';`),'1')
assert.equal(sql(`SELECT COUNT(*) FROM toilet_translation tr JOIN toilet t ON t.toilet_id=tr.toilet_id WHERE tr.toilet_id=${newId} AND tr.locale='ko' AND tr.name=t.name;`),'1')
assert.equal((await call(newReportId,{})).status,400)
assert.equal(sql(`SELECT COUNT(*) FROM audit_log WHERE target_id=${newReportId} AND action='REPORT_APPROVED';`),'1')
const before=await call(15);assert.equal(before.data.report.reportType,'COORDINATE_CORRECTION');assert.equal(before.data.report.status,'PENDING')
const applied={confirmedLatitude:36.3662988,confirmedLongitude:127.314569,note:'격리 실제 주소 조회·관리자 좌표 보정 검증 (운영 반영 없음)'}
const result=await call(15,applied);assert.equal(result.status,200,JSON.stringify(result.data));assert.equal(result.data.status,'APPROVED')
const after=await call(15);assert.equal(after.data.toilet.latitude,applied.confirmedLatitude);assert.equal(after.data.toilet.longitude,applied.confirmedLongitude)
assert.equal(after.data.report.latitude,before.data.report.latitude);assert.equal(after.data.report.longitude,before.data.report.longitude)
assert.ok(after.data.toilet.roadAddress||after.data.toilet.jibunAddress)
assert.equal(sql('SELECT COUNT(*) FROM coordinate_revision WHERE report_id=15;'),'1')
assert.equal(sql("SELECT COUNT(*) FROM audit_log WHERE target_id=15 AND action='REPORT_APPROVED';"),'1')
assert.equal(sql("SELECT COUNT(*) FROM toilet_translation WHERE toilet_id=53585 AND locale='ko';"),'1')
assert.equal((await call(15,applied)).status,400)
assert.equal((await call(11)).data.report.status,'PENDING','User report must remain untouched')
const evidence={checkedAt:new Date().toISOString(),providerSuccess:true,approvalChannel,newReport:{id:newReportId,toiletId:newId,status:'APPROVED',address:created.data.toilet.roadAddress||created.data.toilet.jibunAddress},coordinateReport:{id:15,status:'APPROVED',latitude:after.data.toilet.latitude,longitude:after.data.toilet.longitude,address:after.data.toilet.roadAddress||after.data.toilet.jibunAddress},checks:['new facility persisted once','Korean source synchronized','server address lookup succeeded','coordinate override persisted with original proposal intact','coordinate revision and decision audits persisted once','duplicate approvals rejected','user report 11 preserved'],productionWrites:0}
await writeFile(output,JSON.stringify(evidence,null,2)+'\n');console.log(JSON.stringify(evidence))
