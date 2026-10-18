export type GenerationMode = 'standard' | 'workflow'

export const GENERATION_MODE_LABELS = {
  standard: '标准模式',
  workflow: '工作流模式',
} as const

export const normalizeGenerationMode = (value: unknown): GenerationMode =>
  value === 'workflow' ? 'workflow' : 'standard'

export const isWorkflowHistoryComplete = (record?: { messageType?: string; message?: string }): boolean =>
  record?.messageType === 'ai'
  && /\n\n工作流已完成，生成结果可预览。(本次质检服务不可用，已跳过检查。)?$/.test(record.message || '')

export type WorkflowStep = {
  id: string
  name: string
  status: 'running' | 'completed' | 'retrying' | 'skipped' | 'failed'
  message: string
  retryCount: number
}

export const WORKFLOW_STATUS_LABELS: Record<WorkflowStep['status'], string> = {
  running: '进行中',
  completed: '已完成',
  retrying: '准备修复',
  skipped: '已跳过',
  failed: '失败',
}

export const updateWorkflowProgress = (steps: WorkflowStep[], event: WorkflowStep): WorkflowStep[] => {
  const index = steps.findIndex((step) => step.id === event.id)
  if (index < 0) return [...steps, event]
  return steps.map((step, position) => (position === index ? event : step))
}
