import { useState } from "react";
import { useInfiniteQuery } from "@tanstack/react-query";
import {
  Link,
  useNavigate,
  useParams,
  useSearchParams,
} from "react-router-dom";
import { useSession, roleLabel } from "../App";
import { ApiError, get } from "../api/client";
import type { Case, DraftItem, HistoryEvent, Item, Page } from "../api/client";
import { useCase, useCaseSaved, useCommand } from "../api/queries";
import {
  Button,
  CommandError,
  CreationSteps,
  DirtyGuard,
  ErrorPanel,
  Field,
  Icon,
  Loading,
  Notice,
  Select,
  Title,
  formatDate,
  submit,
  validText,
} from "../components/ui";
import {
  categories,
  ItemFacts,
  ItemHeading,
  Progress,
  observationLabels,
  workLabels,
} from "../components/domain";

export function CasesPage() {
  const { house, me } = useSession();
  const [search, setSearch] = useSearchParams();
  const queue = house?.role === "DISPATCHER" ? search.get("queue") || "" : "";
  const query = useInfiniteQuery({
    queryKey: ["cases", me.userId, house?.houseId, queue],
    enabled: !!house,
    initialPageParam: null as string | null,
    queryFn: ({ signal, pageParam }) =>
      get<Page<Case>>(
        `/cases?${new URLSearchParams({ houseId: house!.houseId, ...(queue ? { queueState: queue } : {}), ...(pageParam ? { cursor: pageParam } : {}) })}`,
        signal,
      ),
    getNextPageParam: (page) => page.nextCursor,
  });
  if (!house)
    return (
      <section className="card">
        <Title>Подключите свой дом</Title>
        <p>Чтобы создать обращение, нужен код от диспетчера.</p>
        <Link className="button primary" to="/access">
          Ввести код приглашения
        </Link>
      </section>
    );
  const all = query.data?.pages.flatMap((page) => page.items) || [];
  return (
    <>
      <div className="page-heading">
        <div>
          <Title>
            {house.role === "DISPATCHER"
              ? "Очередь обращений"
              : "Мои обращения"}
          </Title>
          <p className="page-description">{house.address}</p>
        </div>
        <Link className="button primary" to={`/new?house=${house.houseId}`}>
          <Icon name="plus" />
          Новое обращение
        </Link>
      </div>
      <section className="toolbar" aria-label="Фильтры">
        {me.memberships.length > 1 && (
          <Select
            label="Дом"
            value={house.houseId}
            onChange={(value) => setSearch({ house: value })}
          >
            {me.memberships.map((m) => (
              <option key={m.houseId} value={m.houseId}>
                {m.address} · {roleLabel(m.role)}
              </option>
            ))}
          </Select>
        )}
        {house.role === "DISPATCHER" && (
          <Select
            label="Показывать"
            value={queue}
            onChange={(value) =>
              setSearch({
                house: house.houseId,
                ...(value ? { queue: value } : {}),
              })
            }
          >
            <option value="">Все доступные</option>
            <option value="NEEDS_ACCEPTANCE">Ждут приёма</option>
            <option value="NEEDS_ACTION">Нужен следующий шаг</option>
            <option value="AWAITING_AUTHOR">Ждут наблюдения автора</option>
            <option value="ARCHIVED">Есть архивные пункты</option>
          </Select>
        )}
        <Button
          kind="quiet"
          onClick={() => {
            void query.refetch();
          }}
          disabled={query.isFetching}
        >
          <Icon name="refresh" />
          Обновить
        </Button>
      </section>
      {query.isPending ? (
        <Loading />
      ) : query.error ? (
        <ErrorPanel
          error={query.error}
          retry={() => {
            void query.refetch();
          }}
        />
      ) : (
        <>
          {!all.length ? (
            <section className="card empty">
              <span className="empty-symbol">
                <Icon name="home" />
              </span>
              <h2>
                {queue
                  ? "Под этот фильтр пока ничего не подходит"
                  : "Пока нет обращений"}
              </h2>
              <p>
                {queue
                  ? "Выберите другой фильтр или покажите все доступные обращения."
                  : "Создайте первое обращение. Каждую проблему можно будет отслеживать отдельно."}
              </p>
              {queue && (
                <Button
                  kind="secondary"
                  onClick={() => setSearch({ house: house.houseId })}
                >
                  Сбросить фильтр
                </Button>
              )}
            </section>
          ) : house.role === "DISPATCHER" ? (
            <table className="queue-table" aria-label="Доступные обращения">
              <thead>
                <tr>
                  <th scope="col">Обращение</th>
                  <th scope="col">Приём</th>
                  <th scope="col">Наблюдения автора</th>
                  <th scope="col">Создано</th>
                </tr>
              </thead>
              <tbody>
                {all.map((value) => (
                  <tr key={value.caseId}>
                    <td>
                      <p className="eyebrow">№{value.displayNumber}</p>
                      <Link to={`/cases/${value.caseId}`}>
                        {value.items[0]?.symptom ||
                          value.sourceText.slice(0, 100)}
                      </Link>
                    </td>
                    <td data-label="Приём">
                      {value.submissionStatus === "DRAFT"
                        ? "Черновик"
                        : value.receipt.acceptedAt
                          ? "Принято диспетчером"
                          : "Ждёт приёма"}
                    </td>
                    <td data-label="Наблюдения автора">
                      {value.progress.confirmed} из {value.progress.total}{" "}
                      подтверждены
                      {value.progress.withdrawn > 0 &&
                        ` · отозвано ${value.progress.withdrawn}`}
                    </td>
                    <td data-label="Создано">{formatDate(value.createdAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          ) : (
            <div className="case-list">
              {all.map((value) => (
                <article className="card case-row" key={value.caseId}>
                  <div>
                    <p className="eyebrow">
                      №{value.displayNumber} · {formatDate(value.createdAt)}
                    </p>
                    <h2>
                      <Link to={`/cases/${value.caseId}`}>
                        {value.items[0]?.symptom ||
                          value.sourceText.slice(0, 100)}
                      </Link>
                    </h2>
                    <p className="case-status">
                      {value.submissionStatus === "DRAFT"
                        ? "Черновик · список ещё не подтверждён"
                        : value.receipt.acceptedAt
                          ? "Принято диспетчером"
                          : "Список подтверждён · ждёт приёма"}
                    </p>
                  </div>
                  <div className="list-progress">
                    <strong>
                      {value.progress.confirmed} из {value.progress.total}
                    </strong>
                    <span className="caption">
                      подтверждены автором
                      {value.progress.withdrawn > 0 &&
                        ` · отозвано ${value.progress.withdrawn}`}
                    </span>
                  </div>
                  <span className="row-arrow">
                    <Icon name="arrow" />
                  </span>
                </article>
              ))}
            </div>
          )}
          {query.hasNextPage && (
            <Button
              kind="secondary"
              onClick={() => {
                void query.fetchNextPage();
              }}
              disabled={query.isFetching}
            >
              Загрузить ещё
            </Button>
          )}
        </>
      )}
    </>
  );
}
export function CasePage() {
  const { caseId = "" } = useParams();
  const { me } = useSession();
  const query = useCase(caseId);
  const saved = useCaseSaved();
  const acceptance = useCommand<Case>(saved);
  if (query.isPending || (!query.isFetchedAfterMount && query.isFetching))
    return <Loading />;
  if (query.error || !query.data)
    return (
      <ErrorPanel
        error={query.error}
        retry={() => {
          void query.refetch();
        }}
      />
    );
  const data = query.data;
  const author = data.authorId === me.userId;
  const membership = me.memberships.find((m) => m.houseId === data.houseId);
  const dispatcher = membership?.role === "DISPATCHER";
  return (
    <>
      <Link className="back-link" to={`/?house=${data.houseId}`}>
        ← К обращениям
      </Link>
      <div className="page-heading">
        <div>
          <Title>Обращение №{data.displayNumber}</Title>
          <p className="page-description">{membership?.address}</p>
        </div>
      </div>
      <div className="case-meta">
        <p className="receipt-status">
          {data.submissionStatus === "DRAFT"
            ? "Черновик"
            : data.receipt.acceptedAt
              ? `Принято диспетчером ${formatDate(data.receipt.acceptedAt)}`
              : "Ждёт приёма диспетчером"}
        </p>
        <Button
          kind="quiet"
          onClick={() => {
            void query.refetch();
          }}
          disabled={query.isFetching}
        >
          <Icon name="refresh" />
          Обновить карточку
        </Button>
      </div>
      {data.submissionStatus === "DRAFT" ? (
        <section className="card">
          <p className="eyebrow">Исходное обращение</p>
          <p className="preserve">{data.sourceText}</p>
          <Notice>
            Черновик. Только вы видите его до подтверждения списка.
          </Notice>
          {author && (
            <div className="actions">
              <Link className="button primary" to={`/cases/${caseId}/edit`}>
                Проверить пункты
              </Link>
              <Link className="button secondary" to={`/cases/${caseId}/source`}>
                Изменить текст
              </Link>
            </div>
          )}
        </section>
      ) : (
        <>
          {dispatcher && !data.receipt.acceptedAt && (
            <>
              <CommandError operation={acceptance} />
              <Button
                disabled={acceptance.locked}
                onClick={() =>
                  acceptance.run(`/cases/${caseId}/acceptance`, {
                    expectedCaseVersion: data.caseVersion,
                  })
                }
              >
                {acceptance.isPending ? "Принимаем…" : "Принять обращение"}
              </Button>
            </>
          )}
        </>
      )}
      {data.submissionStatus === "CONFIRMED" && (
        <Progress value={data.progress} />
      )}
      <div className="section-heading">
        <h2>Пункты обращения</h2>
        <span className="caption">{data.items.length}</span>
      </div>
      <div className="item-grid">
        {[...data.items]
          .sort(
            (a, b) =>
              Number(a.observation === "RESTORED") -
              Number(b.observation === "RESTORED"),
          )
          .map((item) => (
            <CaseItemCard
              key={item.itemId}
              data={data}
              item={item}
              author={author}
              dispatcher={dispatcher}
            />
          ))}
      </div>
      {dispatcher &&
        data.submissionStatus === "CONFIRMED" &&
        data.items.some((i) => i.administrativeStatus === "ACTIVE") && (
          <Link className="button primary" to={`/cases/${caseId}/reports/new`}>
            Добавить отчёт о работах
          </Link>
        )}
      {data.submissionStatus === "CONFIRMED" && (
        <details className="card source-details">
          <summary>Исходный текст и квитанция</summary>
          <p className="caption">Создано {formatDate(data.createdAt)}</p>
          <p className="preserve">{data.sourceText}</p>
          <p className="caption">
            Список подтверждён {formatDate(data.receipt.confirmedAt)}
          </p>
        </details>
      )}
      <History data={data} meId={me.userId} />
    </>
  );
}
function CaseItemCard({
  data,
  item,
  author,
  dispatcher,
}: {
  data: Case;
  item: Item;
  author: boolean;
  dispatcher: boolean;
}) {
  const details = (
    <>
      <ItemFacts item={item} />
      {data.submissionStatus === "CONFIRMED" &&
        item.administrativeStatus === "ACTIVE" && (
          <div className="actions vertical">
            {author && (
              <Link
                className="button primary"
                to={`/cases/${data.caseId}/items/${item.itemId}/observe`}
              >
                Сообщить своё наблюдение
              </Link>
            )}
            {dispatcher && (
              <Link
                className="button secondary"
                to={`/cases/${data.caseId}/items/${item.itemId}/next-action`}
              >
                Указать следующий шаг
              </Link>
            )}
            <details className="item-more">
              <summary>Другие действия</summary>
              <div className="minor-actions">
                {author && (
                  <Link
                    to={`/cases/${data.caseId}/items/${item.itemId}/withdraw`}
                  >
                    Отозвать пункт
                  </Link>
                )}
                {dispatcher && (
                  <Link
                    to={`/cases/${data.caseId}/items/${item.itemId}/archive`}
                  >
                    В архив
                  </Link>
                )}
              </div>
            </details>
          </div>
        )}
    </>
  );
  return (
    <article className="card item-card">
      <ItemHeading
        item={item}
        number={data.items.findIndex((i) => i.itemId === item.itemId) + 1}
      />
      {item.observation === "RESTORED" &&
      item.administrativeStatus === "ACTIVE" ? (
        <>
          <p className="positive">По наблюдению автора - восстановилось</p>
          <details>
            <summary>Сведения и действия</summary>
            {details}
          </details>
        </>
      ) : (
        details
      )}
    </article>
  );
}
const eventLabels: Record<string, string> = {
  CASE_CREATED: "Обращение создано",
  CASE_SOURCE_PATCHED: "Текст уточнён автором",
  CASE_ITEMS_PATCHED: "Список пунктов изменён",
  CASE_CONFIRMED: "Список подтверждён автором",
  CASE_ACCEPTED: "Диспетчер принял обращение",
  ITEM_NEXT_ACTION_SET: "Обновлён следующий шаг",
  REPORT_SAVED: "Сохранён отчёт о работах",
  REPORT_LINK_CONFIRMED: "Подтверждена связь отчёта с пунктом",
  OBSERVATION_RECORDED: "Автор сообщил наблюдение",
  ITEM_ADMINISTRATIVE_DECISION: "Административное решение по пункту",
};
function History({ data, meId }: { data: Case; meId: string }) {
  const [open, setOpen] = useState(false);
  const query = useInfiniteQuery({
    queryKey: ["history", data.caseId],
    enabled: open,
    initialPageParam: null as string | null,
    queryFn: ({ signal, pageParam }) =>
      get<Page<HistoryEvent>>(
        `/cases/${data.caseId}/history?limit=50${pageParam ? `&cursor=${encodeURIComponent(pageParam)}` : ""}`,
        signal,
      ),
    getNextPageParam: (page) => page.nextCursor,
  });
  return (
    <details
      className="card timeline"
      onToggle={(event) => setOpen(event.currentTarget.open)}
    >
      <summary>История обращения</summary>
      {query.isPending ? (
        <Loading />
      ) : query.error ? (
        <ErrorPanel
          error={query.error}
          retry={() => {
            void query.refetch();
          }}
        />
      ) : (
        <>
          <ol>
            {query.data?.pages
              .flatMap((page) => page.items)
              .map((event) => {
                const item = data.items.find(
                  (i) => i.itemId === event.details.itemId,
                );
                const reportId =
                  typeof event.details.reportId === "string"
                    ? event.details.reportId
                    : null;
                return (
                  <li key={event.eventId}>
                    <p className="caption">
                      {formatDate(event.createdAt)} ·{" "}
                      {event.actorId === meId
                        ? "Вы"
                        : event.actorId === data.authorId
                          ? "Автор обращения"
                          : "Диспетчер"}
                    </p>
                    <strong>
                      {eventLabels[event.eventType] || "Изменение обращения"}
                    </strong>
                    {item && <p>{item.symptom}</p>}
                    {typeof event.details.value === "string" &&
                      event.details.value in observationLabels && (
                        <p>
                          {
                            observationLabels[
                              event.details.value as Item["observation"]
                            ]
                          }
                        </p>
                      )}
                    {typeof event.details.workStatus === "string" &&
                      event.details.workStatus in workLabels && (
                        <p>
                          {
                            workLabels[
                              event.details.workStatus as Item["workStatus"]
                            ]
                          }
                        </p>
                      )}
                    {event.details.action === "ARCHIVE" && (
                      <p>Пункт помещён в архив.</p>
                    )}
                    {event.details.action === "WITHDRAW" && (
                      <p>Автор отозвал пункт.</p>
                    )}
                    {reportId && (
                      <Link
                        className="text-link"
                        to={`/cases/${data.caseId}/reports/${reportId}`}
                      >
                        Открыть отчёт
                      </Link>
                    )}
                  </li>
                );
              })}
          </ol>
          {query.hasNextPage && (
            <Button
              kind="secondary"
              disabled={query.isFetching}
              onClick={() => {
                void query.fetchNextPage();
              }}
            >
              Следующие события
            </Button>
          )}
        </>
      )}
    </details>
  );
}

export function NewCasePage() {
  const { house } = useSession();
  const navigate = useNavigate();
  const saved = useCaseSaved();
  const [text, setText] = useState("");
  const create = useCommand<Case>((data) => {
    saved(data);
    setText("");
  });
  // Navigate in render after the form has become clean, so the unsaved-input guard stays truthful.
  if (create.isSuccess && create.data)
    return (
      <Created
        data={create.data}
        next={() =>
          navigate(`/cases/${create.data.caseId}/edit`, { replace: true })
        }
      />
    );
  if (!house)
    return <Notice>Сначала подключите дом на странице доступа.</Notice>;
  return (
    <>
      <Link className="back-link" to={`/?house=${house.houseId}`}>
        ← К обращениям
      </Link>
      <CreationSteps current={1} />
      <Title>Что случилось?</Title>
      <p className="muted">{house.address}</p>
      <p className="page-description">
        Опишите проблему своими словами. Если их несколько, на следующем шаге
        разделите их на пункты.
      </p>
      <section className="card form-card">
        <form
          onSubmit={(e) =>
            submit(e, () =>
              create.run("/cases", {
                houseId: house.houseId,
                sourceText: text,
              }),
            )
          }
        >
          <fieldset disabled={create.locked}>
            <Field
              label="Текст обращения"
              value={text}
              onChange={setText}
              multiline
              limit={5000}
              hint="Укажите место: подъезд, этаж или участок дома."
            />
            <Button type="submit" disabled={!validText(text, 5000)}>
              Сохранить и перейти к пунктам
            </Button>
          </fieldset>
          <CommandError operation={create} />
          {create.isPending && <p role="status">Сохраняем черновик…</p>}
        </form>
      </section>
      <DirtyGuard dirty={!!text} pending={create.locked} />
    </>
  );
}
function Created({ data, next }: { data: Case; next: () => void }) {
  return (
    <section className="card success-screen">
      <span className="success-mark">
        <Icon name="check" />
      </span>
      <Title>Черновик сохранён</Title>
      <Notice tone="success">
        Обращение №{data.displayNumber}. Теперь составьте и проверьте отдельные
        пункты.
      </Notice>
      <Button onClick={next}>Перейти к пунктам</Button>
    </section>
  );
}
export function DraftPage({ sourceOnly = false }: { sourceOnly?: boolean }) {
  const { caseId = "" } = useParams();
  const { me } = useSession();
  const query = useCase(caseId);
  if (query.isPending || (!query.isFetchedAfterMount && query.isFetching))
    return <Loading />;
  if (query.error || !query.data)
    return (
      <ErrorPanel
        error={query.error}
        retry={() => {
          void query.refetch();
        }}
      />
    );
  if (query.data.authorId !== me.userId)
    return (
      <Notice>
        Редактирование недоступно.{" "}
        <Link to={`/cases/${caseId}`}>Открыть карточку</Link>
      </Notice>
    );
  return (
    <DraftForm
      key={`${caseId}-${sourceOnly}`}
      data={query.data}
      sourceOnly={sourceOnly}
      load={async () => (await query.refetch()).data}
    />
  );
}
const emptyItem = (): DraftItem => ({
  category: "OTHER",
  location: "",
  symptom: "",
});
function DraftForm({
  data,
  sourceOnly,
  load,
}: {
  data: Case;
  sourceOnly: boolean;
  load: () => Promise<Case | undefined>;
}) {
  const saved = useCaseSaved();
  const [baseVersion, setBaseVersion] = useState(data.caseVersion);
  const [text, setText] = useState(data.sourceText);
  const [items, setItems] = useState<DraftItem[]>(() =>
    data.items.length
      ? data.items.map((i) => ({
          itemId: i.itemId,
          category: i.category,
          location: i.location,
          symptom: i.symptom,
        }))
      : [emptyItem()],
  );
  const [dirty, setDirty] = useState(false);
  const [hasSaved, setHasSaved] = useState(false);
  const operation = useCommand<Case>((result) => {
    saved(result);
    setBaseVersion(result.caseVersion);
    setDirty(false);
    setHasSaved(true);
    setItems(
      result.items.map((i) => ({
        itemId: i.itemId,
        category: i.category,
        location: i.location,
        symptom: i.symptom,
      })),
    );
  });
  const update = (index: number, values: Partial<DraftItem>) => {
    setDirty(true);
    setHasSaved(false);
    setItems((previous) =>
      previous.map((item, i) => (i === index ? { ...item, ...values } : item)),
    );
  };
  const valid = sourceOnly
    ? validText(text, 5000)
    : items.every(
        (i) => validText(i.location, 300) && validText(i.symptom, 1000),
      );
  return (
    <>
      <Link className="back-link" to={`/cases/${data.caseId}`}>
        ← К карточке
      </Link>
      {!sourceOnly && <CreationSteps current={2} />}
      <Title>
        {sourceOnly
          ? "Уточните исходный текст"
          : "Проверьте отдельные проблемы"}
      </Title>
      {!sourceOnly && (
        <p className="page-description">
          Одна проблема - один пункт. Например, свет и дверь добавьте отдельно:
          так можно проверить результат по каждому.
        </p>
      )}
      {data.submissionStatus !== "DRAFT" && (
        <Notice tone="warning">
          Список уже подтверждён. Изменения отправить нельзя; ваш ввод оставлен
          для сверки.
        </Notice>
      )}
      <form
        onSubmit={(e) =>
          submit(e, () =>
            operation.run(
              `/cases/${data.caseId}/${sourceOnly ? "source" : "items"}`,
              {
                expectedCaseVersion: baseVersion,
                ...(sourceOnly ? { sourceText: text } : { items }),
              },
              "PATCH",
            ),
          )
        }
      >
        <fieldset
          disabled={operation.locked || data.submissionStatus !== "DRAFT"}
        >
          {sourceOnly ? (
            <section className="card">
              <Field
                label="Текст обращения"
                value={text}
                onChange={(v) => {
                  setText(v);
                  setDirty(true);
                  setHasSaved(false);
                }}
                multiline
                limit={5000}
              />
            </section>
          ) : (
            <>
              <details className="card">
                <summary>Исходный текст обращения</summary>
                <p className="preserve">{data.sourceText}</p>
              </details>
              {items.map((item, index) => (
                <section className="card" key={item.itemId || `new-${index}`}>
                  <h2>Пункт {index + 1}</h2>
                  <Select
                    label="Тема проблемы"
                    value={item.category}
                    onChange={(v) =>
                      update(index, { category: v as DraftItem["category"] })
                    }
                  >
                    {Object.entries(categories).map(([key, label]) => (
                      <option key={key} value={key}>
                        {label}
                      </option>
                    ))}
                  </Select>
                  <Field
                    label="Где это произошло?"
                    value={item.location}
                    onChange={(v) => update(index, { location: v })}
                    limit={300}
                    hint="Например: подъезд 1, третий этаж."
                  />
                  <Field
                    label="Что не работает или мешает?"
                    value={item.symptom}
                    onChange={(v) => update(index, { symptom: v })}
                    multiline
                    limit={1000}
                  />
                  <Button
                    kind="quiet"
                    onClick={() => {
                      setItems(items.filter((_, i) => i !== index));
                      setDirty(true);
                      setHasSaved(false);
                    }}
                  >
                    Удалить пункт {index + 1}
                  </Button>
                </section>
              ))}
              <Button
                kind="secondary"
                disabled={items.length >= 20}
                onClick={() => {
                  setItems([...items, emptyItem()]);
                  setDirty(true);
                  setHasSaved(false);
                }}
              >
                Добавить ещё проблему
              </Button>
              <p className="caption">
                До 20 пунктов. Пустой список можно сохранить как черновик.
              </p>
            </>
          )}
          <div className="form-actions">
            <Button
              type="submit"
              disabled={
                !valid ||
                (operation.error instanceof ApiError &&
                  operation.error.status === 409)
              }
            >
              Сохранить {sourceOnly ? "текст" : "список"}
            </Button>
          </div>
        </fieldset>
        <CommandError operation={operation} />
        {operation.isPending && <p role="status">Сохраняем…</p>}
        <ConflictReview
          error={operation.error}
          load={load}
          onAdopt={(current) => {
            setBaseVersion(current.caseVersion);
            operation.reset();
            setHasSaved(false);
          }}
        />
        {hasSaved && !dirty && (
          <Notice tone="success">
            {sourceOnly
              ? "Текст сохранён."
              : "Список сохранён. Теперь подтвердите его отдельно."}{" "}
            <Link
              className="text-link"
              to={`/cases/${data.caseId}/${sourceOnly ? "edit" : "review"}`}
            >
              {sourceOnly ? "К пунктам" : "Проверить и подтвердить"}
            </Link>
          </Notice>
        )}
      </form>
      <DirtyGuard dirty={dirty} pending={operation.locked} />
    </>
  );
}
export function ConflictReview({
  error,
  load,
  onAdopt,
}: {
  error: unknown;
  load: () => Promise<Case | undefined>;
  onAdopt?: (data: Case) => void;
}) {
  const [current, setCurrent] = useState<Case>();
  const [loading, setLoading] = useState(false);
  if (!(error instanceof ApiError) || error.status !== 409) return null;
  return (
    <section className="card conflict">
      <h2>Сравните с актуальной карточкой</h2>
      <p>
        Ваш ввод выше сохранён на экране. Ничего не перезаписывается
        автоматически.
      </p>
      <Button
        kind="secondary"
        disabled={loading}
        onClick={async () => {
          setLoading(true);
          try {
            setCurrent(await load());
          } finally {
            setLoading(false);
          }
        }}
      >
        Загрузить актуальную версию
      </Button>
      {current && (
        <>
          <p className="caption">Версия карточки: {current.caseVersion}</p>
          <p className="preserve">{current.sourceText}</p>
          <ul>
            {current.items.map((item) => (
              <li key={item.itemId}>
                {item.symptom} · {item.location} · {workLabels[item.workStatus]}
              </li>
            ))}
          </ul>
          {onAdopt && (
            <Button
              kind="secondary"
              onClick={() => {
                onAdopt(current);
                setCurrent(undefined);
              }}
            >
              Я сравнил данные, продолжить с новой версией
            </Button>
          )}
          <Link className="text-link" to={`/cases/${current.caseId}`}>
            Вернуться к карточке
          </Link>
        </>
      )}
    </section>
  );
}
export function ConfirmCasePage() {
  const { caseId = "" } = useParams();
  const { me } = useSession();
  const query = useCase(caseId);
  const saved = useCaseSaved();
  const operation = useCommand<Case>(saved);
  if (query.isPending || (!query.isFetchedAfterMount && query.isFetching))
    return <Loading />;
  if (query.error || !query.data)
    return (
      <ErrorPanel
        error={query.error}
        retry={() => {
          void query.refetch();
        }}
      />
    );
  const data = query.data;
  if (data.submissionStatus === "CONFIRMED")
    return (
      <section className="card success-screen">
        <span className="success-mark">
          <Icon name="check" />
        </span>
        <Title>Список подтверждён</Title>
        <p>
          Квитанция №{data.displayNumber} от{" "}
          {formatDate(data.receipt.confirmedAt)}.
        </p>
        <Notice>
          Диспетчер увидит ваши пункты. Подтверждение списка не означает
          выполнение работ.
        </Notice>
        <Link className="button primary" to={`/cases/${caseId}`}>
          Открыть обращение
        </Link>
      </section>
    );
  return (
    <>
      <CreationSteps current={3} />
      <Title>Всё верно?</Title>
      <p>
        Подтвердите, что этот список точно описывает ваше обращение. После
        подтверждения текст и состав нельзя изменить.
      </p>
      {data.items.map((item, index) => (
        <article className="card" key={item.itemId}>
          <ItemHeading item={item} number={index + 1} />
        </article>
      ))}
      {!data.items.length && (
        <Notice tone="warning">Добавьте хотя бы один пункт.</Notice>
      )}
      <CommandError operation={operation} />
      <div className="actions">
        <Button
          disabled={
            !data.items.length ||
            data.authorId !== me.userId ||
            operation.locked ||
            !!operation.error
          }
          onClick={() =>
            operation.run(`/cases/${caseId}/confirm`, {
              expectedCaseVersion: data.caseVersion,
              sourceRevision: data.sourceRevision,
            })
          }
        >
          Подтвердить список
        </Button>
        <Link className="button secondary" to={`/cases/${caseId}/edit`}>
          Исправить пункты
        </Link>
      </div>
      <ConflictReview
        error={operation.error}
        load={async () => (await query.refetch()).data}
        onAdopt={() => operation.reset()}
      />
      <DirtyGuard dirty={false} pending={operation.locked} />
    </>
  );
}
