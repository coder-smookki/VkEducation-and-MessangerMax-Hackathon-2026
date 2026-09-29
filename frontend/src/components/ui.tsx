import { useEffect, useId, useRef, useState } from "react";
import type { FormEvent, ReactNode } from "react";
import { createPortal } from "react-dom";
import { useBlocker, useBeforeUnload } from "react-router-dom";
import { ApiError, errorMessage } from "../api/client";
import { BrandSplash, StateIllustration } from "./brand";
import { closingConfirmation } from "../platform/max";

export function Button({
  children,
  onClick,
  disabled,
  kind = "primary",
  type = "button",
}: {
  children: ReactNode;
  onClick?: () => void;
  disabled?: boolean;
  kind?: "primary" | "secondary" | "danger" | "quiet";
  type?: "submit" | "button";
}) {
  return (
    <button
      type={type}
      className={`button ${kind}`}
      onClick={onClick}
      disabled={disabled}
    >
      {children}
    </button>
  );
}
export function Notice({
  children,
  tone = "info",
}: {
  children: ReactNode;
  tone?: "info" | "warning" | "success";
}) {
  return (
    <aside className={`notice ${tone}`}>
      <span className="notice-icon" aria-hidden="true">
        <Icon
          name={
            tone === "success"
              ? "check"
              : tone === "warning"
                ? "lighting"
                : "other"
          }
        />
      </span>
      <div>{children}</div>
    </aside>
  );
}
export function Loading() {
  return <BrandSplash compact label="Загружаем данные…" />;
}
export function Toast({
  children,
  tone = "success",
}: {
  children: ReactNode;
  tone?: "success" | "error" | "info";
}) {
  const [closed, setClosed] = useState(false);
  const [paused, setPaused] = useState(false);
  useEffect(() => {
    if (tone === "error" || closed || paused) return;
    const timer = window.setTimeout(() => setClosed(true), 8000);
    return () => window.clearTimeout(timer);
  }, [tone, closed, paused]);
  if (closed) return null;
  return createPortal(
    <aside
      className={`toast ${tone}`}
      aria-label="Уведомление"
      onMouseEnter={() => setPaused(true)}
      onMouseLeave={() => setPaused(false)}
      onFocus={() => setPaused(true)}
      onBlur={(event) => {
        if (!event.currentTarget.contains(event.relatedTarget))
          setPaused(false);
      }}
    >
      <span className="notice-icon" aria-hidden="true">
        <Icon name={tone === "success" ? "check" : "other"} />
      </span>
      <div
        className="toast-content"
        role={tone === "error" ? "alert" : "status"}
        aria-atomic="true"
      >
        {children}
      </div>
      <button
        type="button"
        className="toast-close"
        aria-label="Закрыть уведомление"
        onClick={() => setClosed(true)}
      >
        <Icon name="plus" />
      </button>
    </aside>,
    document.body,
  );
}
export function ErrorPanel({
  error,
  retry,
}: {
  error: unknown;
  retry?: () => void;
}) {
  if (!error) return null;
  return (
    <div className={`notice error ${retry ? "load-error" : ""}`} role="alert">
      {retry && <StateIllustration kind="error" />}
      <p>{errorMessage(error)}</p>
      {error instanceof ApiError && error.requestId && (
        <p className="caption">
          Код запроса: <span className="identifier">{error.requestId}</span>
        </p>
      )}
      {retry && (
        <Button kind="secondary" onClick={retry}>
          Повторить загрузку
        </Button>
      )}
    </div>
  );
}
export function CommandError({
  operation,
}: {
  operation: { error: unknown; retry: () => void; isPending: boolean };
}) {
  const error = operation.error;
  if (!error) return null;
  return (
    <Toast tone="error">
      <p>{errorMessage(error)}</p>
      {error instanceof ApiError && error.requestId && (
        <p className="caption">
          Код запроса: <span className="identifier">{error.requestId}</span>
        </p>
      )}
      {error instanceof ApiError &&
        (error.unknownOutcome || error.status === 401) && (
          <Button
            kind="secondary"
            onClick={operation.retry}
            disabled={operation.isPending}
          >
            Повторить ту же отправку
          </Button>
        )}
    </Toast>
  );
}
export function Field({
  label,
  value,
  onChange,
  limit,
  multiline = false,
  hint,
  type = "text",
  required = true,
  error,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  limit?: number;
  multiline?: boolean;
  hint?: string;
  type?: string;
  required?: boolean;
  error?: string;
}) {
  const id = useId();
  const length = Array.from(value).length;
  const tooLong = limit !== undefined && length > limit;
  const invalid = !!error || tooLong;
  const props = {
    id,
    value,
    required,
    "aria-describedby": `${id}-help${error ? ` ${id}-error` : ""}`,
    "aria-invalid": invalid || undefined,
    onChange: (
      event: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>,
    ) => onChange(event.target.value),
  };
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      {multiline ? (
        <textarea {...props} rows={4} />
      ) : (
        <input {...props} type={type} />
      )}
      {error && (
        <span id={`${id}-error`} className="sr-only">
          {error}
        </span>
      )}
      <div id={`${id}-help`} className={`caption ${tooLong ? "invalid" : ""}`}>
        {hint && <span>{hint}</span>}
        {limit && (
          <span className="counter">
            {length} / {limit}
            {tooLong ? " - сократите текст" : ""}
          </span>
        )}
      </div>
    </div>
  );
}
export function Select({
  label,
  value,
  onChange,
  children,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  children: ReactNode;
}) {
  const id = useId();
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      <select id={id} value={value} onChange={(e) => onChange(e.target.value)}>
        {children}
      </select>
    </div>
  );
}
export const validText = (text: string, max: number) =>
  text.trim().length > 0 && Array.from(text).length <= max;
export const textError = (text: string, max: number, emptyMessage: string) =>
  !text.trim()
    ? emptyMessage
    : Array.from(text).length > max
      ? `Сократите текст до ${max} символов.`
      : undefined;
export function focusInvalid(form: HTMLFormElement) {
  window.requestAnimationFrame(() => {
    const field = form.querySelector<HTMLElement>(
      '[aria-invalid="true"], [data-validation-error]',
    );
    field?.focus();
    field?.scrollIntoView({ block: "center" });
  });
}
export function submit(event: FormEvent, action: () => void) {
  event.preventDefault();
  action();
}
export const formatDate = (value: string | null | undefined) =>
  value
    ? new Intl.DateTimeFormat("ru-RU", {
        dateStyle: "medium",
        timeStyle: "short",
      }).format(new Date(value))
    : "Пока нет";

export function CreationSteps({ current }: { current: 1 | 2 | 3 }) {
  return (
    <ol
      className="creation-steps"
      aria-label={`Создание обращения, шаг ${current} из 3`}
    >
      {["Описание", "Пункты", "Проверка"].map((label, index) => (
        <li
          key={label}
          aria-current={index + 1 === current ? "step" : undefined}
          data-complete={index + 1 < current}
        >
          <span className="step-dot" aria-hidden="true">
            {index + 1 < current ? <Icon name="check" /> : index + 1}
          </span>
          <span>{label}</span>
        </li>
      ))}
    </ol>
  );
}

export function Icon({
  name,
}: {
  name:
    | "plus"
    | "refresh"
    | "settings"
    | "arrow"
    | "home"
    | "check"
    | "lighting"
    | "door"
    | "cleaning"
    | "other"
    | "user"
    | "lock"
    | "search"
    | "file"
    | "list"
    | "clock"
    | "pin";
}) {
  const paths = {
    plus: "M12 5v14M5 12h14",
    refresh:
      "M21 3v5h-5M3 21v-5h5M3 12a9 9 0 0 1 15.36-6.36L21 8M21 12a9 9 0 0 1-15.36 6.36L3 16",
    settings:
      "M9 3h6l1 3 3 1 2 5-2 5-3 1-1 3H9l-1-3-3-1-2-5 2-5 3-1 1-3ZM15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0",
    user: "M16 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0ZM4 21v-2a7 7 0 0 1 7-7h2a7 7 0 0 1 7 7v2H4Z",
    lock: "M7 10V7a5 5 0 0 1 10 0v3M5 10h14v11H5V10ZM12 14v3",
    search: "m16 16 5 5M18 10a8 8 0 1 1-16 0 8 8 0 0 1 16 0",
    file: "M6 3h8l4 4v14H6V3ZM14 3v5h4M9 12h6M9 16h6",
    list: "M9 6h12M9 12h12M9 18h12M3 6h.01M3 12h.01M3 18h.01",
    clock: "M12 8v5l3 2M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0",
    pin: "M19 10c0 5-7 12-7 12S5 15 5 10a7 7 0 0 1 14 0ZM15 10a3 3 0 1 1-6 0 3 3 0 0 1 6 0",
    arrow: "m9 5 7 7-7 7",
    home: "m3 10 9-7 9 7M5 9v12h14V9M9 21v-7h6v7",
    check: "m5 12 4 4L19 6",
    lighting: "M9 18h6M10 21h4M8 14a6 6 0 1 1 8 0c-1 1-1 2-1 2H9s0-1-1-2",
    door: "M4 21h16M6 21V3h12v18M14 12h.01",
    cleaning: "m16 3-6 10M4 21h13l-1-7-6-3-6 10ZM8 21l3-6",
    other: "M12 8v5M12 17h.01M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0",
  };
  return (
    <svg
      className="icon"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.7"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d={paths[name]} />
    </svg>
  );
}

export function Dialog({
  title,
  children,
  onCancel,
}: {
  title: string;
  children: ReactNode;
  onCancel: () => void;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  const id = useId();
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    const dialog = ref.current;
    dialog?.showModal();
    return () => {
      dialog?.close();
      previous?.focus();
    };
  }, []);
  return (
    <dialog
      ref={ref}
      aria-labelledby={id}
      onCancel={(event) => {
        event.preventDefault();
        onCancel();
      }}
    >
      <h2 id={id}>{title}</h2>
      {children}
    </dialog>
  );
}
export function DirtyGuard({
  dirty,
  pending = false,
}: {
  dirty: boolean;
  pending?: boolean;
}) {
  const blocker = useBlocker(dirty || pending);
  useBeforeUnload((event) => {
    if (dirty || pending) {
      event.preventDefault();
      event.returnValue = "";
    }
  });
  useEffect(() => {
    closingConfirmation(dirty || pending);
    return () => closingConfirmation(false);
  }, [dirty, pending]);
  if (blocker.state !== "blocked") return null;
  return (
    <Dialog
      title={
        pending ? "Отправка ещё не завершилась" : "Остаться на этом экране?"
      }
      onCancel={() => blocker.reset()}
    >
      <p>
        {pending
          ? "Дождитесь ответа. Сервер мог сохранить команду."
          : "Несохранённый ввод будет потерян. Восстановить его после ухода не получится."}
      </p>
      <div className="actions">
        <Button onClick={() => blocker.reset()}>Остаться</Button>
        {!pending && (
          <Button kind="danger" onClick={() => blocker.proceed()}>
            Уйти без сохранения
          </Button>
        )}
      </div>
    </Dialog>
  );
}
export function Title({ children }: { children: ReactNode }) {
  const ref = useRef<HTMLHeadingElement>(null);
  useEffect(() => {
    ref.current?.focus({ preventScroll: true });
    window.scrollTo(0, 0);
  }, []);
  return (
    <h1 ref={ref} tabIndex={-1}>
      {children}
    </h1>
  );
}
