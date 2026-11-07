import { test } from 'node:test'
import assert from 'node:assert/strict'
import { nextKnowledgeRequest, readKnowledgeRequest, rememberFirstKnowledgeRequest, takeFirstKnowledgeRequest } from '../src/utils/knowledgeRequest.ts'

test('请求重试固定选择，改选后发送使用新请求标识', () => {
  const prior = { prompt: '生成订单', id: 'old', useKnowledge: true }
  assert.equal(nextKnowledgeRequest(prior, '生成订单', true, 'new').id, 'old')
  assert.equal(nextKnowledgeRequest(prior, '生成订单', false, 'new').id, 'new')
  assert.equal(readKnowledgeRequest(JSON.stringify(prior))?.useKnowledge, true)
  assert.equal(readKnowledgeRequest('{"prompt":"旧请求","id":"old"}')?.useKnowledge, false)
  assert.equal(readKnowledgeRequest('{invalid'), undefined)
})

test('首页选择只按应用消费一次，不能跨应用复用', () => {
  rememberFirstKnowledgeRequest('app-1', true)
  assert.equal(takeFirstKnowledgeRequest('app-2'), undefined)
  assert.equal(takeFirstKnowledgeRequest('app-1'), true)
  assert.equal(takeFirstKnowledgeRequest('app-1'), undefined)
})
