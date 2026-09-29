import { useEffect, useRef, useState } from "react";
import { useInfiniteQuery } from "@tanstack/react-query";
import {
  Link,
  Navigate,
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
  Toast,
  Title,
  formatDate,
  submit,
  validText,
  textError,
  focusInvalid,
} from "../components/ui";
import {
  categories,
  CategorySymbol,
  CaseStatus,
  ItemFacts,
  ItemHeading,
  Progress,
  observationLabels,
  workLabels,
} from "../components/domain";
import { StateIllustration } from "../components/brand";

type ListFilter = "all" | "waiting" | "draft" | "restored";
const listFilters: { value: ListFilter; label: string }[] = [
  { value: "all", label: "Все" },
  { value: "waiting", label: "Ждут приёма" },
  { value: "draft", label: "Черновики" },
  { value: "restored", label: "Восстановлено" },
];
function matchesFilter(value: Case, filter: ListFilter) {
  if (filter === "waiting")
    return value.submissionStatus === "CONFIRMED" && !value.receipt.acceptedAt;
  if (filter === "draft") return value.submissionStatus === "DRAFT";
  if (filter === "restored") return value.progress.allConfirmed;
  return true;
}
const queueFilters = [
  { value: "", label: "Все", hint: "Все доступные обращения выбранного дома." },
  {
    value: "NEEDS_ACCEPTANCE",
    label: "Ждут приёма",
    hint: "Автор подтвердил список. Примите обращение, чтобы начать работу.",
  },
  {
    value: "NEEDS_ACTION",
    label: "Нужен шаг",
    hint: "Нужен следующий шаг: он не назначен, срок прошёл или автор сообщил, что проблема осталась.",
  },
  {
    value: "AWAITING_AUTHOR",
    label: "Проверка автора",
    hint: "О выполнении сообщили. Ожидается проверка результата автором.",
  },
  {
    value: "ARCHIVED",
    label: "Архив",
    hint: "Обращения, в которых есть архивные пункты.",
  },
];
function normalizeSearch(value: string) {
  return value
    .toLocaleLowerCase("ru")
    .replaceAll("ё", "е")
    .replace(/\s+/g, " ")
    .trim();
}
function caseSearchResult(value: Case, needle: string) {
  const itemText = (item: Item) =>
    [item.symptom, item.location, categories[item.category]].join(" ");
  if (
    needle &&
    !normalizeSearch(
      [
        value.displayNumber,
        value.sourceText,
        ...value.items.map(itemText),
      ].join(" "),
    ).includes(needle)
  )
    return null;
  const index = needle
    ? value.items.findIndex((item) =>
        normalizeSearch(itemText(item)).includes(needle),
      )
    : -1;
  const item = value.items[index >= 0 ? index : 0];
  const sourceMatch =
    !!needle && index < 0 && normalizeSearch(value.sourceText).includes(needle);
  // A secondary item/source match must be visible; the first item can be unrelated.
  let source = value.sourceText;
  if (sourceMatch) {
    source = source.replace(/\s+/g, " ").trim();
    const start = Math.max(0, normalizeSearch(source).indexOf(needle) - 45);
    source = `${start ? "…" : ""}${source.slice(start, start + 160)}${source.length > start + 160 ? "…" : ""}`;
  }
  return {
    value,
    item,
    index,
    sourceMatch,
    title:
      sourceMatch || !item
        ? source.slice(0, sourceMatch ? 165 : 100)
        : item.symptom,
  };
}
export function CasesPage() {
  const { house, me } = useSession();
  const [search, setSearch] = useSearchParams();
  const [searchOpen, setSearchOpen] = useState(false);
  const [local, setLocal] = useState({
    houseId: "",
    text: "",
    filter: "all" as ListFilter,
  });
  const text = local.houseId === house?.houseId ? local.text : "";
  const filter = local.houseId === house?.houseId ? local.filter : "all";
  const dispatcher = house?.role === "DISPATCHER";
  const queue = dispatcher ? search.get("queue") || "" : "";
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
      <section className="card empty empty-state">
        <StateIllustration kind="house" />
        <Title>Подключите свой дом</Title>
        <p>Чтобы создать обращение, нужен код от диспетчера.</p>
        <Link className="button primary" to="/access">
          Ввести код приглашения
        </Link>
      </section>
    );
  const all = query.data?.pages.flatMap((page) => page.items) || [];
  const needle = normalizeSearch(text);
  const visible = all
    .filter((value) => dispatcher || matchesFilter(value, filter))
    .map((value) => caseSearchResult(value, needle))
    .filter((result) => result !== null);
  function setText(value: string) {
    setLocal({ houseId: house!.houseId, text: value, filter });
  }
  function resetFilters() {
    setLocal({ houseId: house!.houseId, text: "", filter: "all" });
    if (queue) setSearch({ house: house!.houseId });
  }
  return (
    <>
      <div className="page-heading case-list-heading">
        <div>
          {dispatcher && (
            <p className="eyebrow dispatcher-label">Рабочее место диспетчера</p>
          )}
          <div className="case-list-title">
            <Title>{dispatcher ? "Очередь обращений" : "Мои обращения"}</Title>
            <button
              className="icon-button search-trigger"
              type="button"
              aria-label="Поиск обращений"
              aria-expanded={searchOpen || !!text}
              aria-controls="case-search"
              onClick={() => {
                if (searchOpen || text) {
                  setSearchOpen(false);
                  setText("");
                } else setSearchOpen(true);
              }}
            >
              <Icon name="search" />
            </button>
            <button
              className="icon-button"
              type="button"
              aria-label="Обновить"
              onClick={() => {
                void query.refetch();
              }}
              disabled={query.isFetching}
            >
              <Icon name="refresh" />
            </button>
          </div>
          <p className="page-description">{house.address}</p>
        </div>
        {!dispatcher && (
          <Link className="button primary" to={`/new?house=${house.houseId}`}>
            <Icon name="plus" />
            Новое обращение
          </Link>
        )}
      </div>
      <div id="case-search" hidden={!searchOpen && !text}>
        <label className="case-search">
          <Icon name="search" />
          <span className="sr-only">Поиск по загруженным обращениям</span>
          <input
            type="search"
            value={text}
            onChange={(event) => setText(event.target.value)}
            placeholder="Номер, тема или место"
          />
        </label>
      </div>
      <section className="toolbar list-tools" aria-label="Фильтры">
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
        {dispatcher ? (
          <div className="dispatcher-filters">
            <div className="filter-chips" aria-label="Очередь диспетчера">
              {queueFilters.map((item) => (
                <button
                  key={item.value}
                  type="button"
                  className={`filter-chip${queue === item.value ? " active" : ""}`}
                  aria-pressed={queue === item.value}
                  onClick={() =>
                    setSearch({
                      house: house.houseId,
                      ...(item.value ? { queue: item.value } : {}),
                    })
                  }
                >
                  {item.label}
                </button>
              ))}
            </div>
            <p className="caption queue-hint">
              {queueFilters.find((item) => item.value === queue)?.hint}
            </p>
          </div>
        ) : (
          <div className="filter-chips" aria-label="Состояние обращений">
            {listFilters.map((item) => (
              <button
                className={`filter-chip${filter === item.value ? " active" : ""}`}
                type="button"
                key={item.value}
                aria-pressed={filter === item.value}
                onClick={() =>
                  setLocal({ houseId: house.houseId, text, filter: item.value })
                }
              >
                {item.label}
                {query.data && (
                  <span className="filter-count">
                    {
                      all.filter((value) => matchesFilter(value, item.value))
                        .length
                    }
                  </span>
                )}
              </button>
            ))}
          </div>
        )}
      </section>
      {!!needle && !query.isPending && !query.error && (
        <p className="caption list-scope" role="status">
          Найдено: {visible.length} из {all.length} загруженных обращений.
        </p>
      )}
      {query.hasNextPage && !query.error && (
        <p className="caption list-scope">
          Загружено {all.length} обращений. Поиск и счётчики относятся к
          загруженным карточкам. Остальные доступны по кнопке «Загрузить ещё».
        </p>
      )}
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
          {!visible.length ? (
            <section className="card empty empty-state">
              <StateIllustration
                kind={all.length || queue ? "empty" : "house"}
              />
              <h2>
                {queue || needle || filter !== "all"
                  ? "Под этот фильтр пока ничего не подходит"
                  : "Пока нет обращений"}
              </h2>
              <p>
                {queue || needle || filter !== "all"
                  ? "Измените поиск или покажите все доступные обращения."
                  : dispatcher
                    ? "Здесь появятся обращения жителей после подтверждения списка."
                    : "Здесь будут ваши обращения и новости о работах по дому."}
              </p>
              {(queue || needle || filter !== "all") && (
                <Button kind="secondary" onClick={resetFilters}>
                  Сбросить фильтр
                </Button>
              )}
            </section>
          ) : dispatcher ? (
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
                {visible.map(({ value, item, index, sourceMatch, title }) => (
                  <tr key={value.caseId}>
                    <td>
                      <p className="eyebrow">№{value.displayNumber}</p>
                      <Link to={`/cases/${value.caseId}`}>{title}</Link>
                      <SearchMatch
                        item={item}
                        index={index}
                        sourceMatch={sourceMatch}
                      />
                      {item?.location && (
                        <p className="caption">
                          {item.location}
                          {value.items.length > 1 &&
                            ` · ещё пунктов: ${value.items.length - 1}`}
                        </p>
                      )}
                    </td>
                    <td data-label="Приём">
                      <CaseStatus value={value} />
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
              {visible.map(({ value, item, index, sourceMatch, title }) => (
                <article
                  className={`card case-row${value.submissionStatus === "DRAFT" ? " draft-card" : ""}`}
                  key={value.caseId}
                >
                  <CategorySymbol
                    category={sourceMatch ? "OTHER" : item?.category || "OTHER"}
                  />
                  <div className="case-card-content">
                    <p className="eyebrow">
                      №{value.displayNumber} · {formatDate(value.createdAt)}
                    </p>
                    <div className="case-card-top">
                      <h2>
                        <Link to={`/cases/${value.caseId}`}>{title}</Link>
                      </h2>
                      <CaseStatus value={value} />
                    </div>
                    <SearchMatch
                      item={item}
                      index={index}
                      sourceMatch={sourceMatch}
                    />
                    {item?.location && !sourceMatch && (
                      <p className="caption">
                        {item.location}
                        {value.items.length > 1 &&
                          ` · ещё пунктов: ${value.items.length - 1}`}
                      </p>
                    )}
                    <div className="case-card-bottom">
                      <div className="mini-progress" aria-hidden="true">
                        <span
                          style={{
                            width: `${value.progress.total ? (value.progress.confirmed / value.progress.total) * 100 : 0}%`,
                          }}
                        />
                      </div>
                      <span className="caption">
                        <strong>
                          {value.progress.confirmed} из {value.progress.total}
                        </strong>{" "}
                        подтверждены автором
                        {value.progress.withdrawn > 0 &&
                          ` · отозвано ${value.progress.withdrawn}`}
                      </span>
                    </div>
                    {value.submissionStatus === "DRAFT" && (
                      <Link
                        className="draft-continue"
                        to={`/cases/${value.caseId}/edit`}
                      >
                        Продолжить черновик
                      </Link>
                    )}
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
  const { me, selectHouse } = useSession();
  const query = useCase(caseId);
  const caseHouseId = query.isSuccess ? query.data.houseId : undefined;
  useEffect(() => {
    if (caseHouseId) selectHouse(caseHouseId);
  }, [caseHouseId, selectHouse]);
  const saved = useCaseSaved();
  const acceptance = useCommand<Case>(saved);
  if (query.isPending || (!query.isFetchedAfterMount && query.isFetching))
    return <Loading />;
  if (
    !query.data ||
    (query.error instanceof ApiError && [403, 404].includes(query.error.status))
  )
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
        <Icon name="arrow" />К обращениям
      </Link>
      <section className="card case-intro">
        <div className="case-intro-summary">
          <CategorySymbol category={data.items[0]?.category || "OTHER"} />
          <div>
            <Title>Обращение №{data.displayNumber}</Title>
            <p className="caption">{formatDate(data.createdAt)}</p>
            <p className="page-description">{membership?.address}</p>
          </div>
        </div>
        <div className="case-meta">
          <div>
            <CaseStatus value={data} />
            {data.receipt.acceptedAt && (
              <p className="caption">
                Принято диспетчером {formatDate(data.receipt.acceptedAt)}
              </p>
            )}
          </div>
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
      </section>
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
                disabled={
                  acceptance.locked ||
                  (acceptance.error instanceof ApiError &&
                    acceptance.error.status === 409)
                }
                onClick={() =>
                  acceptance.run(`/cases/${caseId}/acceptance`, {
                    expectedCaseVersion: data.caseVersion,
                  })
                }
              >
                {acceptance.isPending ? "Принимаем…" : "Принять обращение"}
              </Button>
              <ConflictReview
                error={acceptance.error}
                load={async () =>
                  (await query.refetch({ throwOnError: true })).data
                }
                onAdopt={() => acceptance.reset()}
              />
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
          <summary>
            <Icon name="arrow" />
            <span>Исходный текст и квитанция</span>
          </summary>
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
              <summary>
                <Icon name="arrow" />
                <span>Другие действия</span>
              </summary>
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
            <summary>
              <Icon name="arrow" />
              <span>Сведения и действия</span>
            </summary>
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
      <summary>
        <Icon name="arrow" />
        <span>История обращения</span>
      </summary>
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

function SearchMatch({
  item,
  index,
  sourceMatch,
}: {
  item?: Item;
  index: number;
  sourceMatch: boolean;
}) {
  if (sourceMatch)
    return <p className="caption search-match">Совпадение в исходном тексте</p>;
  if (index < 0 || !item) return null;
  return (
    <p className="caption search-match">
      Пункт {index + 1} · {categories[item.category]}
      {item.administrativeStatus === "WITHDRAWN"
        ? " · Отозван"
        : item.administrativeStatus === "ARCHIVED"
          ? " · В архиве"
          : ""}
    </p>
  );
}

export function NewCasePage() {
  const { house } = useSession();
  const navigate = useNavigate();
  const saved = useCaseSaved();
  const [text, setText] = useState("");
  const [attempted, setAttempted] = useState(false);
  const [validationAttempt, setValidationAttempt] = useState(0);
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
  if (house.role === "DISPATCHER")
    return <Navigate to={`/?house=${house.houseId}`} replace />;
  return (
    <>
      <Link className="back-link" to={`/?house=${house.houseId}`}>
        <Icon name="arrow" />К обращениям
      </Link>
      <CreationSteps current={1} />
      <div className={`creation-intro ${text ? "has-text" : ""}`}>
        <StateIllustration kind="document" />
        <div>
          <Title>Что случилось?</Title>
          <p className="muted">{house.address}</p>
          <p className="page-description">
            Напишите, что произошло. Каждый вопрос вы сможете выделить в
            отдельный пункт.
          </p>
        </div>
      </div>
      <section className="card form-card">
        <form
          noValidate
          onSubmit={(e) => {
            e.preventDefault();
            setAttempted(true);
            if (!validText(text, 5000)) {
              setValidationAttempt((value) => value + 1);
              focusInvalid(e.currentTarget);
              return;
            }
            setValidationAttempt(0);
            create.run("/cases", {
              houseId: house.houseId,
              sourceText: text,
            });
          }}
        >
          <fieldset disabled={create.locked}>
            <Field
              label="Текст обращения"
              value={text}
              onChange={setText}
              multiline
              limit={5000}
              hint="Укажите место: подъезд, этаж или участок дома."
              error={
                attempted
                  ? textError(text, 5000, "Опишите, что произошло.")
                  : undefined
              }
            />
            <Notice>
              Можно писать как есть. Список проблем вы проверите перед
              отправкой.
            </Notice>
            <Button type="submit">Сохранить и перейти к пунктам</Button>
          </fieldset>
          <CommandError operation={create} />
          {create.isPending && <p role="status">Сохраняем черновик…</p>}
          {validationAttempt > 0 && !validText(text, 5000) && (
            <Toast key={validationAttempt} tone="error">
              {text.trim()
                ? "Сократите текст обращения до 5000 символов."
                : "Заполните обязательные поля."}
            </Toast>
          )}
        </form>
      </section>
      <DirtyGuard dirty={!!text} pending={create.locked} />
    </>
  );
}
function Created({ data, next }: { data: Case; next: () => void }) {
  return (
    <section className="card success-screen">
      <StateIllustration kind="success" />
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
  if (
    !query.data ||
    (query.error instanceof ApiError && [403, 404].includes(query.error.status))
  )
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
      load={async () => (await query.refetch({ throwOnError: true })).data}
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
  const [attempted, setAttempted] = useState(false);
  const [needsItem, setNeedsItem] = useState(false);
  const [validationAttempt, setValidationAttempt] = useState(0);
  const advance = useRef(false);
  const formRef = useRef<HTMLFormElement>(null);
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
  function save(goNext: boolean) {
    if (operation.locked || data.submissionStatus !== "DRAFT") return;
    setAttempted(true);
    setNeedsItem(goNext && !sourceOnly && items.length === 0);
    if (!valid || (goNext && !sourceOnly && !items.length)) {
      setValidationAttempt((value) => value + 1);
      if (formRef.current) focusInvalid(formRef.current);
      return;
    }
    setValidationAttempt(0);
    advance.current = goNext;
    setHasSaved(false);
    operation.run(
      `/cases/${data.caseId}/${sourceOnly ? "source" : "items"}`,
      {
        expectedCaseVersion: baseVersion,
        ...(sourceOnly ? { sourceText: text } : { items }),
      },
      "PATCH",
    );
  }
  if (hasSaved && !dirty && advance.current) {
    return (
      <Navigate
        to={`/cases/${data.caseId}/${sourceOnly ? "edit" : "review"}`}
      />
    );
  }
  return (
    <>
      <Link className="back-link" to={`/cases/${data.caseId}`}>
        <Icon name="arrow" />К карточке
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
        ref={formRef}
        noValidate
        onSubmit={(e) => submit(e, () => save(true))}
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
                error={
                  attempted
                    ? textError(text, 5000, "Опишите, что произошло.")
                    : undefined
                }
              />
            </section>
          ) : (
            <>
              <details className="card">
                <summary>
                  <Icon name="arrow" />
                  <span>Исходный текст обращения</span>
                </summary>
                <p className="preserve">{data.sourceText}</p>
              </details>
              {items.map((item, index) => (
                <section
                  className="card draft-item"
                  key={item.itemId || `new-${index}`}
                >
                  <div className="draft-item-heading">
                    <CategorySymbol category={item.category} />
                    <h2>Пункт {index + 1}</h2>
                  </div>
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
                    error={
                      attempted
                        ? textError(
                            item.location,
                            300,
                            "Укажите место: подъезд, этаж или участок дома.",
                          )
                        : undefined
                    }
                  />
                  <Field
                    label="Что не работает или мешает?"
                    value={item.symptom}
                    onChange={(v) => update(index, { symptom: v })}
                    multiline
                    limit={1000}
                    error={
                      attempted
                        ? textError(
                            item.symptom,
                            1000,
                            "Опишите, что не работает или мешает.",
                          )
                        : undefined
                    }
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
              <div
                className={needsItem ? "empty-items invalid" : "empty-items"}
                data-validation-error={needsItem || undefined}
                tabIndex={needsItem ? -1 : undefined}
              >
                <Button
                  kind="secondary"
                  disabled={items.length >= 20}
                  onClick={() => {
                    setItems([...items, emptyItem()]);
                    setDirty(true);
                    setHasSaved(false);
                    setNeedsItem(false);
                  }}
                >
                  {items.length
                    ? "Добавить ещё проблему"
                    : "Добавить первую проблему"}
                </Button>
              </div>
              <p className="caption">
                До 20 пунктов. Пустой список можно сохранить как черновик.
              </p>
            </>
          )}
          <div className="form-actions">
            <Button
              type="submit"
              disabled={
                operation.error instanceof ApiError &&
                operation.error.status === 409
              }
            >
              {sourceOnly
                ? "Сохранить текст и перейти к пунктам"
                : "Перейти к проверке"}
            </Button>
            <Button
              kind="secondary"
              disabled={
                operation.error instanceof ApiError &&
                operation.error.status === 409
              }
              onClick={() => save(false)}
            >
              Сохранить {sourceOnly ? "текст" : "список"}
            </Button>
          </div>
        </fieldset>
        <CommandError operation={operation} />
        {validationAttempt > 0 && (!valid || needsItem) && (
          <Toast key={validationAttempt} tone="error">
            {needsItem
              ? "Добавьте хотя бы одну проблему и заполните её поля."
              : (
                    sourceOnly
                      ? !text.trim()
                      : items.some(
                          (item) =>
                            !item.location.trim() || !item.symptom.trim(),
                        )
                  )
                ? "Заполните обязательные поля."
                : "Сократите текст в выделенных полях до указанного лимита."}
          </Toast>
        )}
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
          <Toast>
            {sourceOnly
              ? "Текст сохранён."
              : items.length
                ? "Список сохранён. Можно перейти к проверке."
                : "Пустой черновик сохранён. Для отправки добавьте хотя бы одну проблему."}
          </Toast>
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
  const [loadError, setLoadError] = useState<unknown>();
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
          setCurrent(undefined);
          setLoadError(undefined);
          try {
            const latest = await load();
            if (!latest) throw new Error("CASE_REFRESH_FAILED");
            setCurrent(latest);
          } catch (failure) {
            setLoadError(failure);
          } finally {
            setLoading(false);
          }
        }}
      >
        Загрузить актуальную версию
      </Button>
      <ErrorPanel error={loadError} />
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
  if (
    !query.data ||
    (query.error instanceof ApiError && [403, 404].includes(query.error.status))
  )
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
        <StateIllustration kind="success" />
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
      <aside className="notice review-intro">
        <span className="category-symbol">
          <Icon name="other" />
        </span>
        <div>
          <strong>Проверьте данные перед отправкой</strong>
          <p>
            Подтвердите, что этот список точно описывает ваше обращение. После
            подтверждения текст и состав нельзя изменить.
          </p>
        </div>
      </aside>
      <section className="card review-source">
        <dl className="facts">
          <div>
            <dt>Дом</dt>
            <dd>
              {me.memberships.find(
                (membership) => membership.houseId === data.houseId,
              )?.address || "Дом обращения"}
            </dd>
          </div>
          <div>
            <dt>Исходный текст</dt>
            <dd className="preserve">{data.sourceText}</dd>
          </div>
        </dl>
      </section>
      {data.items.map((item, index) => (
        <article className="card review-item" key={item.itemId}>
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
            (operation.error instanceof ApiError &&
              operation.error.status === 409)
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
        load={async () => (await query.refetch({ throwOnError: true })).data}
        onAdopt={() => operation.reset()}
      />
      <DirtyGuard dirty={false} pending={operation.locked} />
    </>
  );
}
