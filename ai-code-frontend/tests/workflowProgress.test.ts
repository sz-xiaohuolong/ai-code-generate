import { test } from 'node:test'
import assert from 'node:assert/strict'
import { normalizeGenerationMode, updateWorkflowProgress, isWorkflowHistoryComplete } from '../src/utils/workflowProgress.ts'

test('旧偏好和损坏的偏好使用标准模式', () => {
  assert.equal(normalizeGenerationMode(null), 'standard')
  assert.equal(normalizeGenerationMode('unknown'), 'standard')
  assert.equal(normalizeGenerationMode('workflow'), 'workflow')
})

test('同一节点再次运行时更新状态并保留原有顺序', () => {
  const steps = [
    { id: 'code_generator', name: '代码生成', status: 'completed' as const, message: '完成', retryCount: 0 },
    { id: 'code_quality_check', name: '代码质检', status: 'retrying' as const, message: '准备修复', retryCount: 0 },
  ]
  const result = updateWorkflowProgress(steps, {
    id: 'code_generator', name: '代码生成', status: 'running', message: '正在修复', retryCount: 1,
  })
  assert.equal(result.length, 2)
  assert.equal(result[0]?.status, 'running')
  assert.equal(result[0]?.retryCount, 1)
  assert.equal(result[1]?.status, 'retrying')
  assert.equal(steps[0]?.status, 'completed')
})

test('跳过检查和失败不会被当作通过', () => {
  const skipped = updateWorkflowProgress([], {
    id: 'code_quality_check', name: '代码质检', status: 'skipped', message: '服务不可用', retryCount: 0,
  })
  assert.equal(skipped[0]?.status, 'skipped')
  const failed = updateWorkflowProgress(skipped, {
    id: 'code_quality_check', name: '代码质检', status: 'failed', message: '重试耗尽', retryCount: 2,
  })
  assert.equal(failed[0]?.status, 'failed')
  assert.equal(failed.length, 1)
})

test('历史只有明确保存了最终成功结果才恢复预览', () => {
  assert.equal(isWorkflowHistoryComplete({ messageType: 'user', message: '需求' }), false)
  assert.equal(isWorkflowHistoryComplete({ messageType: 'ai', message: '工作流已中断' }), false)
  assert.equal(isWorkflowHistoryComplete({ messageType: 'ai', message: '生成中的部分代码' }), false)
  assert.equal(isWorkflowHistoryComplete({ messageType: 'ai', message: '代码\n\n工作流已完成，生成结果可预览。' }), true)
  assert.equal(isWorkflowHistoryComplete({ messageType: 'ai', message: '代码\n\n工作流已完成，生成结果可预览。本次质检服务不可用，已跳过检查。' }), true)
})
