// 영상용 화면 녹화(목 모드). CDP 스크린캐스트 프레임을 시각과 함께 저장하고 ffmpeg로 mp4를 만든다.
import { chromium } from '@playwright/test'
import fs from 'node:fs'
import { execFileSync } from 'node:child_process'

const BASE = 'http://localhost:5199'
const OUT = new URL('./clips/', import.meta.url).pathname
fs.mkdirSync(OUT, { recursive: true })

const CURSOR = `
(() => {
  const add = () => {
    if (document.getElementById('__cur')) return
    const c = document.createElement('div'); c.id = '__cur'
    c.style.cssText = 'position:fixed;left:0;top:0;width:34px;height:34px;z-index:2147483647;pointer-events:none;transform:translate(-200px,-200px);transition:none'
    c.innerHTML = '<svg width="34" height="34" viewBox="0 0 24 24"><path d="M4 2l15 9-6.5 1.6L16 20l-2.6 1.3-3.4-7.3L5 18z" fill="#111" stroke="#fff" stroke-width="1.4" stroke-linejoin="round"/></svg>'
    document.documentElement.appendChild(c)
    const r = document.createElement('div'); r.id = '__ring'
    r.style.cssText = 'position:fixed;left:0;top:0;width:44px;height:44px;margin:-22px 0 0 -22px;border-radius:50%;border:3px solid #3B40D8;z-index:2147483646;pointer-events:none;opacity:0'
    document.documentElement.appendChild(r)
    addEventListener('mousemove', e => { c.style.transform = 'translate(' + (e.clientX - 4) + 'px,' + (e.clientY - 2) + 'px)' }, true)
    addEventListener('mousedown', e => { r.style.transition = 'none'; r.style.left = e.clientX + 'px'; r.style.top = e.clientY + 'px'; r.style.opacity = '1'; r.style.transform = 'scale(.6)'; requestAnimationFrame(() => { r.style.transition = 'transform .35s ease-out, opacity .35s ease-out'; r.style.transform = 'scale(1.4)'; r.style.opacity = '0' }) }, true)
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', add); else add()
})()`

async function session(name, scenario, body) {
  const browser = await chromium.launch()
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 760 }, deviceScaleFactor: 1.5, locale: 'ko-KR', colorScheme: 'light' })
  await ctx.addInitScript(CURSOR)
  const page = await ctx.newPage()
  // 로그인은 녹화 밖에서 한다
  await page.goto(BASE + '/login' + (scenario ? '?mock=' + scenario : ''))
  await page.getByLabel('이메일').fill('demo@iseonjwa.dev')
  await page.getByLabel('비밀번호', { exact: true }).fill('password1234')
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await page.waitForURL((u) => !u.pathname.startsWith('/login'))
  await page.waitForTimeout(500)

  const dir = OUT + name + '/'
  fs.rmSync(dir, { recursive: true, force: true }); fs.mkdirSync(dir)
  const cdp = await ctx.newCDPSession(page)
  const frames = []
  cdp.on('Page.screencastFrame', async (f) => {
    const file = dir + String(frames.length).padStart(5, '0') + '.jpg'
    fs.writeFileSync(file, Buffer.from(f.data, 'base64'))
    frames.push({ file, t: f.metadata.timestamp })
    await cdp.send('Page.screencastFrameAck', { sessionId: f.sessionId }).catch(() => {})
  })
  const marks = []
  const t0 = Date.now() / 1000
  const mark = (label) => marks.push({ label, t: Date.now() / 1000 - t0 })
  await cdp.send('Page.startScreencast', { format: 'jpeg', quality: 92, maxWidth: 1920, maxHeight: 1140, everyNthFrame: 1 })
  await page.mouse.move(900, 700)
  const click = async (loc, pause = 500) => {
    await loc.evaluate((el) => el.scrollIntoView({ block: 'center', behavior: 'instant' }))
    await page.waitForTimeout(350)
    const b = await loc.boundingBox()
    await page.mouse.move(b.x + b.width / 2, b.y + b.height / 2, { steps: 22 })
    await page.waitForTimeout(220)
    await page.mouse.down(); await page.mouse.up()
    await page.waitForTimeout(pause)
  }
  await body(page, click, mark)
  await page.waitForTimeout(1200)
  await cdp.send('Page.stopScreencast')
  await page.waitForTimeout(300)
  // 프레임 길이 = 다음 프레임까지의 시간
  let list = ''
  for (let i = 0; i < frames.length; i++) {
    const d = i + 1 < frames.length ? frames[i + 1].t - frames[i].t : 1.0
    list += `file '${frames[i].file}'\nduration ${Math.max(d, 0.001).toFixed(4)}\n`
  }
  list += `file '${frames[frames.length - 1].file}'\n`
  fs.writeFileSync(dir + 'list.txt', list)
  execFileSync('ffmpeg', ['-loglevel', 'error', '-y', '-f', 'concat', '-safe', '0', '-i', dir + 'list.txt', '-vf', 'fps=30,scale=1920:-2:flags=lanczos,format=yuv420p', '-c:v', 'libx264', '-crf', '16', '-preset', 'slow', OUT + name + '.mp4'])
  fs.writeFileSync(OUT + name + '.marks.json', JSON.stringify({ firstFrame: frames[0].t - t0, marks }, null, 1))
  console.log(name, frames.length, 'frames', marks.map(m => m.label + '@' + m.t.toFixed(2)).join(' '))
  await browser.close()
}

const which = process.argv[2] || 'all'

if (which === 'all' || which === 'booking') await session('booking', '', async (page, click, mark) => {
  await page.goto(BASE + '/concerts/7'); await page.waitForTimeout(1400); mark('concert')
  await click(page.getByRole('complementary').getByRole('link', { name: '예매하기' }), 300)
  await page.waitForURL(/queue/); mark('queue')
  await page.waitForURL(/seats/, { timeout: 30000 }); mark('seats')
  await page.waitForTimeout(900)
  await click(page.getByRole('radiogroup', { name: '매수' }).getByRole('radio', { name: '1매' }))
  await click(page.getByRole('radio', { name: /^A구역/ }))
  await click(page.getByRole('button', { name: /^[^,]*A구역 1열 4번,/ }), 700); mark('picked')
  await click(page.getByRole('button', { name: '1매 선택 완료' }), 1600); mark('held')
  await click(page.getByRole('dialog', { name: '좌석을 잡았어요' }).getByRole('button', { name: '결제하러 가기' }), 1200)
  await page.waitForURL(/payment/); mark('payment')
  await click(page.getByRole('button', { name: /원 결제하기$/ }), 300); mark('paid')
  await page.getByRole('heading', { level: 1, name: '예매가 확정됐어요' }).waitFor({ timeout: 30000 }); mark('confirmed')
  await page.waitForTimeout(2500)
})

if (which === 'all' || which === 'declined') await session('declined', 'payment-declined,queue-ready', async (page, click, mark) => {
  await page.goto(BASE + '/concerts/7'); await page.waitForTimeout(600)
  await page.getByRole('complementary').getByRole('link', { name: '예매하기' }).click()
  await page.waitForURL(/seats/, { timeout: 30000 }); await page.waitForTimeout(600)
  await page.getByRole('radiogroup', { name: '매수' }).getByRole('radio', { name: '1매' }).click()
  await page.getByRole('radio', { name: /^A구역/ }).click()
  await page.getByRole('button', { name: /^[^,]*A구역 1열 5번,/ }).click()
  await page.getByRole('button', { name: '1매 선택 완료' }).click()
  await page.getByRole('dialog', { name: '좌석을 잡았어요' }).getByRole('button', { name: '결제하러 가기' }).click()
  await page.waitForURL(/payment/); await page.waitForTimeout(1000); mark('payment')
  await click(page.getByRole('button', { name: /원 결제하기$/ }), 300); mark('paid')
  await page.getByText('결제가 거절됐어요').first().waitFor({ timeout: 30000 }); mark('declined')
  await page.waitForTimeout(3500)
})

if (which === 'all' || which === 'lottery') await session('lottery', '', async (page, click, mark) => {
  await page.goto(BASE + '/lotteries/1'); await page.waitForTimeout(1400); mark('lottery')
  await click(page.getByRole('button', { name: '카드 등록', exact: true }), 1400); mark('card')
  await click(page.getByRole('button', { name: '1매 응모하기' }), 2200); mark('applied')
  await page.screenshot({ path: OUT + 'lottery-after-apply.png' })
  // 새로고침하면 목 상태가 처음으로 돌아가므로 앱 안 이동으로 넘어간다
  const spa = (p) => page.evaluate((p) => { history.pushState({}, '', p); dispatchEvent(new PopStateEvent('popstate')) }, p)
  await spa('/lotteries/3'); await page.waitForTimeout(2600); mark('won')
  await page.screenshot({ path: OUT + 'lottery-won.png' })
  await spa('/me/reservations'); await page.waitForTimeout(50); await spa('/lotteries/3')
  await page.getByText('당첨되고 결제까지 끝났어요').first().waitFor({ timeout: 20000 }); mark('paid')
  await page.waitForTimeout(2500)
  await page.screenshot({ path: OUT + 'lottery-paid.png' })
})
