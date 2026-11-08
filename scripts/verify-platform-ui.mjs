/** 平台界面回归：全部 API 由测试拦截，不连接真实业务接口或模型。 */
import assert from 'node:assert/strict'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'

const { chromium } = await import(process.env.PLAYWRIGHT_MODULE || 'playwright')
const origin = process.env.UI_TEST_URL || 'http://127.0.0.1:5175'
const evidence = resolve(process.env.UI_EVIDENCE_DIR || '.evidence/ui-implementation-20261108')
await mkdir(evidence, { recursive: true })
const browser = await chromium.launch({ channel: 'chrome', headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, colorScheme: 'light', locale: 'zh-CN' })
const page = await context.newPage()
page.setDefaultTimeout(12000)
const errors = []
page.on('pageerror', error => errors.push(error.message))
const requests = []
const checks = []
const contrasts = []
const state = { role: 'admin', failure: '', empty: false, createFailure: false, delay: 0, activeTask: null, longName: false, createDelay: 0, authFailure: false, authNetworkFailure: false }
const avatar = 'data:image/svg+xml,' + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="40" height="40"><rect width="40" height="40" rx="20" fill="#e4ecfb"/><text x="20" y="26" text-anchor="middle" font-size="18" fill="#305bad">林</text></svg>')
const person = { id: '1001', userName: '林舟', userAccount: 'linzhou', userRole: 'admin', userAvatar: avatar }
const users = Array.from({ length: 12 }, (_, i) => ({ id: String(469694549116215290n + BigInt(i)), userName: ['林舟', '陈一', '许南', '周可', '江望', '李安'][i % 6], userAccount: `creator_${i + 1}`, userAvatar: avatar, userProfile: i % 3 ? '用 AI 记录灵感，构建自己的数字作品。' : '', userRole: i === 0 ? 'admin' : i === 2 ? 'ban' : 'user', createTime: '2026-11-06T10:24:00' }))
const names = ['摄影作品集', '城市漫游指南', '极简个人博客', '独立咖啡店', '灵感收集工具', '设计工作室官网']
const apps = Array.from({ length: 12 }, (_, i) => ({ id: String(469694549116215310n + BigInt(i)), appName: names[i % 6], initPrompt: '创建一个布局清晰、可在手机上阅读的网站。以大幅作品展示和简洁文字为主，保留导航、内容与联系入口。', userId: '1001', user: person, codeGenType: ['html', 'multi_file', 'vue_project'][i % 3], priority: i % 2 ? 0 : 99, cover: i % 3 === 2 ? '' : `${origin}/ui-test-cover/${i}`, createTime: '2026-11-06T10:24:00', updateTime: '2026-11-07T13:30:00', generationMode: 'standard', deployKey: i === 0 ? 'ui-test-work' : undefined }))
const chats = Array.from({ length: 12 }, (_, i) => ({ id: String(469694549116215340n + BigInt(i)), appId: apps[i % 6].id, userId: '1001', messageType: i % 2 ? 'ai' : 'user', message: i % 2 ? '页面已完成。包含作品展示、分类筛选和联系入口，适配桌面与手机。\n\n```html\n<main>作品展示</main>\n```' : '请将首页调整为更简洁的作品展示，保留完整导航和联系入口，并支持手机浏览。', createTime: '2026-11-06T10:24:00', updateTime: '2026-11-07T11:08:00' }))
const preview = '<!doctype html><html lang="zh-CN"><meta charset="utf-8"><style>body{margin:0;font-family:system-ui;background:#f6f2eb;color:#27382e}nav{display:flex;justify-content:space-between;padding:24px 32px;border-bottom:1px solid #d4d8cf;font-size:13px}main{padding:48px 36px}h1{font-size:48px;line-height:1.2;margin:12px 0 20px;font-weight:500}.scene{height:250px;background:#d4ddd0;border-radius:2px;margin-top:32px;display:grid;place-items:center;font-size:22px;color:#506750}p{font-size:14px;line-height:1.8;color:#5c6b60}</style><nav><strong>城市漫游</strong><span>目的地　关于我们</span></nav><main><p>界面验收用示例作品</p><h1>慢一点，<br>发现城市的另一面。</h1><p>收集街角的咖啡馆、公园与书店。<br>为下一段周末散步，留一点期待。</p><div class="scene">下一站，去街角走走。</div></main></html>'
const envelope = (data, code = 0, message = '') => JSON.stringify({ code, data, message })
await context.route('**/*', async route => {
  const request = route.request()
  const url = new URL(request.url())
  if (url.pathname.startsWith('/ui-test-cover/')) {
    const n = Number(url.pathname.split('/').at(-1))
    const bg = ['#e7ece3','#e7eaf2','#f1e9df'][n % 3]
    return route.fulfill({ contentType: 'image/svg+xml', body: `<svg xmlns="http://www.w3.org/2000/svg" width="640" height="400"><rect width="640" height="400" fill="${bg}"/><path d="M0 300L180 190L380 320L520 140L640 230V400H0Z" fill="#819785" opacity=".35"/><text x="42" y="76" font-family="sans-serif" font-size="26" fill="#37473e">${names[n % 6]}</text><text x="42" y="110" font-family="sans-serif" font-size="14" fill="#53645a">界面验收 · 示例封面</text></svg>` })
  }
  if (url.pathname.startsWith('/api/static/')) return route.fulfill({ contentType: 'text/html', body: preview })
  if (!url.pathname.startsWith('/api/')) {
    if (url.origin === origin || url.protocol === 'data:') return route.continue()
    return route.fulfill({ status: 204, body: '' })
  }
  const path = url.pathname.slice(4)
  const body = request.postDataJSON() || {}
  requests.push({ path, method: request.method(), body })
  const reply = (data, code = 0, message = '') => route.fulfill({ contentType: 'application/json', headers: { 'access-control-allow-origin': origin, 'access-control-allow-credentials': 'true', 'x-request-id': 'ui-test-request' }, body: envelope(data, code, message) })
  if (request.method() === 'OPTIONS') return route.fulfill({ status: 204, headers: { 'access-control-allow-origin': origin, 'access-control-allow-credentials': 'true', 'access-control-allow-methods': 'GET,POST,OPTIONS', 'access-control-allow-headers': 'content-type' }, body: '' })
  if (path === '/user/get/login') return state.role === 'guest' ? reply(null, 40100) : reply({ ...person, userRole: state.role })
  if (path === '/app/add') {
    if (state.createDelay) await new Promise(resolve => setTimeout(resolve, state.createDelay))
    return state.createFailure ? reply(null, 50000, '创建暂不可用') : reply(apps[0].id)
  }
  if (path === '/user/login' || path === '/user/register') {
    if (state.authNetworkFailure) return route.abort('failed')
    if (state.authFailure) return reply(null, 40000, '账号或密码不正确')
    if (path === '/user/login') { state.role = 'user'; return reply({ ...person, userRole: 'user' }) }
    return reply(person.id)
  }
  if (path === '/app/get/vo' || path === '/app/admin/get/vo') return reply({ ...apps[0], appName: state.longName ? '这是用于检查长名称是否挤压工作台操作按钮的应用名称'.repeat(3) : '城市漫游指南' })
  if (path === '/app/generation/tasks/current') return reply({ task: state.activeTask, previewAvailable: true })
  if (path === '/app/generation/tasks/ui-test-task/stop') {
    state.activeTask = { ...state.activeTask, status: 'CANCELLED', revision: 2, message: '任务已停止，保留上次成功预览' }
    return reply(state.activeTask)
  }
  if (path === '/app/generation/tasks/ui-test-task') return reply(state.activeTask)
  if (path === '/app/generation/tasks/ui-test-task/events') return route.fulfill({ contentType: 'text/event-stream', body: `event: task-snapshot\ndata: ${JSON.stringify(state.activeTask)}\n\n`, headers: { 'access-control-allow-origin': origin, 'access-control-allow-credentials': 'true' } })
  if (path === '/app/traces') return reply({ records: [], nextCursor: null })
  if (path === '/chatHistory/app/list/page/vo') return reply({ records: chats.slice(0, 2), totalRow: 2 })
  if (path.includes('/list/page')) {
    if (state.delay) await new Promise(resolve => setTimeout(resolve, state.delay))
    if (path === state.failure) return reply(null, 50000, '列表暂不可用')
    let rows = path.startsWith('/user') ? users : path.startsWith('/chatHistory') ? chats : apps
    if (state.empty) rows = []
    for (const field of ['userAccount', 'userName', 'appName', 'initPrompt', 'message', 'messageType', 'codeGenType']) {
      if (body[field]) rows = rows.filter(row => String(row[field] || '').includes(body[field]))
    }
    const totalRow = rows.length
    const size = body.pageSize || 10
    const current = body.pageNum || 1
    return reply({ records: rows.slice((current - 1) * size, current * size), totalRow })
  }
  if (['/user/update', '/user/delete', '/app/admin/update', '/app/admin/delete', '/app/update'].includes(path)) return reply(true)
  // 未声明的接口一律拦截，避免验收触达真实后端、部署或模型。
  return reply(null, 50000, '此接口未加入界面验收替身')
})
async function check(name, fn) { await fn(); checks.push(name); console.log(`通过：${name}`) }
async function shot(name, fullPage = false) {
  await page.evaluate(async () => {
    await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))
    await Promise.all(document.getAnimations()
      .filter(animation => animation.effect?.getComputedTiming().endTime < 2000)
      .map(animation => animation.finished.catch(() => {})))
  })
  // 抽屉挂载即为 visible，但入场时仍可能在视口外；截图前确认面板已完全进入。
  await page.waitForFunction(() => [...document.querySelectorAll('.ant-drawer-open .ant-drawer-content-wrapper')].every(el => {
    const rect = el.getBoundingClientRect()
    return rect.left >= -1 && rect.right <= innerWidth + 1 && rect.width > 0
  }))
  await page.screenshot({ path: resolve(evidence, `${name}.png`), fullPage, animations: 'disabled' })
}
async function noOverflow() { assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), '页面不应出现横向溢出') }
try {
  await page.goto(origin)
  await page.locator('.app-card').first().waitFor()
  await check('首页集中展示模式与创建动作，头部无重复模式', async () => {
    assert.equal(await page.locator('.global-header .ant-segmented').count(), 0)
    assert.equal(await page.getByRole('button', { name: '开始生成', exact: true }).count(), 1)
  })
  await check('系统浅色偏好下全站仍为暗色，顶栏与首页基底连续', async () => {
    assert.equal(await page.locator('html').getAttribute('data-theme'), 'dark')
    for (const selector of ['body', '.hero-section', '.library', '.layout-footer']) {
      assert.equal(await page.locator(selector).evaluate(el => getComputedStyle(el).backgroundColor), 'rgb(9, 13, 22)')
    }
    const header = await page.locator('.layout-header').evaluate(el => {
      const style = getComputedStyle(el)
      return { background: style.backgroundColor, blur: style.backdropFilter, height: style.height, border: style.borderBottomColor }
    })
    assert.deepEqual(header, { background: 'rgba(9, 13, 22, 0.8)', blur: 'blur(16px)', height: '64px', border: 'rgba(255, 255, 255, 0.08)' })
    assert.equal(await page.locator('.global-header').evaluate(el => getComputedStyle(el).height), '63px')
  })
  await check('网格与全屏聚光移除，流线安静且微光贴合输入框', async () => {
    assert.equal(await page.locator('.stage-grid, .spotlight').count(), 0)
    assert.equal(await page.locator('.background-lines').evaluate(el => getComputedStyle(el).opacity), '0.04')
    const glow = await page.locator('.prompt-box').evaluate(el => {
      const style = getComputedStyle(el, '::before')
      return { width: parseFloat(style.width), height: parseFloat(style.height), pointer: style.pointerEvents }
    })
    assert.ok(glow.width < 900 && glow.height < 500)
    assert.equal(glow.pointer, 'none')
  })
  await check('示例、提示、输入与主按钮满足实测对比度', async () => {
    const pairs = await page.evaluate(() => {
      const ratio = (foreground, background) => {
        const luminance = color => {
          const rgb = color.match(/[\d.]+/g).slice(0, 3).map(value => {
            const channel = Number(value) / 255
            return channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4
          })
          return rgb[0] * 0.2126 + rgb[1] * 0.7152 + rgb[2] * 0.0722
        }
        const a = luminance(foreground), b = luminance(background)
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05)
      }
      const style = selector => getComputedStyle(document.querySelector(selector))
      const chip = style('.sample-chip'), prompt = style('.prompt-box'), action = style('.create-button'), shell = style('.hero-section')
      const search = style('.section-search input')
      return [
        { name: '示例文字', ratio: ratio(chip.color, chip.backgroundColor), min: 4.5 },
        { name: '示例边框与内侧', ratio: ratio(chip.borderColor, chip.backgroundColor), min: 3 },
        { name: '示例边框与外侧', ratio: ratio(chip.borderColor, shell.backgroundColor), min: 3 },
        { name: '轮播提示', ratio: ratio(style('.prompt-placeholder').color, prompt.backgroundColor), min: 4.5 },
        { name: '主按钮文字', ratio: ratio(action.color, action.backgroundColor), min: 4.5 },
        { name: '主按钮边界', ratio: ratio(action.backgroundColor, prompt.backgroundColor), min: 3 },
        { name: '搜索文字', ratio: ratio(search.color, search.backgroundColor), min: 4.5 },
        { name: '作品辅文案', ratio: ratio(style('.card-prompt').color, style('.app-card').backgroundColor), min: 4.5 },
      ]
    })
    contrasts.push(...pairs)
    for (const pair of pairs) assert.ok(pair.ratio >= pair.min, `${pair.name}：${pair.ratio.toFixed(2)}:1`)
    await page.locator('.sample-chip').first().focus()
    assert.ok(await page.locator('.sample-chip').first().evaluate(el => {
      const style = getComputedStyle(el)
      return style.outlineStyle === 'solid' && parseFloat(style.outlineWidth) >= 2
    }))
    await page.locator('.sample-chip').first().blur()
  })
  await check('深色作品卡片与部署胶囊融入统一表面', async () => {
    const style = await page.locator('.app-card').first().evaluate(el => {
      const css = getComputedStyle(el)
      return { background: css.backgroundColor, border: css.borderColor }
    })
    assert.deepEqual(style, { background: 'rgb(18, 26, 39)', border: 'rgba(255, 255, 255, 0.08)' })
    assert.equal(await page.locator('.deploy-state.deployed').first().evaluate(el => getComputedStyle(el).backgroundColor), 'rgb(26, 53, 48)')
  })
  await shot('home-desktop')
  await shot('home-full', true)
  await check('空输入轮播示例，聚焦后暂停轮播', async () => {
    const hint = page.locator('.prompt-placeholder span')
    const initial = await hint.innerText()
    await page.waitForFunction(text => document.querySelector('.prompt-placeholder span')?.textContent !== text, initial, { timeout: 5500 })
    await page.getByRole('textbox', { name: '描述你想创建的应用' }).focus()
    const focusedHint = await hint.innerText()
    await page.waitForTimeout(4400)
    assert.equal(await hint.innerText(), focusedHint)
  })
  await check('Bento 卡片宽度错落，元数据与流光对应真实内容', async () => {
    const cards = page.locator('.app-card')
    assert.ok((await cards.nth(0).boundingBox()).width > (await cards.nth(1).boundingBox()).width)
    assert.ok((await cards.first().innerText()).includes('标准模式'))
    assert.ok((await cards.first().innerText()).includes('更新于'))
    assert.ok((await cards.first().innerText()).includes('已部署'))
    await cards.first().hover({ position: { x: 100, y: 100 } })
    assert.ok(await cards.first().evaluate(el => el.style.getPropertyValue('--pointer-x').length > 0))
    await page.waitForFunction(() => getComputedStyle(document.querySelector('.app-card')).boxShadow.includes('32px'))
    await shot('home-bento', true)
  })
  await check('复制提示词与部署入口保留原行为', async () => {
    await context.grantPermissions(['clipboard-read', 'clipboard-write'])
    await page.locator('.app-card').first().getByRole('button', { name: '复制提示词', exact: true }).click()
    assert.equal(await page.evaluate(() => navigator.clipboard.readText()), apps[0].initPrompt)
    await page.evaluate(() => { window.open = url => { window.__openedWork = url; return null } })
    await page.locator('.app-card').first().getByRole('button', { name: '打开已部署作品', exact: true }).click()
    assert.ok((await page.evaluate(() => window.__openedWork)).includes('ui-test-work'))
  })
  await check('输入法组合快捷键不触发创建', async () => {
    const before = requests.filter(item => item.path === '/app/add').length
    const input = page.getByRole('textbox', { name: '描述你想创建的应用' })
    await input.fill('正在组合的中文需求')
    await input.dispatchEvent('keydown', { key: 'Enter', ctrlKey: true, isComposing: true })
    await page.waitForTimeout(100)
    assert.equal(requests.filter(item => item.path === '/app/add').length, before)
  })
  await check('首页模式与需求正确提交，失败保留输入', async () => {
    await page.getByText('工作流模式', { exact: true }).click()
    await page.getByRole('textbox', { name: '描述你想创建的应用' }).fill('创建一个读书笔记网站')
    state.createFailure = true; state.createDelay = 1100
    const before = requests.filter(item => item.path === '/app/add').length
    await page.evaluate(() => window.scrollTo(0, 0))
    await page.getByRole('button', { name: '开始生成', exact: true }).click()
    await page.locator('.prompt-input.vanishing').waitFor()
    assert.ok(await page.locator('.prompt-particles').evaluate(el => el.width > 0 && el.getContext('2d').getImageData(0, 0, el.width, el.height).data.some(value => value > 0)))
    await shot('home-particles')
    await page.getByRole('textbox', { name: '描述你想创建的应用' }).press('Control+Enter')
    assert.equal(requests.filter(item => item.path === '/app/add').length, before + 1)
    await page.locator('.creation-diagnostic').waitFor()
    const create = requests.findLast(item => item.path === '/app/add')
    assert.equal(create.body.generationMode, 'workflow')
    assert.equal(create.body.initPrompt, '创建一个读书笔记网站')
    assert.equal(await page.getByRole('textbox', { name: '描述你想创建的应用' }).inputValue(), '创建一个读书笔记网站')
    assert.equal(await page.locator('.prompt-input.vanishing').count(), 0)
    state.createFailure = false; state.createDelay = 0
  })
  await check('首页请求失败提供重试，恢复后显示数据', async () => {
    state.failure = '/app/my/list/page/vo'
    await page.getByRole('textbox', { name: '搜索我的应用' }).fill('摄影')
    await page.getByRole('textbox', { name: '搜索我的应用' }).press('Enter')
    await page.getByRole('button', { name: '重新加载作品' }).waitFor()
    state.failure = ''
    await page.getByRole('button', { name: '重新加载作品' }).click()
    await page.locator('.app-card').filter({ hasText: '摄影作品集' }).first().waitFor()
  })
  await page.goto(`${origin}/admin/userManage`)
  await page.locator('.record-identity').first().waitFor()
  await shot('admin-users-desktop')
  await check('用户身份列保留账号、昵称、长 ID 与角色', async () => {
    assert.ok((await page.locator('tbody').innerText()).includes(users[0].id))
    assert.ok((await page.locator('tbody').innerText()).includes('creator_1'))
    assert.ok((await page.locator('tbody').innerText()).includes('封禁'))
  })
  await check('用户筛选与重置保持接口语义', async () => {
    await page.getByLabel('账号', { exact: true }).fill('creator_1')
    await page.getByRole('button', { name: '搜索', exact: true }).click()
    await page.waitForFunction(() => document.querySelector('.admin-count')?.textContent?.includes('4'))
    assert.equal(requests.findLast(item => item.path === '/user/list/page/vo').body.userAccount, 'creator_1')
    await page.getByRole('button', { name: '重置', exact: true }).click()
    await page.waitForFunction(() => document.querySelector('.admin-count')?.textContent?.includes('12'))
  })
  await check('管理员分页请求正确，返回第一页', async () => {
    await page.locator('.ant-pagination-item-2').click()
    await page.waitForFunction(() => document.querySelectorAll('tbody tr.ant-table-row').length === 2)
    assert.equal(requests.findLast(item => item.path === '/user/list/page/vo').body.pageNum, 2)
    await page.locator('.ant-pagination-item-1').click()
    await page.waitForFunction(() => document.querySelectorAll('tbody tr.ant-table-row').length === 10)
  })
  await check('用户编辑可保存，删除仍需确认', async () => {
    await page.getByRole('button', { name: '编辑', exact: true }).first().click()
    await page.locator('.ant-modal:visible, .ant-drawer-content-wrapper:visible').waitFor()
    await page.locator('.ant-modal:visible, .ant-drawer-content-wrapper:visible').getByLabel('昵称').fill('验收编辑昵称')
    await page.locator('.ant-modal:visible, .ant-drawer-content-wrapper:visible').getByRole('button', { name: '确定' }).click()
    await page.locator('.ant-modal:visible, .ant-drawer-content-wrapper:visible').waitFor({ state: 'hidden' })
    assert.equal(requests.findLast(item => item.path === '/user/update').body.userName, '验收编辑昵称')
    const old = requests.filter(item => item.path === '/user/delete').length
    await page.getByRole('button', { name: '删除', exact: true }).first().click()
    await page.getByText('确定删除该用户吗？').waitFor()
    assert.equal(requests.filter(item => item.path === '/user/delete').length, old)
    await page.getByRole('button', { name: '取消', exact: true }).click()
  })
  await check('管理员列表失败与空列表分开，均可恢复', async () => {
    state.failure = '/user/list/page/vo'
    await page.getByRole('button', { name: '搜索', exact: true }).click()
    await page.locator('.admin-inline-error').waitFor()
    await shot('admin-error')
    state.failure = ''; state.empty = true
    await page.getByRole('button', { name: '重新加载', exact: true }).click()
    await page.getByRole('button', { name: '清空筛选' }).waitFor()
    await shot('admin-empty')
    state.empty = false
    await page.getByRole('button', { name: '清空筛选' }).click()
    await page.locator('.record-identity').first().waitFor()
  })
  await page.goto(`${origin}/admin/appManage`)
  await page.locator('.record-identity').first().waitFor()
  await shot('admin-apps-desktop')
  await check('应用管理保留封面放大预览', async () => {
    await page.locator('.record-cover .ant-image').first().click()
    await page.locator('.ant-image-preview-img').waitFor()
    await page.locator('.ant-image-preview-operations-operation').first().click()
    await page.locator('.ant-image-preview-img').waitFor({ state: 'hidden' })
  })
  await check('应用长需求可查看，精选切换使用原优先级接口', async () => {
    await page.getByRole('button', { name: /查看摄影作品集的完整需求/ }).first().click()
    await page.locator('.ant-modal:visible, .ant-drawer-content-wrapper:visible').waitFor()
    assert.ok((await page.locator('.ant-modal:visible, .ant-drawer-content-wrapper:visible').innerText()).includes(apps[0].initPrompt))
    await page.locator('.ant-modal:visible, .ant-drawer-content-wrapper:visible').getByRole('button', { name: 'Close', exact: true }).click()
    await page.locator('.ant-modal:visible, .ant-drawer-content-wrapper:visible').waitFor({ state: 'hidden' })
    await Promise.all([page.waitForResponse(response => response.url().includes('/app/admin/update')), page.getByRole('button', { name: '取消精选', exact: true }).first().click()])
    assert.equal(requests.findLast(item => item.path === '/app/admin/update').body.priority, 0)
  })
  await page.goto(`${origin}/admin/chatHistoryManage`)
  await page.locator('tbody .record-excerpt').first().waitFor()
  await shot('admin-messages-desktop')
  await check('消息详情完整保留正文及关联 ID', async () => {
    await page.getByRole('button', { name: `查看完整消息 ${chats[0].id}`, exact: true }).click()
    await page.locator('.ant-modal:visible, .ant-drawer-content-wrapper:visible').waitFor()
    const text = await page.locator('.ant-modal:visible, .ant-drawer-content-wrapper:visible').innerText()
    assert.ok(text.includes(chats[0].message) && text.includes(chats[0].appId))
    await page.locator('.ant-modal:visible, .ant-drawer-content-wrapper:visible').getByRole('button', { name: 'Close', exact: true }).click()
    await page.locator('.ant-modal:visible, .ant-drawer-content-wrapper:visible').waitFor({ state: 'hidden' })
  })
  await page.goto(`${origin}/app/chat/${apps[0].id}`)
  await page.locator('.preview-frame').waitFor()
  await shot('workbench-desktop')
  await check('生成记录以抽屉覆盖，关闭保留草稿、尺寸与焦点', async () => {
    const input = page.getByRole('textbox', { name: '向 AI 描述修改或新需求' })
    await input.fill('把导航背景改为透明')
    const before = await page.locator('.workspace').boundingBox()
    await page.getByRole('button', { name: '生成记录', exact: true }).click()
    await page.locator('.ant-modal:visible, .ant-drawer-content-wrapper:visible').waitFor()
    assert.ok((await page.locator('.workspace').boundingBox()).height === before.height)
    await shot('workbench-trace')
    await page.keyboard.press('Escape')
    await page.locator('.ant-modal:visible, .ant-drawer-content-wrapper:visible').waitFor({ state: 'hidden' })
    assert.equal(await input.inputValue(), '把导航背景改为透明')
    await page.waitForFunction(() => document.activeElement?.textContent?.trim() === '生成记录')
  })
  await check('桌面分栏键盘调节与折叠保持可用', async () => {
    const splitter = page.getByRole('separator')
    await splitter.focus(); await page.keyboard.press('ArrowRight')
    assert.equal(await splitter.getAttribute('aria-valuenow'), '38')
    await page.getByRole('button', { name: '折叠对话面板', exact: true }).click()
    assert.equal(await page.locator('.chat-panel').isVisible(), false)
    await page.getByRole('button', { name: '展开对话面板', exact: true }).click()
  })
  await page.setViewportSize({ width: 390, height: 844 })
  await check('手机视图切换保留草稿，工作区不横向溢出', async () => {
    await page.getByRole('button', { name: '对话', exact: true }).click()
    await shot('workbench-mobile-chat')
    await page.getByRole('button', { name: '预览', exact: true }).click()
    assert.equal(await page.locator('.chat-panel').isVisible(), false)
    assert.equal(await page.locator('.preview-frame').isVisible(), true)
    await shot('workbench-mobile-preview')
    await page.getByRole('button', { name: '代码', exact: true }).click()
    assert.equal(await page.locator('.code-viewer-wrapper').isVisible(), true)
    await page.getByRole('button', { name: '对话', exact: true }).click()
    assert.equal(await page.getByRole('textbox', { name: '向 AI 描述修改或新需求' }).inputValue(), '把导航背景改为透明')
    await noOverflow()
  })
  await check('手机记录抽屉适配视口', async () => {
    await page.getByRole('button', { name: '生成记录', exact: true }).click()
    await page.locator('.ant-modal:visible, .ant-drawer-content-wrapper:visible').waitFor()
    assert.ok((await page.locator('.ant-modal:visible, .ant-drawer-content-wrapper:visible').boundingBox()).width <= 390)
    await shot('workbench-mobile-trace')
    await page.keyboard.press('Escape')
    await page.locator('.ant-modal:visible, .ant-drawer-content-wrapper:visible').waitFor({ state: 'hidden' })
  })
  await check('手机恢复生成任务后停止入口可用，停止后保留成功预览', async () => {
    state.activeTask = { id: 'ui-test-task', appId: apps[0].id, clientRequestId: 'ui-test', prompt: '调整导航布局', status: 'RUNNING', stage: '生成代码', message: '正在生成代码', output: '正在调整导航…', revision: 1, previewAvailable: true, steps: [] }
    await page.goto(`${origin}/app/chat/${apps[0].id}`)
    await page.locator('.generation-task-status').waitFor()
    const stop = page.locator('.generation-task-status').getByRole('button')
    const bounds = await stop.boundingBox()
    assert.ok(bounds.y >= 0 && bounds.y + bounds.height <= 844)
    await shot('workbench-mobile-running')
    await stop.click()
    await page.locator('.generation-task-status').waitFor({ state: 'hidden' })
    await page.getByRole('button', { name: '预览', exact: true }).click()
    assert.equal(await page.locator('.preview-frame').isVisible(), true)
    state.activeTask = null
  })
  await check('320px 长应用名不挤掉部署和生成记录动作', async () => {
    state.longName = true
    await page.setViewportSize({ width: 320, height: 720 })
    await page.goto(`${origin}/app/chat/${apps[0].id}`)
    await page.getByRole('button', { name: '生成记录', exact: true }).waitFor()
    await noOverflow()
    for (const selector of ['.primary-action-btn', '.trace-trigger']) {
      const bounds = await page.locator(selector).boundingBox()
      assert.ok(bounds.x >= 0 && bounds.x + bounds.width <= 320)
    }
    await shot('workbench-narrow-long-name')
    state.longName = false
    await page.setViewportSize({ width: 390, height: 844 })
  })
  for (const [route, name] of [['/', 'home'], ['/admin/userManage', 'admin-users'], ['/admin/appManage', 'admin-apps'], ['/admin/chatHistoryManage', 'admin-messages']]) {
    await page.goto(origin + route)
    await page.locator(route === '/' ? '.app-card' : 'tbody tr.ant-table-row').first().waitFor()
    await check(`${name} 手机无整页横向溢出`, noOverflow)
    await shot(`${name}-mobile`, true)
  }
  await page.setViewportSize({ width: 834, height: 1112 })
  await page.goto(origin)
  await page.locator('.app-card').first().waitFor()
  await check('平板首页、导航与双列作品无横向溢出', noOverflow)
  await shot('home-tablet', true)
  await page.emulateMedia({ colorScheme: 'dark', reducedMotion: 'reduce' })
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.goto(`${origin}/admin/userManage`)
  await page.locator('.record-identity').first().waitFor()
  await shot('admin-users-dark')
  await check('深色管理操作链接与表格底色对比度不低于 4.5', async () => {
    const ratio = await page.locator('.ant-table-cell .ant-btn-link').first().evaluate(el => {
      const luminance = color => {
        const rgb = color.match(/[\d.]+/g).slice(0, 3).map(value => {
          const channel = Number(value) / 255
          return channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4
        })
        return rgb[0] * 0.2126 + rgb[1] * 0.7152 + rgb[2] * 0.0722
      }
      const foreground = luminance(getComputedStyle(el).color)
      const background = luminance(getComputedStyle(el.closest('.ant-table')).backgroundColor)
      return (Math.max(foreground, background) + 0.05) / (Math.min(foreground, background) + 0.05)
    })
    assert.ok(ratio >= 4.5, `链接对比度 ${ratio.toFixed(2)}:1`)
  })
  await check('系统偏好变化后仍保持暗色，弹窗继承统一表面', async () => {
    assert.equal(await page.locator('html').getAttribute('data-theme'), 'dark')
    await page.getByRole('button', { name: '编辑', exact: true }).first().click()
    await page.locator('.ant-modal:visible, .ant-drawer-content-wrapper:visible').waitFor()
    const bg = await page.locator('.ant-modal-content').evaluate(el => getComputedStyle(el).backgroundColor)
    assert.notEqual(bg, 'rgb(255, 255, 255)')
    await shot('admin-edit-dark')
    await page.keyboard.press('Escape')
    await page.locator('.ant-modal:visible, .ant-drawer-content-wrapper:visible').waitFor({ state: 'hidden' })
  })
  await page.goto(origin)
  await page.locator('.app-card').first().waitFor()
  await shot('home-dark')
  await page.goto(`${origin}/app/chat/${apps[0].id}`)
  await page.locator('.preview-frame').waitFor()
  await shot('workbench-dark')
  state.role = 'guest'
  const privateBefore = requests.filter(item => item.path === '/app/my/list/page/vo').length
  await page.goto(origin)
  await page.getByRole('button', { name: '登录查看作品' }).waitFor()
  await check('未登录首页不请求私有作品接口', async () => {
    assert.equal(requests.filter(item => item.path === '/app/my/list/page/vo').length, privateBefore)
  })
  await check('未登录与普通用户仍被管理路由拦截', async () => {
    await page.goto(`${origin}/admin/userManage`)
    await page.waitForURL('**/user/login?**')
    state.role = 'user'
    await page.goto(`${origin}/admin/userManage`)
    await page.waitForURL(origin + '/')
    assert.equal(await page.locator('.admin-page').count(), 0)
  })
  await check('减少动画模式停止流线、轮播与粒子，创建仍可用', async () => {
    await page.goto(origin)
    await page.locator('.prompt-placeholder').waitFor()
    assert.equal(await page.locator('.line-light').first().evaluate(el => getComputedStyle(el).animationName), 'none')
    assert.equal(await page.locator('.prompt-box').evaluate(el => getComputedStyle(el, '::before').animationName), 'none')
    const hint = await page.locator('.prompt-placeholder').innerText()
    await page.waitForTimeout(4400)
    assert.equal(await page.locator('.prompt-placeholder').innerText(), hint)
    state.createFailure = true; state.createDelay = 400
    await page.getByRole('textbox', { name: '描述你想创建的应用' }).fill('减少动画也可以创建')
    await page.getByRole('button', { name: '开始生成', exact: true }).click()
    assert.equal(await page.locator('.prompt-input.vanishing').count(), 0)
    await page.locator('.creation-diagnostic').waitFor()
    state.createFailure = false; state.createDelay = 0
  })
  await check('创建成功只提交一次，知识库选择带入原工作台', async () => {
    const before = requests.filter(item => item.path === '/app/add').length
    await page.getByRole('textbox', { name: '描述你想创建的应用' }).fill('创建一个读书笔记应用')
    await page.getByRole('checkbox', { name: '使用个人知识库' }).check()
    await page.getByRole('button', { name: '开始生成', exact: true }).click()
    await page.waitForURL(`${origin}/app/chat/${apps[0].id}`)
    assert.equal(requests.filter(item => item.path === '/app/add').length, before + 1)
    assert.deepEqual(Object.keys(requests.findLast(item => item.path === '/app/add').body).sort(), ['generationMode', 'initPrompt'])
    await page.getByRole('checkbox', { name: '使用个人知识库' }).waitFor()
    assert.equal(await page.getByRole('checkbox', { name: '使用个人知识库' }).isChecked(), true)
  })
  await page.emulateMedia({ colorScheme: 'light', reducedMotion: 'no-preference' })
  state.role = 'guest'
  await page.goto(`${origin}/user/login`)
  await page.getByRole('heading', { name: '欢迎回来。' }).waitFor()
  await page.locator('.auth-art > img').evaluate(img => img.decode())
  await shot('login-desktop', true)
  await check('登录左右分栏与本地图片就绪，无额外登录功能', async () => {
    const form = await page.locator('.auth-content').boundingBox()
    const art = await page.locator('.auth-art').boundingBox()
    assert.ok(form.x + form.width < art.x)
    assert.equal(await page.locator('.auth-art > img').evaluate(img => img.naturalWidth > 0), true)
    assert.equal(await page.locator('.auth-form-wrap input').count(), 2)
    assert.equal(await page.locator('.global-header').count(), 0)
    await noOverflow()
  })
  await check('登录校验与网络失败保留输入，成功沿用返回路径', async () => {
    const before = requests.filter(item => item.path === '/user/login').length
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await page.getByText('请输入账号', { exact: true }).waitFor()
    assert.equal(requests.filter(item => item.path === '/user/login').length, before)
    await page.getByLabel('账号', { exact: true }).fill('ui_test_user')
    await page.getByLabel('密码', { exact: true }).fill('ui_test_password')
    state.authNetworkFailure = true
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await page.locator('.auth-error').waitFor()
    assert.equal(await page.getByLabel('账号', { exact: true }).inputValue(), 'ui_test_user')
    assert.equal(await page.getByLabel('密码', { exact: true }).inputValue(), 'ui_test_password')
    state.authNetworkFailure = false; state.authFailure = true
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await page.getByText('账号或密码不正确', { exact: true }).waitFor()
    await shot('login-error')
    state.authFailure = false
    await page.goto(`${origin}/user/login?redirect=/about`)
    await page.getByLabel('账号', { exact: true }).fill('ui_test_user')
    await page.getByLabel('密码', { exact: true }).fill('ui_test_password')
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await page.waitForURL(origin + '/about')
  })
  state.role = 'guest'
  await page.goto(`${origin}/user/register`)
  await page.getByRole('heading', { name: '让想法，有个开始。' }).waitFor()
  await page.locator('.auth-art > img').evaluate(img => img.decode())
  await shot('register-desktop', true)
  await check('注册保留三字段、确认密码校验和成功回登录', async () => {
    const before = requests.filter(item => item.path === '/user/register').length
    await page.getByLabel('账号', { exact: true }).fill('ui_test_user')
    await page.getByLabel('密码', { exact: true }).fill('ui_test_password')
    await page.getByLabel('确认密码', { exact: true }).fill('different_password')
    await page.getByRole('button', { name: '注册', exact: true }).click()
    await page.getByText('两次输入的密码不一致', { exact: true }).waitFor()
    assert.equal(requests.filter(item => item.path === '/user/register').length, before)
    await page.getByLabel('确认密码', { exact: true }).fill('ui_test_password')
    await page.getByRole('button', { name: '注册', exact: true }).click()
    await page.waitForURL(origin + '/user/login')
    const payload = requests.findLast(item => item.path === '/user/register').body
    assert.deepEqual(Object.keys(payload).sort(), ['checkPassword', 'userAccount', 'userPassword'])
  })
  for (const route of ['login', 'register']) {
    await page.setViewportSize({ width: 390, height: 844 })
    await page.goto(`${origin}/user/${route}`)
    await page.locator('.auth-form-wrap').waitFor()
    await check(`${route} 手机表单完整可用，无横向溢出`, async () => {
      assert.equal(await page.locator('.auth-art').isVisible(), false)
      await noOverflow()
    })
    await shot(`${route}-mobile`, true)
    await page.emulateMedia({ colorScheme: 'dark', reducedMotion: 'reduce' })
    await page.setViewportSize({ width: 1440, height: 1000 })
    await shot(`${route}-dark`, true)
    await page.emulateMedia({ colorScheme: 'light', reducedMotion: 'no-preference' })
  }
  await check('浏览器无未捕获脚本异常', async () => assert.deepEqual(errors, []))
} catch (error) {
  await shot('failure', true)
  throw error
} finally {
  await writeFile(resolve(evidence, 'browser-results.json'), JSON.stringify({ checks, contrasts, errors, requestCount: requests.length, scope: '真实 Chrome + 受控 API 数据；未验证真实后端或供应商' }, null, 2))
  await browser.close()
}
