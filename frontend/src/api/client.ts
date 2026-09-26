import type { components } from "./schema";

type Schema = components["schemas"];
export type Me = Schema["MeView"];
export type Membership = Schema["Membership"];
export type Case = Schema["AuthorCaseView"];
export type Item = Schema["CaseItemView"];
export type DraftItem = Schema["DraftItemInput"];
export type Report = Schema["WorkReportView"];
export type Suggestions = Schema["ReportSuggestions"];
export type HistoryEvent = Schema["HistoryEvent"];
export type Page<T> = { items: T[]; nextCursor: string | null };
export type Command = Readonly<{
  path: string;
  method: "POST" | "PATCH" | "PUT";
  body: string;
  key: string;
}>;

export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    public requestId = "",
    public unknownOutcome = false,
  ) {
    super(code);
  }
}
let csrf: Promise<Schema["CsrfToken"]> | undefined;
export function resetCsrf() {
  csrf = undefined;
}

// A retry keeps its serialized body and key; it never reads newer form values or versions.
export function command(
  path: string,
  body: unknown,
  method: Command["method"] = "POST",
): Command {
  return Object.freeze({
    path,
    method,
    body: JSON.stringify(body),
    key: crypto.randomUUID(),
  });
}
async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const mutation = options.method && options.method !== "GET";
  const requestId = crypto.randomUUID();
  const timeout = AbortSignal.timeout(20_000);
  const signal = options.signal
    ? AbortSignal.any([timeout, options.signal])
    : timeout;
  let response: Response;
  try {
    response = await fetch(`/api/v1${path}`, {
      ...options,
      signal,
      credentials: "same-origin",
      cache: "no-store",
      headers: {
        Accept: "application/json",
        "X-Request-Id": requestId,
        ...options.headers,
      },
    });
  } catch (error) {
    if (options.signal?.aborted) throw error;
    throw new ApiError(0, "NETWORK_ERROR", requestId, Boolean(mutation));
  }
  const id = response.headers.get("X-Request-Id") || requestId;
  if (!response.ok) {
    const problem = (await response.json().catch(() => ({}))) as Partial<
      Schema["Problem"]
    >;
    if (response.status === 401 && !["/me", "/auth/max"].includes(path))
      window.dispatchEvent(new Event("koren:session-expired"));
    throw new ApiError(
      response.status,
      problem.code || "HTTP_ERROR",
      id,
      Boolean(mutation && response.status >= 500),
    );
  }
  if (response.status === 204) return undefined as T;
  try {
    return (await response.json()) as T;
  } catch {
    throw new ApiError(
      response.status,
      "INVALID_RESPONSE",
      id,
      Boolean(mutation),
    );
  }
}
export const get = <T>(path: string, signal?: AbortSignal) =>
  request<T>(path, { signal });
export async function send<T>(operation: Command): Promise<T> {
  for (let attempt = 0; attempt < 2; attempt++) {
    csrf ??= get<Schema["CsrfToken"]>("/csrf").catch((error) => {
      csrf = undefined;
      throw error;
    });
    const token = await csrf;
    try {
      return await request<T>(operation.path, {
        method: operation.method,
        body: operation.body,
        headers: {
          "Content-Type": "application/json",
          [token.headerName]: token.token,
          "Idempotency-Key": operation.key,
        },
      });
    } catch (error) {
      if (
        error instanceof ApiError &&
        error.code === "CSRF_TOKEN_INVALID" &&
        attempt === 0
      ) {
        resetCsrf();
        continue;
      }
      throw error;
    }
  }
  throw new ApiError(403, "CSRF_TOKEN_INVALID");
}
export function errorMessage(error: unknown): string {
  if (!(error instanceof ApiError))
    return "Не удалось выполнить запрос. Повторите попытку.";
  if (error.unknownOutcome)
    return "Ответ не получен. Сохранение могло произойти. Повторите эту же отправку: второй записи не будет.";
  const messages: Record<string, string> = {
    NETWORK_ERROR:
      "Нет связи с сервером. Проверьте подключение и повторите загрузку.",
    AUTH_REQUIRED:
      "Сессия завершилась. Войдите снова через MAX. Ввод остаётся на этом экране.",
    INVALID_MAX_INIT_DATA:
      "MAX не подтвердил вход. Закройте и снова откройте мини-приложение из MAX.",
    CSRF_TOKEN_INVALID: "Не удалось подтвердить запрос. Повторите отправку.",
    ACTION_FORBIDDEN:
      "Сейчас это действие вам недоступно. Обновите данные о доступе.",
    RESOURCE_NOT_FOUND: "Обращение не найдено или недоступно вашему аккаунту.",
    INVITATION_REJECTED:
      "Код не подходит, уже использован или истёк. Получите новый код у диспетчера.",
    INVALID_INVITATION:
      "Код не подходит, уже использован или истёк. Получите новый код у диспетчера.",
    CASE_VERSION_CONFLICT:
      "Обращение изменилось. Загрузите актуальную версию и сравните её со своим вводом.",
    ITEM_VERSION_CONFLICT:
      "Пункт изменился. Загрузите актуальную версию и проверьте свой выбор.",
    STALE_CONTEXT:
      "Появились новые сведения о работах. Проверьте актуальный пункт перед ответом.",
    CASE_ALREADY_CONFIRMED:
      "Список уже подтверждён и больше не редактируется. Откройте карточку.",
    LINK_ALREADY_EXISTS:
      "Этот отчёт уже связан с выбранным пунктом. Откройте актуальную карточку.",
    IDEMPOTENCY_KEY_REUSED:
      "Эта команда уже использована. Обновите карточку перед новым действием.",
    INVALID_FIELDS:
      "Проверьте обязательные поля, длину текста и дату следующего обновления.",
    INVALID_TRANSITION:
      "Состояние пункта не позволяет это действие. Обновите карточку.",
    PAYLOAD_TOO_LARGE:
      "Текст слишком большой. Сократите его и повторите отправку.",
    INVALID_RESPONSE: "Сервер вернул непонятный ответ. Повторите загрузку.",
  };
  return (
    messages[error.code] ||
    (error.status === 403
      ? "Доступ не подтверждён. Проверьте код или обратитесь к диспетчеру."
      : `Запрос не выполнен (HTTP ${error.status}). Повторите попытку.`)
  );
}
