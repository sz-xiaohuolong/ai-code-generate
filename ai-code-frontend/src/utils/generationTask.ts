export type TaskStatus = 'STARTING' | 'RUNNING' | 'STOPPING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED' | 'TIMED_OUT' | 'INTERRUPTED'
export type TaskStep = {
  id: string
  name: string
  status: 'running' | 'completed' | 'retrying' | 'skipped' | 'failed'
  message: string
  retryCount: number
}
export type GenerationTask = {
  id: string
  appId: number | string
  clientRequestId: string
  prompt: string
  status: TaskStatus
  stage: string
  message: string
  output: string
  revision: number
  createdAt?: number
  userMessageId?: string | number
  aiMessageId?: string | number
  previewAvailable: boolean
  steps: TaskStep[]
  repairCount?: number
  modelCalls?: number
  inputTokens?: number
  outputTokens?: number
}
export type TaskChatMessage = {
  role: 'user' | 'ai'
  content: string
  id?: string | number
  taskId?: string
  clientRequestId?: string
  taskStatus?: TaskStatus
  createTime?: string
  stage?: 'analyzing' | 'coding' | 'done' | 'error'
  errorMessage?: string
  rawPrompt?: string
  workflowSteps?: TaskStep[]
  workflowCompleted?: boolean
}

export const isTaskActive = (status: TaskStatus): boolean =>
  status === 'STARTING' || status === 'RUNNING' || status === 'STOPPING'

/** 快照替换聚合输出；恢复时复用已有历史，避免重复追加一轮对话。 */
export const applyTaskSnapshot = (messages: TaskChatMessage[], task: GenerationTask): void => {
  let current = messages.find((item) => item.role === 'ai' && (item.taskId === task.id || item.clientRequestId === task.clientRequestId
    || (task.aiMessageId != null && String(item.id) === String(task.aiMessageId))))
  const last = messages[messages.length - 1]
  if (!current && last?.role === 'ai' && !last.taskId) {
    const pending = last.rawPrompt === task.prompt && (last.stage === 'analyzing' || last.stage === 'coding')
    const persisted = !isTaskActive(task.status) && !!task.createdAt && !!last.createTime
      && Date.parse(last.createTime) >= task.createdAt
      && messages[messages.length - 2]?.content === task.prompt
    if (pending || persisted) current = last
  }
  if (!current) {
    if (last?.role !== 'user' || last.content !== task.prompt) messages.push({ role: 'user', content: task.prompt })
    current = { role: 'ai', content: '', rawPrompt: task.prompt }
    messages.push(current)
  }
  current.taskId = task.id
  current.taskStatus = task.status
  current.content = task.output || ''
  current.workflowSteps = (task.steps || []).filter((step) => step.id !== 'workflow')
  current.workflowCompleted = task.status === 'SUCCEEDED'
  current.stage = task.status === 'SUCCEEDED' ? 'done' : isTaskActive(task.status) ? 'coding' : 'error'
  current.errorMessage = current.stage === 'error' ? task.message : undefined
}

export const prepareTaskSubmission = (messages: TaskChatMessage[], prompt: string, clientRequestId: string): void => {
  const pending = messages.find((item) => item.role === 'ai' && item.clientRequestId === clientRequestId)
  if (pending) {
    pending.stage = 'analyzing'
    pending.errorMessage = undefined
    return
  }
  messages.push(
    { role: 'user', content: prompt, clientRequestId },
    { role: 'ai', content: '', stage: 'analyzing', rawPrompt: prompt, clientRequestId },
  )
}
