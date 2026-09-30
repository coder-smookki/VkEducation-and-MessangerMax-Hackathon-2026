import os
"""Extract visible Russian UI text from the same rendered DOM as the PNG files."""
from pathlib import Path
import json, os
from importlib.metadata import version
from playwright.sync_api import sync_playwright
from render_design import markup
ROOT=Path(__file__).resolve().parents[1]
CAT=json.loads((ROOT/'source/catalog.json').read_text())
lines=['# КОРЕНЬ — точные тексты интерфейса','',
'Тексты извлечены из отрендеренного DOM каждого ID. Светлая и тёмная темы используют одинаковую редакцию; на C обе колонки сохранены. Подписи внешнего контейнера B/R и подписи листов C — дизайнерские пояснения, не пользовательские элементы Mini App. Ввод/placeholder перечислены отдельно, поскольку не входят в обычный innerText. Динамические сообщения прототипа дополнительно находятся в source/app.js.','']
with sync_playwright() as w:
 b=w.chromium.launch(executable_path=os.environ.get('CHROMIUM_PATH','/usr/bin/chromium'),headless=True,args=['--no-sandbox'])
 p=b.new_page(locale='ru-RU')
 for id,s in CAT.items():
  p.set_viewport_size({'width':s['width'],'height':s['height']})
  p.set_content(markup(id));p.wait_for_function('window.KOREN_READY===true')
  text=p.locator('#root').inner_text()
  fields=p.evaluate('''() => [...document.querySelectorAll('input,textarea,select')].map(e=>({name:e.name,label:e.closest('label')?.querySelector('.field-label')?.textContent||e.closest('label')?.querySelector('strong')?.textContent||e.getAttribute('aria-label')||'',type:e.type||e.tagName.toLowerCase(),value:e.value,placeholder:e.placeholder||'',checked:e.checked??null}))''')
  labels=p.evaluate('''()=>[...document.querySelectorAll('[aria-label]')].map(e=>e.getAttribute('aria-label'))''')
  lines.extend([f'## {id} — {s["title"]}','','```text',text,'```',''])
  if fields:lines.extend(['### Поля и выбранные значения','','```json',json.dumps(fields,ensure_ascii=False,indent=2),'```',''])
  if labels:lines.extend(['### Доступные подписи без видимого текста','','; '.join(dict.fromkeys(labels)), ''])
 b.close()
(ROOT/'ui-copy.md').write_text('\n'.join(lines),encoding='utf-8')
import platform,subprocess
info={'python':platform.python_version(),'playwright':version('playwright'),'pillow':version('Pillow'),'chromium':subprocess.check_output([os.environ.get('CHROMIUM_PATH','/usr/bin/chromium'),'--version'],text=True).strip(),'font':'Local Inter; font binaries excluded','render':'Inline HTML through page.set_content; no remote resources'}
(ROOT/'source/export-environment.json').write_text(json.dumps(info,ensure_ascii=False,indent=2))
print('UI copy extracted for',len(CAT),'IDs; environment saved')
