// Only APIs documented at https://dev.max.ru/docs/webapps/bridge are used.
declare global {
  interface Window {
    WebApp?: {
      initData: string;
      openMaxLink?(url: string): void;
      BackButton?: {
        show(): void;
        hide(): void;
        onClick(fn: () => void): void;
        offClick(fn: () => void): void;
      };
      enableClosingConfirmation?(): void;
      disableClosingConfirmation?(): void;
    };
  }
}
export function getRawMaxInitData(): string | null {
  return window.WebApp?.initData || null;
}
export const MAX_APP_URL = "https://max.ru/t109_hakaton_max_bot?startapp";
// Only a UX hint. The server verifies the signature and its own clock/TTL.
export function needsMaxRelaunch(): boolean {
  const raw = getRawMaxInitData();
  if (!raw) return true;
  const date = new URLSearchParams(raw).get("auth_date");
  return date !== null && Date.now() / 1000 - Number(date) >= 300;
}
export function openMaxApp(event: { preventDefault(): void }): void {
  try {
    if (window.WebApp?.openMaxLink) {
      window.WebApp.openMaxLink(MAX_APP_URL);
      event.preventDefault();
    }
  } catch {
    // Keep the regular HTTPS link usable when the optional Bridge fails.
  }
}
export function startCaseId(): string | null {
  // Address only. GET /cases/{id} after login remains the access boundary.
  const value =
    new URLSearchParams(getRawMaxInitData() || "").get("start_param") || "";
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(
    value,
  )
    ? value
    : null;
}
export function nativeBack(callback: (() => void) | null): () => void {
  const back = window.WebApp?.BackButton;
  try {
    if (callback) {
      back?.show();
      back?.onClick(callback);
    } else back?.hide();
  } catch {
    /* browser fallback */
  }
  return () => {
    try {
      if (callback) back?.offClick(callback);
      back?.hide();
    } catch {
      /* optional bridge */
    }
  };
}
export function closingConfirmation(enabled: boolean) {
  try {
    if (enabled) window.WebApp?.enableClosingConfirmation?.();
    else window.WebApp?.disableClosingConfirmation?.();
  } catch {
    /* beforeunload and route blocker remain active */
  }
}
