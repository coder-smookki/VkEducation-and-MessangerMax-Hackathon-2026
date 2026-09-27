import type { Case, Item } from "../api/client";
import { formatDate, Icon } from "./ui";
export const categories: Record<Item["category"], string> = {
  LIGHTING: "Освещение",
  ENTRANCE_DOOR: "Дверь",
  CLEANING: "Уборка",
  OTHER: "Другая проблема",
};
const categoryIcons = {
  LIGHTING: "lighting",
  ENTRANCE_DOOR: "door",
  CLEANING: "cleaning",
  OTHER: "other",
} as const;
export const workLabels = {
  NOT_ASSIGNED: "Нет сведений о работах",
  PLANNED: "Работы запланированы",
  REPORTED_DONE: "Сообщили о выполнении",
};
export const observationLabels = {
  NOT_RECEIVED: "Автор ещё не проверил",
  RESTORED: "По наблюдению автора - восстановилось",
  PERSISTS: "По наблюдению автора - проблема осталась",
  CANNOT_CHECK: "Автор пока не может проверить",
};
export const relationLabels = {
  REPORTED_DONE: "В тексте сообщается о выполнении",
  PLANNED: "В тексте описан план",
  SYMPTOM_PERSISTS: "В тексте проблема остаётся",
  INSUFFICIENT_INFORMATION: "Недостаточно сведений",
  UNRELATED: "Относится к другой проблеме",
  CONFLICT: "Противоречивые сведения",
};
export function CategorySymbol({ category }: { category: Item["category"] }) {
  return (
    <span className={`category-symbol category-${category.toLowerCase()}`}>
      <Icon name={categoryIcons[category]} />
    </span>
  );
}
export function CaseStatus({ value }: { value: Case }) {
  const draft = value.submissionStatus === "DRAFT";
  return (
    <span
      className={`status-badge ${draft ? "neutral" : value.receipt.acceptedAt ? "accepted" : "waiting"}`}
    >
      {draft
        ? "Черновик"
        : value.receipt.acceptedAt
          ? "Принято диспетчером"
          : "Ждёт приёма"}
    </span>
  );
}
export function Progress({ value }: { value: Case["progress"] }) {
  return (
    <section className="progress-summary" aria-label="Результат по пунктам">
      <div className="progress-copy">
        <strong>
          {value.confirmed} из {value.total}
        </strong>
        <span>
          {value.allConfirmed
            ? "По всем пунктам автор подтвердил восстановление"
            : "Восстановление подтверждено автором"}
        </span>
      </div>
      <div className="progress-track" aria-hidden="true">
        <span
          style={{
            width: `${value.total ? (value.confirmed / value.total) * 100 : 0}%`,
          }}
        />
      </div>
      <p className="caption">
        Пока без подтверждения восстановления: {value.remaining}.
        {value.withdrawn > 0 &&
          ` Отозвано: ${value.withdrawn}. Отзыв не считается выполнением.`}
      </p>
    </section>
  );
}
export function ItemFacts({ item }: { item: Item }) {
  const active = item.administrativeStatus === "ACTIVE";
  return (
    <>
      <dl className="facts">
        <div>
          <dt>Работы</dt>
          <dd>{workLabels[item.workStatus]}</dd>
        </div>
        <div>
          <dt>Проверка автора</dt>
          <dd className={item.observation === "RESTORED" ? "positive" : ""}>
            {observationLabels[item.observation]}
          </dd>
        </div>
      </dl>
      {!active && (
        <aside className="notice warning">
          <strong>
            {item.administrativeStatus === "ARCHIVED"
              ? "В архиве"
              : "Отозван автором"}
          </strong>
          <p className="preserve">{item.administrativeReason}</p>
          <p className="caption">Это решение не подтверждает ремонт.</p>
        </aside>
      )}
      {item.nextAction && (
        <div className="next-action">
          <p className="next-action-label">
            {active ? "Следующий шаг" : "Последний план до завершения"}
          </p>
          <p className="preserve">{item.nextAction.text}</p>
          <p className="caption">
            Ответственный:{" "}
            {item.nextAction.ownerDisplayName || "диспетчер дома"}
          </p>
          <p className="caption">
            Следующее обновление: {formatDate(item.nextAction.updateDueAt)}. Это
            срок сообщения о ходе работ.
          </p>
          {active &&
            item.nextAction.updateDueAt &&
            Date.parse(item.nextAction.updateDueAt) < Date.now() && (
              <p className="warning-text">
                Срок обновления прошёл. Новых сведений пока нет.
              </p>
            )}
        </div>
      )}
    </>
  );
}
export function ItemHeading({ item, number }: { item: Item; number?: number }) {
  return (
    <div className="item-heading">
      <CategorySymbol category={item.category} />
      <div>
        <p className="eyebrow">
          {number && `Пункт ${number} · `}
          {categories[item.category]}
        </p>
        <h3>{item.symptom}</h3>
        <p className="muted preserve">{item.location}</p>
      </div>
    </div>
  );
}
