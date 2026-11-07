import test from 'node:test'
import assert from 'node:assert/strict'
import { formatTraceUsage, mergeTraceSteps, traceStepRows, traceErrorText, traceMetadata, type TraceStep } from '../src/utils/generationTrace.ts'

const step = (spanId: string, stepNo: string, parentSpanId = 'root', revision = '1'): TraceStep => ({
  traceId: 'trace', spanId, parentSpanId, stepNo, revision, attemptNo: 1,
  stepKind: 'STAGE', stepName: 'generation', status: 'RUNNING', startedAt: '2026-11-07T00:00:00Z',
})

test('分项缺报显示未知与覆盖率；实际零值不当作未上报', () => {
  assert.equal(formatTraceUsage(null, '0', '2'), '未知 · 0/2 次上报')
  assert.equal(formatTraceUsage('0', '1', '2'), '已知 0 Token · 1/2 次上报')
  assert.equal(formatTraceUsage('9007199254740993', '2', '2'), '已知 9,007,199,254,740,993 Token · 2/2 次上报')
  assert.equal(formatTraceUsage(null, '0', '0'), '暂无模型调用')
})

test('分页刷新按步骤编号去重，迟到旧版本不覆盖新状态', () => {
  const finished = { ...step('a', '1', 'root', '3'), status: 'FAILED' }
  const merged = mergeTraceSteps([finished, step('b', '2')], [step('a', '1'), step('c', '9007199254740993')])
  assert.equal(merged.length, 3)
  assert.equal(merged[0]?.status, 'FAILED')
  assert.equal(merged[2]?.stepNo, '9007199254740993')
})

test('父步骤缺页时明确标注；加载父级后恢复层级，异常环不死循环', () => {
  const child = step('child', '2', 'parent')
  assert.equal(traceStepRows([child], 'root')[0]?.parentMissing, true)
  const rows = traceStepRows([child, step('parent', '1')], 'root')
  assert.equal(rows[1]?.depth, 1)
  assert.equal(rows[1]?.parentMissing, false)
  assert.ok(traceStepRows([step('a', '1', 'b'), step('b', '2', 'a')], 'root').every(row => row.depth <= 2))
})

test('查询错误区分权限、过期和不可用，不展示底层错误正文', () => {
  assert.match(traceErrorText({ code: 40101 }), /仅应用所有者/)
  assert.match(traceErrorText({ code: 40400 }), /保留期/)
  assert.match(traceErrorText(new Error('password=secret')), /暂不可用/)
  assert.doesNotMatch(traceErrorText(new Error('password=secret')), /secret/)
})

test('步骤元数据只呈现受支持字段，解析失败不渲染任意内容', () => {
  assert.deepEqual(traceMetadata('{"toolName":"writeFile","relativePath":"src/main.ts","secret":"private"}'), [
    ['工具', 'writeFile'], ['文件', 'src/main.ts'],
  ])
  assert.deepEqual(traceMetadata('invalid'), [])
})
