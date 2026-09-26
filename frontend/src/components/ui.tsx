import { useEffect, useId, useRef } from "react";
import type { FormEvent, ReactNode } from "react";
import { useBlocker, useBeforeUnload } from "react-router-dom";
import { ApiError, errorMessage } from "../api/client";
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
  return <aside className={`notice ${tone}`}>{children}</aside>;
}
export function Loading() {
  return (
    <p className="loading" role="status">
      Загружаем данные…
    </p>
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
    <div className="notice error" role="alert">
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
  return (
    <>
      <ErrorPanel error={error} />
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
    </>
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
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  limit?: number;
  multiline?: boolean;
  hint?: string;
  type?: string;
  required?: boolean;
}) {
  const id = useId();
  const length = Array.from(value).length;
  const invalid = limit !== undefined && length > limit;
  const props = {
    id,
    value,
    required,
    "aria-describedby": `${id}-help`,
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
      <div id={`${id}-help`} className={`caption ${invalid ? "invalid" : ""}`}>
        {hint}
        {limit && (
          <span className="counter">
            {length} / {limit}
            {invalid ? " - сократите текст" : ""}
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
