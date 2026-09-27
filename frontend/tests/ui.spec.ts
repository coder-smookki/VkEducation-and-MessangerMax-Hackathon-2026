import { test, expect } from "@playwright/test";
import type { Page, Route } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import meFixture from "../../contracts/examples/me-response.json" with { type: "json" };
import caseFixture from "../../contracts/examples/case-view-target.json" with { type: "json" };
import reportFixture from "../../contracts/examples/report-view.json" with { type: "json" };
import suggestionsFixture from "../../contracts/examples/report-suggestions-ready.json" with { type: "json" };
const caseId = caseFixture.caseId;
const copy = <T>(value: T): T => structuredClone(value);
async function setup(page: Page, dispatcher = false) {
  const me = copy(meFixture);
  if (dispatcher) {
    me.userId = "00000000-0000-0000-0000-000000000007";
    me.memberships[0].role = "DISPATCHER";
  }
  const data = copy(caseFixture);
  await page.route("https://st.max.ru/**", (r) =>
    r.fulfill({ body: "", contentType: "application/javascript" }),
  );
  await page.route("**/api/v1/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path === "/api/v1/me") return route.fulfill({ json: me });
    if (path === "/api/v1/csrf")
      return route.fulfill({
        json: {
          headerName: "X-XSRF-TOKEN",
          parameterName: "_csrf",
          token: "masked-fixture",
        },
      });
    if (path.endsWith("/history"))
      return route.fulfill({ json: { items: [], nextCursor: null } });
    if (path === "/api/v1/cases")
      return route.fulfill({ json: { items: [data], nextCursor: null } });
    if (path === `/api/v1/cases/${caseId}`)
      return route.fulfill({ json: data });
    if (path.endsWith("/suggestions"))
      return route.fulfill({ json: suggestionsFixture });
    if (path.includes("/reports/"))
      return route.fulfill({ json: reportFixture });
    return route.fulfill({ status: 404, json: { code: "NOT_FOUND" } });
  });
  return { me, data };
}
function problem(route: Route, status: number, code: string) {
  return route.fulfill({
    status,
    contentType: "application/problem+json",
    json: { type: "about:blank", status, code, title: code },
  });
}

for (const start of [caseId, "invalid", ""]) {
  test(`MAX launch fragment routes safely: ${start || "no start parameter"}`, async ({
    page,
  }) => {
    await setup(page);
    await page.addInitScript((id) => {
      window.WebApp = {
        initData: new URLSearchParams({ start_param: id }).toString(),
      };
    }, start);
    await page.goto("/#WebAppPlatform=web&WebAppData=synthetic-launch");
    await expect(page).toHaveURL(
      start === caseId ? new RegExp(`#/cases/${caseId}$`) : /#\/$/,
    );
    await expect(
      page.getByRole("heading", { name: "Страница не найдена" }),
    ).toHaveCount(0);
    if (start !== caseId) {
      await expect(
        page.getByRole("link", { name: "Новое обращение" }),
      ).toBeVisible();
    }
    await page.goto("/#/access");
    await expect(
      page.getByRole("heading", { name: "Доступ к дому", exact: true }),
    ).toBeVisible();
    await expect(page).toHaveURL(/#\/access$/);
  });
}

test("outside MAX explains login and never invents a user or role", async ({
  page,
}) => {
  await page.route("https://st.max.ru/**", (r) => r.fulfill({ body: "" }));
  await page.route("**/api/v1/me", (r) => problem(r, 401, "AUTH_REQUIRED"));
  await page.goto("/");
  await expect(
    page.getByRole("heading", { name: "Каждая проблема остаётся на виду" }),
  ).toBeVisible();
  await expect(
    page.getByText("Откройте мини-приложение из чата", { exact: false }),
  ).toBeVisible();
  await expect(page.getByRole("link", { name: "Новое обращение" })).toHaveCount(
    0,
  );
  await expect(
    page.getByRole("link", { name: "Открыть в MAX" }),
  ).toHaveAttribute("href", "https://max.ru/t109_hakaton_max_bot?startapp");
  // The skip link must focus content without replacing the hash-router route.
  await page.getByRole("link", { name: "К содержимому", exact: true }).focus();
  await page.keyboard.press("Enter");
  await expect(page.getByRole("main")).toBeFocused();
  await expect(
    page.getByRole("heading", { name: "Каждая проблема остаётся на виду" }),
  ).toBeVisible();
});

test("account without membership needs an invitation", async ({ page }) => {
  await setup(page);
  await page.route("**/api/v1/me", (r) =>
    r.fulfill({
      json: { userId: meFixture.userId, displayName: null, memberships: [] },
    }),
  );
  await page.goto("/");
  await expect(
    page.getByRole("heading", { name: "Подключите свой дом" }),
  ).toBeVisible();
  await page.getByRole("link", { name: "Ввести код приглашения" }).click();
  await page.getByLabel("Код приглашения").fill("bad-code");
  await page.route("**/memberships/claim", (r) =>
    problem(r, 403, "ACTION_FORBIDDEN"),
  );
  await page.getByRole("button", { name: "Подключить дом" }).click();
  await expect(page.getByRole("alert")).toBeVisible();
  await expect(page.getByLabel("Код приглашения")).toHaveValue("bad-code");
});

test("profile navigation shows the authenticated account and server memberships", async ({
  page,
}) => {
  const { me } = await setup(page);
  await page.goto("/");
  const navigation = page.getByRole("navigation", {
    name: "Основная навигация",
  });
  await navigation.getByRole("link", { name: "Профиль", exact: true }).click();
  await expect(page).toHaveURL(/#\/profile$/);
  await expect(
    page.getByRole("heading", { name: "Профиль", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("heading", { name: me.displayName! }),
  ).toBeVisible();
  const house = page.getByRole("link", {
    name: `${me.memberships[0].address} Житель`,
    exact: true,
  });
  await expect(house).toHaveAttribute(
    "href",
    `#/?house=${me.memberships[0].houseId}`,
  );
  await expect(
    page.getByRole("link", { name: "ДомПульс, главная", exact: true }),
  ).toBeVisible();
  await page.getByRole("link", { name: "Выйти", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "Выйти из ДомПульса?", exact: true }),
  ).toBeVisible();
  // Navigation itself never terminates a session; the existing explicit command is required.
  await expect(
    page.getByRole("button", { name: "Завершить сессию", exact: true }),
  ).toBeVisible();
  await page
    .getByRole("link", { name: "Остаться в ДомПульсе", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "Доступ к дому", exact: true }),
  ).toBeVisible();
});

test("theme, large text and denied storage preserve input; cancelled exit restores focus", async ({
  page,
}) => {
  await setup(page);
  await page.addInitScript(() => {
    Storage.prototype.setItem = () => {
      throw new Error("blocked");
    };
  });
  await page.goto("/#/new");
  await page.getByLabel("Текст обращения").fill("Дверь скрипит 🚪");
  await page
    .getByRole("button", { name: "Настройки вида", exact: true })
    .click();
  await page.getByRole("radio", { name: "Тёмная", exact: true }).check();
  await page.getByLabel("Крупный текст").check();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  await expect(page.getByLabel("Текст обращения")).toHaveValue(
    "Дверь скрипит 🚪",
  );
  await page.getByRole("button", { name: "Готово", exact: true }).click();
  await page.getByRole("link", { name: "Обращения", exact: true }).click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await page.getByRole("button", { name: "Остаться", exact: true }).click();
  await expect(page.getByLabel("Текст обращения")).toHaveValue(
    "Дверь скрипит 🚪",
  );
  await expect(
    page.getByRole("link", { name: "Обращения", exact: true }),
  ).toBeFocused();
});

test("unknown outcome retries exact body and key with CSRF refresh", async ({
  page,
}) => {
  const { data } = await setup(page);
  const requests: { body: string | null; key: string | undefined }[] = [];
  let csrfCount = 0;
  await page.route("**/api/v1/csrf", (route) => {
    csrfCount++;
    return route.fulfill({
      json: { headerName: "X-XSRF-TOKEN", token: `masked-${csrfCount}` },
    });
  });
  await page.route("**/api/v1/cases", async (route) => {
    requests.push({
      body: route.request().postData(),
      key: route.request().headers()["idempotency-key"],
    });
    if (requests.length === 1) return problem(route, 403, "CSRF_TOKEN_INVALID");
    if (requests.length === 2) return route.abort("connectionfailed");
    return route.fulfill({
      status: 201,
      json: { ...data, submissionStatus: "DRAFT" },
    });
  });
  await page.goto("/#/new");
  await page.getByLabel("Текст обращения").fill("Не горит свет");
  await page
    .getByRole("button", { name: "Сохранить и перейти к пунктам" })
    .click();
  await expect(
    page.getByText("Ответ не получен.", { exact: false }),
  ).toBeVisible();
  await expect(page.getByLabel("Текст обращения")).toBeDisabled();
  await page.getByRole("button", { name: "Повторить ту же отправку" }).click();
  await expect(
    page.getByRole("heading", { name: "Черновик сохранён" }),
  ).toBeVisible();
  expect(requests).toHaveLength(3);
  expect(requests[0]).toEqual(requests[1]);
  expect(requests[1]).toEqual(requests[2]);
  expect(csrfCount).toBe(2);
});

test("pending save blocks double click and navigation", async ({ page }) => {
  const { data } = await setup(page);
  let release!: () => void;
  const wait = new Promise<void>((resolve) => {
    release = resolve;
  });
  let writes = 0;
  await page.route("**/api/v1/cases", async (route) => {
    writes++;
    await wait;
    await route.fulfill({ status: 201, json: data });
  });
  await page.goto("/#/new");
  await page.getByLabel("Текст обращения").fill("Свет не работает");
  await page
    .getByRole("button", { name: "Сохранить и перейти к пунктам" })
    .click();
  await expect(
    page.getByRole("button", { name: "Сохранить и перейти к пунктам" }),
  ).toBeDisabled();
  await expect(
    page.getByRole("heading", { name: "Черновик сохранён" }),
  ).toHaveCount(0);
  await page.getByRole("link", { name: "Обращения", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "Отправка ещё не завершилась" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Остаться", exact: true }).click();
  release();
  await expect(
    page.getByRole("heading", { name: "Черновик сохранён" }),
  ).toBeVisible();
  expect(writes).toBe(1);
});

test("409 preserves edits and requires comparison before adopting a version", async ({
  page,
}) => {
  const { data } = await setup(page);
  data.submissionStatus = "DRAFT";
  let version = 5;
  await page.route(`**/api/v1/cases/${caseId}`, (route) =>
    route.fulfill({ json: { ...data, caseVersion: version } }),
  );
  await page.route("**/source", (route) => {
    version = 6;
    return problem(route, 409, "CASE_VERSION_CONFLICT");
  });
  await page.goto(`/#/cases/${caseId}/source`);
  await page.getByLabel("Текст обращения").fill("Мой новый текст");
  await page
    .getByRole("button", { name: "Сохранить текст", exact: true })
    .click();
  await expect(page.getByRole("alert")).toBeVisible();
  await page
    .getByRole("button", { name: "Загрузить актуальную версию" })
    .click();
  await expect(page.getByText("Версия карточки: 6")).toBeVisible();
  await expect(page.getByLabel("Текст обращения")).toHaveValue(
    "Мой новый текст",
  );
  let body: Record<string, unknown> = {};
  await page.route("**/source", async (route) => {
    body = route.request().postDataJSON();
    await route.fulfill({
      json: { ...data, sourceText: body.sourceText, caseVersion: 7 },
    });
  });
  await page
    .getByRole("button", {
      name: "Я сравнил данные, продолжить с новой версией",
    })
    .click();
  await page
    .getByRole("button", { name: "Сохранить текст", exact: true })
    .click();
  await expect(
    page.getByText("Текст сохранён.", { exact: false }),
  ).toBeVisible();
  expect(body).toEqual({
    expectedCaseVersion: 6,
    sourceText: "Мой новый текст",
  });
});

test("Unicode limits and empty draft cannot be confirmed", async ({ page }) => {
  const { data } = await setup(page);
  data.submissionStatus = "DRAFT";
  data.items = [];
  await page.goto("/#/new");
  await page.getByLabel("Текст обращения").fill("💡".repeat(5000));
  await expect(page.getByText("5000 / 5000", { exact: true })).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Сохранить и перейти к пунктам" }),
  ).toBeEnabled();
  await page.getByLabel("Текст обращения").fill("💡".repeat(5001));
  await expect(
    page.getByRole("button", { name: "Сохранить и перейти к пунктам" }),
  ).toBeDisabled();
  await page.getByLabel("Текст обращения").fill("");
  await page.getByRole("link", { name: "Обращения", exact: true }).click();
  await page
    .getByRole("link", { name: data.sourceText.slice(0, 100), exact: true })
    .click();
  await page
    .getByRole("link", { name: "Проверить пункты", exact: true })
    .click();
  await page.getByRole("button", { name: "Удалить пункт 1" }).click();
  await page.route("**/items", (route) =>
    route.fulfill({ json: { ...data, caseVersion: 6 } }),
  );
  await page
    .getByRole("button", { name: "Сохранить список", exact: true })
    .click();
  await page.getByRole("link", { name: "Проверить и подтвердить" }).click();
  await expect(
    page.getByRole("button", { name: "Подтвердить список", exact: true }),
  ).toBeDisabled();
});

test("no automatic report links and dispatcher cannot observe for author", async ({
  page,
}) => {
  await setup(page, true);
  await page.goto(`/#/cases/${caseId}`);
  await expect(
    page.getByRole("link", { name: "Сообщить своё наблюдение" }),
  ).toHaveCount(0);
  await page.goto(`/#/cases/${caseId}/reports/${reportFixture.reportId}`);
  await expect(page.getByRole("checkbox").first()).not.toBeChecked();
  await expect(
    page.getByRole("button", { name: "Проверить выбранные связи" }),
  ).toBeDisabled();
  await expect(
    page.getByText("Ничего не выбрано автоматически.", { exact: false }),
  ).toBeVisible();
});

test("404 hides revoked/foreign cases", async ({ page }) => {
  await setup(page);
  await page.route(`**/api/v1/cases/${caseId}`, (route) =>
    problem(route, 404, "RESOURCE_NOT_FOUND"),
  );
  await page.goto(`/#/cases/${caseId}`);
  await expect(page.getByRole("alert")).toContainText(
    "не найдено или недоступно",
  );
  await expect(page.getByRole("heading", { name: /Обращение №/ })).toHaveCount(
    0,
  );
});
for (const width of [320, 390, 768, 1440])
  for (const theme of ["light", "dark"]) {
    test(`responsive/a11y ${width}px ${theme}, normal and 200%`, async ({
      page,
    }) => {
      const { data } = await setup(page);
      data.items[1].symptom = "Очень длинное описание двери 🚪 ".repeat(6);
      await page.setViewportSize({ width, height: 1000 });
      await page.emulateMedia({
        colorScheme: theme as "light" | "dark",
        reducedMotion: "reduce",
      });
      await page.goto(`/#/cases/${caseId}`);
      await expect(
        page.getByRole("heading", { name: /Обращение №/ }),
      ).toBeVisible();
      expect(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth + 1,
        ),
      ).toBeTruthy();
      expect(
        (
          await new AxeBuilder({ page })
            .withTags(["wcag2a", "wcag2aa", "wcag21aa"])
            .analyze()
        ).violations,
      ).toEqual([]);
      await page.evaluate(() => {
        document.documentElement.style.fontSize = "36px";
      });
      expect(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth + 1,
        ),
      ).toBeTruthy();
      expect(
        (
          await new AxeBuilder({ page })
            .withTags(["wcag2a", "wcag2aa", "wcag21aa"])
            .analyze()
        ).violations,
      ).toEqual([]);
    });
  }

for (const width of [320, 1440]) {
  test(`dispatcher queue remains accessible at ${width}px and large text`, async ({
    page,
  }) => {
    await setup(page, true);
    await page.setViewportSize({ width, height: 1000 });
    await page.emulateMedia({ colorScheme: "dark" });
    await page.goto("/");
    await expect(
      page.getByRole("table", { name: "Доступные обращения" }),
    ).toBeVisible();
    await expect(
      page.getByRole("link", { name: "Не горит свет", exact: true }),
    ).toBeVisible();
    await page.evaluate(() => {
      document.documentElement.style.fontSize = "36px";
    });
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth + 1,
      ),
    ).toBeTruthy();
    expect(
      (
        await new AxeBuilder({ page })
          .withTags(["wcag2a", "wcag2aa", "wcag21aa"])
          .analyze()
      ).violations,
    ).toEqual([]);
  });
}

test("switching house cannot render an older in-flight response", async ({
  page,
}) => {
  const { data, me } = await setup(page);
  let release!: () => void;
  const wait = new Promise<void>((resolve) => {
    release = resolve;
  });
  await page.route("**/api/v1/cases?*", async (route) => {
    if (
      new URL(route.request().url()).searchParams.get("houseId") ===
      me.memberships[0].houseId
    ) {
      await wait;
      await route
        .fulfill({ json: { items: [data], nextCursor: null } })
        .catch(() => {});
    } else await route.fulfill({ json: { items: [], nextCursor: null } });
  });
  await page.goto("/");
  await page
    .getByLabel("Дом", { exact: true })
    .selectOption(me.memberships[1].houseId);
  await expect(
    page.getByRole("heading", { name: "Очередь обращений" }),
  ).toBeVisible();
  await expect(
    page.getByRole("heading", { name: "Пока нет обращений" }),
  ).toBeVisible();
  release();
  await expect(
    page.getByRole("link", { name: "Не горит свет", exact: true }),
  ).toHaveCount(0);
});

test("session renewal preserves the form and retries the same command", async ({
  page,
}) => {
  const { data, me } = await setup(page);
  await page.addInitScript(() => {
    window.WebApp = { initData: "synthetic-launch" };
  });
  const keys: string[] = [];
  await page.route("**/api/v1/auth/max", (route) =>
    route.fulfill({ json: me }),
  );
  await page.route("**/api/v1/cases", (route) => {
    keys.push(route.request().headers()["idempotency-key"]!);
    return keys.length === 1
      ? problem(route, 401, "AUTH_REQUIRED")
      : route.fulfill({ json: data, status: 201 });
  });
  await page.goto("/#/new");
  await page.getByLabel("Текст обращения").fill("Сохранить после нового входа");
  await page
    .getByRole("button", { name: "Сохранить и перейти к пунктам" })
    .click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await page
    .getByRole("button", { name: "Войти через MAX", exact: true })
    .click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(page.getByLabel("Текст обращения")).toHaveValue(
    "Сохранить после нового входа",
  );
  await page.getByRole("button", { name: "Повторить ту же отправку" }).click();
  await expect(
    page.getByRole("heading", { name: "Черновик сохранён" }),
  ).toBeVisible();
  expect(keys[0]).toBe(keys[1]);
});

test("valid evidence is shown verbatim; stale or mismatched evidence is hidden", async ({
  page,
}) => {
  await setup(page, true);
  const report = {
    ...reportFixture,
    links: [],
    reportText: "Лампу на третьем этаже заменили.",
  };
  const analysis = copy(suggestionsFixture);
  analysis.result.reportId = report.reportId;
  analysis.result.suggestions[0].evidence = {
    start: 0,
    end: Array.from(report.reportText).length,
    quote: report.reportText,
  };
  await page.route(`**/api/v1/reports/${report.reportId}`, (r) =>
    r.fulfill({ json: report }),
  );
  await page.route("**/suggestions", (r) => r.fulfill({ json: analysis }));
  await page.goto(`/#/cases/${caseId}/reports/${report.reportId}`);
  await expect(page.locator(".evidence q")).toHaveText(report.reportText);
  await expect(page.getByRole("checkbox").first()).not.toBeChecked();
  analysis.result.suggestions[0].workRound = 90;
  await page.reload();
  await expect(
    page.getByRole("heading", { name: "Связь отчёта с пунктами" }),
  ).toBeVisible();
  await expect(page.locator(".evidence q")).toHaveCount(0);
  analysis.result.suggestions[0].workRound = 0;
  analysis.result.suggestions[0].evidence!.quote = "Неправильная цитата";
  await page.reload();
  await expect(
    page.getByRole("heading", { name: "Связь отчёта с пунктами" }),
  ).toBeVisible();
  await expect(page.locator(".evidence q")).toHaveCount(0);
});

test("accessible editor and report forms at 320px with large text", async ({
  page,
}) => {
  const { data } = await setup(page, true);
  await page.setViewportSize({ width: 320, height: 844 });
  await page.goto(`/#/cases/${caseId}/reports/new`);
  await page
    .getByRole("button", { name: "Настройки вида", exact: true })
    .click();
  await page.getByLabel("Крупный текст").check();
  await page.getByRole("radio", { name: "Тёмная", exact: true }).check();
  await page.getByRole("button", { name: "Готово", exact: true }).click();
  await page.getByLabel("Текст отчёта").fill("Свет заменили 🚪 ".repeat(50));
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth + 1,
    ),
  ).toBeTruthy();
  expect(
    (
      await new AxeBuilder({ page })
        .withTags(["wcag2a", "wcag2aa", "wcag21aa"])
        .analyze()
    ).violations,
  ).toEqual([]);
  await page.getByLabel("Текст отчёта").fill("");
  await page.getByRole("link", { name: "← К обращению", exact: true }).click();
  data.submissionStatus = "DRAFT";
  await page.route("**/api/v1/me", (r) => r.fulfill({ json: meFixture }));
  await page.reload();
  await page
    .getByRole("link", { name: "Проверить пункты", exact: true })
    .click();
  expect(
    (
      await new AxeBuilder({ page })
        .withTags(["wcag2a", "wcag2aa", "wcag21aa"])
        .analyze()
    ).violations,
  ).toEqual([]);
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth + 1,
    ),
  ).toBeTruthy();
});

test("appearance dialog preserves input, restores focus, persists settings and respects reduced motion", async ({
  page,
}) => {
  await setup(page);
  await page.setViewportSize({ width: 320, height: 844 });
  await page.emulateMedia({
    colorScheme: "light",
    reducedMotion: "no-preference",
  });
  await page.goto("/#/new");
  await page.getByLabel("Текст обращения").fill("Дверь не закрывается");
  const trigger = page.getByRole("button", {
    name: "Настройки вида",
    exact: true,
  });
  await trigger.click();
  const dialog = page.getByRole("dialog", { name: "Внешний вид приложения" });
  await expect(dialog).toBeVisible();
  await page.getByRole("radio", { name: "Тёмная", exact: true }).check();
  await page.getByLabel("Крупный текст").check();
  await page.getByLabel("Уменьшить анимации").check();
  await expect(page.locator("html")).toHaveAttribute(
    "data-reduce-motion",
    "true",
  );
  await page.evaluate(() => {
    document.documentElement.style.fontSize = "36px";
  });
  expect(
    await dialog.evaluate((el) => el.scrollWidth <= el.clientWidth + 1),
  ).toBeTruthy();
  expect(
    (
      await new AxeBuilder({ page })
        .withTags(["wcag2a", "wcag2aa", "wcag21aa"])
        .analyze()
    ).violations,
  ).toEqual([]);
  await page.keyboard.press("Escape");
  await expect(dialog).toHaveCount(0);
  await expect(trigger).toBeFocused();
  await expect(page.getByLabel("Текст обращения")).toHaveValue(
    "Дверь не закрывается",
  );
  expect(await page.evaluate(() => Object.keys(localStorage))).toEqual([
    "koren:preferences",
  ]);
  await page.getByLabel("Текст обращения").fill("");
  await page.reload();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  await expect(page.locator("html")).toHaveAttribute("data-large", "true");
  await expect(page.locator("html")).toHaveAttribute(
    "data-reduce-motion",
    "true",
  );
  expect(
    await page
      .locator(".page-content")
      .evaluate((el) => getComputedStyle(el).animationName),
  ).toBe("none");
  await trigger.click();
  await page.getByLabel("Уменьшить анимации").uncheck();
  await page.getByRole("radio", { name: "Как в системе", exact: true }).check();
  await page.getByRole("button", { name: "Готово", exact: true }).click();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "light");
  await page.emulateMedia({ colorScheme: "dark", reducedMotion: "reduce" });
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  expect(
    await page
      .locator(".page-content")
      .evaluate((el) => getComputedStyle(el).animationName),
  ).toBe("none");
});

test("history is requested only on demand and rare item actions remain reachable", async ({
  page,
}) => {
  await setup(page);
  let historyRequests = 0;
  await page.route("**/history?**", (route) => {
    historyRequests++;
    return route.fulfill({ json: { items: [], nextCursor: null } });
  });
  await page.goto(`/#/cases/${caseId}`);
  await expect(
    page.getByRole("heading", { name: /Обращение №/ }),
  ).toBeVisible();
  expect(historyRequests).toBe(0);
  const action = page
    .getByRole("link", { name: "Сообщить своё наблюдение" })
    .first();
  const size = await action.boundingBox();
  expect(size!.height).toBeGreaterThanOrEqual(52);
  expect(size!.height).toBeLessThan(100);
  await page.getByText("История обращения", { exact: true }).click();
  await expect.poll(() => historyRequests).toBe(1);
  const item = page
    .locator(".item-card")
    .filter({ hasText: "Дверь не закрывается" });
  await expect(item.getByRole("link", { name: "Отозвать пункт" })).toHaveCount(
    0,
  );
  await item.getByText("Другие действия", { exact: true }).click();
  await item.getByRole("link", { name: "Отозвать пункт", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "Отозвать этот пункт?" }),
  ).toBeVisible();
  await expect(
    page.getByText("Действие нельзя отменить.", { exact: false }),
  ).toBeVisible();
});

test("redesigned screen families remain accessible in both themes", async ({
  page,
}) => {
  const { data } = await setup(page);
  const capture = async (name: string) => {
    await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth + 1,
      ),
    ).toBeTruthy();
    expect(
      (
        await new AxeBuilder({ page })
          .withTags(["wcag2a", "wcag2aa", "wcag21aa"])
          .analyze()
      ).violations,
    ).toEqual([]);
    if (process.env.KOREN_DESIGN_CAPTURE)
      await page.screenshot({
        path: `../tmp/redesign/${name}.png`,
        fullPage: !(await page.getByRole("dialog").isVisible()),
        animations: "disabled",
      });
  };
  for (const theme of ["light", "dark"] as const) {
    await page.emulateMedia({ colorScheme: theme, reducedMotion: "reduce" });
    for (const [name, path] of [
      ["list", "/"],
      ["case", `/cases/${caseId}`],
      ["new", "/new"],
      ["observation", `/cases/${caseId}/items/${data.items[1].itemId}/observe`],
      ["access", "/access"],
      ["profile", "/profile"],
    ]) {
      await page.goto(`/?screen=${name}-${theme}#${path}`);
      await capture(`${name}-${theme}`);
    }
    await page
      .getByRole("button", { name: "Настройки вида", exact: true })
      .click();
    await capture(`preferences-${theme}`);
    await page.getByRole("button", { name: "Готово", exact: true }).click();
  }
  data.submissionStatus = "DRAFT";
  await page.goto(`/?screen=draft#/cases/${caseId}/edit`);
  await capture("draft-dark");
  await page.goto(`/?screen=review#/cases/${caseId}/review`);
  await capture("review-dark");
  await setup(page, true);
  for (const [name, path] of [
    ["queue", "/"],
    ["report", `/cases/${caseId}/reports/${reportFixture.reportId}`],
  ]) {
    await page.setViewportSize({ width: 1440, height: 1000 });
    await page.goto(`/?screen=${name}#${path}`);
    await capture(`${name}-desktop-dark`);
  }
});
