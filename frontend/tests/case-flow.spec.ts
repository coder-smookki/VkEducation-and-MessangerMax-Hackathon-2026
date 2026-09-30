import { test, expect } from "@playwright/test";
import type { Page, Route } from "@playwright/test";
import type { Case, Report } from "../src/api/client";
import meFixture from "../../contracts/examples/me-response.json" with { type: "json" };
import caseFixture from "../../contracts/examples/case-view-target.json" with { type: "json" };
import reportFixture from "../../contracts/examples/report-view.json" with { type: "json" };

// Isolated HTTP fault injection. Product runtime never imports these fixtures.
async function setup(page: Page, dispatcher = false) {
  const me = structuredClone(meFixture);
  if (dispatcher) {
    me.userId = "00000000-0000-0000-0000-000000000007";
    me.memberships[0].role = "DISPATCHER";
  }
  const data = structuredClone(caseFixture) as Case;
  const report = { ...structuredClone(reportFixture), links: [] } as Report;
  await page.route("https://st.max.ru/**", (r) =>
    r.fulfill({ body: "", contentType: "application/javascript" }),
  );
  await page.route("**/api/v1/**", (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith("/me")) return route.fulfill({ json: me });
    if (path.endsWith("/csrf"))
      return route.fulfill({
        json: {
          headerName: "X-XSRF-TOKEN",
          token: "test-token",
          parameterName: "_csrf",
        },
      });
    if (path === `/api/v1/cases/${data.caseId}`)
      return route.fulfill({ json: data });
    if (path === "/api/v1/cases")
      return route.fulfill({ json: { items: [data], nextCursor: null } });
    if (path.endsWith("/history"))
      return route.fulfill({ json: { items: [], nextCursor: null } });
    if (path.endsWith("/suggestions"))
      return route.fulfill({
        json: { status: "MANUAL_REQUIRED", analysisId: null, result: null },
      });
    if (path === `/api/v1/reports/${report.reportId}`)
      return route.fulfill({ json: report });
    return problem(route, 404, "RESOURCE_NOT_FOUND");
  });
  return { data, report, me };
}
function problem(route: Route, status: number, code: string) {
  return route.fulfill({
    status,
    contentType: "application/problem+json",
    json: { status, code, requestId: "fault-injection" },
  });
}
const refreshName = "Загрузить актуальную версию";
const adoptName = "Я сравнил данные, продолжить с новой версией";

test("acceptance conflict requires refreshed review and a new command with the current version", async ({
  page,
}) => {
  const { data, me } = await setup(page, true);
  const commands: { body: unknown; key: string | undefined }[] = [];
  await page.route("**/acceptance", async (route) => {
    commands.push({
      body: route.request().postDataJSON(),
      key: route.request().headers()["idempotency-key"],
    });
    if (commands.length === 1) {
      data.caseVersion++;
      return problem(route, 409, "CASE_VERSION_CONFLICT");
    }
    data.caseVersion++;
    data.receipt.acceptedAt = new Date().toISOString();
    data.receipt.acceptedBy = me.userId;
    return route.fulfill({ json: data });
  });
  await page.goto(`/#/cases/${data.caseId}`);
  const accept = page.getByRole("button", {
    name: "Принять обращение",
    exact: true,
  });
  await accept.click();
  await expect(accept).toBeDisabled();
  await page.getByRole("button", { name: refreshName }).click();
  await expect(page.getByText("Версия карточки: 6")).toBeVisible();
  await expect(accept).toBeDisabled();
  await page.getByRole("button", { name: adoptName }).click();
  await accept.click();
  await expect(
    page.getByText("Принято диспетчером", { exact: true }),
  ).toBeVisible();
  expect(commands.map((command) => command.body)).toEqual([
    { expectedCaseVersion: 5 },
    { expectedCaseVersion: 6 },
  ]);
  expect(commands[0].key).toBeTruthy();
  expect(commands[1].key).not.toBe(commands[0].key);
});

test("failed conflict refresh preserves a draft and can be retried", async ({
  page,
}) => {
  const { data } = await setup(page);
  data.submissionStatus = "DRAFT";
  data.receipt.confirmedAt = null;
  await page.route("**/source", (route) =>
    problem(route, 409, "CASE_VERSION_CONFLICT"),
  );
  await page.goto(`/#/cases/${data.caseId}/source`);
  const text = page.getByLabel("Текст обращения", { exact: true });
  await text.fill("Не горит свет на втором этаже. Мой сохранённый ввод.");
  await page
    .getByRole("button", { name: "Сохранить текст", exact: true })
    .click();
  let fails = true;
  await page.route(`**/api/v1/cases/${data.caseId}`, (route) =>
    fails
      ? problem(route, 503, "SERVICE_UNAVAILABLE")
      : route.fulfill({ json: { ...data, caseVersion: 6 } }),
  );
  await page.getByRole("button", { name: refreshName }).click();
  await expect(page.getByRole("button", { name: refreshName })).toBeEnabled();
  await expect(text).toHaveValue(
    "Не горит свет на втором этаже. Мой сохранённый ввод.",
  );
  await expect(page.getByRole("button", { name: adoptName })).toHaveCount(0);
  fails = false;
  await page.getByRole("button", { name: refreshName }).click();
  await page.getByRole("button", { name: adoptName }).click();
  await expect(text).toHaveValue(
    "Не горит свет на втором этаже. Мой сохранённый ввод.",
  );
  await expect(
    page.getByRole("button", { name: "Сохранить текст", exact: true }),
  ).toBeEnabled();
});

test("confirmation recovers from a definite server rejection without permanent button locking", async ({
  page,
}) => {
  const { data } = await setup(page);
  data.submissionStatus = "DRAFT";
  data.receipt.confirmedAt = null;
  let attempts = 0;
  await page.route("**/confirm", (route) => {
    if (++attempts === 1) return problem(route, 422, "INVALID_TRANSITION");
    data.submissionStatus = "CONFIRMED";
    data.receipt.confirmedAt = new Date().toISOString();
    data.caseVersion++;
    return route.fulfill({ json: data });
  });
  await page.goto(`/#/cases/${data.caseId}/review`);
  const submit = page.getByRole("button", {
    name: "Подтвердить список",
    exact: true,
  });
  await submit.click();
  await expect(page.getByRole("alert")).toBeVisible();
  await expect(submit).toBeEnabled();
  await submit.click();
  await expect(
    page.getByRole("heading", { name: "Список подтверждён", exact: true }),
  ).toBeVisible();
  expect(attempts).toBe(2);
});

test("next action definite rejection keeps the review usable", async ({
  page,
}) => {
  const { data } = await setup(page, true);
  await page.route("**/next-action", (route) =>
    problem(route, 422, "INVALID_FIELDS"),
  );
  await page.goto(
    `/#/cases/${data.caseId}/items/${data.items[0].itemId}/next-action`,
  );
  await page
    .getByLabel("Что будет сделано дальше?")
    .fill("Проверить освещение");
  await page.getByRole("button", { name: "Проверить перед отправкой" }).click();
  await page.getByRole("button", { name: "Подтвердить отправку" }).click();
  await expect(page.getByRole("alert")).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Подтвердить отправку" }),
  ).toBeEnabled();
  await page.getByRole("button", { name: "Изменить ответ" }).click();
  await expect(page.getByLabel("Что будет сделано дальше?")).toHaveValue(
    "Проверить освещение",
  );
});

test("report link conflict refreshes the report and preserves still available choices", async ({
  page,
}) => {
  const { data, report } = await setup(page, true);
  let reportReads = 0;
  await page.route(`**/api/v1/reports/${report.reportId}`, (route) => {
    reportReads++;
    return route.fulfill({ json: report });
  });
  await page.route("**/links", (route) => {
    data.caseVersion++;
    data.items[0].itemVersion++;
    return problem(route, 409, "CASE_VERSION_CONFLICT");
  });
  await page.goto(`/#/cases/${data.caseId}/reports/${report.reportId}`);
  await page.getByRole("checkbox", { name: "Не горит свет Этаж 3" }).check();
  await page.getByRole("button", { name: "Проверить выбранные связи" }).click();
  await page
    .getByRole("button", { name: "Подтвердить выбранные связи" })
    .click();
  const beforeRefresh = reportReads;
  await page.getByRole("button", { name: refreshName }).click();
  await page.getByRole("button", { name: adoptName }).click();
  await expect(
    page.getByRole("checkbox", { name: "Не горит свет Этаж 3" }),
  ).toBeChecked();
  await expect(
    page.getByRole("button", { name: "Проверить выбранные связи" }),
  ).toBeEnabled();
  expect(reportReads).toBeGreaterThan(beforeRefresh);
});

test("revoked case access during conflict review hides the cached form", async ({
  page,
}) => {
  const { data } = await setup(page);
  data.submissionStatus = "DRAFT";
  data.receipt.confirmedAt = null;
  await page.route("**/source", (route) =>
    problem(route, 409, "CASE_VERSION_CONFLICT"),
  );
  await page.goto(`/#/cases/${data.caseId}/source`);
  await page
    .getByLabel("Текст обращения", { exact: true })
    .fill("Ввод до отзыва доступа");
  await page
    .getByRole("button", { name: "Сохранить текст", exact: true })
    .click();
  await page.route(`**/api/v1/cases/${data.caseId}`, (route) =>
    problem(route, 404, "RESOURCE_NOT_FOUND"),
  );
  await page.getByRole("button", { name: refreshName }).click();
  await expect(page.getByLabel("Текст обращения", { exact: true })).toHaveCount(
    0,
  );
  await expect(page.getByRole("alert")).toBeVisible();
});

test("a direct case selects its authorised house and keeps it across profile navigation", async ({
  page,
}) => {
  const { data, me } = await setup(page);
  me.memberships.unshift({
    ...me.memberships[0],
    houseId: "00000000-0000-0000-0000-000000000099",
    address: "Другой учебный дом",
  });
  await page.goto(`/#/cases/${data.caseId}`);
  await expect(
    page.getByRole("heading", {
      name: `Обращение №${data.displayNumber}`,
      exact: true,
    }),
  ).toBeVisible();
  const navigation = page.getByRole("navigation", {
    name: "Основная навигация",
  });
  await navigation.getByRole("link", { name: "Профиль", exact: true }).click();
  await navigation
    .getByRole("link", { name: "Обращения", exact: true })
    .click();
  await expect(
    page.getByRole("combobox", { name: "Дом", exact: true }),
  ).toHaveValue(data.houseId);
  await expect(
    page.getByRole("link", { name: "Новое обращение", exact: true }),
  ).toHaveAttribute("href", `#/new?house=${data.houseId}`);
});
