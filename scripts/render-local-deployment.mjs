import {readFileSync} from 'node:fs'
import {pathToFileURL} from 'node:url'

// Candidate only: never writes .github/workflows, contacts a server or deploys.
export function renderLocalDeployment(source, role) {
  if (!['api','batch'].includes(role)) throw Error('INVALID_ROLE')
  let text=source.replaceAll('\r\n','\n').replace(/^# HISTORICAL TEST FIXTURE ONLY:.*\n/, '')
  const replace=(a,b)=> {
    if (text.split(a).length !== 2) throw Error('DEPLOYMENT_BASE_DRIFT')
    text=text.replace(a,b)
  }
  replace('  build-and-deploy:\n    runs-on: ubuntu-latest', `  build-and-deploy:
    if: >-
      github.repository == 'toilet-project/toilet-api' &&
      vars.ACCOUNT_LIFECYCLE_DEPLOYMENT_APPROVED == 'true' &&
      vars.ACCOUNT_LIFECYCLE_DEPLOYMENT_APPROVED_SHA == github.sha
    runs-on: ubuntu-latest`)
  replace('          CACHE_ORIGIN: ${{ vars.WEB_CACHE_ORIGIN }}',
    `          CACHE_ORIGIN: \${{ vars.WEB_CACHE_ORIGIN }}
          CACHE_CONTRACT_VERSION: \${{ vars.WEB_CACHE_CONTRACT_VERSION || '1' }}`)
  replace('            WEB_CACHE_ORIGIN=${{ vars.WEB_CACHE_ORIGIN }}',
    `            WEB_CACHE_ORIGIN=\${{ vars.WEB_CACHE_ORIGIN }}
            WEB_CACHE_CONTRACT_VERSION=\${{ vars.WEB_CACHE_CONTRACT_VERSION || '1' }}`)
  replace("          ERASURE_LEDGER_DEPLOYMENT_PROFILE: 'us-runtime'",
    `          ERASURE_LEDGER_DEPLOYMENT_PROFILE: 'local-paused'
          ERASURE_LEDGER_PROVIDER: 'LOCAL'
          ERASURE_LEDGER_LOCAL_DEPLOYMENT_APPROVED: \${{ vars.ERASURE_LEDGER_LOCAL_DEPLOYMENT_APPROVED || 'false' }}
          ERASURE_LEDGER_LOCAL_ACCEPTANCE_VERIFIED: 'false'
          ERASURE_LEDGER_LOCAL_DIRECTORY: '/home/luha/geupddong-erasure-ledger'
          ERASURE_LEDGER_LOCAL_STORE_ID: \${{ vars.ERASURE_LEDGER_LOCAL_STORE_ID }}
          LOCAL_LEDGER_RUNTIME_UID: '1000'
          LOCAL_LEDGER_RUNTIME_GID: '1000'`)
  for (const key of ['ERASURE_LEDGER_US_DEPLOYMENT_APPROVED','ERASURE_LEDGER_ENDPOINT','ERASURE_LEDGER_BUCKET',
    'ERASURE_LEDGER_ACCESS_KEY_ID','ERASURE_LEDGER_SECRET_ACCESS_KEY']) {
    const lines=text.split('\n').filter(line=>line.startsWith('          '+key+':'))
    if(lines.length!==1) throw Error('DEPLOYMENT_BASE_DRIFT')
    replace(lines[0]+'\n','')
  }
  replace('            set -eu\n            umask 077', `            set -eu
            umask 077
            # Read-only preflight must finish BEFORE touching operational configuration.
            test -x /home/luha/erasure-tools/local-ledger-preflight
            printf '%s' '\${{ steps.lifecycle.outputs.payload }}' | /home/luha/erasure-tools/local-ledger-preflight ${role} '\${{ vars.ERASURE_LEDGER_LOCAL_STORE_ID }}'`)
  if(role==='api') {
    replace(`            printf '%s' '\${{ steps.lifecycle.outputs.payload }}' | /home/luha/erasure-tools/local-ledger-preflight api '\${{ vars.ERASURE_LEDGER_LOCAL_STORE_ID }}'`,
      `            printf '%s' '\${{ steps.lifecycle.outputs.payload }}' | /home/luha/erasure-tools/local-ledger-preflight api '\${{ vars.ERASURE_LEDGER_LOCAL_STORE_ID }}'
            profile_photo_env=/home/luha/.config/geupddong/profile-photo.env
            test -f "$profile_photo_env"
            test "$(stat -c '%a:%u:%g' "$profile_photo_env")" = "600:$(id -u):$(id -g)"
            profile_photo_names=$(sed -n 's/^\\([A-Z0-9_]*\\)=.*$/\\1/p' "$profile_photo_env" | sort | paste -sd, -)
            profile_photo_base_names='PROFILE_PHOTO_R2_ACCESS_KEY_ID,PROFILE_PHOTO_R2_BUCKET,PROFILE_PHOTO_R2_ENDPOINT,PROFILE_PHOTO_R2_SECRET_ACCESS_KEY'
            profile_photo_cdn_names='PROFILE_PHOTO_CDN_TOKEN,PROFILE_PHOTO_CDN_ZONE_ID,PROFILE_PHOTO_R2_ACCESS_KEY_ID,PROFILE_PHOTO_R2_BUCKET,PROFILE_PHOTO_R2_ENDPOINT,PROFILE_PHOTO_R2_SECRET_ACCESS_KEY'
            profile_photo_cdn_enabled='\${{ vars.PROFILE_PHOTO_CDN_ENABLED || 'false' }}'
            case "$profile_photo_cdn_enabled:$profile_photo_names" in
              false:"$profile_photo_base_names"|false:"$profile_photo_cdn_names"|true:"$profile_photo_cdn_names") ;;
              *) exit 1 ;;
            esac`)
    replace('            KAKAO_REST_API_KEY=\${{ secrets.KAKAO_REST_API_KEY }}',
      `            KAKAO_REST_API_KEY=\${{ secrets.KAKAO_REST_API_KEY }}
            GOOGLE_TRANSLATION_API_KEY=\${{ secrets.GOOGLE_TRANSLATION_API_KEY }}
            DISPLAY_GROUP_TRANSLATION_ENABLED=true`)
    replace('            KAKAO_CLIENT_SECRET=\${{ secrets.KAKAO_CLIENT_SECRET }}',
      `            KAKAO_CLIENT_SECRET=\${{ secrets.KAKAO_CLIENT_SECRET }}
            PROFILE_PHOTO_ENABLED=\${{ vars.PROFILE_PHOTO_ENABLED || 'false' }}
            PROFILE_PHOTO_CDN_ENABLED=\${{ vars.PROFILE_PHOTO_CDN_ENABLED || 'false' }}
            KAKAO_LOGIN_SCOPES=\${{ vars.PROFILE_PHOTO_ENABLED == 'true' && 'profile_nickname,account_email,profile_image' || 'profile_nickname,account_email' }}`)
    replace('            JWT_SECRET=\${{ secrets.JWT_SECRET }}',
      `            JWT_SECRET=\${{ secrets.JWT_SECRET }}
            SERVICE_ANALYTICS_ENABLED=true
            ANALYTICS_VISITOR_HMAC_SECRET=\${{ secrets.JWT_SECRET }}
            SERVICE_ANALYTICS_DAILY_CRON=0 30 2 * * *`)
    replace('                  - .account-lifecycle.env',
      `                  - .account-lifecycle.env
                  - /home/luha/.config/geupddong/profile-photo.env`)
  }
  const service=role==='api' ? 'api' : 'toilet-batch'
  const userLine=`              ${service}:\n                user: "1000:1000"`
  replace(`              ${service}:`,userLine)
  const mount=`                  - type: bind
                    source: /home/luha/geupddong-erasure-ledger
                    target: /home/luha/geupddong-erasure-ledger
                    read_only: false
                    bind:
                      create_host_path: false`
  if(role==='batch') replace('                  - ./region-results:/var/lib/toilet-region',
    '                  - ./region-results:/var/lib/toilet-region\n'+mount)
  else {
    replace('                container_name: toilet-api', '                container_name: toilet-api\n                volumes:\n'+mount)
    // The existing Redis configuration is already deployed: this release must not recreate it.
    replace('            docker compose pull', '            docker compose pull api')
    replace('            docker compose up -d --wait --wait-timeout 120 redis api',
      '            docker compose up -d --no-deps --wait --wait-timeout 120 api')
  }
  return '# REVIEW CANDIDATE ONLY: not installed or approved for production.\n'+text
}

if(process.argv[1] && import.meta.url===pathToFileURL(process.argv[1]).href) {
  const role=process.argv[2]
  const source=readFileSync(new URL('../deploy/us-paused.baseline.yml',import.meta.url),'utf8')
  process.stdout.write(renderLocalDeployment(source,role))
}
