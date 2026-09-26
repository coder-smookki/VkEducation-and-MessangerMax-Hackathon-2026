import { useState } from "react";
import { Link, useParams } from "react-router-dom";
import { useSession } from "../App";
import type { Case, Item } from "../api/client";
import { useCase, useCaseSaved, useCommand } from "../api/queries";
import {
  Button,
  CommandError,
  DirtyGuard,
  ErrorPanel,
  Field,
  Loading,
  Notice,
  Title,
  formatDate,
  submit,
  validText,
} from "../components/ui";
import { ItemFacts, ItemHeading } from "../components/domain";
import { ConflictReview } from "./cases";

export function ItemActionPage() {
  const { caseId = "", itemId = "", action = "" } = useParams();
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
  const data = query.data;
  const item = data.items.find((i) => i.itemId === itemId);
  const author = data.authorId === me.userId;
  const dispatcher = me.memberships.some(
    (m) => m.houseId === data.houseId && m.role === "DISPATCHER",
  );
  const allowed =
    action === "observe" || action === "withdraw"
      ? author
      : (action === "next-action" || action === "archive") && dispatcher;
  if (!item || !allowed || data.submissionStatus !== "CONFIRMED")
    return (
      <Notice>
        Действие недоступно. <Link to={`/cases/${caseId}`}>К карточке</Link>
      </Notice>
    );
  return (
    <ItemForm
      key={`${caseId}-${itemId}-${action}`}
      data={data}
      item={item}
      action={action}
      actorId={me.userId}
      load={async () => (await query.refetch()).data}
    />
  );
}
const values = {
  RESTORED: "Восстановилось",
  PERSISTS: "Проблема осталась",
  CANNOT_CHECK: "Не могу проверить",
} as const;
function ItemForm({
  data,
  item,
  action,
  actorId,
  load,
}: {
  data: Case;
  item: Item;
  action: string;
  actorId: string;
  load: () => Promise<Case | undefined>;
}) {
  const [snapshot, setSnapshot] = useState({
    caseVersion: data.caseVersion,
    item,
  });
  const [text, setText] = useState(
    action === "next-action" ? item.nextAction?.text || "" : "",
  );
  const [due, setDue] = useState("");
  const [value, setValue] = useState<keyof typeof values | "">("");
  const [review, setReview] = useState(false);
  const [dirty, setDirty] = useState(false);
  const saved = useCaseSaved();
  const operation = useCommand<Case>((result) => {
    saved(result);
    setDirty(false);
  });
  const observation = action === "observe";
  const next = action === "next-action";
  const valid = observation
    ? !!value
    : validText(text, 1000) &&
      (!next ||
        !due ||
        (Number.isFinite(Date.parse(due)) && Date.parse(due) > Date.now()));
  const active = item.administrativeStatus === "ACTIVE";
  function execute() {
    const base = {
      expectedCaseVersion: snapshot.caseVersion,
      expectedItemVersion: snapshot.item.itemVersion,
    };
    if (observation)
      operation.run(`/items/${item.itemId}/observations`, {
        ...base,
        contentVersion: snapshot.item.contentVersion,
        workRound: snapshot.item.workRound,
        value,
        source:
          snapshot.item.workStatus === "REPORTED_DONE"
            ? "AFTER_REPORT"
            : "INDEPENDENT",
      });
    else if (next)
      operation.run(
        `/items/${item.itemId}/next-action`,
        {
          ...base,
          text,
          ownerId: actorId,
          updateDueAt: due ? new Date(due).toISOString() : null,
        },
        "PUT",
      );
    else
      operation.run(`/items/${item.itemId}/administrative-decisions`, {
        ...base,
        action: action === "archive" ? "ARCHIVE" : "WITHDRAW",
        reason: text,
      });
  }
  if (operation.isSuccess)
    return (
      <section className="card">
        <Title>
          {observation
            ? "Наблюдение сохранено"
            : next
              ? "Следующий шаг сохранён"
              : "Решение сохранено"}
        </Title>
        <p>Изменение относится только к выбранному пункту.</p>
        <Link className="button primary" to={`/cases/${data.caseId}`}>
          Вернуться к обращению
        </Link>
      </section>
    );
  return (
    <>
      <Link className="back-link" to={`/cases/${data.caseId}`}>
        ← К обращению
      </Link>
      <Title>
        {observation
          ? "Что сейчас с этой проблемой?"
          : next
            ? "Следующий шаг по пункту"
            : action === "archive"
              ? "Архивировать этот пункт?"
              : "Отозвать этот пункт?"}
      </Title>
      <article className="card">
        <ItemHeading item={item} />
        <ItemFacts item={item} />
      </article>
      {!active && (
        <Notice tone="warning">
          Пункт уже закрыт административным решением. Новый ответ недоступен;
          ваш ввод остаётся ниже.
        </Notice>
      )}
      <section className="card">
        <form
          onSubmit={(e) =>
            submit(e, () => (review ? execute() : setReview(true)))
          }
        >
          <fieldset disabled={operation.locked || !active}>
            {!review ? (
              <>
                {observation ? (
                  <fieldset className="options">
                    <legend>Ваше наблюдение</legend>
                    {Object.entries(values).map(([key, label]) => (
                      <label className="choice" key={key}>
                        <input
                          type="radio"
                          name="observation"
                          value={key}
                          checked={value === key}
                          onChange={() => {
                            setValue(key as keyof typeof values);
                            setDirty(true);
                          }}
                        />
                        {label}
                      </label>
                    ))}
                  </fieldset>
                ) : (
                  <>
                    {!next && (
                      <Notice tone="warning">
                        Действие нельзя отменить. Оно не означает, что проблема
                        устранена, и не уменьшает исходное число пунктов.
                      </Notice>
                    )}
                    <Field
                      label={
                        next ? "Что будет сделано дальше?" : "Причина решения"
                      }
                      value={text}
                      onChange={(v) => {
                        setText(v);
                        setDirty(true);
                      }}
                      multiline
                      limit={1000}
                    />
                    {next && (
                      <>
                        <Notice>
                          Вы берёте этот шаг на себя как диспетчер дома.
                        </Notice>
                        <Field
                          label="Когда будет следующее обновление?"
                          type="datetime-local"
                          required={false}
                          value={due}
                          onChange={(v) => {
                            setDue(v);
                            setDirty(true);
                          }}
                          hint="Время вашего устройства. Это срок сообщения о ходе работ, не обещание ремонта."
                        />
                      </>
                    )}
                  </>
                )}
                <Button type="submit" disabled={!valid}>
                  Проверить перед отправкой
                </Button>
              </>
            ) : (
              <>
                <h2>Проверьте ответ</h2>
                <p className="preserve">
                  {observation && value ? values[value] : text}
                </p>
                {next && (
                  <p>
                    Обновление:{" "}
                    {due
                      ? formatDate(new Date(due).toISOString())
                      : "Дата не указана"}
                  </p>
                )}
                <p>
                  Только пункт «{item.symptom}». Остальные пункты не изменятся.
                </p>
                <div className="actions">
                  <Button
                    type="submit"
                    kind={!observation && !next ? "danger" : "primary"}
                    disabled={!valid || !!operation.error}
                  >
                    {operation.isPending
                      ? "Сохраняем…"
                      : "Подтвердить отправку"}
                  </Button>
                  <Button
                    kind="secondary"
                    onClick={() => {
                      setReview(false);
                      operation.reset();
                    }}
                  >
                    Изменить ответ
                  </Button>
                </div>
              </>
            )}
          </fieldset>
          <CommandError operation={operation} />
          <ConflictReview
            error={operation.error}
            load={load}
            onAdopt={(current) => {
              const updated = current.items.find(
                (i) => i.itemId === item.itemId,
              );
              if (updated) {
                setSnapshot({
                  caseVersion: current.caseVersion,
                  item: updated,
                });
                operation.reset();
                setReview(false);
              }
            }}
          />
        </form>
      </section>
      <DirtyGuard dirty={dirty} pending={operation.locked} />
    </>
  );
}
