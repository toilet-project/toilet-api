// API integration against the expiring native MySQL fixture only. Never prints credentials.
import {readFile,writeFile} from 'node:fs/promises'
import {dirname,basename,resolve} from 'node:path'
import {randomUUID} from 'node:crypto'
import assert from 'node:assert/strict'
const [input,output]=process.argv.slice(2)
const meta=JSON.parse(await readFile(input,'utf8'))
assert.equal(dirname(resolve(input)),dirname(resolve(output)))
assert.equal(basename(dirname(resolve(input))),`account-retention-mysql-${meta.marker}`)
assert.match(meta.jdbcUrl,/^jdbc:mysql:\/\/127\.0\.0\.1:\d+\/account_retention_test_[a-f0-9]{32}\?/)
assert.ok(Date.parse(meta.expiresAt)>Date.now()&&Date.parse(meta.expiresAt)-Date.now()<=7200000)
const origin=`http://127.0.0.1:${meta.port}`
async function call(path,{method='GET',body,token=meta.tokens['3'],headers={}}={}){
  const response=await fetch(origin+path,{method,headers:{...(token?{Authorization:`Bearer ${token}`} :{}),...(body?{'Content-Type':'application/json'}:{}),...headers},body:body?JSON.stringify(body):undefined})
  return {status:response.status,data:await response.json().catch(()=>null)}
}
const created=await call('/api/v1/reports/guest',{method:'POST',token:null,headers:{Origin:'https://preview.geupddong.com','X-Report-Guest':randomUUID(),'Idempotency-Key':randomUUID()},body:{reportType:'FACILITY_MISSING',toiletId:53586,reason:'격리 시설 조치 회귀 검증'}})
assert.equal(created.status,201)
const reportId=created.data.id,path=`/api/admin/v1/reports/${reportId}/actions`
let state=(await call(path)).data
assert.equal(state.reportStatus,'PENDING');assert.equal(state.facility.visibilityStatus,'VISIBLE')
const originalLatitude=state.facility.latitude
const historyCount=state.history.length
const pendingFailure=await call(path,{method:'POST',body:{action:'UPDATE_OPENING_HOURS',reason:'대기 제보 실패 원자성 검증',requestId:randomUUID(),expectedState:state.expectedState,openingHours:{openingPolicy:'SCHEDULED',open24h:false,holidayPolicy:'UNKNOWN',schedules:[]}}})
assert.equal(pendingFailure.status,400);assert.equal((await call(path)).data.reportStatus,'PENDING');assert.equal((await call(path)).data.expectedState,state.expectedState)
const initialState=state.expectedState
const hide={action:'HIDE_TEMPORARILY',reason:'격리 시험: 현장 확인 후 임시 숨김',requestId:randomUUID(),expectedState:initialState}
const noAuth=await call(path,{method:'POST',body:hide,token:null});assert.ok([401,403].includes(noAuth.status))
const member=await call(path,{method:'POST',body:hide,token:meta.tokens['1']});assert.equal(member.status,403)
const hidden=await call(path,{method:'POST',body:hide});assert.equal(hidden.status,200)
state=hidden.data;assert.equal(state.facility.visibilityStatus,'HIDDEN_TEMPORARY');assert.equal(state.reportStatus,'APPROVED');assert.equal(state.history.length,historyCount+1)
const replay=await call(path,{method:'POST',body:hide});assert.equal(replay.status,200);assert.equal(replay.data.history.length,historyCount+1)
assert.equal((await call(path,{method:'POST',body:{...hide,reason:'changed'}})).status,409)
assert.equal((await call(path,{method:'POST',body:{...hide,action:'RESTORE',requestId:randomUUID()}})).status,409)
async function apply(action,extra={}){
  const response=await call(path,{method:'POST',body:{action,reason:`격리 시험: ${action}`,requestId:randomUUID(),expectedState:state.expectedState,...extra}})
  assert.equal(response.status,200,JSON.stringify(response.data));state=response.data
}
await apply('RESTORE');assert.equal(state.facility.visibilityStatus,'VISIBLE');assert.equal(state.history.length,historyCount+2)
const beforeFailure=state.expectedState
const invalid=await call(path,{method:'POST',body:{action:'UPDATE_OPENING_HOURS',reason:'실패 롤백 검증',requestId:randomUUID(),expectedState:state.expectedState,openingHours:{openingPolicy:'SCHEDULED',open24h:false,holidayPolicy:'UNKNOWN',schedules:[]}}})
assert.equal(invalid.status,400);assert.equal((await call(path)).data.expectedState,beforeFailure)
const firstStart=String(state.openingHours?.schedules?.[0]?.startTime).startsWith('09:00')?'09:30':'09:00'
await apply('UPDATE_OPENING_HOURS',{openingHours:{openingPolicy:'SCHEDULED',open24h:false,holidayPolicy:'CLOSED',schedules:[{dayOfWeek:1,slotIndex:0,startTime:firstStart,endTime:'18:00',crossesMidnight:false,closed:false},{dayOfWeek:2,slotIndex:0,startTime:'20:00',endTime:'02:00',crossesMidnight:true,closed:false}]}})
assert.equal(state.openingHours.manualOverride,true);assert.equal(state.openingHours.schedules.length,2)
const invalidCoordinate=await call(path,{method:'POST',body:{action:'UPDATE_COORDINATES',reason:'범위 오류 검증',requestId:randomUUID(),expectedState:state.expectedState,latitude:0,longitude:0}})
assert.equal(invalidCoordinate.status,400);assert.equal((await call(path)).data.history.length,historyCount+3)
await apply('UPDATE_COORDINATES',{latitude:originalLatitude===36.3661?36.36612:36.3661,longitude:127.3145})
assert.equal(state.facility.coordinateSource,'ADMIN_CONFIRMED');assert.ok(state.facility.roadAddress || state.facility.jibunAddress)
assert.equal(state.history.length,historyCount+4)
const detail=await call(`/api/admin/v1/reports/${reportId}`)
assert.equal(detail.data.report.status,'APPROVED');assert.notEqual(Number(detail.data.report.latitude),state.facility.latitude)
const evidence={reportId,toiletId:53586,actions:state.history.map(v=>v.action),visibility:state.facility.visibilityStatus,openingPolicy:state.openingHours.openingPolicy,manualOverride:state.openingHours.manualOverride,coordinateSource:state.facility.coordinateSource,checks:['unauthenticated','member-denied','hide-approves-atomically','restore-on-approved','idempotency','request-reuse-conflict','stale-conflict','pending-failure-not-approved','invalid-hours-rollback','confirmed-schedule','invalid-coordinate-rollback','provider-coordinate-address','original-report-preserved'],checkedAt:new Date().toISOString()}
await writeFile(output,JSON.stringify(evidence,null,2),{flag:'wx'})
console.log(JSON.stringify(evidence))
