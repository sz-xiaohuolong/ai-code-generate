const trimTrailingSlash = (value: string) => value.replace(/\/+$/, '')

export const APP_PREVIEW_BASE_URL = trimTrailingSlash(
  import.meta.env.VITE_APP_PREVIEW_BASE_URL || 'http://localhost:8123/api/static',
)

export const APP_DEPLOY_BASE_URL = trimTrailingSlash(
  import.meta.env.VITE_APP_DEPLOY_BASE_URL || 'http://localhost',
)

export const buildAppPreviewUrl = (codeGenType?: string, appId?: string | number) => {
  if (!codeGenType || !appId) {
    return ''
  }
  return `${APP_PREVIEW_BASE_URL}/${codeGenType}_${appId}/`
}

export const buildAppDeployUrl = (deployKey?: string) => {
  if (!deployKey) {
    return ''
  }
  return `${APP_DEPLOY_BASE_URL}/${deployKey}/`
}
