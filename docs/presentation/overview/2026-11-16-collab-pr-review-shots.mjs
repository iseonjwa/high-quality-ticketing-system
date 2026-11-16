// 브로셔용 PR 상호 리뷰 캡처를 만든다. 저장소가 공개라 로그인 없이 PR 페이지를 열어 리뷰 카드만 2배 해상도로 찍는다.
// 사용: playwright가 설치된 폴더에서 node 2026-11-16-collab-pr-review-shots.mjs (결과는 이 파일 옆에 저장한다)
import { chromium } from 'playwright';
const OUT = new URL('./', import.meta.url).pathname;
const targets = [
  [26, '제안 4개 모두 동의합니다'],
  [283, '리뷰 포인트(응답 형식, RC-4 기준)에 답한다'],
  [458, '11/12에 코드 점검을 하다가'],
  [458, '추첨 경로까지 봐 주셔서 감사합니다'],
  [487, '방향(차단기는 Redis 장애만'],
  [526, '설정은 의도대로 적용되는 것으로 확인했고'],
];
const browser = await chromium.launch();
const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 }, deviceScaleFactor: 2, locale: 'ko-KR', colorScheme: 'light' });
const page = await ctx.newPage();
let last = 0;
for (const [n, snip] of targets) {
  if (n !== last) { await page.goto(`https://github.com/iseonjwa/high-quality-ticketing-system/pull/${n}`, { waitUntil: 'networkidle' }); last = n; }
  const box = await page.evaluate((snip) => {
    const all = [...document.querySelectorAll('.comment-body, .markdown-body')];
    const body = all.find(e => e.innerText.includes(snip.replace(/\*\*/g, '')));
    if (!body) return null;
    const card = body.closest('.timeline-comment, .TimelineItem, .js-comment-container, .review-comment') || body;
    card.scrollIntoView({ block: 'start' });
    card.setAttribute('data-cc-shot', '1');
    return true;
  }, snip);
  if (!box) { console.log('miss', n, snip); continue; }
  const el = page.locator('[data-cc-shot="1"]').first();
  const bb = await el.boundingBox();
  const file = `${OUT}2026-11-16-collab-pr-review-${n}-${targets.filter(t=>t[0]===n).findIndex(t=>t[1]===snip)}.png`;
  await page.screenshot({ path: file, clip: { x: bb.x - 8, y: bb.y - 8, width: Math.min(bb.width + 16, 1270), height: Math.min(bb.height + 16, 1400) }, fullPage: true });
  await page.evaluate(() => document.querySelectorAll('[data-cc-shot]').forEach(e => e.removeAttribute('data-cc-shot')));
  console.log('ok', file, Math.round(bb.width), Math.round(bb.height));
}
await browser.close();
