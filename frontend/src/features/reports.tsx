import { StateIllustration } from "../components/brand";
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link, useParams } from "react-router-dom";
import { useSession } from "../App";
import { ApiError, get } from "../api/client";
import type { Case, Report, Suggestions } from "../api/client";
import type { components } from "../api/schema";
import { useCase, useCaseSaved, useCommand } from "../api/queries";
import {
  Button,
  CommandError,
  DirtyGuard,
  ErrorPanel,
  Field,
  Loading,
  Notice,
  Select,
  Title,
  formatDate,
  submit,
  validText,
} from "../components/ui";
import { ItemHeading, relationLabels, workLabels } from "../components/domain";
import { ConflictReview } from "./cases";

type Work = "PLANNED" | "REPORTED_DONE";
export function NewReportPage() {
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
  if (
    !me.memberships.some(
      (m) => m.houseId === query.data.houseId && m.role === "DISPATCHER",
    ) ||
    query.data.submissionStatus !== "CONFIRMED"
  )
    return (
      <Notice>
        Сохранение отчёта доступно диспетчеру подтверждённого обращения.
      </Notice>
    );
  return (
    <ReportForm
      key={caseId}
      data={query.data}
      load={async () => (await query.refetch()).data}
    />
  );
}
function ReportForm({
  data,
  load,
}: {
  data: Case;
  load: () => Promise<Case | undefined>;
}) {
  const [text, setText] = useState("");
  const [selected, setSelected] = useState<string[]>([]);
  const [version, setVersion] = useState(data.caseVersion);
  const saved = useCaseSaved();
  const operation = useCommand<components["schemas"]["CreateReportResponse"]>(
    (result) => {
      saved(result.case);
    },
  );
  if (operation.isSuccess && operation.data)
    return (
      <section className="card success-screen">
        <StateIllustration kind="success" />
        <Title>Текст отчёта сохранён</Title>
        <Notice tone="success">
          Пункты ещё не изменились. Отдельно укажите, с какими из них связан
          отчёт.
        </Notice>
        <Link
          className="button primary"
          to={`/cases/${data.caseId}/reports/${operation.data.report.reportId}`}
        >
          Проверить связи с пунктами
        </Link>
      </section>
    );
  const active = data.items.filter(
    (item) => item.administrativeStatus === "ACTIVE",
  );
  return (
    <>
      <Link className="back-link" to={`/cases/${data.caseId}`}>
        ← К обращению
      </Link>
      <Title>Отчёт о работах</Title>
      <form
        onSubmit={(e) =>
          submit(e, () =>
            operation.run(`/cases/${data.caseId}/reports`, {
              expectedCaseVersion: version,
              reportText: text,
              targetItemIds: selected,
            }),
          )
        }
      >
        <fieldset disabled={operation.locked}>
          <div className="report-layout">
            <section className="card">
              <Field
                label="Текст отчёта"
                value={text}
                onChange={setText}
                multiline
                limit={5000}
                hint="Сохраните точный текст сообщения о работах."
              />
              <Notice>
                Сохранение текста не меняет статус работ и не подтверждает
                ремонт.
              </Notice>
            </section>
            <section className="card">
              <h2>Какие пункты проверить по этому отчёту?</h2>
              <p className="caption">
                Это область проверки, ещё не подтверждённая связь.
              </p>
              {active.map((item) => (
                <label className="choice item-choice" key={item.itemId}>
                  <input
                    type="checkbox"
                    checked={selected.includes(item.itemId)}
                    onChange={(e) =>
                      setSelected(
                        e.target.checked
                          ? [...selected, item.itemId]
                          : selected.filter((id) => id !== item.itemId),
                      )
                    }
                  />
                  <span>
                    <strong>{item.symptom}</strong>
                    <small>{item.location}</small>
                  </span>
                </label>
              ))}
            </section>
          </div>
          <Button
            type="submit"
            disabled={
              !validText(text, 5000) ||
              !selected.length ||
              selected.some((id) => !active.some((i) => i.itemId === id)) ||
              (operation.error instanceof ApiError &&
                operation.error.status === 409)
            }
          >
            Сохранить текст отчёта
          </Button>
        </fieldset>
        <CommandError operation={operation} />
        <ConflictReview
          error={operation.error}
          load={load}
          onAdopt={(current) => {
            setVersion(current.caseVersion);
            operation.reset();
          }}
        />
      </form>
      <DirtyGuard
        dirty={!!text || !!selected.length}
        pending={operation.locked}
      />
    </>
  );
}
export function ReportPage() {
  const { caseId = "", reportId = "" } = useParams();
  const { me } = useSession();
  const caseQuery = useCase(caseId);
  const reportQuery = useQuery({
    queryKey: ["report", reportId],
    queryFn: ({ signal }) => get<Report>(`/reports/${reportId}`, signal),
  });
  const analysis = useQuery({
    queryKey: ["suggestions", reportId],
    enabled: !!reportQuery.data,
    queryFn: ({ signal }) =>
      get<Suggestions>(`/reports/${reportId}/suggestions`, signal),
    refetchInterval: (query) =>
      ["QUEUED", "RUNNING"].includes(query.state.data?.status || "") &&
      query.state.dataUpdateCount < 30
        ? 2000
        : false,
  });
  if (
    caseQuery.isPending ||
    reportQuery.isPending ||
    (!caseQuery.isFetchedAfterMount && caseQuery.isFetching) ||
    (!reportQuery.isFetchedAfterMount && reportQuery.isFetching)
  )
    return <Loading />;
  if (caseQuery.error || reportQuery.error)
    return (
      <ErrorPanel
        error={caseQuery.error || reportQuery.error}
        retry={() => {
          void caseQuery.refetch();
          void reportQuery.refetch();
        }}
      />
    );
  if (
    !caseQuery.data ||
    !reportQuery.data ||
    reportQuery.data.caseId !== caseId
  )
    return <Notice>Отчёт не найден в этом обращении.</Notice>;
  const data = caseQuery.data;
  const dispatcher = me.memberships.some(
    (m) => m.houseId === data.houseId && m.role === "DISPATCHER",
  );
  return (
    <LinkForm
      key={reportId}
      data={data}
      report={reportQuery.data}
      analysis={analysis.data}
      analysisError={analysis.error}
      retryAnalysis={() => {
        void analysis.refetch();
      }}
      dispatcher={dispatcher}
      load={async () => (await caseQuery.refetch()).data}
      meId={me.userId}
    />
  );
}
function LinkForm({
  data,
  report,
  analysis,
  analysisError,
  retryAnalysis,
  dispatcher,
  load,
  meId,
}: {
  data: Case;
  report: Report;
  analysis?: Suggestions;
  analysisError: unknown;
  retryAnalysis: () => void;
  dispatcher: boolean;
  load: () => Promise<Case | undefined>;
  meId: string;
}) {
  const [choices, setChoices] = useState<Record<string, Work>>({});
  const [snapshot, setSnapshot] = useState(data);
  const [review, setReview] = useState(false);
  const saved = useCaseSaved();
  const operation = useCommand<Case>((result) => {
    saved(result);
    setChoices({});
    setReview(false);
    setSnapshot(result);
  });
  const available = data.items.filter(
    (item) =>
      report.targetItemIds.includes(item.itemId) &&
      item.administrativeStatus === "ACTIVE" &&
      !report.links.some((link) => link.itemId === item.itemId),
  );
  const ids = Object.keys(choices);
  const valid =
    ids.length > 0 && ids.every((id) => available.some((i) => i.itemId === id));
  function execute() {
    const links = ids.map((id) => {
      const item = snapshot.items.find((i) => i.itemId === id)!;
      return {
        itemId: id,
        expectedItemVersion: item.itemVersion,
        contentVersion: item.contentVersion,
        expectedWorkRound: item.workRound,
        workStatus: choices[id],
      };
    });
    operation.run(`/reports/${report.reportId}/links`, {
      expectedCaseVersion: snapshot.caseVersion,
      links,
    });
  }
  return (
    <>
      <Link className="back-link" to={`/cases/${data.caseId}`}>
        ← К обращению
      </Link>
      <Title>
        {dispatcher ? "Связь отчёта с пунктами" : "Отчёт о работах"}
      </Title>
      <div className="report-layout">
        <section className="card report-text">
          <p className="eyebrow">
            Сохранённый текст · {formatDate(report.createdAt)}
          </p>
          <blockquote className="preserve">{report.reportText}</blockquote>
          <p className="caption">
            Добавил: {report.actorId === meId ? "вы" : "диспетчер дома"}.
          </p>
          <Notice>
            Сообщение о выполнении работ не заменяет наблюдение автора.
          </Notice>
          {report.links.length > 0 && (
            <>
              <h2>Подтверждённые связи</h2>
              <ul>
                {report.links.map((link) => (
                  <li key={link.itemId}>
                    <strong>
                      {data.items.find((i) => i.itemId === link.itemId)
                        ?.symptom || "Пункт обращения"}
                    </strong>
                    <p>
                      {workLabels[link.workStatus]} · раунд {link.workRound}
                    </p>
                  </li>
                ))}
              </ul>
            </>
          )}
        </section>
        {dispatcher && (
          <section className="card">
            <h2>
              {review
                ? "Подтвердите точные связи"
                : "Выберите только подходящие пункты"}
            </h2>
            {operation.isSuccess && (
              <Notice tone="success">
                Связь сохранена. Остальные пункты не изменились.
              </Notice>
            )}
            {!available.length ? (
              <p>Новых доступных пунктов для этого отчёта нет.</p>
            ) : (
              <form
                onSubmit={(e) =>
                  submit(e, () => (review ? execute() : setReview(true)))
                }
              >
                {!review && (
                  <>
                    {analysisError ? (
                      <>
                        <ErrorPanel
                          error={analysisError}
                          retry={retryAnalysis}
                        />
                        <Notice>
                          Можно проверить текст и выбрать связи вручную.
                        </Notice>
                      </>
                    ) : !analysis || analysis.status === "MANUAL_REQUIRED" ? (
                      <Notice>
                        Автоматическая подсказка недоступна. Проверьте текст и
                        выберите связи вручную.
                      </Notice>
                    ) : analysis.status === "STALE" ? (
                      <Notice tone="warning">
                        Подсказка устарела. Используйте актуальные сведения ниже
                        и проверьте текст вручную.
                      </Notice>
                    ) : ["QUEUED", "RUNNING"].includes(analysis.status) ? (
                      <div className="analysis-pending" role="status">
                        <StateIllustration kind="analysis" />
                        <h3>Анализ в процессе</h3>
                        <span
                          className="brand-loading-track"
                          aria-hidden="true"
                        >
                          <span />
                        </span>
                        <p className="muted">
                          Подсказка ещё готовится. Можно продолжить вручную.
                        </p>
                      </div>
                    ) : (
                      <Notice>
                        Подсказка анализа требует вашей проверки. Ничего не
                        выбрано автоматически.
                      </Notice>
                    )}
                  </>
                )}
                <fieldset disabled={operation.locked}>
                  {(review
                    ? available.filter((i) => ids.includes(i.itemId))
                    : available
                  ).map((item) => {
                    const candidate =
                      analysis?.status === "READY" &&
                      analysis.result?.sourceRevision === data.sourceRevision &&
                      analysis.result.caseId === data.caseId &&
                      analysis.result.reportId === report.reportId &&
                      ["RULES", "NLI"].includes(analysis.result.mode)
                        ? analysis.result.suggestions.find(
                            (s) =>
                              s.itemId === item.itemId &&
                              s.contentVersion === item.contentVersion &&
                              s.workRound === item.workRound,
                          )
                        : undefined;
                    const evidence = candidate?.evidence;
                    const quoteValid =
                      evidence &&
                      Array.from(report.reportText)
                        .slice(evidence.start, evidence.end)
                        .join("") === evidence.quote;
                    return (
                      <div className="link-choice" key={item.itemId}>
                        {review ? (
                          <>
                            <ItemHeading item={item} />
                            <p>{workLabels[choices[item.itemId]]}</p>
                          </>
                        ) : (
                          <>
                            <label className="choice">
                              <input
                                type="checkbox"
                                checked={item.itemId in choices}
                                onChange={(e) =>
                                  setChoices((previous) => {
                                    const next = { ...previous };
                                    if (e.target.checked)
                                      next[item.itemId] = "REPORTED_DONE";
                                    else delete next[item.itemId];
                                    return next;
                                  })
                                }
                              />
                              <span>
                                <strong>{item.symptom}</strong>
                                <small>{item.location}</small>
                              </span>
                            </label>
                            {candidate &&
                              (quoteValid ||
                                (!evidence &&
                                  candidate.relation ===
                                    "INSUFFICIENT_INFORMATION")) && (
                                <aside className="evidence">
                                  <p className="caption">
                                    {relationLabels[candidate.relation]}
                                  </p>
                                  {quoteValid && <q>{evidence.quote}</q>}
                                </aside>
                              )}
                            {item.itemId in choices && (
                              <Select
                                label={`Что сообщается: ${item.symptom}`}
                                value={choices[item.itemId]}
                                onChange={(v) =>
                                  setChoices({
                                    ...choices,
                                    [item.itemId]: v as Work,
                                  })
                                }
                              >
                                <option value="REPORTED_DONE">
                                  О выполнении работ
                                </option>
                                <option value="PLANNED">О плане работ</option>
                              </Select>
                            )}
                          </>
                        )}
                      </div>
                    );
                  })}
                  {review && (
                    <Notice>
                      Откроется новый раунд только для выбранных пунктов. Их
                      прежние наблюдения останутся в истории, новый ответ автора
                      будет ожидаться отдельно.
                    </Notice>
                  )}
                  {!valid && (
                    <p className="caption">
                      Выберите хотя бы один доступный пункт.
                    </p>
                  )}
                  <div className="actions">
                    <Button
                      type="submit"
                      disabled={!valid || !!operation.error}
                    >
                      {review
                        ? "Подтвердить выбранные связи"
                        : "Проверить выбранные связи"}
                    </Button>
                    {review && (
                      <Button
                        kind="secondary"
                        onClick={() => {
                          setReview(false);
                          operation.reset();
                        }}
                      >
                        Изменить выбор
                      </Button>
                    )}
                  </div>
                </fieldset>
                <CommandError operation={operation} />
                <ConflictReview
                  error={operation.error}
                  load={load}
                  onAdopt={(current) => {
                    setSnapshot(current);
                    setChoices({});
                    setReview(false);
                    operation.reset();
                  }}
                />
              </form>
            )}
          </section>
        )}
      </div>
      <DirtyGuard dirty={ids.length > 0} pending={operation.locked} />
    </>
  );
}
