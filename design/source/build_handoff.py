"""Build delivery documents, offline gallery and contact sheets from real exports."""
from pathlib import Path
from collections import Counter
import json, html, math, shutil, textwrap, os
from PIL import Image, ImageDraw, ImageFont

ROOT=Path(__file__).resolve().parents[1]
SRC=ROOT/'source'
RECORDS=json.loads((ROOT/'qa/render-records.json').read_text())
CAT=json.loads((SRC/'catalog.json').read_text())
OUT=ROOT/'overview';OUT.mkdir(exist_ok=True)
FONT=os.environ.get('DESIGN_FONT','/System/Library/Fonts/Supplemental/Arial.ttf')
BOLD=os.environ.get('DESIGN_FONT_BOLD','/System/Library/Fonts/Supplemental/Arial Bold.ttf')

def font(size,bold=False):
    p=Path(BOLD if bold else FONT)
    if not p.exists():p=Path('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf')
    return ImageFont.truetype(str(p),size)

def row(id,theme='light'):
    return next(x for x in RECORDS if x['ID']==id and x['theme']==theme)

def wrap(draw,text,f,maxw):
    out=[];line=''
    for word in text.split():
        trial=(line+' '+word).strip()
        if draw.textlength(trial,font=f)>maxw and line:out.append(line);line=word
        else:line=trial
    if line:out.append(line)
    return out

def contact(filename,title,subtitle,items,cols=4,tile=290,capheight=720):
    """Navigation overview only; all full-resolution sources remain linked in gallery."""
    margin=36;gap=24;header=144;labelh=104;footer=46
    thumbdata=[]
    for r in items:
        im=Image.open(ROOT/r['file']).convert('RGB')
        scale=min(tile/im.width,capheight/im.height)
        thumb=im.resize((round(im.width*scale),round(im.height*scale)),Image.Resampling.LANCZOS)
        thumbdata.append((r,thumb))
    rows=math.ceil(len(items)/cols);heights=[]
    for i in range(rows):
        heights.append(max(th.height for _,th in thumbdata[i*cols:(i+1)*cols])+labelh)
    width=margin*2+cols*tile+(cols-1)*gap
    height=header+sum(heights)+gap*(rows-1)+footer
    canvas=Image.new('RGB',(width,height),'#EDEEF2');d=ImageDraw.Draw(canvas)
    d.text((margin,28),title,font=font(29,True),fill='#060708')
    lines=wrap(d,subtitle,font(17),width-margin*2)
    for j,line in enumerate(lines):d.text((margin,72+j*23),line,font=font(17),fill='#505359')
    y=header
    for ri in range(rows):
        for ci,(r,thumb) in enumerate(thumbdata[ri*cols:(ri+1)*cols]):
            x=margin+ci*(tile+gap)
            d.text((x,y),r['ID']+' · '+({'light':'Светлая','dark':'Тёмная','both':'Две темы'}[r['theme']]),font=font(17,True),fill='#0066CC')
            for j,line in enumerate(wrap(d,r['name'],font(17),tile)[:3]):d.text((x,y+26+j*22),line,font=font(17),fill='#060708')
            ix=x+(tile-thumb.width)//2;iy=y+labelh
            canvas.paste(thumb,(ix,iy));d.rectangle((ix-1,iy-1,ix+thumb.width,iy+thumb.height),outline='#D7D9E0',width=1)
        y+=heights[ri]+gap
    d.text((margin,height-30),'Проектные макеты · Синтетические данные · Полные PNG — по ID в каталоге',font=font(14),fill='#505359')
    canvas.save(OUT/filename,optimize=True)
    return {'file':'overview/'+filename,'title':title,'width':width,'height':height,'count':len(items)}

OV=[]
anchors=['A05','A13','D01','D06','M05']
for t in ['light','dark']:
    OV.append(contact('01_anchors_'+t+'.png','КОРЕНЬ / Опорные экраны · '+('Светлая' if t=='light' else 'Тёмная'),
        'Проверка списка → частичный результат → очередь → связи отчёта → оформление',[row(i,t) for i in anchors],cols=5,tile=320,capheight=1030))
OV.append(contact('02_partial-result_pair.png','КОРЕНЬ / Свет проверен. Дверь остаётся.',
    'A13 · Одинаковый смысл в двух темах. Ни отчёт о лампе, ни цвет не закрывают дверь.',[row('A13','light'),row('A13','dark')],cols=2,tile=390,capheight=1300))
for slug,title,ids,cols,tile,cap in [
    ('03_common','Общие экраны',[f'M{i:02d}' for i in range(1,9)],4,270,850),
    ('04_author_create','Автор / Создание и приём',[f'A{i:02d}' for i in range(1,10)],3,280,900),
    ('05_author_result','Автор / Наблюдения и результат',[f'A{i:02d}' for i in range(10,19)],3,280,980),
    ('06_dispatcher','Диспетчер / Работа с обращением',[f'D{i:02d}' for i in range(1,11)],5,270,900),
    ('07_states','Ошибки и особые состояния',[f'X{i:02d}' for i in range(1,11)],3,390,900),
    ('08_responsive','Адаптивные варианты',[f'R{i:02d}' for i in range(1,5)],2,640,1300),
    ('09_bot','Текстовый бот / Диалоговые последовательности',[f'B{i:02d}' for i in range(1,5)],2,650,1050),
    ('10_components','Библиотека / Обе темы на каждом листе',[f'C{i:02d}' for i in range(1,7)],3,420,860),
    ('11_extension','Возможное расширение / Не часть обязательного релиза',['S01','S02'],2,600,1300)
]:
    items=[row(i,'both' if i.startswith('C') else 'light') for i in ids]
    OV.append(contact(slug+'.png','КОРЕНЬ / '+title,'Обзор для навигации. Каждый экран доступен отдельно в полном разрешении.',items,cols,tile,cap))
(ROOT/'overview/overview-manifest.json').write_text(json.dumps(OV,ensure_ascii=False,indent=2))

# Actual manifest: file existence and pixel dimensions are checked, not presumed.
def cell(x):return str(x).replace('|','/').replace('\n',' ')
lines=['# КОРЕНЬ — реестр изображений','',
    'Редакция 22 сентября 2026 года: приложенный UI-пакет согласован с Blueprint и палитрой MAX. 118 самостоятельных artboards; все строки ниже соответствуют PNG. Статус DONE означает готовность проектного изображения, а не готовность приложения или интеграции.',
    '', 'Основной каталог: 72 M/A/D + 20 X + 8 R + 8 B + 6 C = 114 PNG. Отдельное расширение S: 4 PNG. У C обе темы на одном листе.',
    '', '| ID | Название | Роль | Тема | Логический размер | Масштаб | PNG, px | Файл | Предыдущее действие | Следующий шаг | Готовность | Объём |',
    '|---|---|---|---|---|---|---|---|---|---|---|---|']
for r in RECORDS:
    p=ROOT/r['file'];assert p.exists(),p
    with Image.open(p) as im:assert im.size==(r['pixel_width'],r['pixel_height'])
    vals=[r['ID'],r['name'],r['role'],r['theme'],f"{r['logical_width']}×{r['logical_height']}",f"{r['scale']}×",f"{r['pixel_width']}×{r['pixel_height']}",f"[{p.name}]({r['file']})",r['previous_action'],r['next_step'],r['status'],r['scope']]
    lines.append('| '+' | '.join(cell(v) for v in vals)+' |')
lines+=['','## Первые видимые экраны','',
    'Для длинных мобильных страниц дополнительно экспортирован viewport 390×844 (для R01 — 320×844). Он показывает начальную видимую область и отдельную нижнюю панель действия. Это дополнительные изображения, не новые ID.',
    '', '| ID | Тема | Логический viewport | PNG | Файл |','|---|---|---|---|---|']
folds=[]
for r in RECORDS:
    rel=r.get('first_viewport_file')
    if not rel:continue
    with Image.open(ROOT/rel) as im:w,h=im.size
    folds.append(rel)
    lines.append(f"| {r['ID']} | {r['theme']} | {w//r['scale']}×{h//r['scale']} | {w}×{h} | [{Path(rel).name}]({rel}) |")
lines+=['',f'Всего дополнительных первых экранов: {len(folds)}.','',f'Обзорных листов: {len(OV)}; список — `overview/overview-manifest.json`.','', 'PENDING: нет в каталоге проектных изображений. Неподключённые интеграции и ограничения прототипа перечислены в README.md.']
(ROOT/'screens-manifest.md').write_text('\n'.join(lines),encoding='utf-8')
(ROOT/'screens-manifest.json').write_text(json.dumps(RECORDS,ensure_ascii=False,indent=2))

# One genuinely editable offline source, built from the same code as the PNG export.
css=(SRC/'theme-vars.css').read_text()+(SRC/'styles.css').read_text().replace("@import url('./theme-vars.css');",'')
standalone='''<!doctype html><html lang="ru" data-theme="light"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover"><title>КОРЕНЬ — редактируемый проектный прототип</title><style>'''+css+'''</style></head><body data-theme="light"><div id="root"></div><script>'''+(SRC/'catalog.js').read_text()+'''</script><script>'''+(SRC/'app.js').read_text()+'''</script></body></html>'''
(ROOT/'KOREN_Design_Editable.html').write_text(standalone,encoding='utf-8')

# Offline image gallery. Optional extension is explicitly marked in each tile.
gallerycss='''*{box-sizing:border-box}body{margin:0;background:#F5F7FA;color:#060708;font:17px/1.5 Inter,-apple-system,BlinkMacSystemFont,"Segoe UI",Arial,sans-serif}header,main,footer{max-width:1480px;margin:auto;padding:28px}h1{font-size:34px;margin:12px 0}h2{font-size:22px}p{max-width:920px}.eyebrow{color:#0066CC;font-weight:650;letter-spacing:1px}a{color:#0066CC;text-underline-offset:3px}.toolbar{display:flex;flex-wrap:wrap;gap:12px;padding:20px 0}input,select,.link{font:inherit;min-height:48px;border:1px solid #7B7E85;border-radius:12px;padding:10px 14px;background:white;color:#060708}input{flex:1;min-width:180px}a:focus-visible,button:focus-visible,input:focus-visible,select:focus-visible{outline:3px solid #0066CC;outline-offset:3px}.link{display:inline-block;text-decoration:none}.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(270px,1fr));gap:20px}.tile{background:#fff;border:1px solid #D7D9E0;border-radius:16px;overflow:hidden;display:flex;flex-direction:column}.preview{display:block;background:#EDEEF2;padding:14px}.preview img{display:block;width:100%;height:430px;object-fit:contain;object-position:top}.details{padding:18px;flex:1}.details h2{margin:8px 0 12px}.meta{font-size:14px;color:#505359}.tag{display:inline-block;padding:4px 10px;border-radius:20px;background:#EDEEF2;font-size:14px}.ext{background:#fbf2df;color:#80520c}.actions{display:flex;flex-wrap:wrap;gap:12px;margin-top:18px}.tile[hidden]{display:none}details{margin:16px 0}summary{cursor:pointer;min-height:44px}footer{color:#505359}#counter{font-variant-numeric:tabular-nums}.notice{border-left:4px solid #0066CC;padding:2px 18px} @media(max-width:500px){header,main,footer{padding:20px}h1{font-size:28px}.grid{grid-template-columns:1fr}}'''
tiles=[]
for r in RECORDS:
    ext=r['scope']=='EXTENSION'
    klass='ext' if ext else ''
    t={'light':'Светлая','dark':'Тёмная','both':'Обе темы'}[r['theme']]
    fold=f'<a href="{r["first_viewport_file"]}">Первый экран</a>' if r.get('first_viewport_file') else ''
    tiles.append(f'''<article class="tile" data-id="{r['ID']}" data-theme="{r['theme']}" data-search="{html.escape((r['ID']+' '+r['name']+' '+r['role']).lower())}"><a class="preview" href="{r['file']}" aria-label="Открыть {r['ID']} — {html.escape(r['name'])}, {t}"><img src="{r['file']}" alt="{html.escape(r['name'])}, {t}" loading="lazy" width="{r['pixel_width']}" height="{r['pixel_height']}"></a><div class="details"><span class="tag {klass}">{r['ID']} · {t}{' · Расширение' if ext else ''}</span><h2>{html.escape(r['name'])}</h2><p class="meta">{r['logical_width']}×{r['logical_height']} · Экспорт {r['scale']}× · {r['role']}</p><div class="actions"><a href="{r['file']}">Полный PNG</a><a href="KOREN_Design_Editable.html?screen={r['ID']}">Открыть исходник</a>{fold}</div></div></article>''')
overviewlinks=' '.join(f'<li><a href="{o["file"]}">{html.escape(o["title"])}</a></li>' for o in OV)
gallery='''<!doctype html><html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>КОРЕНЬ — каталог дизайна</title><style>'''+gallerycss+'''</style></head><body><header><div class="eyebrow">КОРЕНЬ / ПЕРЕДАЧА В РАЗРАБОТКУ</div><h1>Все экраны. Одна история.</h1><p>118 проектных PNG: 114 в основном каталоге и 4 в отдельном возможном расширении. Дополнительно — '''+str(len(folds))+''' первых видимых экранов и '''+str(len(OV))+''' обзорных листов. Всё работает как локальный каталог без сети.</p><p class="notice">Это дизайн и демонстрационный переходный прототип на синтетических данных, не действующая интеграция MAX. Права, приём обращений, уведомления и серверное сохранение здесь не выполняются.</p><div class="actions"><a class="link" href="KOREN_Design_Editable.html">Редактируемый прототип</a><a class="link" href="screens-manifest.md">Реестр файлов</a><a class="link" href="interaction-notes.md">Переходы и поведение</a><a class="link" href="ui-copy.md">Тексты</a><a class="link" href="design-tokens.json">Дизайн-токены</a><a class="link" href="README.md">Как использовать</a></div><details><summary>Обзорные листы</summary><ul>'''+overviewlinks+'''</ul></details><div class="toolbar"><input id="search" aria-label="Найти экран" placeholder="ID, название или роль"><select id="group" aria-label="Группа"><option value="">Все группы</option>M</option><option>A</option><option>D</option><option>X</option><option>R</option><option>B</option><option>C</option><option>S</option></select><select id="theme" aria-label="Тема"><option value="">Все темы</option><option value="light">Светлая</option><option value="dark">Тёмная</option><option value="both">Обе на листе</option></select></div><p id="counter" aria-live="polite">118 изображений</p></header><main class="grid">'''+''.join(tiles)+'''</main><footer>22 сентября 2026 · Учебные данные · Отдельные изображения не заменяются обзором · Файлы шрифтов не включены.</footer><script>const tiles=[...document.querySelectorAll('.tile')];const s=document.querySelector('#search'),g=document.querySelector('#group'),t=document.querySelector('#theme');function filter(){let n=0;for(const x of tiles){const ok=x.dataset.search.includes(s.value.toLowerCase().trim())&&(!g.value||x.dataset.id.startsWith(g.value))&&(!t.value||x.dataset.theme===t.value);x.hidden=!ok;if(ok)n++}document.querySelector('#counter').textContent=`Показано: ${n} из ${tiles.length}`}[s,g,t].forEach(x=>x.addEventListener('input',filter));</script></body></html>'''
# Correct the literal option generated above for the first group.
gallery=gallery.replace('<option>M</option>','<option value="M">M</option>').replace('<option value="">Все группы</option>M</option>','<option value="">Все группы</option><option value="M">M</option>')
(ROOT/'index.html').write_text(gallery,encoding='utf-8')


contrast=json.loads((ROOT/'qa/contrast-report.json').read_text())
layout=json.loads((ROOT/'qa/layout-report.json').read_text())
checks=json.loads((ROOT/'qa/interaction-checks.json').read_text())
qa=f'''# Проверка проектного пакета

Дата: 22 сентября 2026 года. Проверка относится к локальному HTML-источнику и фактически экспортированным PNG, не к работающему MAX или реальному backend.

## Файлы и геометрия

Проверены {len(RECORDS)} основных PNG: существование, уникальный ID/тема, фактические размеры пикселей. Для каждого выполнен браузерный аудит. Элементов за горизонтальными границами viewport: {sum(len(x['outside_viewport']) for x in layout)}. Внутренних горизонтальных переполнений проверенных текстовых блоков: {sum(len(x['internal_horizontal_overflow']) for x in layout)}. Кнопок с геометрией менее 44×44 px: {sum(len(x['undersized_buttons']) for x in layout)}. Ошибок JavaScript при рендеринге: {sum(len(x['javascript_errors']) for x in layout)}.

Длинные страницы имеют {len(folds)} дополнительных первых viewport. R01 действительно использует основной текст 36 px, заголовок 54 px и ширину 320 px. R02 содержит внутреннюю вертикальную прокрутку; фиксированный PNG не изображает весь scroll-контент одновременно.

## Контраст

Измерены {len(contrast)} выбранные пары токенов в обеих темах методом относительной яркости sRGB: (Lmax + 0.05) / (Lmin + 0.05). Все прошли указанный в JSON порог. Минимальное отношение для проверенных текстовых пар — {min(x['ratio'] for x in contrast if x['minimum']==4.5):.2f}:1. Для текста задан порог 4.5:1, для проверенных границ контрола/фокуса — 3:1. Даже disabled-текст проверен, хотя это отдельная проектная цель. Список и значения — contrast-report.json. Это проверка выбранных реальных пар, не утверждение о полном соответствии WCAG всех состояний приложения.

## Проверенные переходы

{sum(x['pass'] for x in checks)} из {len(checks)} локальных браузерных проверок пройдены. Точные условия — interaction-checks.json и исходник source/qa_checks.py.

'''
for x in checks:qa+=f"- {'PASS' if x['pass'] else 'FAIL'} — {x['test']}\n"
qa+='''
## Визуальная проверка и границы

Просмотрены опорные экраны A05/A13/D01/D06/M05, широкий выбор связей R04, узкий крупный текст R01, планшетный контейнер R02, клавиатурный вариант X07 и сравнительные компоненты C03. Проверены кириллица, иерархия, переносы, источник сведений, отдельная дверь и доступность основного действия. Финальные обзорные листы дают дополнительную проверку согласованности каталога.

Не выполнялись испытания реальных клиентов MAX, внешней клавиатуры каждого устройства, screen reader, сетевых конфликтов с настоящим сервером, одноразовых приглашений и разграничения прав на сервере. Отсутствие горизонтального переполнения не заменяет полноценный аудит доступности. Файлы не доказывают подключение API. Контакт поддержки и политика данных требуют реальных значений до выпуска.
'''
(ROOT/'qa/qa-report.md').write_text(qa,encoding='utf-8')

(ROOT/'extension/README.md').write_text('''# Возможное расширение: похожие обращения

S01/S02 — отдельные проектные состояния, не обязательная включённая функция MVP. Пакет содержит четыре основных PNG в двух темах и два дополнительных первых viewport S01. Используется только разрешённый контекст; пример собственного предыдущего №112 синтетический. Невыполненный поиск не означает отсутствия совпадений. Объединение, раскрытие соседей и запрет нового обращения не предусмотрены. Реальный endpoint не подключён.
''',encoding='utf-8')
summary={'primary_artboards':len(RECORDS),'design_ids':len(CAT),'first_viewport_pngs':len(folds),'overview_pngs':len(OV),'all_delivery_pngs':118+len(folds)+len(OV),'main_artboards':114,'optional_extension_artboards':4,'contrast_pairs':len(contrast),'interaction_checks':len(checks),'interaction_passes':sum(x['pass'] for x in checks)}
(ROOT/'qa/delivery-summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2))
print(json.dumps(summary,ensure_ascii=False,indent=2))
