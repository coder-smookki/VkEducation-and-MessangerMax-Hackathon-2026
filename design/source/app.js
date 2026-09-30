(() => {
'use strict';
const SCREENS = window.KOREN_SCREENS;
const ICONS = window.KOREN_ICONS;
const root = document.getElementById('root');
const params = new URLSearchParams(window.KOREN_RENDER_QUERY || location.search);
const exporting = params.get('export') === '1';
const firstFold = params.get('fold') === '1';
const forcedTheme = params.get('theme');
const store = {
 get(key, fallback) { try { return localStorage.getItem('koren-design:' + key) || fallback; } catch (_) { return fallback; } },
 set(key, value) { try { localStorage.setItem('koren-design:' + key, value); } catch (_) {} },
};
const sessionCache = new Map();
const session = {
 get(key) { if(sessionCache.has(key)) return sessionCache.get(key); try { return sessionStorage.getItem('koren-design:' + key); } catch (_) { return null; } },
 set(key, value) { sessionCache.set(key,value); try { sessionStorage.setItem('koren-design:' + key, value); } catch (_) {} },
};
let screen = params.get('screen') || 'A13';
let themeMode = exporting ? 'system' : store.get('theme', 'system');
let textMode = exporting ? 'normal' : store.get('text', 'normal');
let historyStack = [];
let origin = null;
let phase = 'linked';
let contextRole = SCREENS[screen]?.role || 'Автор';
let linkSelection = new Set();
let queueFilter = 'all';
let draftEditIndex = 0;
let addDraft = false;
let conflictText = null;
let retryLocked = false;
const scrollPositions = new Map();
let activeHouse = 'ул. Садовая, 12';
let draftItems = [
 {title:'Не горит свет', place:'2-й этаж, у лестницы'},
 {title:'Не закрывается дверь', place:'Входная дверь подъезда'}
];

function escape(s) { return String(s).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c])); }
function icon(name) { return `<svg class="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${ICONS[name] || ICONS.info}</svg>`; }
function button(label, id, kind='primary', attrs='') { return `<button class="button ${kind}" type="button" ${id ? `data-go="${id}"` : ''} ${attrs}><span>${label}</span></button>`; }
function effectiveTheme() {
 if (exporting && forcedTheme) return forcedTheme;
 if (themeMode === 'system') return matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
 return themeMode;
}
function setTheme() {
 const theme = effectiveTheme();
 document.documentElement.dataset.theme = theme;
 document.body.dataset.theme = theme;
 document.documentElement.classList.toggle('text-large', textMode === 'large' && screen !== 'R01');
 document.querySelectorAll('.app-shell:not(.component-theme .app-shell)').forEach(el => el.dataset.theme = theme);
 if (screen==='M05') {
  document.querySelectorAll('[data-pref="theme"]').forEach(el=>el.checked=el.value===themeMode);
  document.querySelectorAll('[data-pref="text"]').forEach(el=>el.checked=el.value===textMode);
 }
}
function header(data) {
 if (data.id === 'M01') return '';
 const back = data.back ? `<button class="icon-button" type="button" data-back="${data.back}" aria-label="Назад">${icon('back')}</button>` : '';
 const settings = data.id === 'M05' ? '' : `<button class="icon-button" type="button" data-go="M05" aria-label="Настройки">${icon('settings')}</button>`;
 return `<header class="app-header">${back}<div class="app-brand"><span class="brand-symbol">${icon('root')}</span><span>КОРЕНЬ</span></div><span class="header-space"></span>${settings}</header>`;
}
function context(dispatch=false) {
 return `<button class="context-button" type="button" data-go="M04" aria-label="Сменить доступный дом или контекст"><span class="context-line">${icon('home')}<span>${escape(activeHouse)}<span class="context-meta">Учебный дом${dispatch ? ' · Диспетчер' : ''}</span></span></span>${icon('down')}</button>`;
}
function shell(data, extra='') {
 return `<div class="app-shell ${extra}" data-theme="${effectiveTheme()}">${header(data)}<main class="app-main" tabindex="-1">${data.context ? context(data.role==='Диспетчер') : ''}${data.body || ''}</main>${data.action ? `<footer class="app-footer">${data.action}</footer>` : ''}${data.keyboard || ''}</div>`;
}
function sidebar(active='queue') {
 return `<aside class="desktop-sidebar"><div class="app-brand"><span class="brand-symbol">${icon('root')}</span><span>КОРЕНЬ</span></div>${context(true)}<nav class="side-nav" aria-label="Диспетчер"><button class="${active==='queue'?'active':''}" data-go="D01">${icon('file')}Очередь</button><button data-go="D10">${icon('archive')}Архив</button><button data-go="M05">${icon('settings')}Настройки</button></nav><div class="side-footer">Марина<br>Диспетчер</div></aside>`;
}
function desktopTop() { return '<div class="desktop-topline"><span>Учебный дом · ул. Садовая, 12</span><span>24 сентября</span></div>'; }
function queueDesktop() {
 return `<div class="desktop-shell">${sidebar()}<main class="desktop-main">${desktopTop()}<div class="heading"><h1>Очередь</h1><p class="subtitle">Сначала обращения, где нужен ваш шаг.</p></div><div class="toolbar">${button('Все действия','D02','secondary')}${button('Нужно принять','D02','secondary')}${button('Нужен ответ','D02','secondary')}<button class="text-link" data-go="D10">${icon('archive')}<span>Архив</span></button></div><div class="queue-table"><div class="table-head"><span>Обращение</span><span>Что требуется</span><span>Следующий шаг</span><span></span></div><article class="table-row"><div><p class="meta">№124 · Подъезд 2</p><h3>Свет и входная дверь</h3><p class="meta">2 подтверждённых пункта</p></div><div><span class="status">${icon('clock')}Нужно принять</span><p class="meta">Новое</p></div><div><p>Принять обращение</p><p class="meta">Список подтверждён автором<br>24 сентября, 09:12</p></div><button class="icon-button" data-go="D03" aria-label="Открыть обращение 124">${icon('chevron')}</button></article><article class="table-row"><div><p class="meta">№121 · Подъезд 1</p><h3>Не горит свет у лифта</h3><p class="meta">1 пункт</p></div><div><span class="status warning">${icon('alert')}Проблема осталась</span><p class="meta">Нужен ответ</p></div><div><p>Уточнить следующий шаг</p><p class="meta">Автор сообщил о проблеме<br>23 сентября</p></div><span></span></article><article class="table-row"><div><p class="meta">№119 · Подъезд 1</p><h3>Не закрывается входная дверь</h3><p class="meta">1 пункт</p></div><div><span class="status">${icon('clock')}Автор ещё не проверил</span><p class="meta">Ожидается наблюдение</p></div><div><p>Ждём наблюдения автора</p><p class="meta">Отчёт связан с дверью<br>22 сентября</p></div><span></span></article></div><div class="desktop-note">${icon('info')}<p>В очереди только подтверждённые обращения доступного дома. Неподтверждённые черновики автора здесь не отображаются.</p></div></main></div>`;
}
function linksDesktop() {
 const temp=document.createElement('div'); temp.innerHTML=SCREENS.D06.body;
 const report=temp.querySelector('.report').outerHTML;
 const choices=temp.querySelector('.link-choices').outerHTML;
 return `<div class="desktop-shell">${sidebar()}<main class="desktop-main">${desktopTop()}<div class="heading"><p class="eyebrow">Обращение №124 · Подъезд 2</p><h1>Выберите пункты для отчёта</h1><p class="subtitle">Сохранённый текст и подтверждение связи — два разных действия.</p></div><div class="desktop-columns"><section class="desktop-panel"><h2>Сообщение сотрудника</h2><div class="saved-report-bar">${icon('file')}<span>Отчёт сохранён · 24 сентября, 11:40</span></div>${report}<div class="notice"><div><strong>Связи ещё не подтверждены</strong><p>Сохранение отчёта не означает, что оба пункта выполнены.</p></div></div>${button('Вернуться к отчёту','D05','secondary')}</section><section class="desktop-panel"><h2>К чему относится отчёт?</h2><p class="meta">Подсказка не выбирает за вас. Отметьте подходящий пункт.</p>${choices}<p class="action-help" data-links-help>Выберите пункт, чтобы продолжить.</p>${button('Проверить связь','D07','primary','data-links-next disabled')}<p class="meta" style="margin-top:16px">На следующем шаге вы проверите точный список. Наблюдение за автора не выставляется.</p></section></div></main></div>`;
}
function dynamicHistory(data) {
 if(exporting || !origin) return data;
 const doc=document.createElement('div');doc.innerHTML=data.body;
 const list=doc.querySelector('.timeline');
 const limits={A09:2,A10:3,A11:5,D08:phase==='prelink'?3:5,D03:2};
 if(limits[origin]) [...list.children].slice(limits[origin]).forEach(e=>e.remove());
 if(origin==='A14') {
  [...list.children].slice(3).forEach(e=>e.remove());
  doc.querySelector('h1').textContent='История пункта';
  doc.querySelector('.subtitle').textContent='№124 · Не закрывается дверь';
  list.insertAdjacentHTML('beforeend','<li><span class="timeline-dot"></span><div><p class="meta">24 сентября, 09:20</p><strong>Следующий шаг по двери</strong><p>Марина: «Проверим доводчик». Обновление — 25 сентября, до 16:00.</p></div></li>');
 }
 const extra={
  A16:[['25 сентября, 13:10','Сообщение о двери','Марина: «Доводчик отрегулировали. Дверь закрывается».'],['13:15','Отчёт связан с дверью','Марина подтвердила связь только с пунктом 2.'],['15:10','По двери — всё в порядке','Анна сохранила отдельное наблюдение по новому отчёту.']],
  A18:[['24 сентября, 12:20','Пункт о двери отозван','Анна: «Пункт добавлен по ошибке: речь о другой двери». Это не подтверждение ремонта.']],
  D10:[['25 сентября, 16:10','Перенесено в архив','Марина сняла пункт о двери с текущей очереди по согласованию с автором. Результат по двери не подтверждён.']]
 };
 if(extra[origin]) extra[origin].forEach(([time,title,desc])=>list.insertAdjacentHTML('beforeend',`<li><span class="timeline-dot"></span><div><p class="meta">${time}</p><strong>${title}</strong><p>${desc}</p></div></li>`));
 data.body=doc.innerHTML;data.role=contextRole;data.back=origin;
 return data;
}
function render(id=screen) {
 screen=SCREENS[id] ? id : 'M08';
 let data=JSON.parse(JSON.stringify(SCREENS[screen]));
 document.title=`${data.id} · ${data.title} — КОРЕНЬ`;
 document.documentElement.classList.toggle('large-200', data.layout==='large200');
 document.documentElement.classList.toggle('text-large',textMode==='large' && data.layout!=='large200');
 document.body.classList.toggle('export-full',exporting && !firstFold);
 document.body.classList.toggle('rendering',exporting);
 if(data.role!=='Общая' && data.role!=='Библиотека' && data.id!=='A15') contextRole=data.role;
 if(data.id==='A15') data=dynamicHistory(data);
 if(!exporting && data.id==='D08' && phase==='prelink') {
  data.body=SCREENS.A10.body.replace('История обращения','История обращения');
  data.action=button('Добавить отчёт','D05')+button('Изменить следующий шаг','D04','secondary');
 }
 if(!exporting && data.id==='X02') conflictText=session.get('draft:next-step') || 'Проверим доводчик';
 if(!exporting && data.id==='D04' && conflictText) data.body=`<div class="notice warning"><div><strong>Актуальная карточка перечитана</strong><p>Ниже ваш прежний незаписанный текст. Сравните его с текущим планом перед повторным сохранением.</p><p>Ваш текст: «${escape(conflictText)}»</p></div></div>`+data.body;
 if(data.layout==='components') {
  root.innerHTML=`<div class="components-board"><header class="board-header"><h1>${data.id} · ${data.title}</h1><p>КОРЕНЬ / библиотека используемых элементов / проектные состояния</p></header><div class="component-columns">${['light','dark'].map(t=>`<section class="component-theme" data-theme="${t}"><h2 class="theme-column-title">${t==='light'?'Светлая тема':'Тёмная тема'}</h2>${data.columns[t]}</section>`).join('')}</div></div>`;
 } else if(data.layout==='panes') {
  root.innerHTML=`<div class="panes-board"><header class="board-header"><h1>${data.title}</h1><p>Самостоятельные варианты одного контекстного состояния</p></header><div class="panes-row">${data.panes.map(p=>`<div class="pane-column"><div class="pane-caption">${p.title}</div>${shell(p)}</div>`).join('')}</div></div>`;
 } else if(data.layout==='bot') {
  root.innerHTML=`<div class="bot-board"><header class="board-header"><h1>${data.title}</h1><p>Схема поведения. Условный внешний контейнер, не действующая интеграция MAX.</p></header><div class="panes-row">${data.frames.map(f=>`<div class="pane-column"><div class="pane-caption">${f.title}</div><section class="bot-shell"><header class="chat-header">${icon('chat')}<div><strong>КОРЕНЬ</strong><p>Текстовый бот</p></div></header><div class="chat-messages">${f.messages}</div></section></div>`).join('')}</div></div>`;
 } else if(data.layout==='tablet') {
  root.innerHTML=`<div class="tablet-board"><p class="tablet-caption">Условная внешняя область MAX · мини-приложение внутри</p>${shell(data,'in-tablet')}</div>`;
 } else if(data.layout==='desktop-queue') root.innerHTML=queueDesktop();
 else if(data.layout==='desktop-links') root.innerHTML=linksDesktop();
 else root.innerHTML=shell(data,data.layout==='keyboard'?'keyboard-shell':'');
 setTheme();
 if(!exporting) {
  root.querySelectorAll('[data-persist]').forEach(el=>{const value=session.get('draft:'+el.dataset.persist);if(value!==null) el.value=value;});
  if(screen==='A05') applyDraftList();
  if(screen==='A06') populateDraftEditor();
  if(screen==='A02') {
   const rows=root.querySelectorAll('.appeal-row');
   if(rows[1]){rows[1].removeAttribute('data-go');rows[1].dataset.action='extra-draft';}
  }
  if(['D06','X04','R04'].includes(screen)) {
   root.querySelectorAll('[data-link-check]').forEach(c=>c.checked=linkSelection.has(c.value));
   updateLinks();
  }
  if(screen==='D01' && queueFilter!=='all') {
   const groups=[...root.querySelectorAll('.queue-group')];
   const keep={new:0,answer:1,observation:2}[queueFilter];
   groups.forEach((g,i)=>{if(i!==keep)g.remove();});
   const filterButton=root.querySelector('.toolbar .button span');
   if(filterButton)filterButton.textContent={new:'Нужно принять',answer:'Нужен ответ',observation:'Ждём наблюдения'}[queueFilter];
  }
  if(screen==='D02') {
   const selected=root.querySelector(`input[name="queue-filter"][value="${queueFilter}"]`);if(selected)selected.checked=true;
  }
  const scroller=root.querySelector('.app-main');if(scroller && scrollPositions.has(screen))scroller.scrollTop=scrollPositions.get(screen);
  root.querySelectorAll('input[autofocus],textarea[autofocus]').forEach(e=>setTimeout(()=>e.focus(),0));
  if(!document.querySelector('.prototype-banner')) {
   const banner=document.createElement('aside');banner.className='prototype-banner';banner.innerHTML='<strong>Проектный каталог</strong>Локальные синтетические переходы. MAX и сервер не подключены.<button data-action="catalog">Открыть все 62 ID</button><button data-action="hide-banner">Скрыть эту панель</button>';document.body.appendChild(banner);
  }
 }
 const query=new URLSearchParams(location.search);query.set('screen',screen);
 try { history.replaceState(null,'','?'+query.toString()); } catch(_) {}
 window.KOREN_READY = true;
}
function go(id, trigger=null) {
 const currentMain=root.querySelector('.app-main');if(currentMain)scrollPositions.set(screen,currentMain.scrollTop);
 if(id==='A15') origin=screen;
 if(id==='A06') {
  addDraft=trigger?.dataset.mode==='add';
  const aria=trigger?.getAttribute('aria-label') || '';
  draftEditIndex=Number((aria.match(/\d+$/)||['1'])[0])-1;
 }
 if(screen==='D03' && id==='D04')phase='prelink';
 if(screen==='D07' && id==='D08')phase='linked';
 if(id==='D05')linkSelection.clear();
 historyStack.push(screen);render(id);
}
function dialog(title,message,actions=[['Понятно',null]],callback=null) {
 const old=document.querySelector('.demo-dialog-backdrop');if(old)old.remove();
 const wrap=document.createElement('div');wrap.className='demo-dialog-backdrop';
 wrap.innerHTML=`<section class="demo-dialog" role="dialog" aria-modal="true" aria-labelledby="dialog-title"><h2 id="dialog-title">${title}</h2><p>${message}</p>${actions.map(([label,id],i)=>button(label,id,i===0?'primary':'secondary',`data-dialog-index="${i}"`)).join('')}</section>`;
 document.body.appendChild(wrap);const previous=document.activeElement;
 wrap.addEventListener('click',event=>{const b=event.target.closest('[data-dialog-index]');if(!b)return;const n=Number(b.dataset.dialogIndex);wrap.remove();if(callback)callback(n);if(b.dataset.go)go(b.dataset.go);if(!b.dataset.go && previous?.focus)previous.focus();});
 const focusables=[...wrap.querySelectorAll('button')];focusables[0].focus();
 wrap.addEventListener('keydown',e=>{if(e.key==='Escape'){wrap.remove();previous?.focus();}if(e.key==='Tab'){const first=focusables[0],last=focusables.at(-1);if(e.shiftKey&&document.activeElement===first){e.preventDefault();last.focus();}else if(!e.shiftKey&&document.activeElement===last){e.preventDefault();first.focus();}}});
}
function applyDraftList() {
 const list=root.querySelector('.editable-list');if(!list)return;
 list.innerHTML=draftItems.map((x,i)=>`<div class="editable-item"><span class="edit-number">${i+1}</span><div><h3>${escape(x.title)}</h3><p class="meta">${escape(x.place)}</p></div><button class="icon-button" data-go="A06" aria-label="Изменить пункт ${i+1}">${icon('edit')}</button></div>`).join('');
}
function populateDraftEditor() {
 const item=addDraft?{title:'',place:''}:draftItems[draftEditIndex] || draftItems[0];
 root.querySelector('h1').textContent=addDraft?'Добавить пункт':'Изменить пункт';
 const subtitle=root.querySelector('.subtitle');if(subtitle)subtitle.textContent=addDraft?'Новый пункт черновика':`Пункт ${draftEditIndex+1} · ещё не подтверждён`;
 root.querySelector('[name="item-title"]').value=item.title;
 root.querySelector('[name="item-place"]').value=item.place;
 const remove=root.querySelector('[data-action="delete-draft-item"]');if(remove)remove.hidden=addDraft;
}
function updateLinks() {
 const selected=[...root.querySelectorAll('[data-link-check]:checked')];
 linkSelection=new Set(selected.map(el=>el.value));
 const button=root.querySelector('[data-links-next]');
 const help=root.querySelector('[data-links-help]');
 if(button)button.disabled=selected.length===0;
 if(help)help.textContent=selected.length ? `Вы выбрали пунктов: ${selected.length}. Проверьте точный список на следующем шаге.` : 'Выберите пункт, чтобы продолжить.';
}
function validateRequired(name) {
 const field=root.querySelector(`[name="${name}"]`);if(!field)return true;
 if(field.value.trim())return true;
 field.setAttribute('aria-invalid','true');field.closest('.field')?.classList.add('has-error');
 if(!field.closest('.field')?.querySelector('.field-error'))field.insertAdjacentHTML('afterend','<span class="field-error">Заполните это поле, чтобы продолжить.</span>');
 field.focus();return false;
}
function manualState() {
 const a=root.querySelector('[name="manual-title"]'),b=root.querySelector('[name="manual-place"]'),go=root.querySelector('[data-action="manual-confirm"]');
 if(go)go.disabled=!!a && (!a.value.trim() || !b?.value.trim());
}
document.addEventListener('input',event=>{
 const target=event.target;
 if(target.matches('[data-persist]'))session.set('draft:'+target.dataset.persist,target.value);
 if(screen==='A07')manualState();
});
document.addEventListener('change',event=>{
 const t=event.target;
 if(t.matches('[data-pref="theme"]')){themeMode=t.value;store.set('theme',themeMode);setTheme();}
 if(t.matches('[data-pref="text"]')){textMode=t.value;store.set('text',textMode);setTheme();}
 if(t.matches('[data-link-check]'))updateLinks();
});
document.addEventListener('keydown',event=>{
 if((event.key==='Enter'||event.key===' ') && event.target.matches('article[role="button"]')){event.preventDefault();event.target.click();}
});
document.addEventListener('click',event=>{
 const t=event.target.closest('button,[role="button"]');if(!t||t.disabled)return;
 if(exporting)return;
 if(t.hasAttribute('data-dialog-index'))return;
 if(t.dataset.require&&!validateRequired(t.dataset.require)){event.preventDefault();return;}
 if(screen==='X01' && t.dataset.go==='A05' && !validateRequired('appeal-description-error'))return;
 if(screen==='A06' && t.dataset.go==='A05' && t.textContent.includes('Сохранить')) {
  if(!validateRequired('item-title')||!validateRequired('item-place'))return;
  const item={title:root.querySelector('[name="item-title"]').value,place:root.querySelector('[name="item-place"]').value};
  if(addDraft)draftItems.push(item);else draftItems[draftEditIndex]=item;
 }
 if(t.dataset.linksNext!==undefined && linkSelection.has('door')) {
  dialog('Проверьте выбор двери','В этом учебном отчёте нет сведений о работе с дверью. Уберите этот пункт или вернитесь к тексту отчёта. Это проверка фиксированного сценария макета, не автоматическое заключение о ремонте.',[['Вернуться к выбору',null],['Открыть отчёт','D05']]);return;
 }
 const action=t.dataset.action;
 if(action==='invite') {
  const v=root.querySelector('[name="invite-code"]')?.value.trim();
  if(!v){validateRequired('invite-code');return;}
  if(v!=='СЦЕНАРИЙ'){go('X10');return;}
 }
 if(action==='context'){const selected=root.querySelector('input[name="context"]:checked');const id=selected?.value;activeHouse=selected?.closest('.choice-row')?.querySelector('strong')?.textContent || 'ул. Садовая, 12';go(id||'A02');return;}
 if(action==='new-appeal') {
  if(!validateRequired('appeal-description')){go('X01');return;}
  const place=root.querySelector('[name="appeal-place"]')?.value.trim();if(!place){go('A04');return;}
 }
 if(action==='observation') {
  const choice=root.querySelector('input[name="observation"]:checked')?.value;
  if(!choice){dialog('Выберите наблюдение','Все три варианта доступны. Выберите тот, который соответствует тому, что вы можете проверить.');return;}
  go(choice==='ok'?'A13':'X09');return;
 }
 if(action==='delete-draft-item') {
  dialog('Удалить пункт из черновика?','Пункт ещё не подтверждён. Остальной черновик останется.',[['Удалить',null],['Оставить',null]],n=>{if(n===0){draftItems.splice(draftEditIndex,1);go('A05');}});return;
 }
 if(action==='extra-draft') {
  dialog('Черновик: повреждена ручка перил','Это отдельная синтетическая строка списка. Полный интерактивный путь в этом каталоге показан на обращении №124; переход в другую заявку не подменяется данными №124.');return;
 }
 if(action==='add-manual') {
  if(!validateRequired('manual-title')||!validateRequired('manual-place'))return;
  dialog('Пункт готов к добавлению','В полном приложении новый пункт добавляется в этот же список. В локальном каталоге показан фиксированный пример из двух пунктов.');return;
 }
 if(action==='remove-manual') {
  root.querySelectorAll('[name="manual-title"],[name="manual-place"]').forEach(x=>x.closest('.field').remove());
  t.remove();manualState();return;
 }
 if(action==='manual-confirm') {
  const n=root.querySelector('[name="manual-title"]');
  if(n && (!validateRequired('manual-title')||!validateRequired('manual-place')))return;
  dialog('Ручной список готов к подтверждению','Это локальный дизайн-прототип. Серверное сохранение и выдача номера здесь не выполняются. Образец подтверждения из двух пунктов доступен в каталоге A08.');return;
 }
 if(action==='reset-filters') {
  queueFilter='all';const all=root.querySelector('[name="queue-filter"][value="all"]');if(all)all.checked=true;return;
 }
 if(action==='apply-filters')queueFilter=root.querySelector('[name="queue-filter"]:checked')?.value||'all';
 if(action==='resolve-conflict')conflictText=root.querySelector('[name="conflict-step"]')?.value||'Проверим доводчик';
 if(action==='retry-same') {
  if(retryLocked)return;retryLocked=true;t.disabled=true;t.setAttribute('aria-busy','true');t.querySelector('span').textContent='Проверяем прежнюю отправку…';
  setTimeout(()=>{dialog('Сетевой результат не имитируется','В работающем приложении этот шаг проверяет прежнюю команду с тем же идентификатором. В макете нет сервера, поэтому сохранение не объявляется ни успешным, ни потерянным.');retryLocked=false;t.disabled=false;t.removeAttribute('aria-busy');t.querySelector('span').textContent='Проверить и повторить';},400);return;
 }
 if(action==='catalog') {
  const menu=document.createElement('nav');menu.className='catalog-drawer';menu.innerHTML='<strong>Все 62 дизайн-состояния</strong><button data-action="close-catalog">Закрыть каталог</button>'+Object.values(SCREENS).map(s=>`<button data-catalog-go="${s.id}">${s.id} · ${s.title}</button>`).join('');document.body.appendChild(menu);return;
 }
 if(action==='close-catalog'){document.querySelector('.catalog-drawer')?.remove();return;}
 if(action==='hide-banner'){document.querySelector('.prototype-banner')?.remove();return;}
 if(t.dataset.catalogGo){document.querySelector('.catalog-drawer')?.remove();go(t.dataset.catalogGo);return;}
 if(t.dataset.back){const main=root.querySelector('.app-main');if(main)scrollPositions.set(screen,main.scrollTop);const prev=historyStack.pop()||t.dataset.back;render(prev);return;}
 if(t.dataset.go)go(t.dataset.go,t);
});
matchMedia('(prefers-color-scheme: dark)').addEventListener('change',()=>{if(themeMode==='system')setTheme();});
render();
window.KOREN_APP={render,go,getScreen:()=>screen};
})();
