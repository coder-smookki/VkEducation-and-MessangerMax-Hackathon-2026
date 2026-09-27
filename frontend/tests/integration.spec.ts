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
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { isAbsolute, resolve } from "node:path";
import { spawnSync } from "node:child_process";
import AxeBuilder from "@axe-core/playwright";
import type { Case, Me, Report, Suggestions } from "../src/api/client";
import type { components } from "../src/api/schema";

const root = resolve(import.meta.dirname, "../..");
const composeProject = process.env.KOREN_E2E_PROJECT || "";
const baseURL = process.env.KOREN_E2E_URL || "";
// Fail at module load, before reading a token, authenticating, or issuing SQL.
// This suite creates memberships and changes data and must never target a live stand.
const target = baseURL ? new URL(baseURL) : null;
if (
  !composeProject.startsWith("koren-ui-check-") ||
  !target ||
  target.protocol !== "http:" ||
  !["127.0.0.1", "localhost", "[::1]"].includes(target.hostname) ||
  target.username ||
  target.password
)
  throw new Error(
    "Integration requires a disposable koren-ui-check-* project and its HTTP loopback URL; run scripts/check_miniapp.py",
  );
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
const analysisEnabled = process.env.KOREN_E2E_ANALYSIS === "true";
if (analysisEnabled !== (env.ANALYSIS_ENABLED === "true"))
  throw new Error(
    "The integration analysis flag must match the disposable stand env",
  );
const captureDirectory = process.env.KOREN_QA_CAPTURE;
const capturedScreens: {
  screen: string;
  route: string;
  viewport: { width: number; height: number } | null;
  theme: string;
  fontSize: string;
  file: string;
}[] = [];
if (captureDirectory && !isAbsolute(captureDirectory))
  throw new Error("KOREN_QA_CAPTURE must be an absolute directory path");

// These screens use the disposable Java/PostgreSQL API. Only MAX launch data
// is synthetic; no application API response is replaced for this audit.
async function auditScreen(page: Page, screen: string) {
  if (!composeProject.startsWith("koren-ui-check-"))
    throw new Error(
      "Screen audits require the disposable check_miniapp.py stand",
    );
  await expect(page.locator("main")).toBeVisible();
  await page.evaluate(async () => {
    await document.fonts.ready;
    window.scrollTo(0, 0);
  });
  const layout = await page.evaluate(() => {
    const root = document.documentElement;
    const controls = Array.from(
      document.querySelectorAll<HTMLElement>(
        "main button, main .button, main input:not([type=checkbox]):not([type=radio]), main textarea, main select",
      ),
    ).filter((element) => element.getClientRects().length > 0);
    return {
      overflow: root.scrollWidth - root.clientWidth,
      clippedControls: controls.flatMap((element) => {
        const rect = element.getBoundingClientRect();
        if (rect.width === 0 || rect.height === 0) return [];
        const label =
          element.getAttribute("aria-label") ||
          element.getAttribute("name") ||
          element.tagName.toLowerCase();
        // Filter chips and wide dispatcher tables intentionally scroll in
        // their own bounded region; an offscreen chip is still reachable.
        for (
          let ancestor = element.parentElement;
          ancestor;
          ancestor = ancestor.parentElement
        ) {
          if (
            ["auto", "scroll"].includes(getComputedStyle(ancestor).overflowX) &&
            ancestor.scrollWidth > ancestor.clientWidth
          )
            return [];
        }
        return rect.left < -1 || rect.right > root.clientWidth + 1
          ? [label]
          : [];
      }),
      theme: root.dataset.theme || "system",
      fontSize: getComputedStyle(root).fontSize,
    };
  });
  if (captureDirectory) {
    mkdirSync(captureDirectory, { recursive: true });
    const file = `${String(capturedScreens.length + 1).padStart(2, "0")}-${screen}.png`;
    await page.screenshot({
      path: resolve(captureDirectory, file),
      fullPage: true,
    });
    capturedScreens.push({
      screen,
      route: new URL(page.url()).hash
        .split("?")[0]
        .replace(/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}/gi, ":id"),
      viewport: page.viewportSize(),
      theme: layout.theme,
      fontSize: layout.fontSize,
      file,
    });
    writeFileSync(
      resolve(captureDirectory, "manifest.json"),
      JSON.stringify(
        {
          source: "disposable real Java/PostgreSQL; synthetic users and cases",
          screens: capturedScreens,
        },
        null,
        2,
      ),
    );
  }
  expect(layout.overflow, `${screen}: horizontal overflow`).toBeLessThanOrEqual(
    1,
  );
  expect(
    layout.clippedControls,
    `${screen}: controls outside viewport`,
  ).toEqual([]);
  const accessibility = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21aa"])
    .analyze();
  expect(accessibility.violations, `${screen}: accessibility`).toEqual([]);
  const targets = page.locator(
    "main :is(button:not(:disabled), a.button, input:not([type=checkbox]):not([type=radio]):not(:disabled), textarea:not(:disabled), select:not(:disabled)):visible",
  );
  const count = await targets.count();
  for (const index of new Set([0, Math.floor(count / 2), count - 1])) {
    if (index < 0 || index >= count) continue;
    const target = targets.nth(index);
    await target.scrollIntoViewIfNeeded();
    await expect
      .poll(
        () =>
          target.evaluate((element) => {
            const rect = element.getBoundingClientRect();
            const hit = document.elementFromPoint(
              rect.x + rect.width / 2,
              rect.y + rect.height / 2,
            );
            return !!hit && (hit === element || element.contains(hit));
          }),
        { message: `${screen}: visible control receives a pointer` },
      )
      .toBe(true);
  }
  await page.evaluate(() => window.scrollTo(0, 0));
}

async function appearance(page: Page, theme: "light" | "dark", large = false) {
  await page
    .getByRole("button", { name: "Настройки вида", exact: true })
    .click();
  await page
    .getByRole("radio", {
      name: theme === "light" ? "Светлая" : "Тёмная",
      exact: true,
    })
    .check();
  await page
    .getByRole("switch", { name: "Крупный текст", exact: true })
    .setChecked(large);
  await page.getByRole("button", { name: "Готово", exact: true }).click();
}
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
function signedLaunch(
  userId: number,
  start?: string,
  profile?: { username: string; photo_url: string },
) {
  const fields = {
    auth_date: String(Math.floor(Date.now() / 1000)),
    query_id: randomUUID(),
    user: JSON.stringify({
      id: userId,
      first_name: "Синтетический",
      last_name: "тест",
      language_code: "ru",
      ...profile,
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
test("verified MAX profile survives reload through the release proxy and its image policy", async ({
  page,
}) => {
  const avatarUrl = "https://i.oneme.ru/synthetic-profile-test.svg";
  const initData = signedLaunch(Date.now() * 100 + 99, undefined, {
    username: "synthetic_profile",
    photo_url: avatarUrl,
  });
  await page.addInitScript((raw) => {
    window.WebApp = { initData: raw };
  }, initData);
  await page.route("https://st.max.ru/**", (route) =>
    route.fulfill({ contentType: "application/javascript", body: "" }),
  );
  await page.route(avatarUrl, (route) =>
    route.fulfill({
      contentType: "image/svg+xml",
      body: '<svg xmlns="http://www.w3.org/2000/svg" width="64" height="64"><circle cx="32" cy="32" r="32" fill="#126bff"/></svg>',
    }),
  );
  await page.goto("/#/profile");
  const name = page.getByRole("heading", {
    name: "Синтетический тест",
    exact: true,
  });
  const photo = page.getByRole("img", {
    name: "Фото профиля Синтетический тест",
  });
  await expect(name).toBeVisible();
  await expect(
    page.getByText("@synthetic_profile", { exact: true }),
  ).toBeVisible();
  await expect
    .poll(() =>
      photo.evaluate(
        (image: HTMLImageElement) => image.complete && image.naturalWidth > 0,
      ),
    )
    .toBe(true);
  await page.reload();
  await expect(name).toBeVisible();
  await expect(photo).toBeVisible();
  const me = await page.request.get(`${baseURL}/api/v1/me`);
  expect(me.status()).toBe(200);
  expect(await me.json()).toMatchObject({
    displayName: "Синтетический тест",
    username: "synthetic_profile",
    avatarUrl,
  });
});
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
async function submitReview(page: Page, screen?: string) {
  await page.getByRole("button", { name: "Проверить перед отправкой" }).click();
  if (screen) await auditScreen(page, screen);
  await page
    .getByRole("button", { name: "Подтвердить отправку", exact: true })
    .click();
}

test("real API: author → dispatcher → partial result → archive, with access and idempotency", async ({
  browser,
}) => {
  test.setTimeout(360_000);
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
    // MAX supplies a launch fragment before the hash router starts; Bridge holds signed initData.
    await author.page.setViewportSize({ width: 320, height: 844 });
    await author.page.goto("/#WebAppData=synthetic-launch&WebAppPlatform=web");
    await expect(
      author.page.getByRole("region", { name: "Тестовый стенд" }),
    ).toContainText("Учебный дом, данные тестовые");
    await expect(
      author.page.getByRole("heading", {
        name: "Пока нет обращений",
        exact: true,
      }),
    ).toBeVisible();
    await appearance(author.page, "light");
    await auditScreen(author.page, "resident-empty-mobile");
    await author.page.getByRole("link", { name: "Новое обращение" }).click();
    await expect(author.page.getByLabel("Текст обращения")).toBeVisible();
    await auditScreen(author.page, "resident-new-mobile");
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
    expect(initial.displayNumber).toMatch(/^ДЕМО-\d+$/);
    await expect(
      author.page.getByRole("heading", { name: "Черновик сохранён" }),
    ).toBeVisible();
    await auditScreen(author.page, "resident-draft-saved-mobile");
    await author.page.goto(`/#/cases/${initial.caseId}/source`);
    await expect(
      author.page.getByRole("heading", { name: "Уточните исходный текст" }),
    ).toBeVisible();
    await auditScreen(author.page, "resident-source-mobile");
    await author.page
      .getByLabel("Текст обращения")
      .fill(
        "В подъезде на третьем этаже не горит свет, и входная дверь не закрывается.",
      );
    await author.page
      .getByRole("button", { name: "Сохранить текст", exact: true })
      .click();
    await expect(
      author.page.getByText("Текст сохранён.", { exact: false }),
    ).toBeVisible();
    await author.page
      .getByRole("link", { name: "К пунктам", exact: true })
      .click();
    await expect(
      author.page.getByRole("heading", {
        name: "Проверьте отдельные проблемы",
      }),
    ).toBeVisible();
    await auditScreen(author.page, "resident-items-empty-mobile");
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
    await auditScreen(author.page, "resident-items-filled-mobile");
    await author.page
      .getByRole("button", { name: "Сохранить список", exact: true })
      .click();
    await author.page
      .getByRole("link", { name: "Проверить и подтвердить" })
      .click();
    await expect(
      author.page.getByRole("heading", { name: "Всё верно?" }),
    ).toBeVisible();
    await auditScreen(author.page, "resident-review-mobile");
    await author.page
      .getByRole("button", { name: "Подтвердить список", exact: true })
      .click();
    await expect(
      author.page.getByRole("heading", { name: "Список подтверждён" }),
    ).toBeVisible();
    await auditScreen(author.page, "resident-confirmed-mobile");
    await author.page
      .getByRole("link", { name: "Открыть обращение", exact: true })
      .click();
    const confirmed = await snapshot(author.context, initial.caseId);
    expect(confirmed.submissionStatus).toBe("CONFIRMED");
    expect(confirmed.items).toHaveLength(2);
    const [light, door] = confirmed.items;
    await expect(
      author.page.getByRole("heading", {
        name: `Обращение №${confirmed.displayNumber}`,
        exact: true,
      }),
    ).toBeVisible();
    await auditScreen(author.page, "resident-card-mobile");
    await author.page.goto("/");
    await expect(
      author.page.getByRole("link", { name: "Не горит свет", exact: true }),
    ).toBeVisible();
    await auditScreen(author.page, "resident-list-mobile");
    await author.page.goto(`/#/cases/${initial.caseId}`);
    await dispatcher.page.setViewportSize({ width: 1440, height: 1000 });
    await dispatcher.page.goto("/");
    await expect(
      dispatcher.page.getByRole("heading", {
        name: "Очередь обращений",
        exact: true,
      }),
    ).toBeVisible();
    await appearance(dispatcher.page, "light");
    await auditScreen(dispatcher.page, "dispatcher-queue-desktop");
    await dispatcher.page.goto(`/#/cases/${initial.caseId}`);
    await expect(
      dispatcher.page.getByRole("button", {
        name: "Принять обращение",
        exact: true,
      }),
    ).toBeVisible();
    await auditScreen(dispatcher.page, "dispatcher-accept-desktop");
    await dispatcher.page
      .getByRole("button", { name: "Принять обращение", exact: true })
      .click();
    await expect(
      dispatcher.page.getByText("Принято диспетчером", { exact: true }),
    ).toBeVisible();
    await auditScreen(dispatcher.page, "dispatcher-accepted-desktop");
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
    await auditScreen(dispatcher.page, "dispatcher-next-action-desktop");
    await submitReview(
      dispatcher.page,
      "dispatcher-next-action-review-desktop",
    );
    await expect(
      dispatcher.page.getByRole("heading", { name: "Следующий шаг сохранён" }),
    ).toBeVisible();
    await auditScreen(dispatcher.page, "dispatcher-next-action-saved-desktop");
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
    await auditScreen(dispatcher.page, "dispatcher-report-new-desktop");
    await dispatcher.page
      .getByRole("button", { name: "Сохранить текст отчёта" })
      .click();
    await expect(
      dispatcher.page.getByRole("heading", { name: "Текст отчёта сохранён" }),
    ).toBeVisible();
    await auditScreen(dispatcher.page, "dispatcher-report-saved-desktop");
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
    if (!analysisEnabled) {
      await expect(
        dispatcher.page.getByText("Автоматическая подсказка недоступна.", {
          exact: false,
        }),
      ).toBeVisible();
      const reportId = new URL(reportUrl).hash.split("/").at(-1);
      const manual = await dispatcher.context.request.get(
        `${baseURL}/api/v1/reports/${reportId}/suggestions`,
      );
      expect(manual.status()).toBe(200);
      expect(await manual.json()).toMatchObject({
        status: "MANUAL_REQUIRED",
        analysisId: null,
        result: null,
      });
    }
    await expect(
      dispatcher.page.getByRole("checkbox").first(),
    ).not.toBeChecked();
    await auditScreen(dispatcher.page, "dispatcher-report-links-desktop");
    await dispatcher.page
      .getByRole("checkbox", { name: /Не горит свет/ })
      .check();
    await dispatcher.page
      .getByRole("button", { name: "Проверить выбранные связи" })
      .click();
    await auditScreen(dispatcher.page, "dispatcher-report-review-desktop");
    await dispatcher.page
      .getByRole("button", { name: "Подтвердить выбранные связи" })
      .click();
    await expect(
      dispatcher.page.getByText("Связь сохранена.", { exact: false }),
    ).toBeVisible();
    await auditScreen(dispatcher.page, "dispatcher-report-linked-desktop");
    const afterLink = await snapshot(author.context, initial.caseId);
    expect(afterLink.items[0].workRound).toBe(1);
    expect(afterLink.items[0].observation).toBe("NOT_RECEIVED");
    expect(afterLink.items[1].workRound).toBe(0);
    await author.page.setViewportSize({ width: 390, height: 844 });
    await author.page.reload();
    await appearance(author.page, "dark", true);
    await author.page
      .locator(".item-card")
      .filter({ hasText: "Не горит свет" })
      .getByRole("link", { name: "Сообщить своё наблюдение" })
      .click();
    await author.page
      .getByRole("radio", { name: "Восстановилось", exact: true })
      .check();
    await auditScreen(author.page, "resident-observe-dark-large");
    await submitReview(author.page, "resident-observation-review-dark-large");
    await expect(
      author.page.getByRole("heading", { name: "Наблюдение сохранено" }),
    ).toBeVisible();
    await auditScreen(author.page, "resident-observation-saved-dark-large");
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
    await auditScreen(author.page, "resident-partial-dark-large");
    await author.page.getByText("История обращения", { exact: true }).click();
    await expect(
      author.page.getByText("Автор сообщил наблюдение", { exact: true }),
    ).toBeVisible();
    await author.page
      .getByText("Исходный текст и квитанция", { exact: true })
      .click();
    await auditScreen(author.page, "resident-history-source-dark-large");
    await author.page.goto(reportUrl);
    await expect(
      author.page.getByRole("heading", {
        name: "Отчёт о работах",
        exact: true,
      }),
    ).toBeVisible();
    await auditScreen(author.page, "resident-report-dark-large");
    await author.page.goto(`/#/cases/${initial.caseId}`);
    await expect(author.page.getByLabel("Результат по пунктам")).toContainText(
      "1 из 2",
    );
    await appearance(author.page, "light");
    expect(
      (
        await new AxeBuilder({ page: author.page })
          .withTags(["wcag2a", "wcag2aa", "wcag21aa"])
          .analyze()
      ).violations,
    ).toEqual([]);
    await author.page.screenshot({
      path: test.info().outputPath("partial-real-light.png"),
      fullPage: true,
    });
    await author.page
      .getByRole("button", { name: "Настройки вида", exact: true })
      .click();
    await author.page
      .getByRole("radio", { name: "Тёмная", exact: true })
      .check();
    await author.page
      .getByRole("button", { name: "Готово", exact: true })
      .click();
    await author.page.screenshot({
      path: test.info().outputPath("partial-real-dark.png"),
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
      .getByRole("link", { name: "К обращению", exact: true })
      .click();
    await dispatcher.page
      .locator(".item-card")
      .filter({ hasText: "Дверь не закрывается" })
      .getByText("Другие действия", { exact: true })
      .click();
    await dispatcher.page
      .locator(".item-card")
      .filter({ hasText: "Дверь не закрывается" })
      .getByRole("link", { name: "В архив", exact: true })
      .click();
    await dispatcher.page
      .getByLabel("Причина решения")
      .fill("Передано в план обследования. Ремонт не подтверждён.");
    await appearance(dispatcher.page, "dark");
    await auditScreen(dispatcher.page, "dispatcher-archive-dark-desktop");
    await submitReview(
      dispatcher.page,
      "dispatcher-archive-review-dark-desktop",
    );
    await dispatcher.page
      .getByRole("button", { name: "Повторить ту же отправку" })
      .click();
    await expect(
      dispatcher.page.getByRole("heading", { name: "Решение сохранено" }),
    ).toBeVisible();
    await auditScreen(dispatcher.page, "dispatcher-archive-saved-dark-desktop");
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
    await expect(
      dispatcher.page.getByRole("heading", {
        name: `Обращение №${confirmed.displayNumber}`,
        exact: true,
      }),
    ).toBeVisible();
    await auditScreen(dispatcher.page, "dispatcher-archived-card-dark-desktop");
    await dispatcher.page.goto(reportUrl);
    await expect(
      dispatcher.page.getByRole("heading", { name: "Подтверждённые связи" }),
    ).toBeVisible();
    await auditScreen(
      dispatcher.page,
      "dispatcher-report-history-dark-desktop",
    );
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
  test.setTimeout(180_000);
  const house = randomHouse();
  const resident = await actor(browser, house, null, 4);
  try {
    const invitation = randomUUID();
    const hash = createHash("sha256").update(invitation).digest("hex");
    sql(
      `INSERT INTO house_invitation(id,house_id,code_hash,expires_at) VALUES ('${randomUUID()}','${house}','${hash}',now()+interval '1 hour');`,
    );
    await resident.page.setViewportSize({ width: 320, height: 844 });
    await resident.page.goto("/");
    await expect(
      resident.page.getByRole("heading", {
        name: "Подключите свой дом",
        exact: true,
      }),
    ).toBeVisible();
    await appearance(resident.page, "light");
    await auditScreen(resident.page, "resident-no-house-mobile");
    await resident.page
      .getByRole("link", { name: "Ввести код приглашения" })
      .click();
    await expect(resident.page.getByLabel("Код приглашения")).toBeVisible();
    await auditScreen(resident.page, "resident-access-mobile");
    await resident.page
      .getByLabel("Код приглашения")
      .fill("invalid-synthetic-code");
    await resident.page.getByRole("button", { name: "Подключить дом" }).click();
    await expect(resident.page.getByRole("alert")).toBeVisible();
    await auditScreen(resident.page, "resident-invitation-invalid-mobile");
    await resident.page.getByLabel("Код приглашения").fill(invitation);
    await resident.page.getByRole("button", { name: "Подключить дом" }).click();
    await expect(
      resident.page.getByText("Доступ обновлён.", { exact: false }),
    ).toBeVisible();
    await auditScreen(resident.page, "resident-access-connected-mobile");
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
      await auditScreen(
        resident.page,
        `resident-observation-${value.toLowerCase()}-mobile`,
      );
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
      .getByText("Другие действия", { exact: true })
      .click();
    await resident.page
      .locator(".item-card")
      .filter({ hasText: "Дверь не закрывается" })
      .getByRole("link", { name: "Отозвать пункт", exact: true })
      .click();
    await resident.page
      .getByLabel("Причина решения")
      .fill("Ошибся местом, обращение отзываю.");
    await auditScreen(resident.page, "resident-withdraw-mobile");
    await submitReview(resident.page, "resident-withdraw-review-mobile");
    await expect(
      resident.page.getByRole("heading", { name: "Решение сохранено" }),
    ).toBeVisible();
    await auditScreen(resident.page, "resident-withdraw-saved-mobile");
    const withdrawn = await snapshot(resident.context, created.caseId);
    expect(withdrawn.progress).toEqual({
      total: 2,
      confirmed: 0,
      withdrawn: 1,
      remaining: 1,
      allConfirmed: false,
    });
    expect(withdrawn.items[1].administrativeStatus).toBe("WITHDRAWN");
    await resident.page
      .getByRole("link", { name: "Вернуться к обращению" })
      .click();
    await expect(
      resident.page.getByRole("heading", {
        name: `Обращение №${withdrawn.displayNumber}`,
        exact: true,
      }),
    ).toBeVisible();
    await auditScreen(resident.page, "resident-withdrawn-card-mobile");
    await resident.page.goto("/#/profile");
    await expect(
      resident.page.getByRole("heading", { name: "Профиль", exact: true }),
    ).toBeVisible();
    await appearance(resident.page, "dark", true);
    await auditScreen(resident.page, "resident-profile-dark-large-mobile");
    await resident.page.goto("/#/access");
    await expect(
      resident.page.getByRole("heading", {
        name: "Доступ к дому",
        exact: true,
      }),
    ).toBeVisible();
    await auditScreen(resident.page, "resident-houses-dark-large-mobile");
    await resident.page.goto("/#/not-a-route");
    await expect(
      resident.page.getByRole("heading", {
        name: "Страница не найдена",
        exact: true,
      }),
    ).toBeVisible();
    await auditScreen(
      resident.page,
      "resident-route-not-found-dark-large-mobile",
    );
    await resident.page.goto(`/#/cases/${randomUUID()}`);
    await expect(resident.page.getByRole("alert")).toBeVisible();
    await auditScreen(
      resident.page,
      "resident-case-not-found-dark-large-mobile",
    );
    await resident.page.goto("/#/logout");
    await expect(
      resident.page.getByRole("heading", {
        name: "Выйти из ДомПульса?",
        exact: true,
      }),
    ).toBeVisible();
    await auditScreen(resident.page, "resident-logout-dark-large-mobile");
    await resident.page
      .getByRole("button", { name: "Завершить сессию", exact: true })
      .click();
    await expect(
      resident.page.getByRole("heading", { name: "Вы вышли", exact: true }),
    ).toBeVisible();
    await auditScreen(resident.page, "resident-logged-out-dark-large-mobile");
    expect(
      (await resident.context.request.get(`${baseURL}/api/v1/me`)).status(),
    ).toBe(401);
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
    const closed = await request.get(path);
    expect(closed.status()).toBe(404);
    expect(closed.headers()["content-type"]).toContain(
      "application/problem+json",
    );
    expect(await closed.json()).toMatchObject({
      status: 404,
      code: "NOT_FOUND",
      requestId: closed.headers()["x-request-id"],
    });
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
  expect(staticPage.headers()["content-security-policy"]).toContain(
    "frame-ancestors 'self' https://web.max.ru https://max.ru",
  );
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

// These tests use HTTP for all product commands. Only houses/memberships are
// synthetic fixtures; Python, durable Java worker, validation and rendering are real.
async function analysisCase(
  author: Awaited<ReturnType<typeof actor>>,
  dispatcher: Awaited<ReturnType<typeof actor>>,
  house: string,
) {
  await author.page.goto("/");
  await expect(
    author.page.getByRole("link", { name: "Новое обращение" }),
  ).toBeVisible();
  await dispatcher.page.goto("/");
  await expect(
    dispatcher.page.getByRole("heading", {
      name: "Очередь обращений",
      exact: true,
    }),
  ).toBeVisible();
  const createdResponse = await mutate(author.context.request, "/cases", {
    houseId: house,
    sourceText: "На третьем этаже не горит свет, входная дверь не закрывается.",
  });
  expect(createdResponse.status()).toBe(201);
  const created = (await createdResponse.json()) as Case;
  const token = await (
    await author.context.request.get(`${baseURL}/api/v1/csrf`)
  ).json();
  const editedResponse = await author.context.request.patch(
    `${baseURL}/api/v1/cases/${created.caseId}/items`,
    {
      data: {
        expectedCaseVersion: created.caseVersion,
        items: [
          {
            category: "LIGHTING",
            location: "Подъезд 1, этаж 3",
            symptom: "Не горит свет",
          },
          {
            category: "ENTRANCE_DOOR",
            location: "Подъезд 1, вход",
            symptom: "Дверь не закрывается",
          },
        ],
      },
      headers: {
        [token.headerName]: token.token,
        "Idempotency-Key": randomUUID(),
      },
    },
  );
  expect(editedResponse.status()).toBe(200);
  const edited = (await editedResponse.json()) as Case;
  const confirmedResponse = await mutate(
    author.context.request,
    `/cases/${created.caseId}/confirm`,
    {
      expectedCaseVersion: edited.caseVersion,
      sourceRevision: edited.sourceRevision,
    },
  );
  expect(confirmedResponse.status()).toBe(200);
  const confirmed = (await confirmedResponse.json()) as Case;
  const acceptedResponse = await mutate(
    dispatcher.context.request,
    `/cases/${created.caseId}/acceptance`,
    {
      expectedCaseVersion: confirmed.caseVersion,
    },
  );
  expect(acceptedResponse.status()).toBe(200);
  return (await acceptedResponse.json()) as Case;
}

for (const scenario of [
  {
    name: "conditional text is not completed work",
    screen: "rules-conditional",
    reportText: "Если лампу на третьем этаже заменили, сообщите диспетчеру.",
    relations: ["INSUFFICIENT_INFORMATION", "INSUFFICIENT_INFORMATION"],
    labels: ["Недостаточно сведений", "Недостаточно сведений"],
    confirmLight: false,
  },
  {
    name: "light and door have separate evidence and no automatic effects",
    screen: "rules-light-door",
    reportText: "💡 Лампу на третьем этаже заменили, дверь заменим завтра.",
    relations: ["REPORTED_DONE", "PLANNED"],
    labels: ["В тексте сообщается о выполнении", "В тексте описан план"],
    confirmLight: true,
  },
] as const) {
  test(`real RULES v3: ${scenario.name}`, async ({ browser }) => {
    test.skip(
      !analysisEnabled,
      "Run scripts/check_miniapp.py --analysis for the real Python/Java worker path",
    );
    test.setTimeout(180_000);
    const house = randomHouse();
    const author = await actor(browser, house, "RESIDENT", 11);
    const dispatcher = await actor(browser, house, "DISPATCHER", 12);
    const errors: string[] = [];
    dispatcher.page.on("pageerror", (error) => errors.push(error.message));
    try {
      const data = await analysisCase(author, dispatcher, house);
      await dispatcher.page.goto(`/#/cases/${data.caseId}`);
      await dispatcher.page
        .getByRole("link", { name: "Добавить отчёт о работах" })
        .click();
      await dispatcher.page
        .getByLabel("Текст отчёта")
        .fill(scenario.reportText);
      await dispatcher.page
        .getByRole("checkbox", { name: /Не горит свет/ })
        .check();
      await dispatcher.page
        .getByRole("checkbox", { name: /Дверь не закрывается/ })
        .check();
      const savedResponse = dispatcher.page.waitForResponse(
        (response) =>
          new URL(response.url()).pathname ===
            `/api/v1/cases/${data.caseId}/reports` &&
          response.request().method() === "POST",
      );
      await dispatcher.page
        .getByRole("button", { name: "Сохранить текст отчёта" })
        .click();
      const response = await savedResponse;
      expect(response.status()).toBe(201);
      const saved =
        (await response.json()) as components["schemas"]["CreateReportResponse"];
      expect(saved.analysis.status).toBe("QUEUED");
      expect(saved.report.reportText).toBe(scenario.reportText);
      expect(saved.report.links).toEqual([]);
      expect(saved.case.items).toEqual(data.items);
      expect(saved.case.progress).toEqual(data.progress);
      await dispatcher.page
        .getByRole("link", { name: "Проверить связи с пунктами" })
        .click();

      let analysis: Suggestions | undefined;
      await expect
        .poll(
          async () => {
            const answer = await dispatcher.context.request.get(
              `${baseURL}/api/v1/reports/${saved.report.reportId}/suggestions`,
            );
            expect(answer.status()).toBe(200);
            analysis = (await answer.json()) as Suggestions;
            return analysis.status;
          },
          { timeout: 60_000, intervals: [500, 1000, 2000] },
        )
        .toBe("READY");
      expect(analysis!.analysisId).toMatch(/^[0-9a-f-]{36}$/);
      expect(analysis!.result).toMatchObject({
        caseId: data.caseId,
        sourceRevision: data.sourceRevision,
        reportId: saved.report.reportId,
        modelVersion: "rules-baseline-v3",
        mode: "RULES",
      });
      const suggestions = analysis!.result!.suggestions;
      expect(suggestions.map((entry) => entry.itemId).sort()).toEqual(
        data.items.map((item) => item.itemId).sort(),
      );
      const job = JSON.parse(
        sql(
          `SELECT json_build_object('status', a.status, 'attempts', a.attempts, 'modelVersion', s.model_version)::text
         FROM report_analysis a JOIN model_suggestion s ON s.id = a.suggestion_id
         WHERE a.report_id = '${saved.report.reportId}';`,
        ),
      ) as { status: string; attempts: number; modelVersion: string };
      expect(job).toMatchObject({
        status: "READY",
        modelVersion: "rules-baseline-v3",
      });
      expect(job.attempts).toBeGreaterThanOrEqual(1);

      for (const [index, item] of data.items.entries()) {
        const suggestion = suggestions.find(
          (entry) => entry.itemId === item.itemId,
        )!;
        expect(suggestion).toMatchObject({
          contentVersion: item.contentVersion,
          workRound: item.workRound,
          relation: scenario.relations[index],
          reason: "ANALYZED",
        });
        const choice = dispatcher.page
          .locator(".link-choice")
          .filter({ hasText: item.symptom });
        await expect(choice.locator("aside.evidence")).toContainText(
          scenario.labels[index],
        );
        await expect(choice.getByRole("checkbox")).not.toBeChecked();
        if (suggestion.evidence) {
          const evidence = suggestion.evidence;
          expect(
            Array.from(scenario.reportText)
              .slice(evidence.start, evidence.end)
              .join(""),
          ).toBe(evidence.quote);
          expect(evidence.end).toBeGreaterThan(evidence.start);
          await expect(choice.locator("q")).toHaveText(evidence.quote);
          if (index === 0) {
            expect(evidence.quote).toMatch(/ламп|свет|освещен/i);
            expect(evidence.quote).not.toMatch(/двер/i);
          } else {
            expect(evidence.quote).toMatch(/двер/i);
            expect(evidence.quote).not.toMatch(/ламп/i);
          }
        } else {
          expect(suggestion.relation).toBe("INSUFFICIENT_INFORMATION");
          await expect(choice.locator("q")).toHaveCount(0);
        }
      }
      await expect(
        dispatcher.page.getByText("Ничего не выбрано автоматически.", {
          exact: false,
        }),
      ).toBeVisible();
      await expect(
        dispatcher.page.getByRole("button", {
          name: "Проверить выбранные связи",
        }),
      ).toBeDisabled();
      await auditScreen(dispatcher.page, scenario.screen);
      const unchanged = await snapshot(author.context, data.caseId);
      expect(unchanged.caseVersion).toBe(saved.case.caseVersion);
      expect(unchanged.items).toEqual(saved.case.items);
      expect(unchanged.progress).toEqual({
        total: 2,
        confirmed: 0,
        withdrawn: 0,
        remaining: 2,
        allConfirmed: false,
      });
      const reportResponse = await dispatcher.context.request.get(
        `${baseURL}/api/v1/reports/${saved.report.reportId}`,
      );
      expect(reportResponse.status()).toBe(200);
      expect(((await reportResponse.json()) as Report).links).toEqual([]);
      await test.info().attach(scenario.screen, {
        body: JSON.stringify({
          reportText: scenario.reportText,
          analysis,
          job,
          progress: unchanged.progress,
        }),
        contentType: "application/json",
      });

      if (scenario.confirmLight) {
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
        const linked = await snapshot(author.context, data.caseId);
        expect(linked.items[0]).toMatchObject({
          workRound: 1,
          workStatus: "REPORTED_DONE",
          observation: "NOT_RECEIVED",
          administrativeStatus: "ACTIVE",
        });
        expect(linked.items[1]).toEqual(unchanged.items[1]);
        expect(linked.progress).toEqual(unchanged.progress);
        await auditScreen(dispatcher.page, "rules-explicit-light-link");
      }
      expect(errors).toEqual([]);
    } finally {
      await author.context.close();
      await dispatcher.context.close();
    }
  });
}
