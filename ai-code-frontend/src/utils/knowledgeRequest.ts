export type KnowledgeRequest = { prompt: string; id: string; useKnowledge: boolean }

export const readKnowledgeRequest = (raw: string | null): KnowledgeRequest | undefined => {
  try {
    const value = JSON.parse(raw || 'null')
    if (!value || typeof value.prompt !== 'string' || typeof value.id !== 'string' || !value.id) return undefined
    return { prompt: value.prompt, id: value.id, useKnowledge: value.useKnowledge === true }
  } catch { return undefined }
}

export const nextKnowledgeRequest = (pending: KnowledgeRequest | undefined, prompt: string, useKnowledge: boolean, id: string): KnowledgeRequest =>
  pending?.prompt === prompt && pending.useKnowledge === useKnowledge ? pending : { prompt, id, useKnowledge }

// 只用于首页到新应用的首次导航；刷新、重新进入和切换应用不恢复开关偏好。
let first: { appId: string; useKnowledge: boolean } | undefined
export const rememberFirstKnowledgeRequest = (appId: string, useKnowledge: boolean) => { first = { appId, useKnowledge } }
export const takeFirstKnowledgeRequest = (appId: string): boolean | undefined => {
  if (first?.appId !== appId) return undefined
  const value = first.useKnowledge
  first = undefined
  return value
}
