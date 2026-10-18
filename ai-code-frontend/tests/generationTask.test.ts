import test from 'node:test'
import assert from 'node:assert/strict'
import { applyTaskSnapshot, isTaskActive, type GenerationTask, type TaskChatMessage } from '../src/utils/generationTask.ts'

const task = (status: GenerationTask['status']): GenerationTask => ({
  id: 'task-1', appId: 1, clientRequestId: 'request-1', prompt: '生成作品集', status,
  stage: '项目构建', message: '正在构建', output: '已生成内容', revision: 1,
  previewAvailable: true, steps: [],
})

test('恢复同一任务时替换聚合输出，不重复追加聊天', () => {
  const messages: TaskChatMessage[] = [{ role: 'user', content: '生成作品集' }]
  applyTaskSnapshot(messages, task('RUNNING'))
  applyTaskSnapshot(messages, { ...task('RUNNING'), output: '已生成内容，继续修改', revision: 2 })
  assert.equal(messages.length, 2)
  assert.equal(messages[1].content, '已生成内容，继续修改')
  assert.equal(messages[1].taskId, 'task-1')
})

test('停止中仍视为活跃，只有清理完成的终态允许新请求', () => {
  assert.equal(isTaskActive('STOPPING'), true)
  assert.equal(isTaskActive('CANCELLED'), false)
  const messages: TaskChatMessage[] = []
  applyTaskSnapshot(messages, task('STOPPING'))
  assert.equal(messages[1].stage, 'coding')
  applyTaskSnapshot(messages, { ...task('CANCELLED'), message: '已停止' })
  assert.equal(messages[1].stage, 'error')
  assert.equal(messages[1].errorMessage, '已停止')
  assert.equal(messages[1].taskStatus, 'CANCELLED')
})

test('已有最终历史时恢复任务不会再添加一轮', () => {
  const messages: TaskChatMessage[] = [
    { role: 'user', content: '生成作品集' },
    { role: 'ai', content: '已生成内容\n\n生成已完成，交付检查通过。', createTime: '2026-10-18T15:00:00+08:00' },
  ]
  applyTaskSnapshot(messages, { ...task('SUCCEEDED'), createdAt: Date.parse('2026-10-18T14:59:00+08:00') })
  assert.equal(messages.length, 2)
  assert.equal(messages[1].stage, 'done')
})

test('提交响应丢失后使用同一请求重试，只保留一轮临时聊天', async () => {
  const { prepareTaskSubmission } = await import('../src/utils/generationTask.ts')
  const messages: TaskChatMessage[] = []
  prepareTaskSubmission(messages, '生成作品集', 'request-1')
  messages[1].stage = 'error'
  messages[1].errorMessage = '响应丢失'
  prepareTaskSubmission(messages, '生成作品集', 'request-1')
  applyTaskSnapshot(messages, task('RUNNING'))
  assert.equal(messages.length, 2)
  assert.equal(messages[1].stage, 'coding')
  assert.equal(messages[1].errorMessage, undefined)
})

test('终态历史时间截断至秒时仍按稳定主键恢复，不重复追加', () => {
  const messages: TaskChatMessage[] = [
    { id: '469322376161681408', role: 'user', content: '生成作品集' },
    { id: '469322376161681409', role: 'ai', content: '生成未完成：已停止', createTime: '2026-10-18T15:00:34+08:00' },
  ]
  applyTaskSnapshot(messages, { ...task('CANCELLED'), createdAt: Date.parse('2026-10-18T15:00:34.063+08:00'),
    aiMessageId: '469322376161681409', userMessageId: '469322376161681408' } as GenerationTask)
  assert.equal(messages.length, 2)
  assert.equal(messages[1].taskStatus, 'CANCELLED')
})
