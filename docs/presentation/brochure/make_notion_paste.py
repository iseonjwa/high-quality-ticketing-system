# Notion 붙여 넣기 도우미(notion-paste.html)를 만든다.
# 본문·서비스별 하위 페이지 마크다운과 그림을 한 HTML 파일에 넣고,
# 페이지마다 "서식 복사"(Notion이 제목·표·목록으로 받는 HTML)와 "마크다운 복사", 그림마다 "이미지 복사" 버튼을 둔다.
# 마크다운이 원본이므로 글을 고칠 때는 .md를 고치고 이 스크립트를 다시 실행한다.
# 실행: python make_notion_paste.py → notion-paste.html (그림을 base64로 넣어 약 6MB. 생성물이라 커밋하지 않는다)
import base64
import json
from pathlib import Path

HERE = Path(__file__).parent
PRES = HERE.parent

IMAGES = {  # 본문 〔그림: 파일명〕·〔썸네일: 파일명〕 표시의 파일명 → 실제 경로
    "2026-11-16-cover-banner.png": HERE / "2026-11-16-cover-banner.png",
    "2026-11-04-slack-app-icon.png": PRES / "brand/2026-11-04-slack-app-icon.png",
    "2026-11-11-system-architecture.png": PRES / "architecture/2026-11-11-system-architecture.png",
    "2026-11-12-user-flow-booking.png": PRES / "architecture/2026-11-12-user-flow-booking.png",
    "2026-11-12-user-flow-lottery-account.png": PRES / "architecture/2026-11-12-user-flow-lottery-account.png",
    "2026-11-14-saga-payment-declined.png": PRES / "architecture/2026-11-14-saga-payment-declined.png",
    "2026-11-16-gateway-before-after.png": HERE / "2026-11-16-gateway-before-after.png",
    "2026-11-16-v1-vs-v2.png": HERE / "2026-11-16-v1-vs-v2.png",
    "2026-11-16-autopay-before-after.png": HERE / "2026-11-16-autopay-before-after.png",
}
for p in sorted((HERE / "stories").glob("thumb-*.png")):
    IMAGES[p.name] = p

PAGES = [("본문", HERE / "2026-11-16-brochure-notion.md")] + [
    (p.read_text(encoding="utf-8").splitlines()[0].lstrip("# ").split(":")[0], p)
    for p in sorted((HERE / "stories").glob("[1-9]-*.md"))
]


def clean(md):
    # Notion에 올리지 않는 출처 줄은 뺀다
    return "\n".join(l for l in md.splitlines() if not l.startswith("출처:"))


pages = [{"name": n, "md": clean(p.read_text(encoding="utf-8"))} for n, p in PAGES]
images = {k: "data:image/png;base64," + base64.b64encode(v.read_bytes()).decode() for k, v in IMAGES.items()}

html = """<!doctype html>
<html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>브로셔 붙여 넣기</title>
<style>
:root{--bg:#F7F8FA;--card:#fff;--ink:#14161E;--body:#373B4A;--muted:#676C7E;--line:#E1E4EA;--p:#3B3BD4;--warn:#FFF3BF}
@media (prefers-color-scheme: dark){:root:not([data-theme="light"]){--bg:#15161C;--card:#1E2029;--ink:#F1F2F6;--body:#D5D8E0;--muted:#9AA0B1;--line:#2E313C;--warn:#5C4A00}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--body);font:15px/1.65 Pretendard,'Noto Sans KR',system-ui,sans-serif}
header{position:sticky;top:0;z-index:2;background:var(--card);border-bottom:1px solid var(--line);padding:12px 16px;display:flex;gap:8px;flex-wrap:wrap;align-items:center}
header b{color:var(--ink);margin-right:8px}
.tab{border:1px solid var(--line);background:transparent;color:var(--body);border-radius:999px;padding:6px 12px;cursor:pointer;font:inherit}
.tab.on{background:var(--p);color:#fff;border-color:var(--p)}
main{max-width:920px;margin:0 auto;padding:16px}
.bar{display:flex;gap:8px;flex-wrap:wrap;margin:8px 0 16px}
.btn{border:0;background:var(--p);color:#fff;border-radius:8px;padding:8px 14px;cursor:pointer;font:inherit;font-weight:700}
.btn.sub{background:transparent;color:var(--p);border:1px solid var(--p)}
.tip{color:var(--muted);font-size:13px}
article{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:24px;overflow-x:auto}
article h1,article h2,article h3,article h4{color:var(--ink);line-height:1.3}
article table{border-collapse:collapse;width:100%;margin:12px 0;font-size:14px}
article th,article td{border:1px solid var(--line);padding:6px 8px;vertical-align:top}
article blockquote{margin:12px 0;padding:8px 14px;border-left:4px solid var(--p);color:var(--body)}
article code{background:var(--bg);padding:1px 5px;border-radius:4px}
.fig{border:1px dashed var(--p);border-radius:10px;padding:10px;margin:14px 0}
.fig img{max-width:100%;border-radius:6px;display:block}
.fig .row{display:flex;gap:8px;align-items:center;margin-top:8px;flex-wrap:wrap}
.check{background:var(--warn);padding:0 4px;border-radius:4px}
#toast{position:fixed;bottom:20px;left:50%;transform:translateX(-50%);background:var(--ink);color:var(--card);padding:10px 16px;border-radius:8px;opacity:0;transition:.2s;pointer-events:none}
#toast.on{opacity:1}
</style>
<script src="https://cdnjs.cloudflare.com/ajax/libs/marked/4.3.0/marked.min.js"></script>
</head><body>
<header><b>브로셔 붙여 넣기</b><span id="tabs"></span></header>
<main>
<div class="bar">
<button class="btn" id="copyRich">서식 복사(Notion용)</button>
<button class="btn sub" id="copyMd">마크다운 복사</button>
<span class="tip">서식 복사는 그림 자리를 "[그림: 파일명]" 글자로 남깁니다. 그 자리에 아래 그림의 "이미지 복사" → Notion에서 붙여 넣기 하세요. 노란 표시는 팀 확인 후 지울 곳입니다.</span>
</div>
<article id="view"></article>
</main>
<div id="toast"></div>
<script>
const PAGES = __PAGES__;
const IMAGES = __IMAGES__;
let cur = 0;
function toast(t){const e=document.getElementById('toast');e.textContent=t;e.classList.add('on');setTimeout(()=>e.classList.remove('on'),1600)}
function render(i){
  cur=i;
  document.querySelectorAll('.tab').forEach((b,j)=>b.classList.toggle('on',j===i));
  let md=PAGES[i].md;
  // 그림·썸네일 표시를 그림 카드로
  md=md.replace(/〔(그림|썸네일): ([^\\s〕]+\\.png)[^〕]*〕/g,(m,k,f)=>'\\n<div class="fig" data-file="'+f+'"></div>\\n');
  let html=marked.parse(md);
  html=html.replace(/〔([^〕]*)〕/g,'<span class="check">〔$1〕</span>');
  const v=document.getElementById('view');v.innerHTML=html;
  v.querySelectorAll('.fig').forEach(d=>{
    const f=d.dataset.file,src=IMAGES[f];
    d.innerHTML=src?'<img alt="'+f+'" src="'+src+'"><div class="row"><button class="btn sub">이미지 복사</button><a class="btn sub" download="'+f+'" href="'+src+'">내려받기</a><span class="tip">'+f+'</span></div>':'<span class="tip">그림 없음: '+f+'</span>';
    const b=d.querySelector('button');if(b)b.onclick=()=>copyImage(src);
  });
  window.scrollTo(0,0);
}
async function copyImage(src){
  try{const blob=await (await fetch(src)).blob();await navigator.clipboard.write([new ClipboardItem({'image/png':blob})]);toast('이미지를 복사했어요. Notion에 붙여 넣으세요')}
  catch(e){toast('복사 실패: 내려받기 버튼을 쓰세요')}
}
async function copyRich(){
  const v=document.getElementById('view').cloneNode(true);
  v.querySelectorAll('.fig').forEach(d=>{const p=document.createElement('p');p.textContent='[그림: '+d.dataset.file+']';d.replaceWith(p)});
  const html=v.innerHTML,text=v.innerText;
  try{await navigator.clipboard.write([new ClipboardItem({'text/html':new Blob([html],{type:'text/html'}),'text/plain':new Blob([text],{type:'text/plain'})})]);toast('서식째 복사했어요')}
  catch(e){const r=document.createRange();r.selectNodeContents(document.getElementById('view'));const s=getSelection();s.removeAllRanges();s.addRange(r);document.execCommand('copy');s.removeAllRanges();toast('복사했어요(그림 포함 선택 복사)')}
}
async function copyMd(){try{await navigator.clipboard.writeText(PAGES[cur].md);toast('마크다운을 복사했어요')}catch(e){toast('복사 실패')}}
const tabs=document.getElementById('tabs');
PAGES.forEach((p,i)=>{const b=document.createElement('button');b.className='tab';b.textContent=p.name;b.onclick=()=>render(i);tabs.appendChild(b)});
// 썸네일·커버·아이콘 모음 탭
PAGES.push({name:'커버·아이콘·썸네일',md:'# 커버·아이콘·썸네일\\n\\n페이지 커버(갤러리 썸네일)\\n\\n〔그림: 2026-11-16-cover-banner.png〕\\n\\n페이지 아이콘\\n\\n〔그림: 2026-11-04-slack-app-icon.png〕\\n\\n서비스별 이야기 카드 커버\\n\\n'+Object.keys(IMAGES).filter(k=>k.startsWith('thumb-')).sort().map(k=>'〔그림: '+k+'〕').join('\\n\\n')});
{const b=document.createElement('button');b.className='tab';b.textContent='커버·아이콘·썸네일';const i=PAGES.length-1;b.onclick=()=>render(i);tabs.appendChild(b)}
document.getElementById('copyRich').onclick=copyRich;
document.getElementById('copyMd').onclick=copyMd;
render(0);
</script>
</body></html>
"""
html = html.replace("__PAGES__", json.dumps(pages, ensure_ascii=False)).replace("__IMAGES__", json.dumps(images))
(HERE / "notion-paste.html").write_text(html, encoding="utf-8")
print("ok", len(pages), "pages,", len(images), "images,", round(len(html) / 1e6, 1), "MB")
