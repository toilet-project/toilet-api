import {validateWebCacheDeployment} from './web-cache-deployment-policy.mjs'
try {
  const result=validateWebCacheDeployment({enabled:process.env.CACHE_ENABLED, origin:process.env.CACHE_ORIGIN,
    secretName:process.env.CACHE_SECRET_NAME,secret:process.env.CACHE_SECRET})
  console.log(JSON.stringify({cacheDeploymentConfigValid:true,...result}))
} catch(error) {
  console.error(error.message)
  process.exitCode=1
}
