// End-to-end controller / authorization / persisted-state checks on the isolated MySQL only.
import {readFile,writeFile} from 'node:fs/promises'
import {randomUUID} from 'node:crypto'
import {basename,dirname,resolve} from 'node:path'
import assert from 'node:assert/strict'
const [metadataPath,output]=process.argv.slice(2),meta=JSON.parse(await readFile(metadataPath,'utf8'))
assert.equal(basename(dirname(resolve(metadataPath))),`account-retention-mysql-${meta.marker}`)
assert.match(meta.jdbcUrl,/^jdbc:mysql:\/\/127\.0\.0\.1:\d+\/account_retention_test_[a-f0-9]{32}\?/)
assert.ok(meta.port>1024&&meta.port!==3306&&Date.parse(meta.expiresAt)>Date.now())
async function call(path,{body,admin=false,key=randomUUID(),guest=randomUUID()}={}){
  const result=await fetch(`http://127.0.0.1:${meta.port}${path}`,{method:body?'POST':'GET',redirect:'error',headers:{...(admin?{Authorization:'Bearer '+meta.tokens['3']}:{}),...(body?{'Content-Type':'application/json',Origin:'https://preview.geupddong.com','Idempotency-Key':key,'X-Report-Guest':guest}:{})},body:body?JSON.stringify(body):undefined,signal:AbortSignal.timeout(12000)})
  return {status:result.status,data:await result.json()}
}
const checks=[],receipts=[]
for(const [type,action] of [['TEMPORARILY_CLOSED','approve'],['FACILITY_MISSING','reject']]){
  const key=randomUUID(),guest=randomUUID(),body={toiletId:53585,reportType:type},created=await call('/api/v1/reports/guest',{body,key,guest})
  assert.equal(created.status,201);const id=created.data.id,detail=await call(`/api/admin/v1/reports/${id}`,{admin:true})
  assert.equal(detail.status,200);assert.equal(detail.data.reporterDisplayName,'비회원')
  assert.match(detail.data.report.openTimeDetail,/09:00.*18:00/)
  assert.equal((await call(`/api/admin/v1/reports/${id}/${action}`,{body:{}})).status,401)
  const decided=await call(`/api/admin/v1/reports/${id}/${action}`,{admin:true,body:{note:'격리 통합 검증 · 운영 데이터 변경 없음'}})
  assert.equal(decided.status,200);const status=action==='approve'?'APPROVED':'REJECTED'
  const after=await call(`/api/admin/v1/reports/${id}`,{admin:true});assert.equal(after.data.report.status,status)
  assert.deepEqual(after.data.toilet,detail.data.toilet);assert.equal(after.data.report.openTimeDetail,detail.data.report.openTimeDetail)
  assert.equal((await call(`/api/admin/v1/reports/${id}/${action}`,{admin:true,body:{}})).status,400)
  assert.equal((await call('/api/v1/reports/guest',{body,key,guest})).data.id,id)
  const search=await call(`/api/admin/v1/reports/search?status=${status}&keyword=${encodeURIComponent('열매마을')}&sort=NEWEST`,{admin:true})
  assert.ok(search.data.items.some(item=>item.id===id))
  receipts.push({id,type,status});checks.push(type+'-saved-reviewed-filtered-without-facility-mutation')
}
// Actual provider key is intentionally absent until explicitly connected. Failure must roll back.
for(const type of ['NEW_FACILITY','COORDINATE_CORRECTION']){
  const body={reportType:type,latitude:36.3661532,longitude:127.3150977,...(type==='NEW_FACILITY'?{name:'격리 관리자 승인 실패 검증'}:{toiletId:53585})}
  const created=await call('/api/v1/reports/guest',{body});assert.equal(created.status,201);const id=created.data.id
  const before=await call(`/api/admin/v1/reports/${id}`,{admin:true})
  const result=await call(`/api/admin/v1/reports/${id}/approve`,{admin:true,body:{}})
  assert.equal(result.status,503);assert.equal(result.data.error.code,'ADDRESS_LOOKUP_UNAVAILABLE')
  const after=await call(`/api/admin/v1/reports/${id}`,{admin:true});assert.equal(after.data.report.status,'PENDING');assert.deepEqual(after.data.toilet,before.data.toilet)
  receipts.push({id,type,status:'PENDING',provider:'not-connected'});checks.push(type+'-provider-failure-rolls-back')
}
const evidence={checkedAt:new Date().toISOString(),checks,receipts,productionWrites:0,providerSuccessVerified:false}
await writeFile(output,JSON.stringify(evidence,null,2)+'\n');console.log(JSON.stringify(evidence))
