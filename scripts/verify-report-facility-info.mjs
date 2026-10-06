// Actual HTTP + isolated native MySQL. No production identity or write endpoint is accepted.
import assert from 'node:assert/strict'
import { readFile, writeFile } from 'node:fs/promises'
import { basename, dirname, resolve } from 'node:path'
import { execFileSync } from 'node:child_process'
import { randomUUID } from 'node:crypto'
const [metadataPath, outputPath] = process.argv.slice(2)
const meta = JSON.parse(await readFile(metadataPath,'utf8'))
assert.equal(basename(dirname(resolve(metadataPath))), `account-retention-mysql-${meta.marker}`)
assert.equal(dirname(resolve(metadataPath)),dirname(resolve(outputPath)))
assert.match(meta.marker,/^[a-f0-9]{10}$/)
assert.ok(meta.port > 1024 && meta.port < 65536 && meta.port !== 3306)
assert.ok(Date.parse(meta.expiresAt) > Date.now() && Date.parse(meta.expiresAt)-Date.now() <= 7200000)
const match = meta.jdbcUrl.match(/^jdbc:mysql:\/\/127\.0\.0\.1:(\d+)\/(account_retention_test_[a-f0-9]{32})\?/)
assert.ok(match); assert.notEqual(Number(match[1]),3306)
const mysql = 'C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe'
const sql = statement => execFileSync(mysql,['--no-defaults','--default-character-set=utf8mb4','--protocol=tcp','--host=127.0.0.1',`--port=${match[1]}`,'--user=root','--batch','--raw','--skip-column-names',match[2],'-e',statement],{encoding:'utf8',windowsHide:true}).trim()
assert.ok(sql('SELECT @@datadir').replaceAll('\\','/').includes(`/account-retention-mysql-${meta.marker}/data/`))
assert.equal(sql('SELECT marker FROM account_retention_fixture_guard.fixture_guard'),meta.marker)
const origin = `http://127.0.0.1:${meta.port}`
const call = async (path, body, headers={}) => {
  const response = await fetch(origin+path,{method:body === undefined?'GET':'POST',headers:{'Content-Type':'application/json',...headers},body:body === undefined?undefined:JSON.stringify(body),redirect:'error',signal:AbortSignal.timeout(20000)})
  return {status:response.status,data:await response.json().catch(()=>null)}
}
const admin = {Authorization:`Bearer ${meta.tokens['3']}`}
const guest = {'Origin':'https://preview.geupddong.com','X-Report-Guest':randomUUID(),'Idempotency-Key':randomUUID()}
const source = await fetch('https://api.geupddong.com/api/v1/toilets/53586',{redirect:'error',signal:AbortSignal.timeout(20000)})
assert.equal(source.status,200); const facility = await source.json()
const proposal = {reportType:'NEW_FACILITY',name:facility.name,latitude:facility.latitude,longitude:facility.longitude,roadAddress:facility.roadAddress,reason:'실제 공개 시설 기반 격리 기능 검증',
  facilityInfo:{toiletType:'개방',openTime:'24시간',openTimeDetail:'공휴일 운영',emergencyBell:true,cctv:false,diaperTable:null,maleDisabledToiletCount:1,femaleDisabledToiletCount:0,agencyName:'검증 기관',phoneNumber:'02-1234-5678'}}
const before = Number(sql('SELECT COUNT(*) FROM toilet'))
const submitted = await call('/api/v1/reports/guest',proposal,guest); assert.equal(submitted.status,201,JSON.stringify(submitted.data)); const id = submitted.data.id
assert.equal(Number(sql('SELECT COUNT(*) FROM toilet')),before)
const detail = await call(`/api/admin/v1/reports/${id}`,undefined,admin)
assert.equal(detail.status,200); assert.equal(detail.data.reporterDisplayName,'비회원'); assert.equal(detail.data.report.facilityInfo.cctv,false)
assert.equal(detail.data.report.facilityInfo.diaperTable,null)
assert.equal((await call(`/api/admin/v1/reports/${id}`,undefined)).status,401)
const invalid = await call(`/api/admin/v1/reports/${id}/approve`,{confirmedFacilityInfo:{name:facility.name,maleDisabledToiletCount:-1}},admin)
assert.equal(invalid.status,400); assert.equal(Number(sql('SELECT COUNT(*) FROM toilet')),before)
const confirmedFacilityInfo = {...proposal.facilityInfo,name:facility.name+' · 확정 검증',openTime:'09:00~18:00',openTimeDetail:'주말 휴무',diaperTable:true,maleDisabledToiletCount:2}
const approved = await call(`/api/admin/v1/reports/${id}/approve`,{note:'격리 시험 관리자 보정',confirmedFacilityInfo},admin)
assert.equal(approved.status,200,JSON.stringify(approved.data)); const toiletId = approved.data.toiletId; assert.ok(Number.isSafeInteger(toiletId))
assert.equal(Number(sql('SELECT COUNT(*) FROM toilet')),before+1)
assert.equal(sql(`SELECT CONCAT_WS('|',name,toilet_type,open_time,open_time_detail,has_emergency_bell,has_cctv,has_diaper_table,male_disabled_toilet_count,female_disabled_toilet_count,data_source) FROM toilet WHERE toilet_id=${toiletId}`),`${confirmedFacilityInfo.name}|개방|09:00~18:00|주말 휴무|Y|N|Y|2|0|USER_REPORT`)
assert.equal(Number(sql(`SELECT COUNT(*) FROM toilet_opening_hours WHERE toilet_id=${toiletId}`)),1)
const reviewed = await call(`/api/admin/v1/reports/${id}`,undefined,admin)
assert.equal(reviewed.data.report.facilityInfo.openTime,'24시간'); assert.equal(reviewed.data.report.facilityInfo.name,facility.name)
const replay = await call('/api/v1/reports/guest',proposal,guest); assert.equal(replay.data.id,id); assert.equal(replay.data.status,'APPROVED')
assert.equal((await call(`/api/admin/v1/reports/${id}/approve`,{confirmedFacilityInfo},admin)).status,400)
const state = await call(`/api/admin/v1/reports/${id}/actions`,undefined,admin); assert.equal(state.data.actionable,true)
const hidden = await call(`/api/admin/v1/reports/${id}/actions`,{action:'HIDE_TEMPORARILY',reason:'비회원 제보 조치 검증',requestId:randomUUID(),expectedState:state.data.expectedState},admin)
assert.equal(hidden.status,200); assert.equal(hidden.data.facility.visibilityStatus,'HIDDEN_TEMPORARY')
const restored = await call(`/api/admin/v1/reports/${id}/actions`,{action:'RESTORE',reason:'시험 원상복구',requestId:randomUUID(),expectedState:hidden.data.expectedState},admin)
assert.equal(restored.status,200); assert.equal(restored.data.facility.visibilityStatus,'VISIBLE')
await writeFile(outputPath,JSON.stringify({passed:true,sourceFacilityId:facility.id,reportId:id,fixtureFacilityId:toiletId,checks:['guest identity and permissions','proposal-only submission','invalid approval rollback','confirmed basic info persisted','opening hours synchronized','original proposal preserved','idempotent completion','single registration','guest hide/restore actions'],productionWrites:false},null,2)+'\n')
console.log(JSON.stringify({passed:true,reportId:id,checks:9,productionWrites:false}))
