// K1: load the official MAX Bridge when connecting the Mini App, then send the raw string to Java.
// Presence of initData is NOT authentication. Never read roles or membership from the client.
declare global {
  interface Window { WebApp?: { initData: string } }
}

export function getRawMaxInitData(): string | null {
  return window.WebApp?.initData || null;
}
