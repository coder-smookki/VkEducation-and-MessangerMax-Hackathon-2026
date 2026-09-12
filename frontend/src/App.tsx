import { useEffect, useState } from 'react';
import { compareDemo, loadDemo } from './api/client';
import type { Comparison, DemoCase } from './api/client';

const relationLabels: Record<Comparison['suggestions'][number]['relation'], string> = {
  REPORTED_DONE: 'В тексте сообщается о выполнении',
  PLANNED: 'В тексте описан план',
  SYMPTOM_PERSISTS: 'В тексте сохраняется проблема',
  INSUFFICIENT_INFORMATION: 'Недостаточно сведений',
  UNRELATED: 'Ответ относится к другому предмету',
  CONFLICT: 'Противоречивые сведения',
};

export function App() {
  const [data, setData] = useState<DemoCase | null>(null);
  const [error, setError] = useState('');
  const [comparison, setComparison] = useState<Comparison | null>(null);
  const [busy, setBusy] = useState(false);
  const [reload, setReload] = useState(0);

  useEffect(() => {
    const abort = new AbortController();
    setError('');
    loadDemo(abort.signal).then(setData).catch((reason: unknown) => {
      if (!abort.signal.aborted) setError(reason instanceof Error ? reason.message : 'Не удалось загрузить обращение');
    });
    return () => abort.abort();
  }, [reload]);

  async function compare() {
    setBusy(true);
    setError('');
    try { setComparison(await compareDemo()); }
    catch (reason) { setError(reason instanceof Error ? reason.message : 'Не удалось сравнить ответ'); }
    finally { setBusy(false); }
  }

  return <main>
    <header className="brand"><a href="#content" aria-label="КОРЕНЬ, к обращению">КОРЕНЬ<span> / MAX</span></a><span className="tag">Стартовый пример</span></header>
    <section className="intro" id="content">
      <p className="eyebrow">Обращение по пунктам</p>
      <h1>Каждая проблема<br />остаётся на виду.</h1>
      <p>Сообщение о ремонте лампы не закрывает вопрос с дверью. Для каждого пункта видны сведения о работах и наблюдение автора.</p>
    </section>
    <aside className="notice">Учебный дом и вымышленные данные. Это каркас до начала хакатона: создание обращений, вход через MAX и настоящая ML-модель ещё не подключены.</aside>
    {error && <div className="error" role="alert"><p>{error}</p><button onClick={() => { setReload(value => value + 1); }}>Повторить загрузку</button></div>}
    {!data && !error && <p role="status">Загружаем пример обращения…</p>}
    {data && <>
      <section className="request"><p className="eyebrow">Исходное обращение</p><blockquote>«{data.originalText}»</blockquote><p className="muted">Демонстрационный подъезд 1</p></section>
      <div className="section-title"><h2>Состояние пунктов</h2><span>{data.progress.confirmed} из {data.progress.total} подтверждены автором</span></div>
      <div className="items">{data.items.map((item, index) => <article key={item.detail.itemId}>
        <span className="item-number">0{index + 1}</span><h3>{item.detail.symptom}</h3><p className="muted">{item.detail.location}</p>
        <dl><dt>Сведения о работах</dt><dd>{item.state.workStatus === 'REPORTED_DONE' ? 'Исполнитель сообщил о выполнении' : item.state.workStatus === 'PLANNED' ? 'Работы запланированы' : 'Ожидаем сведения о работах'}</dd><dt>Наблюдение автора</dt><dd>{item.state.observation === 'NOT_RECEIVED' ? 'Ещё не получено' : item.state.observation === 'RESTORED' ? 'Восстановилось' : item.state.observation === 'PERSISTS' ? 'Проблема сохраняется' : 'Не может проверить'}</dd></dl>
      </article>)}</div>
      <section className="report"><p className="eyebrow">Ответ исполнителя из учебного примера</p><blockquote>«{data.reportText}»</blockquote><p>Связь с освещением заранее задана в примере. Модель её не определяла.</p>
        <button onClick={compare} disabled={busy}>{busy ? 'Проверяем связь сервисов…' : 'Проверить сервис анализа'}</button>
        <div aria-live="polite">{comparison && <div className="analysis"><h3>{comparison.mode === 'STUB' ? 'Сервис отвечает. Модель ещё не подключена.' : comparison.mode === 'MANUAL' ? 'Анализ недоступен. Пункты сохранены в примере.' : 'Подсказки требуют проверки диспетчером.'}</h3>
          <ul>{comparison.suggestions.map(s => <li key={s.itemId}><strong>{data.items.find(i => i.detail.itemId === s.itemId)?.detail.symptom}:</strong> {relationLabels[s.relation]}{s.evidence && <q>{s.evidence.quote}</q>}</li>)}</ul>
        </div>}</div>
      </section>
    </>}
    <footer>Отчёт о работах, наблюдение жителя и административное решение учитываются отдельно.</footer>
  </main>;
}
