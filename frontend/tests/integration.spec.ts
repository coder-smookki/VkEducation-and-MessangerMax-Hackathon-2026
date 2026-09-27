// Synthetic stand only. The MAX launch is signed in this test with the stand's test token;
// it proves the real Java/session/CSRF/PostgreSQL path, not a live MAX client.
import { test, expect } from "@playwright/test";
import type {
  Browser,
  BrowserContext,
  Page,
  APIRequestContext,
} from "@playwright/test";
import { createHash, createHmac, randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { spawnSync } from "node:child_process";
import AxeBuilder from "@axe-core/playwright";
import type { Case, Me } from "../src/api/client";

const root = resolve(import.meta.dirname, "../..");
const envFile = process.env.KOREN_E2E_ENV
  ? resolve(process.env.KOREN_E2E_ENV)
  : "";
const env = envFile
  ? Object.fromEntries(
      readFileSync(envFile, "utf8")
        .split("\n")
        .filter((s) => s.includes("="))
        .map((s) => [s.slice(0, s.indexOf("=")), s.slice(s.indexOf("=") + 1)]),
    )
  : {};
const composeProject = process.env.KOREN_E2E_PROJECT || "koren-kirill";
const baseURL = process.env.KOREN_E2E_URL || "http://127.0.0.1:5173";
function sql(text: string) {
  const result = spawnSync(
    "docker",
    [
      "compose",
      "--env-file",
      envFile,
      "-p",
      composeProject,
      "-f",
      "compose.release.yaml",
      "exec",
      "-T",
      "db",
      "psql",
      "-U",
      "koren",
      "-d",
      "koren",
      "-v",
      "ON_ERROR_STOP=1",
      "-Atq",
    ],
    { cwd: root, input: text, encoding: "utf8" },
  );
  if (result.status !== 0)
    throw new Error(`Synthetic stand SQL failed: ${result.stderr}`);
  return result.stdout.trim();
}
function lifecycle(action: "stop" | "start") {
  if (!composeProject.startsWith("koren-ui-check-"))
    throw new Error(
      "Lifecycle checks require a disposable check_miniapp.py project",
    );
  const result = spawnSync(
    "docker",
    [
      "compose",
      "--env-file",
      envFile,
      "-p",
      composeProject,
      "-f",
      "compose.release.yaml",
      action,
      "backend",
    ],
    { cwd: root, encoding: "utf8" },
  );
  if (result.status !== 0)
    throw new Error(`Synthetic lifecycle failed: ${result.stderr}`);
}
function signedLaunch(userId: number, start?: string) {
  const fields = {
    auth_date: String(Math.floor(Date.now() / 1000)),
    query_id: randomUUID(),
    user: JSON.stringify({
      id: userId,
      first_name: "Синтетический",
      last_name: "тест",
      language_code: "ru",
    }),
    ...(start ? { start_param: start } : {}),
  };
  const secret = createHmac("sha256", "WebAppData")
    .update(env.MAX_BOT_TOKEN)
    .digest();
  const hash = createHmac("sha256", secret)
    .update(
      Object.entries(fields)
        .sort(([a], [b]) => a.localeCompare(b))
        .map(([key, value]) => `${key}=${value}`)
        .join("\n"),
    )
    .digest("hex");
  return new URLSearchParams({ ...fields, hash }).toString();
}
async function mutate(
  request: APIRequestContext,
  path: string,
  body: unknown,
  key = randomUUID(),
) {
  const token = await (await request.get(`${baseURL}/api/v1/csrf`)).json();
  return request.post(`${baseURL}/api/v1${path}`, {
    data: body,
    headers: { [token.headerName]: token.token, "Idempotency-Key": key },
  });
}
async function actor(
  browser: Browser,
  house: string,
  role: "RESIDENT" | "DISPATCHER" | null,
  offset: number,
) {
  const context = await browser.newContext({
    baseURL,
    viewport: { width: 390, height: 844 },
  });
  const initData = signedLaunch(Date.now() * 100 + offset);
  const login = await mutate(context.request, "/auth/max", { initData });
  expect(login.status()).toBe(200);
  const me = (await login.json()) as Me;
  expect(me.userId).toMatch(/^[0-9a-f-]{36}$/);
  if (role)
    sql(
      `INSERT INTO membership(user_id,house_id,role) VALUES ('${me.userId}','${house}','${role}');`,
    );
  await context.clearCookies();
  await context.addInitScript((raw) => {
    window.WebApp = { initData: raw };
  }, initData);
  await context.route("https://st.max.ru/**", (route) =>
    route.fulfill({ contentType: "application/javascript", body: "" }),
  );
  const page = await context.newPage();
  return { context, page, me };
}
async function snapshot(context: BrowserContext, id: string): Promise<Case> {
  const response = await context.request.get(`${baseURL}/api/v1/cases/${id}`);
  expect(response.status()).toBe(200);
  return response.json();
}
async function submitReview(page: Page) {
  await page.getByRole("button", { name: "Проверить перед отправкой" }).click();
  await page
    .getByRole("button", { name: "Подтвердить отправку", exact: true })
    .click();
}

test("real API: author → dispatcher → partial result → archive, with access and idempotency", async ({
  browser,
}) => {
  test.setTimeout(180_000);
  const house = randomUUID();
  sql(
    `INSERT INTO house(id,address) VALUES ('${house}','Синтетический дом frontend E2E');`,
  );
  const author = await actor(browser, house, "RESIDENT", 1);
  const dispatcher = await actor(browser, house, "DISPATCHER", 2);
  const stranger = await actor(browser, randomHouse(), "RESIDENT", 3);
  const errors: string[] = [];
  author.page.on("pageerror", (error) => errors.push(error.message));
  dispatcher.page.on("pageerror", (error) => errors.push(error.message));
  try {
    await author.page.goto("/");
    await author.page.getByRole("link", { name: "Новое обращение" }).click();
    await author.page
      .getByLabel("Текст обращения")
      .fill("На третьем этаже не горит свет, и входная дверь не закрывается.");
    const createdResponse = author.page.waitForResponse(
      (r) =>
        new URL(r.url()).pathname === "/api/v1/cases" &&
        r.request().method() === "POST",
    );
    await author.page
      .getByRole("button", { name: "Сохранить и перейти к пунктам" })
      .click();
    const created = await createdResponse;
    expect(created.status()).toBe(201);
    const initial = (await created.json()) as Case;
    await author.page
      .getByRole("button", { name: "Перейти к пунктам" })
      .click();
    await author.page.getByLabel("Тема проблемы").selectOption("LIGHTING");
    await author.page
      .getByLabel("Где это произошло?")
      .fill("Подъезд 1, этаж 3");
    await author.page
      .getByLabel("Что не работает или мешает?")
      .fill("Не горит свет");
    await author.page
      .getByRole("button", { name: "Добавить ещё проблему" })
      .click();
    await author.page
      .getByLabel("Тема проблемы")
      .nth(1)
      .selectOption("ENTRANCE_DOOR");
    await author.page
      .getByLabel("Где это произошло?")
      .nth(1)
      .fill("Вход в подъезд 1");
    await author.page
      .getByLabel("Что не работает или мешает?")
      .nth(1)
      .fill("Дверь не закрывается");
    await author.page
      .getByRole("button", { name: "Сохранить список", exact: true })
      .click();
    await author.page
      .getByRole("link", { name: "Проверить и подтвердить" })
      .click();
    await author.page
      .getByRole("button", { name: "Подтвердить список", exact: true })
      .click();
    await expect(
      author.page.getByRole("heading", { name: "Список подтверждён" }),
    ).toBeVisible();
    await author.page
      .getByRole("link", { name: "Открыть обращение", exact: true })
      .click();
    const confirmed = await snapshot(author.context, initial.caseId);
    expect(confirmed.submissionStatus).toBe("CONFIRMED");
    expect(confirmed.items).toHaveLength(2);
    const [light, door] = confirmed.items;
    await dispatcher.page.goto(`/#/cases/${initial.caseId}`);
    await dispatcher.page
      .getByRole("button", { name: "Принять обращение", exact: true })
      .click();
    await expect(
      dispatcher.page.getByText("Принято диспетчером", { exact: false }),
    ).toBeVisible();
    await dispatcher.page
      .locator(".item-card")
      .filter({ hasText: "Дверь не закрывается" })
      .getByRole("link", { name: "Указать следующий шаг" })
      .click();
    await dispatcher.page
      .getByLabel("Что будет сделано дальше?")
      .fill("Проверим доводчик двери");
    const future = new Date(Date.now() + 86400000);
    const local = new Date(
      future.getTime() - future.getTimezoneOffset() * 60000,
    )
      .toISOString()
      .slice(0, 16);
    await dispatcher.page
      .getByLabel("Когда будет следующее обновление?")
      .fill(local);
    await submitReview(dispatcher.page);
    await expect(
      dispatcher.page.getByRole("heading", { name: "Следующий шаг сохранён" }),
    ).toBeVisible();
    await dispatcher.page
      .getByRole("link", { name: "Вернуться к обращению" })
      .click();
    await dispatcher.page
      .getByRole("link", { name: "Добавить отчёт о работах" })
      .click();
    await dispatcher.page
      .getByLabel("Текст отчёта")
      .fill("Лампу на третьем этаже заменили.");
    await dispatcher.page.getByRole("checkbox").nth(0).check();
    await dispatcher.page.getByRole("checkbox").nth(1).check();
    await dispatcher.page
      .getByRole("button", { name: "Сохранить текст отчёта" })
      .click();
    await expect(
      dispatcher.page.getByRole("heading", { name: "Текст отчёта сохранён" }),
    ).toBeVisible();
    const afterReport = await snapshot(author.context, initial.caseId);
    expect(
      afterReport.items.map((i) => [i.workRound, i.workStatus, i.observation]),
    ).toEqual([
      [0, "NOT_ASSIGNED", "NOT_RECEIVED"],
      [0, "NOT_ASSIGNED", "NOT_RECEIVED"],
    ]);
    await dispatcher.page
      .getByRole("link", { name: "Проверить связи с пунктами" })
      .click();
    const reportUrl = dispatcher.page.url();
    await expect(
      dispatcher.page.getByRole("checkbox").first(),
    ).not.toBeChecked();
    await dispatcher.page
      .getByRole("checkbox", { name: /Не горит свет/ })
      .check();
    await dispatcher.page
      .getByRole("button", { name: "Проверить выбранные связи" })
      .click();
    await dispatcher.page
      .getByRole("button", { name: "Подтвердить выбранные связи" })
      .click();
    await expect(
      dispatcher.page.getByText("Связь сохранена.", { exact: false }),
    ).toBeVisible();
    const afterLink = await snapshot(author.context, initial.caseId);
    expect(afterLink.items[0].workRound).toBe(1);
    expect(afterLink.items[0].observation).toBe("NOT_RECEIVED");
    expect(afterLink.items[1].workRound).toBe(0);
    await author.page.reload();
    await author.page
      .locator(".item-card")
      .filter({ hasText: "Не горит свет" })
      .getByRole("link", { name: "Сообщить своё наблюдение" })
      .click();
    await author.page
      .getByRole("radio", { name: "Восстановилось", exact: true })
      .check();
    await submitReview(author.page);
    await expect(
      author.page.getByRole("heading", { name: "Наблюдение сохранено" }),
    ).toBeVisible();
    await author.page
      .getByRole("link", { name: "Вернуться к обращению" })
      .click();
    const partial = await snapshot(author.context, initial.caseId);
    expect(partial.progress).toEqual({
      total: 2,
      confirmed: 1,
      withdrawn: 0,
      remaining: 1,
      allConfirmed: false,
    });
    expect(partial.items[1].nextAction?.text).toBe("Проверим доводчик двери");
    await expect(author.page.getByLabel("Результат по пунктам")).toContainText(
      "1 из 2",
    );
    expect(
      (
        await new AxeBuilder({ page: author.page })
          .withTags(["wcag2a", "wcag2aa", "wcag21aa"])
          .analyze()
      ).violations,
    ).toEqual([]);
    await author.page.screenshot({
      path: "../tmp/kirill/partial-real-light.png",
      fullPage: true,
    });
    await author.page.getByText("Настройки вида", { exact: true }).click();
    await author.page.getByLabel("Тема", { exact: true }).selectOption("dark");
    await author.page.getByText("Настройки вида", { exact: true }).click();
    await author.page.screenshot({
      path: "../tmp/kirill/partial-real-dark.png",
      fullPage: true,
    });
    // A dispatcher cannot observe on behalf of the author, even through raw HTTP.
    const forbidden = await mutate(
      dispatcher.context.request,
      `/items/${light.itemId}/observations`,
      {
        expectedCaseVersion: partial.caseVersion,
        expectedItemVersion: partial.items[0].itemVersion,
        contentVersion: 1,
        workRound: 1,
        value: "RESTORED",
        source: "AFTER_REPORT",
      },
    );
    expect(forbidden.status()).toBe(403);
    await stranger.page.goto("/");
    await expect(
      stranger.page.getByRole("heading", {
        name: "Мои обращения",
        exact: true,
      }),
    ).toBeVisible();
    expect(
      (
        await stranger.context.request.get(
          `${baseURL}/api/v1/cases/${initial.caseId}`,
        )
      ).status(),
    ).toBe(404);
    // Unknown response after COMMIT: the browser retries the original key/body on the real server.
    const writes: string[] = [];
    await dispatcher.page.route(
      `**/api/v1/items/${door.itemId}/administrative-decisions`,
      async (route) => {
        writes.push(route.request().headers()["idempotency-key"]!);
        if (writes.length === 1) {
          const response = await route.fetch();
          expect(response.status()).toBe(200);
          await route.abort("connectionfailed");
        } else await route.continue();
      },
    );
    await dispatcher.page
      .getByRole("link", { name: "← К обращению", exact: true })
      .click();
    await dispatcher.page
      .locator(".item-card")
      .filter({ hasText: "Дверь не закрывается" })
      .getByRole("link", { name: "В архив", exact: true })
      .click();
    await dispatcher.page
      .getByLabel("Причина решения")
      .fill("Передано в план обследования. Ремонт не подтверждён.");
    await submitReview(dispatcher.page);
    await dispatcher.page
      .getByRole("button", { name: "Повторить ту же отправку" })
      .click();
    await expect(
      dispatcher.page.getByRole("heading", { name: "Решение сохранено" }),
    ).toBeVisible();
    expect(writes).toHaveLength(2);
    expect(writes[0]).toBe(writes[1]);
    const archived = await snapshot(author.context, initial.caseId);
    expect(archived.progress).toEqual(partial.progress);
    expect(archived.items[1].administrativeStatus).toBe("ARCHIVED");
    const history = await (
      await author.context.request.get(
        `${baseURL}/api/v1/cases/${initial.caseId}/history`,
      )
    ).json();
    expect(
      history.items.filter(
        (e: { eventType: string }) =>
          e.eventType === "ITEM_ADMINISTRATIVE_DECISION",
      ),
    ).toHaveLength(1);
    await dispatcher.page
      .getByRole("link", { name: "Вернуться к обращению" })
      .click();
    await dispatcher.page.goto(reportUrl);
    await expect(
      dispatcher.page.getByRole("heading", { name: "Подтверждённые связи" }),
    ).toBeVisible();
    expect(errors).toEqual([]);
  } finally {
    await author.context.close();
    await dispatcher.context.close();
    await stranger.context.close();
  }
});
function randomHouse() {
  const id = randomUUID();
  sql(
    `INSERT INTO house(id,address) VALUES ('${id}','Другой синтетический дом E2E');`,
  );
  return id;
}

test("real API: one-time invitation, negative observations and withdrawal retain the denominator", async ({
  browser,
}) => {
  const house = randomHouse();
  const resident = await actor(browser, house, null, 4);
  try {
    const invitation = randomUUID();
    const hash = createHash("sha256").update(invitation).digest("hex");
    sql(
      `INSERT INTO house_invitation(id,house_id,code_hash,expires_at) VALUES ('${randomUUID()}','${house}','${hash}',now()+interval '1 hour');`,
    );
    await resident.page.goto("/");
    await resident.page
      .getByRole("link", { name: "Ввести код приглашения" })
      .click();
    await resident.page.getByLabel("Код приглашения").fill(invitation);
    await resident.page.getByRole("button", { name: "Подключить дом" }).click();
    await expect(
      resident.page.getByText("Доступ обновлён.", { exact: false }),
    ).toBeVisible();
    expect(
      (
        await mutate(resident.context.request, "/memberships/claim", {
          invitationCode: invitation,
        })
      ).status(),
    ).toBe(403);
    const created = (await (
      await mutate(resident.context.request, "/cases", {
        houseId: house,
        sourceText: "Свет и дверь",
      })
    ).json()) as Case;
    const token = await (
      await resident.context.request.get(`${baseURL}/api/v1/csrf`)
    ).json();
    const edited = (await (
      await resident.context.request.patch(
        `${baseURL}/api/v1/cases/${created.caseId}/items`,
        {
          data: {
            expectedCaseVersion: created.caseVersion,
            items: [
              {
                category: "LIGHTING",
                location: "Этаж 3",
                symptom: "Свет не горит",
              },
              {
                category: "ENTRANCE_DOOR",
                location: "Вход",
                symptom: "Дверь не закрывается",
              },
            ],
          },
          headers: {
            [token.headerName]: token.token,
            "Idempotency-Key": randomUUID(),
          },
        },
      )
    ).json()) as Case;
    await mutate(resident.context.request, `/cases/${created.caseId}/confirm`, {
      expectedCaseVersion: edited.caseVersion,
      sourceRevision: edited.sourceRevision,
    });
    for (const [index, label, value] of [
      [0, "Проблема осталась", "PERSISTS"],
      [1, "Не могу проверить", "CANNOT_CHECK"],
    ] as const) {
      await resident.page.goto(
        `/#/cases/${created.caseId}/items/${edited.items[index].itemId}/observe`,
      );
      await resident.page
        .getByRole("radio", { name: label, exact: true })
        .check();
      await submitReview(resident.page);
      await expect(
        resident.page.getByRole("heading", { name: "Наблюдение сохранено" }),
      ).toBeVisible();
      const data = await snapshot(resident.context, created.caseId);
      expect(data.items[index].observation).toBe(value);
      expect(data.progress.confirmed).toBe(0);
      expect(data.progress.remaining).toBe(2);
      await resident.page
        .getByRole("link", { name: "Вернуться к обращению" })
        .click();
    }
    await resident.page
      .locator(".item-card")
      .filter({ hasText: "Дверь не закрывается" })
      .getByRole("link", { name: "Отозвать пункт", exact: true })
      .click();
    await resident.page
      .getByLabel("Причина решения")
      .fill("Ошибся местом, обращение отзываю.");
    await submitReview(resident.page);
    await expect(
      resident.page.getByRole("heading", { name: "Решение сохранено" }),
    ).toBeVisible();
    const withdrawn = await snapshot(resident.context, created.caseId);
    expect(withdrawn.progress).toEqual({
      total: 2,
      confirmed: 0,
      withdrawn: 1,
      remaining: 1,
      allConfirmed: false,
    });
    expect(withdrawn.items[1].administrativeStatus).toBe("WITHDRAWN");
  } finally {
    await resident.context.close();
  }
});

test("release proxy serves API failures as JSON and does not expose demo or private services", async ({
  request,
}) => {
  const health = await request.get("/actuator/health");
  expect(health.status()).toBe(200);
  const healthBody = await health.json();
  expect(healthBody.status).toBe("UP");
  expect(healthBody).not.toHaveProperty("details");
  expect(healthBody).not.toHaveProperty("components");
  expect(health.headers()["x-request-id"]).toBeTruthy();
  expect(health.headers()["cache-control"]).toContain("no-store");
  const info = await request.get("/actuator/info");
  expect(info.status()).toBe(200);
  expect((await info.json()).app.sha).toBe(env.RELEASE_SHA);
  for (const path of [
    "/actuator",
    "/actuator/env",
    "/actuator/metrics",
    "/actuator/configprops",
  ]) {
    expect((await request.get(path)).status()).toBe(404);
  }
  expect((await request.post("/actuator/health")).status()).toBe(403);
  expect((await request.post("/max/webhook", { data: {} })).status()).toBe(401);
  const anonymous = await request.get("/api/v1/cases");
  expect(anonymous.status()).toBe(401);
  expect(anonymous.headers()["content-type"]).toContain(
    "application/problem+json",
  );
  const demo = await request.get("/api/v1/demo/case");
  expect(demo.status()).toBe(401);
  const oversized = await request.post("/api/v1/cases", {
    data: "x".repeat(300_000),
  });
  expect(oversized.status()).toBe(413);
  expect(oversized.headers()["content-type"]).toContain(
    "application/problem+json",
  );
  const staticPage = await request.get("/");
  expect(staticPage.status()).toBe(200);
  expect(staticPage.headers()["cache-control"]).toContain("no-store");
  const page = await staticPage.text();
  expect(page).not.toContain(env.MAX_BOT_TOKEN);
  expect((await request.get("/any-client-route")).status()).toBe(200);
  // Only the runner's uniquely named, disposable backend is stopped.
  lifecycle("stop");
  try {
    const unavailable = await request.get("/api/v1/me");
    expect(unavailable.status()).toBe(503);
    expect(unavailable.headers()["content-type"]).toContain(
      "application/problem+json",
    );
    expect((await unavailable.json()).code).toBe("SERVICE_UNAVAILABLE");
    expect((await request.get("/")).status()).toBe(200);
  } finally {
    lifecycle("start");
  }
  await expect
    .poll(async () => (await request.get("/api/v1/csrf")).status(), {
      timeout: 30_000,
    })
    .toBe(200);
});
