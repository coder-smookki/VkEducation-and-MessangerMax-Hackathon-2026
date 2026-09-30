"""Render the local, editable KOREN catalog without network requests.
Requires Python 3.10+, Playwright and a locally installed Chromium executable.
No font binaries are copied or bundled.
"""
from pathlib import Path
import json, math, argparse, os, time
from playwright.sync_api import sync_playwright

ROOT=Path(__file__).resolve().parents[1]
SRC=ROOT/'source'

def markup(screen='A13',theme='light',fold=False,export=True):
    css=(SRC/'theme-vars.css').read_text()+(SRC/'styles.css').read_text().replace("@import url('./theme-vars.css');",'')
    cat=(SRC/'catalog.js').read_text()
    js=(SRC/'app.js').read_text()
    query=f'?screen={screen}&theme={theme}&export={1 if export else 0}'+('&fold=1' if fold else '')
    return '<!doctype html><html lang="ru" data-theme="light"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover"><style>'+css+'</style></head><body data-theme="light"><div id="root"></div><script>window.KOREN_RENDER_QUERY='+json.dumps(query)+';</script><script>'+cat+'</script><script>'+js+'</script></body></html>'

def destination(s):
    if s['id'].startswith('S'):return 'extension'
    if s['id'].startswith('X'):return 'images/states'
    if s['id'].startswith('R'):return 'images/responsive'
    if s['id'].startswith('B'):return 'images/bot'
    if s['id'].startswith('C'):return 'images/components'
    return 'images/mobile'

def run(only=None,first_folds=True):
    screens=json.loads((SRC/'catalog.json').read_text())
    wanted=only or list(screens)
    rows=[];qa=[]
    with sync_playwright() as p:
        browser=p.chromium.launch(executable_path=os.environ.get('CHROMIUM_PATH','/usr/bin/chromium'),headless=True,args=['--no-sandbox'])
        for id in wanted:
            s=screens[id]
            themes=['both'] if s['layout']=='components' else ['light','dark']
            for theme in themes:
                scale=1 if s['layout']=='components' else 2
                context=browser.new_context(viewport={'width':s['width'],'height':s['height']},device_scale_factor=scale,locale='ru-RU',color_scheme='light' if theme=='both' else theme)
                page=context.new_page()
                errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
                page.set_content(markup(id,'light' if theme=='both' else theme))
                page.wait_for_function('window.KOREN_READY === true')
                page.evaluate('document.fonts.ready')
                dims=page.evaluate('({width:document.documentElement.scrollWidth,height:document.documentElement.scrollHeight,viewport:innerWidth})')
                overflow=page.evaluate('''() => {
                  const vw=innerWidth;
                  return [...document.querySelectorAll('body *')].filter(e=>{
                    if(e.tagName==='SCRIPT'||e.tagName==='STYLE'||e.tagName==='SVG'||e.closest('svg')) return false;
                    const r=e.getBoundingClientRect();
                    return r.width>0 && (r.right>vw+.75 || r.left<-.75);
                  }).slice(0,25).map(e=>({tag:e.tagName,cls:e.className,text:e.textContent.trim().slice(0,100),width:Math.round(e.getBoundingClientRect().width),right:Math.round(e.getBoundingClientRect().right)}));
                }''')
                internal=page.evaluate('''() => [...document.querySelectorAll('h1,h2,h3,p,strong,.button,.choice-row,.problem,.notice,.field')].filter(e=>e.scrollWidth>e.clientWidth+1 && e.clientWidth>0).map(e=>({tag:e.tagName,cls:e.className,text:e.textContent.trim().slice(0,110),scroll:e.scrollWidth,width:e.clientWidth})).slice(0,30)''')
                targets=page.evaluate('''() => [...document.querySelectorAll('button')].filter(e=>{const r=e.getBoundingClientRect();return r.width>0&&(r.width<43.5||r.height<43.5)}).map(e=>({text:e.textContent.trim(),aria:e.getAttribute('aria-label'),w:e.getBoundingClientRect().width,h:e.getBoundingClientRect().height}))''')
                fname=f"{id}_{s['slug']}_{theme}.png"
                rel=Path(destination(s))/fname
                page.screenshot(path=str(ROOT/rel),full_page=True,animations='disabled')
                row={'ID':id,'name':s['title'],'role':s['role'],'theme':theme,'logical_width':dims['width'],'logical_height':dims['height'],'scale':scale,'pixel_width':dims['width']*scale,'pixel_height':dims['height']*scale,'file':str(rel),'previous_action':s.get('previous',''),'next_step':s.get('next',''),'status':'DONE','scope':'EXTENSION' if id.startswith('S') else 'COMPONENT' if id.startswith('C') else 'MUST','note':s.get('note','')}
                rows.append(row)
                qa.append({'ID':id,'theme':theme,'document':dims,'outside_viewport':overflow,'internal_horizontal_overflow':internal,'undersized_buttons':targets,'javascript_errors':errors})
                if first_folds and s['layout'] in ('mobile','large200') and dims['height']>844:
                    page.set_viewport_size({'width':s['width'],'height':844})
                    page.set_content(markup(id,theme,fold=True))
                    page.wait_for_function('window.KOREN_READY===true')
                    fold_folder='extension/first-folds' if id.startswith('S') else 'images/first-folds'
                    (ROOT/fold_folder).mkdir(exist_ok=True,parents=True)
                    foldrel=Path(fold_folder)/f'{id}_{s["slug"]}_{theme}_viewport.png'
                    page.screenshot(path=str(ROOT/foldrel),full_page=False,animations='disabled')
                    row['first_viewport_file']=str(foldrel)
                print(f'{id} {theme}: {dims["width"]}×{dims["height"]} @{scale}x; overflow={len(overflow)}/{len(internal)}; targets={len(targets)}',flush=True)
                context.close()
                # Incremental checkpoint: a long export can be resumed safely.
                checkpoint=ROOT/'qa/render-records.json'
                previous=json.loads(checkpoint.read_text()) if checkpoint.exists() else []
                keys={(r['ID'],r['theme']) for r in rows}
                checkpoint.write_text(json.dumps([r for r in previous if (r['ID'],r['theme']) not in keys]+rows,ensure_ascii=False,indent=2))
                qcheckpoint=ROOT/'qa/layout-report.json'
                previous_q=json.loads(qcheckpoint.read_text()) if qcheckpoint.exists() else []
                qcheckpoint.write_text(json.dumps([r for r in previous_q if (r['ID'],r['theme']) not in keys]+qa,ensure_ascii=False,indent=2))
        browser.close()
    # Merge when re-rendering selected IDs so every manifest row still maps to an actual file.
    old=[]
    path=ROOT/'qa/render-records.json'
    if path.exists():old=json.loads(path.read_text())
    replace={(r['ID'],r['theme']) for r in rows}
    rows=[r for r in old if (r['ID'],r['theme']) not in replace]+rows
    rows.sort(key=lambda r:(list(screens).index(r['ID']),r['theme']))
    path.write_text(json.dumps(rows,ensure_ascii=False,indent=2))
    qpath=ROOT/'qa/layout-report.json'
    oldqa=json.loads(qpath.read_text()) if qpath.exists() else []
    qa=[r for r in oldqa if (r['ID'],r['theme']) not in replace]+qa
    qpath.write_text(json.dumps(qa,ensure_ascii=False,indent=2))
    return rows,qa

if __name__=='__main__':
    ap=argparse.ArgumentParser();ap.add_argument('--only',nargs='*');ap.add_argument('--no-first-folds',action='store_true');args=ap.parse_args()
    run(args.only,not args.no_first_folds)
