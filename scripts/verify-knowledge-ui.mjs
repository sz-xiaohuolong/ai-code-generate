/** 知识库 UI 验收：真实浏览器、受控 API，不访问真实业务数据。 */
import assert from 'node:assert/strict'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE || 'playwright')
const origin = process.env.UI_TEST_URL || 'http://127.0.0.1:4177'
const evidence = resolve(process.env.UI_EVIDENCE_DIR || '.evidence/knowledge-ui-20261108')
await mkdir(evidence, { recursive: true })
const browser = await chromium.launch({ channel: 'chrome', headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, colorScheme: 'light', locale: 'zh-CN' })
const page = await context.newPage()
page.setDefaultTimeout(10000)
const errors = [], checks = [], requests = []
page.on('pageerror', error => errors.push(error.message))
// 后端 JsonConfig 将 long 时间戳序列化为字符串，避免仅用数字测试漏掉日期渲染异常。
const baseDocuments = [
  { id: '101', name: '产品接口说明.md', status: 'READY', activeVersionId: '201', activeVersionNo: 2, pendingVersionNo: 2, byteSize: 25840 },
  { id: '102', name: '会员与积分规则.md', status: 'READY', activeVersionId: '202', activeVersionNo: 1, pendingVersionNo: 1, byteSize: 18620 },
  { id: '103', name: '订单状态与支付回调.md', status: 'PROCESSING', activeVersionId: '203', activeVersionNo: 1, pendingVersionNo: 2, byteSize: 32900 },
  { id: '104', name: '品牌文案与写作规范.txt', status: 'FAILED', activeVersionId: '204', activeVersionNo: 1, pendingVersionNo: 2, byteSize: 14600, errorCode: 'INDEX_INCOMPLETE' },
  { id: '105', name: '项目命名约定.txt', status: 'READY', activeVersionId: '205', activeVersionNo: 1, pendingVersionNo: 1, byteSize: 4750 },
].map((item, i) => ({ pendingVersionId: String(301 + i), revision: 3, createdAt: String(1793980800000 + i * 1000), errorCode: null, ...item }))
let records = structuredClone(baseDocuments)
const quota = { usedBytes: 9673114, maxTotalBytes: 20971520, documents: 5, maxDocuments: 50, maxFileBytes: 2097152 }
const state = { enabled: true, failList: false, delay: 0, abortUpload: false, retryAbort: false, listCalls: 0, readyOnNextPoll: false }
const cors = { 'access-control-allow-origin': origin, 'access-control-allow-credentials': 'true', 'access-control-allow-methods': 'GET,POST,DELETE,OPTIONS', 'access-control-allow-headers': 'content-type' }
await context.route('**/*', async route => {
  const req = route.request(), url = new URL(req.url())
  if (!url.pathname.startsWith('/api/')) {
    if (url.origin === origin || url.protocol === 'data:') return route.continue()
    return route.fulfill({ status: 204, body: '' })
  }
  if (req.method() === 'OPTIONS') return route.fulfill({ status: 204, headers: cors, body: '' })
  const path = url.pathname.slice(4)
  const body = req.postData() || ''
  requests.push({ method: req.method(), path, body })
  const reply = (data, code = 0, message = '') => route.fulfill({ contentType: 'application/json', headers: cors, body: JSON.stringify({ code, data, message }) })
  if (path === '/user/get/login') return reply({ id: '1001', userName: '林舟', userRole: 'user' })
  if (path === '/knowledge/documents' && req.method() === 'GET') {
    state.listCalls++
    if (state.delay) await new Promise(resolve => setTimeout(resolve, state.delay))
    if (state.failList) return reply(null, 50000, '资料暂不可用')
    if (state.readyOnNextPoll) { records = records.map(doc => doc.id === '103' ? { ...doc, status: 'READY', activeVersionNo: 2 } : doc); state.readyOnNextPoll = false }
    return reply({ records, total: records.length, enabled: state.enabled, quota: { ...quota, documents: records.length } })
  }
  if (req.method() === 'POST' && /\/retry$/.test(path)) {
    if (state.retryAbort) return route.abort('failed')
    const id = path.split('/')[3]
    records = records.map(doc => doc.id === id ? { ...doc, status: 'PROCESSING', errorCode: null } : doc)
    return reply(true)
  }
  if (req.method() === 'POST' && (path === '/knowledge/documents' || /\/versions$/.test(path))) {
    if (state.abortUpload) return route.abort('failed')
    if (path === '/knowledge/documents') records.push({ ...baseDocuments[0], id: '106', name: '验收资料.md', status: 'PROCESSING', activeVersionId: null, activeVersionNo: null })
    return reply(true)
  }
  if (req.method() === 'DELETE' && path.startsWith('/knowledge/documents/')) {
    records = records.filter(doc => doc.id !== path.split('/').at(-1))
    return reply(true)
  }
  return reply(null, 50000, '未声明的验收接口')
})
async function check(name, run) { await run(); checks.push(name); console.log(`通过：${name}`) }
async function shot(name, fullPage = false) {
  await page.evaluate(async () => {
    await document.fonts.ready
    await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))
    await Promise.all(document.getAnimations().filter(animation => animation.effect?.getComputedTiming().endTime < 2000).map(animation => animation.finished.catch(() => {})))
  })
  await page.mouse.move(0, 0)
  await page.screenshot({ path: resolve(evidence, `${name}.png`), fullPage, animations: 'disabled' })
}
async function noOverflow() { assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), '页面横向溢出') }
async function fresh() { await page.goto(`${origin}/knowledge`); await page.locator('.storage-amount').waitFor() }
async function refresh() { const old = state.listCalls; await page.getByRole('button', { name: '刷新状态' }).click(); await page.waitForFunction(() => !document.querySelector('.refresh-button')?.classList.contains('ant-btn-loading')); assert.ok(state.listCalls > old) }
const multipartField = (body, name) => body.match(new RegExp(`name="${name}"\\r\\n\\r\\n([^\\r]+)`))?.[1]
const uploadFile = { name: '验收资料.md', mimeType: 'text/markdown', buffer: Buffer.from('# 验收资料\n接口与规则。') }
try {
  await fresh()
  await check('桌面展示文档层级、详情与真实容量', async () => {
    assert.equal(await page.locator('.document-list li').count(), 5)
    assert.equal(await page.locator('.detail-filename').textContent(), '产品接口说明.md')
    assert.match(await page.locator('.storage-caption').innerText(), /46\.1%/)
    assert.match(await page.locator('.storage-documents').innerText(), /5 \/ 50/)
    await noOverflow(); await shot('desktop')
  })
  await check('生产构建中所有本地图标实际绘制', async () => {
    assert.ok(await page.locator('.knowledge-page svg use').evaluateAll(icons => icons.every(icon => icon.getBBox().width > 0)), 'SVG 图标没有实际绘制')
  })
  await check('键盘选择文档，侧栏更新且不新增请求', async () => {
    const before = requests.filter(r => r.method !== 'GET').length
    const target = page.getByRole('button', { name: '会员与积分规则.md', exact: true })
    await target.focus(); await page.keyboard.press('Enter')
    assert.equal(await page.locator('.detail-filename').textContent(), '会员与积分规则.md')
    assert.equal(requests.filter(r => r.method !== 'GET').length, before)
  })
  await check('处理中禁用替换，并保留旧版可用说明', async () => {
    await page.getByRole('button', { name: '订单状态与支付回调.md', exact: true }).click()
    assert.ok(await page.getByRole('button', { name: '替换文件', exact: true }).isDisabled())
    assert.match(await page.locator('.document-details').innerText(), /旧版仍可用于生成/)
  })
  await check('自动轮询更新选中文档及可用版本', async () => {
    state.readyOnNextPoll = true
    await page.waitForFunction(() => document.querySelector('.detail-filename')?.textContent === '订单状态与支付回调.md' && document.querySelector('.document-details .status-label')?.textContent.includes('已就绪'), { timeout: 6000 })
    assert.ok(await page.getByRole('button', { name: '替换文件', exact: true }).isEnabled())
    assert.match(await page.locator('.document-facts').innerText(), /v2/)
  })
  await check('重试失败保留幂等标识，再次重试转处理中', async () => {
    state.retryAbort = true
    await page.getByRole('button', { name: '重新处理' }).click()
    await page.waitForFunction(() => !document.querySelector('.retry-button')?.disabled)
    const first = requests.filter(r => r.path.endsWith('/retry')).at(-1)
    assert.equal(JSON.parse(first.body).versionId, '304')
    state.retryAbort = false
    await page.getByRole('button', { name: '重新处理' }).click()
    await page.locator('.retry-button').waitFor({ state: 'detached' })
    assert.equal(JSON.parse(requests.filter(r => r.path.endsWith('/retry')).at(-1).body).clientRequestId, JSON.parse(first.body).clientRequestId)
  })
  await check('替换沿用文档 ID、版本并发条件与文件字段', async () => {
    await page.getByRole('button', { name: '会员与积分规则.md', exact: true }).click()
    const chooser = page.waitForEvent('filechooser')
    await page.getByRole('button', { name: '替换文件', exact: true }).click()
    await (await chooser).setFiles(uploadFile)
    await page.locator('.pending-operation').waitFor({ state: 'detached' })
    const req = requests.find(r => r.path === '/knowledge/documents/102/versions')
    assert.ok(req); assert.equal(multipartField(req.body, 'expectedRevision'), '3'); assert.ok(multipartField(req.body, 'clientRequestId')); assert.match(req.body, /name="file"/)
  })
  await check('上传校验拦截错误类型、空文件和超限文件', async () => {
    const before = requests.filter(r => r.method === 'POST').length
    for (const file of [{ name: 'image.png', mimeType: 'image/png', buffer: Buffer.from('image') }, { ...uploadFile, buffer: Buffer.alloc(0) }, { ...uploadFile, buffer: Buffer.alloc(2097153) }]) await page.locator('input[type=file]').setInputFiles(file)
    assert.equal(requests.filter(r => r.method === 'POST').length, before)
  })
  await check('上传结果未知保留原请求，重试后恢复列表', async () => {
    state.abortUpload = true
    const chooser = page.waitForEvent('filechooser')
    await page.getByRole('button', { name: '上传文档', exact: true }).click()
    await (await chooser).setFiles(uploadFile)
    await page.getByRole('button', { name: '重试本次提交' }).waitFor()
    const first = requests.filter(r => r.method === 'POST' && r.path === '/knowledge/documents').at(-1)
    assert.ok(await page.getByRole('button', { name: '上传文档', exact: true }).isDisabled())
    state.abortUpload = false
    await page.getByRole('button', { name: '重试本次提交' }).click()
    await page.getByRole('button', { name: '验收资料.md', exact: true }).waitFor()
    const second = requests.filter(r => r.method === 'POST' && r.path === '/knowledge/documents').at(-1)
    assert.equal(multipartField(second.body, 'clientRequestId'), multipartField(first.body, 'clientRequestId'))
  })
  await check('删除有明确后果和取消入口，确认仅删除选中文档', async () => {
    await page.getByRole('button', { name: '会员与积分规则.md', exact: true }).click()
    const before = requests.filter(r => r.method === 'DELETE').length
    await page.locator('.detail-actions').getByRole('button', { name: '删除', exact: true }).click()
    const pop = page.locator('.ant-popconfirm').filter({ visible: true })
    assert.match(await pop.innerText(), /已有代码和对话内容不会清除/)
    await pop.getByRole('button', { name: '取消', exact: true }).click()
    assert.equal(requests.filter(r => r.method === 'DELETE').length, before)
    await page.locator('.detail-actions').getByRole('button', { name: '删除', exact: true }).click()
    await page.locator('.ant-popconfirm').filter({ visible: true }).getByRole('button', { name: '删除', exact: true }).click()
    await page.getByRole('button', { name: '会员与积分规则.md', exact: true }).waitFor({ state: 'detached' })
    assert.equal(requests.filter(r => r.method === 'DELETE').at(-1).path, '/knowledge/documents/102')
    assert.equal(await page.locator('.detail-filename').textContent(), '产品接口说明.md')
    assert.ok(await page.evaluate(() => localStorage.getItem('knowledge:changed')))
  })
  await check('删除确认期间切换文档会关闭旧确认，不误删新选择', async () => {
    const before = requests.filter(r => r.method === 'DELETE').length
    await page.locator('.detail-actions').getByRole('button', { name: '删除', exact: true }).click()
    await page.getByRole('button', { name: '项目命名约定.txt', exact: true }).click()
    await page.locator('.ant-popconfirm').filter({ visible: true }).waitFor({ state: 'detached' })
    assert.equal(requests.filter(r => r.method === 'DELETE').length, before)
    assert.equal(await page.locator('.detail-filename').textContent(), '项目命名约定.txt')
  })
  await check('刷新失败保留已加载资料，恢复后清除错误', async () => {
    state.failList = true; await refresh()
    await page.getByText('资料暂不可用', { exact: true }).waitFor()
    assert.ok(await page.locator('.document-list li').count() > 0)
    state.failList = false; await refresh(); await page.locator('.list-error').waitFor({ state: 'detached' })
  })
  await check('深色桌面与窄屏长名称均无横向溢出', async () => {
    records = structuredClone(baseDocuments)
    await fresh(); await page.emulateMedia({ colorScheme: 'dark', reducedMotion: 'reduce' })
    await page.waitForFunction(() => document.documentElement.dataset.theme === 'dark')
    await fresh()
    await noOverflow(); await shot('desktop-dark')
    records[0].name = '这是用于验证中文和连续英文长文件名的资料_' + 'longfilename'.repeat(10) + '.md'
    await fresh()
    for (const width of [1366, 768, 390, 320]) {
      await page.setViewportSize({ width, height: 900 }); await noOverflow()
      assert.ok(await page.getByRole('button', { name: '上传文档', exact: true }).isVisible())
    }
    await page.setViewportSize({ width: 390, height: 844 }); await shot('mobile-dark-long-name', true)
    records = structuredClone(baseDocuments); await page.emulateMedia({ colorScheme: 'light' }); await fresh(); await shot('mobile', true)
    await page.getByRole('button', { name: '会员与积分规则.md', exact: true }).click()
    assert.equal(await page.locator('.detail-filename').textContent(), '会员与积分规则.md')
    assert.ok(await page.locator('#knowledge-document-details').evaluate(el => document.activeElement === el))
    assert.ok((await page.locator('#knowledge-document-details').boundingBox()).y >= 58)
    await shot('mobile-selected')
  })
  await check('接近容量上限、满额与零额度均显示正确', async () => {
    await page.setViewportSize({ width: 1440, height: 1000 })
    quota.usedBytes = quota.maxTotalBytes * .96; await refresh()
    await page.locator('.storage-notice').waitFor(); assert.match(await page.locator('.storage-caption').innerText(), /96\.0%/); await shot('capacity-warning')
    quota.usedBytes = quota.maxTotalBytes + 500; await refresh()
    assert.equal(await page.getByRole('progressbar', { name: '已用存储容量' }).getAttribute('aria-valuenow'), '100')
    assert.match(await page.locator('.storage-caption').innerText(), /剩余 0 B/)
    quota.maxTotalBytes = 0; quota.usedBytes = 0; await refresh()
    assert.equal(await page.getByRole('progressbar', { name: '已用存储容量' }).getAttribute('aria-valuenow'), '0')
  })
  await check('未启用状态禁用上传替换与重试，保留删除', async () => {
    state.enabled = false; records = structuredClone(baseDocuments); await fresh()
    assert.ok(await page.getByRole('button', { name: '上传文档', exact: true }).isDisabled())
    assert.ok(await page.getByRole('button', { name: '替换文件', exact: true }).isDisabled())
    assert.ok(await page.getByRole('button', { name: '重新处理' }).isDisabled())
    assert.ok(await page.locator('.detail-actions').getByRole('button', { name: '删除', exact: true }).isEnabled())
  })
  await check('空状态提供真实上传入口，详情与容量归零', async () => {
    state.enabled = true; records = []; await fresh()
    assert.ok(await page.getByRole('button', { name: '上传第一份文档' }).isEnabled())
    assert.equal(await page.locator('.detail-filename').count(), 0)
    assert.match(await page.locator('.storage-documents').innerText(), /0 \/ 50/); await shot('empty')
  })
  await check('首次加载与请求失败不伪装成空知识库', async () => {
    state.delay = 1200
    await page.goto(`${origin}/knowledge`); await page.getByText('正在加载资料', { exact: true }).waitFor(); await shot('loading')
    await page.locator('.storage-amount').waitFor(); state.delay = 0; state.failList = true
    await page.reload(); await page.getByText('资料暂时没加载出来', { exact: true }).waitFor()
    assert.equal(await page.getByText('从第一份资料开始', { exact: true }).count(), 0); await shot('error')
    state.failList = false; records = structuredClone(baseDocuments); quota.usedBytes = 9673114; quota.maxTotalBytes = 20971520; await refresh()
  })
  await check('浏览器无脚本异常，所有业务请求均在声明范围', async () => {
    assert.deepEqual(errors, [])
    assert.ok(requests.every(r => r.path === '/user/get/login' || r.path.startsWith('/knowledge/documents')))
  })
  await shot('desktop-final')
  await writeFile(resolve(evidence, 'browser-summary.json'), JSON.stringify({ checks, count: checks.length, errors, requests: requests.map(({ method, path }) => ({ method, path })) }, null, 2))
} catch (error) { await shot('failure', true); await writeFile(resolve(evidence, 'browser-failure.txt'), String(error.stack)); throw error }
finally { await browser.close() }
