import { test, expect } from "@playwright/test";
import type { Page } from "@playwright/test";
import meFixture from "../../contracts/examples/me-response.json" with { type: "json" };
import caseFixture from "../../contracts/examples/case-view-target.json" with { type: "json" };

// This probe loads the real Vite client in a browser, without the application
// firing unrelated requests. Only its fetch boundary and clock are controlled.
async function probe(page: Page) {
  await page.route("**/__transport-probe", (route) =>
    route.fulfill({
      contentType: "text/html",
      body: "<html><body></body></html>",
    }),
  );
  await page.goto("/__transport-probe");
}

test("client works without AbortSignal.any/timeout and clears its deadline", async ({
  page,
}) => {
  await probe(page);
  const result = await page.evaluate(async () => {
    Object.defineProperty(AbortSignal, "any", { value: undefined });
    Object.defineProperty(AbortSignal, "timeout", { value: undefined });
    const { get } = await import("/src/api/client.ts");
    const timers = new Set<number>();
    const schedule = window.setTimeout.bind(window);
    const clear = window.clearTimeout.bind(window);
    let deadline = 0;
    window.setTimeout = ((callback: TimerHandler, delay?: number) => {
      const id = schedule(callback, delay);
      if (delay === 20_000) {
        timers.add(id);
        deadline++;
      }
      return id;
    }) as typeof window.setTimeout;
    window.clearTimeout = (id) => {
      timers.delete(id!);
      clear(id);
    };
    let received: RequestInit | undefined;
    window.fetch = async (_url, options) => {
      received = options;
      return new Response(JSON.stringify({ value: "actual transport" }));
    };
    const controller = new AbortController();
    let added = 0;
    let removed = 0;
    const signal = controller.signal;
    const add = signal.addEventListener.bind(signal);
    const remove = signal.removeEventListener.bind(signal);
    signal.addEventListener = (...args) => {
      added++;
      return add(...args);
    };
    signal.removeEventListener = (...args) => {
      removed++;
      return remove(...args);
    };
    const value = await get("/probe", signal);
    return {
      value,
      deadline,
      pendingTimers: timers.size,
      added,
      removed,
      credentials: received?.credentials,
      requestId: new Headers(received?.headers).get("X-Request-Id"),
    };
  });
  expect(result).toMatchObject({
    value: { value: "actual transport" },
    deadline: 1,
    pendingTimers: 0,
    added: 1,
    removed: 1,
    credentials: "same-origin",
  });
  expect(result.requestId).toMatch(/^[a-f0-9-]{36}$/);
});

for (const phase of ["headers", "body"] as const) {
  test(`caller abort during ${phase} stays cancellation and removes listener`, async ({
    page,
  }) => {
    await probe(page);
    const result = await page.evaluate(async (phase) => {
      const { ApiError, get } = await import("/src/api/client.ts");
      const controller = new AbortController();
      let removed = 0;
      const remove = controller.signal.removeEventListener.bind(
        controller.signal,
      );
      controller.signal.removeEventListener = (...args) => {
        removed++;
        return remove(...args);
      };
      let started!: () => void;
      const ready = new Promise<void>((resolve) => {
        started = resolve;
      });
      window.fetch = async (_url, options) => {
        if (phase === "headers") {
          return new Promise<Response>((_resolve, reject) => {
            options!.signal!.addEventListener(
              "abort",
              () => reject(new DOMException("Cancelled", "AbortError")),
              { once: true },
            );
            started();
          });
        }
        return new Response(
          new ReadableStream({
            start(stream) {
              options!.signal!.addEventListener(
                "abort",
                () => stream.error(new DOMException("Cancelled", "AbortError")),
                { once: true },
              );
              window.setTimeout(started, 0);
            },
          }),
        );
      };
      const outcome = get("/probe", controller.signal).catch((error) => ({
        apiError: error instanceof ApiError,
        name: error.name,
      }));
      await ready;
      controller.abort();
      return { outcome: await outcome, removed };
    }, phase);
    expect(result).toEqual({
      outcome: { apiError: false, name: "AbortError" },
      removed: 1,
    });
  });
}

for (const phase of ["headers", "body", "error-body"] as const) {
  test(`command deadline during ${phase} keeps unknown outcome and request ID`, async ({
    page,
  }) => {
    await probe(page);
    const result = await page.evaluate(async (phase) => {
      const { ApiError, command, send } = await import("/src/api/client.ts");
      const schedule = window.setTimeout.bind(window);
      const clear = window.clearTimeout.bind(window);
      const pendingTimers = new Set<number>();
      let expire!: () => void;
      window.setTimeout = ((callback: TimerHandler, delay?: number) => {
        const id = schedule(callback, delay);
        if (delay === 20_000) {
          pendingTimers.add(id);
          expire = callback as () => void;
        }
        return id;
      }) as typeof window.setTimeout;
      window.clearTimeout = (id) => {
        pendingTimers.delete(id!);
        clear(id);
      };
      let started!: () => void;
      const ready = new Promise<void>((resolve) => {
        started = resolve;
      });
      let requestId = "";
      window.fetch = async (url, options) => {
        if (String(url).endsWith("/csrf")) {
          return new Response(
            JSON.stringify({ headerName: "X-XSRF-TOKEN", token: "test-csrf" }),
          );
        }
        requestId = new Headers(options!.headers).get("X-Request-Id")!;
        if (phase === "headers") {
          return new Promise<Response>((_resolve, reject) => {
            options!.signal!.addEventListener(
              "abort",
              () => reject(new DOMException("Expired", "AbortError")),
              { once: true },
            );
            started();
          });
        }
        return new Response(
          new ReadableStream({
            start(stream) {
              options!.signal!.addEventListener(
                "abort",
                () => stream.error(new DOMException("Expired", "AbortError")),
                { once: true },
              );
              // Wait until fetch has returned headers and JSON reading has begun.
              schedule(started, 0);
            },
          }),
          {
            status: phase === "error-body" ? 503 : 200,
            headers: { "X-Request-Id": "server-request-123" },
          },
        );
      };
      const outcome = send(
        command("/cases", { sourceText: "Test only" }),
      ).catch((error) => ({
        apiError: error instanceof ApiError,
        code: error.code,
        unknownOutcome: error.unknownOutcome,
        requestId: error.requestId,
      }));
      await ready;
      expire();
      return {
        outcome: await outcome,
        requestId,
        pendingTimers: pendingTimers.size,
      };
    }, phase);
    expect(result.outcome).toEqual({
      apiError: true,
      code: "NETWORK_ERROR",
      unknownOutcome: true,
      requestId: phase === "headers" ? result.requestId : "server-request-123",
    });
    expect(result.pendingTimers).toBe(0);
  });
}

async function form(page: Page) {
  await page.route("https://st.max.ru/**", (route) =>
    route.fulfill({ body: "" }),
  );
  await page.route("**/api/v1/me", (route) =>
    route.fulfill({ json: meFixture }),
  );
  await page.route("**/api/v1/csrf", (route) =>
    route.fulfill({ json: { headerName: "X-XSRF-TOKEN", token: "test-csrf" } }),
  );
  await page.goto("/#/new");
  await page.getByLabel("Текст обращения").fill("Проверка повторной отправки");
}

test("repeated server failures keep exact retry reachable and unlock after success", async ({
  page,
}) => {
  const requests: { key?: string; body: string | null }[] = [];
  await page.route("**/api/v1/cases", (route) => {
    requests.push({
      key: route.request().headers()["idempotency-key"],
      body: route.request().postData(),
    });
    return requests.length < 3
      ? route.fulfill({ status: 500, json: { code: "INTERNAL_ERROR" } })
      : route.fulfill({
          status: 201,
          json: { ...caseFixture, submissionStatus: "DRAFT" },
        });
  });
  await form(page);
  await page
    .getByRole("button", { name: "Сохранить и перейти к пунктам" })
    .click();
  for (let attempt = 0; attempt < 2; attempt++) {
    await expect(page.getByLabel("Текст обращения")).toBeDisabled();
    const retry = page.getByRole("button", {
      name: "Повторить ту же отправку",
    });
    await expect(retry).toBeEnabled();
    await retry.click();
    await expect.poll(() => requests.length).toBe(attempt + 2);
  }
  await expect(
    page.getByRole("heading", { name: "Черновик сохранён" }),
  ).toBeVisible();
  expect(requests).toHaveLength(3);
  expect(requests[0]).toEqual(requests[1]);
  expect(requests[1]).toEqual(requests[2]);
});

test("rejected command releases form so corrected intent can be sent", async ({
  page,
}) => {
  const requests: { key?: string; body: string | null }[] = [];
  await page.route("**/api/v1/cases", (route) => {
    requests.push({
      key: route.request().headers()["idempotency-key"],
      body: route.request().postData(),
    });
    return requests.length === 1
      ? route.fulfill({ status: 422, json: { code: "INVALID_FIELDS" } })
      : route.fulfill({
          status: 201,
          json: { ...caseFixture, submissionStatus: "DRAFT" },
        });
  });
  await form(page);
  const save = page.getByRole("button", {
    name: "Сохранить и перейти к пунктам",
  });
  await save.click();
  await expect(
    page.getByText("Проверьте обязательные поля", { exact: false }),
  ).toBeVisible();
  await expect(page.getByLabel("Текст обращения")).toBeEnabled();
  await expect(save).toBeEnabled();
  await page.getByLabel("Текст обращения").fill("Исправленное описание");
  await save.click();
  await expect(
    page.getByRole("heading", { name: "Черновик сохранён" }),
  ).toBeVisible();
  expect(requests[0].key).not.toEqual(requests[1].key);
  expect(JSON.parse(requests[1].body!)).toMatchObject({
    sourceText: "Исправленное описание",
  });
});
