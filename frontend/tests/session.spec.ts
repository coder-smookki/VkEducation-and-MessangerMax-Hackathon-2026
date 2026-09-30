import { test, expect } from "@playwright/test";
import type { Page } from "@playwright/test";
import meFixture from "../../contracts/examples/me-response.json" with { type: "json" };
import caseFixture from "../../contracts/examples/case-view-target.json" with { type: "json" };

// Synthetic identities exist only at this test boundary. Runtime still obtains
// its session and memberships from Java; client launch age grants no authority.
async function setup(page: Page, launchAgeSeconds = 0) {
  const state = {
    me: structuredClone(meFixture),
    authenticated: true,
    authRequests: [] as string[],
    houseReads: [] as string[],
    commands: [] as Record<string, unknown>[],
  };
  await page.addInitScript((age) => {
    window.WebApp = {
      initData: new URLSearchParams({
        auth_date: String(Math.floor(Date.now() / 1000) - age),
        hash: "synthetic-signature-test-only",
      }).toString(),
    };
  }, launchAgeSeconds);
  await page.route("https://st.max.ru/**", (route) =>
    route.fulfill({ body: "", contentType: "application/javascript" }),
  );
  await page.route("**/api/v1/**", (route) => {
    const url = new URL(route.request().url());
    if (url.pathname === "/api/v1/csrf")
      return route.fulfill({
        json: { headerName: "X-XSRF-TOKEN", token: "test-csrf" },
      });
    if (url.pathname === "/api/v1/auth/max") {
      const raw = route.request().postDataJSON().initData as string;
      state.authRequests.push(raw);
      const age =
        Date.now() / 1000 - Number(new URLSearchParams(raw).get("auth_date"));
      if (age >= 300)
        return route.fulfill({
          status: 401,
          json: { code: "INVALID_MAX_INIT_DATA" },
        });
      state.authenticated = true;
      return route.fulfill({ json: state.me });
    }
    if (url.pathname === "/api/v1/me")
      return state.authenticated
        ? route.fulfill({ json: state.me })
        : route.fulfill({ status: 401, json: { code: "AUTH_REQUIRED" } });
    if (url.pathname === "/api/v1/cases") {
      if (route.request().method() === "POST") {
        const body = route.request().postDataJSON();
        state.commands.push(body);
        return state.authenticated
          ? route.fulfill({
              status: 201,
              json: {
                ...caseFixture,
                houseId: body.houseId,
                submissionStatus: "DRAFT",
              },
            })
          : route.fulfill({ status: 401, json: { code: "AUTH_REQUIRED" } });
      }
      state.houseReads.push(url.searchParams.get("houseId")!);
      return route.fulfill({ json: { items: [], nextCursor: null } });
    }
    return route.fulfill({ status: 404, json: { code: "RESOURCE_NOT_FOUND" } });
  });
  return state;
}

test("expired session with 30-minute launch preserves input and offers a real MAX relaunch", async ({
  page,
}) => {
  const state = await setup(page, 30 * 60);
  await page.goto("/#/new");
  const input = page.getByLabel("Текст обращения");
  await input.fill("Несохранённое описание после получаса работы");
  state.authenticated = false;
  await page
    .getByRole("button", { name: "Сохранить и перейти к пунктам" })
    .click();
  const dialog = page.getByRole("dialog", { name: "Войдите снова" });
  await expect(dialog).toBeVisible();
  await expect(
    dialog.getByText("Данные запуска MAX устарели.", { exact: false }),
  ).toBeVisible();
  await expect(
    dialog.getByRole("link", { name: "Открыть заново в MAX" }),
  ).toHaveAttribute("href", "https://max.ru/t109_hakaton_max_bot?startapp");
  await expect(
    dialog.getByRole("button", { name: "Войти через MAX", exact: true }),
  ).toHaveCount(0);
  await expect(input).toHaveValue(
    "Несохранённое описание после получаса работы",
  );
  await expect(input).toBeDisabled();
  const copy = dialog.getByLabel("Текст для копирования", { exact: true });
  await expect(copy).toHaveCount(0);
  await dialog
    .getByRole("button", { name: "Подготовить текст для копирования" })
    .click();
  await expect(copy).toHaveValue(
    /Несохранённое описание после получаса работы/,
  );
  await expect(copy).toBeEnabled();
  await expect(copy).toHaveJSProperty("readOnly", true);
  await copy.focus();
  await expect(copy).toBeFocused();
  expect(
    await copy.evaluate((element: HTMLTextAreaElement) => ({
      start: element.selectionStart,
      end: element.selectionEnd,
      length: element.value.length,
    })),
  ).toMatchObject({ start: 0, end: (await copy.inputValue()).length });
  await expect(copy).not.toHaveValue(/auth_date|synthetic-signature-test-only/);
  await expect(input).toHaveValue(
    "Несохранённое описание после получаса работы",
  );
  expect(state.authRequests).toEqual([]);
  await dialog.getByRole("button", { name: "Вернуться к вводу" }).click();
  await expect(dialog).toHaveCount(0);
  await page.getByRole("button", { name: "Повторить ту же отправку" }).click();
  await expect(dialog).toBeVisible();
  await expect(input).toHaveValue(
    "Несохранённое описание после получаса работы",
  );
  expect(state.authRequests).toEqual([]);
  expect(state.commands).toHaveLength(2);
  expect(state.commands[0]).toEqual(state.commands[1]);
});

test("fresh MAX relaunch authenticates after startup me returns 401", async ({
  page,
}) => {
  const state = await setup(page);
  state.authenticated = false;
  await page.goto("/");
  await expect(
    page.getByRole("heading", { name: "Мои обращения", exact: true }),
  ).toBeVisible();
  expect(state.authRequests).toHaveLength(1);
  expect(
    new URLSearchParams(state.authRequests[0]).get("auth_date"),
  ).toBeTruthy();
  await expect(page.getByRole("dialog")).toHaveCount(0);
});

test("access refresh after expiration requests relaunch instead of silently retaining stale access", async ({
  page,
}) => {
  const state = await setup(page, 30 * 60);
  await page.goto("/#/access");
  await expect(
    page.getByRole("heading", { name: "Подключённые дома", exact: true }),
  ).toBeVisible();
  state.authenticated = false;
  await page
    .getByRole("button", { name: "Обновить доступ", exact: true })
    .click();
  const dialog = page.getByRole("dialog", { name: "Войдите снова" });
  await expect(dialog).toBeVisible();
  await expect(
    dialog.getByRole("link", { name: "Открыть заново в MAX" }),
  ).toHaveAttribute("href", "https://max.ru/t109_hakaton_max_bot?startapp");
  expect(state.authRequests).toEqual([]);
});

test("second house survives profile and access navigation and owns the new case", async ({
  page,
}) => {
  const state = await setup(page);
  const second = state.me.memberships[1];
  second.role = "RESIDENT";
  await page.goto("/");
  await page.getByLabel("Дом", { exact: true }).selectOption(second.houseId);
  await expect(
    page.getByRole("heading", { name: "Мои обращения", exact: true }),
  ).toBeVisible();
  await page.getByRole("link", { name: "Профиль", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "Профиль", exact: true }),
  ).toBeVisible();
  await page.getByRole("link", { name: "Обращения", exact: true }).click();
  await expect(page.getByLabel("Дом", { exact: true })).toHaveValue(
    second.houseId,
  );
  await page.getByRole("link", { name: "Доступ к дому", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "Доступ к дому", exact: true }),
  ).toBeVisible();
  await page.getByRole("link", { name: "Обращения", exact: true }).click();
  await expect(page.getByLabel("Дом", { exact: true })).toHaveValue(
    second.houseId,
  );
  await page
    .getByRole("link", { name: "Новое обращение", exact: true })
    .click();
  await expect(page.getByText(second.address, { exact: true })).toBeVisible();
  await page.getByLabel("Текст обращения").fill("Обращение во второй дом");
  await page
    .getByRole("button", { name: "Сохранить и перейти к пунктам" })
    .click();
  await expect(
    page.getByRole("heading", { name: "Черновик сохранён", exact: true }),
  ).toBeVisible();
  expect(state.commands).toHaveLength(1);
  expect(state.commands[0].houseId).toBe(second.houseId);
  expect(state.houseReads.at(-1)).toBe(second.houseId);
});

test("dispatcher workspace survives navigation, prevents resident creation and switches with the house role", async ({
  page,
}) => {
  const state = await setup(page);
  const [resident, dispatcher] = state.me.memberships;
  await page.goto(`/#/?house=${dispatcher.houseId}`);
  await expect(
    page.getByRole("heading", { name: "Очередь обращений", exact: true }),
  ).toBeVisible();
  await page.getByRole("link", { name: "Профиль", exact: true }).click();
  await page.getByRole("link", { name: "Очередь", exact: true }).click();
  await expect(page.getByLabel("Дом", { exact: true })).toHaveValue(
    dispatcher.houseId,
  );
  await page.getByRole("link", { name: "Дома", exact: true }).click();
  await page.getByRole("link", { name: "Очередь", exact: true }).click();
  await expect(page.getByLabel("Дом", { exact: true })).toHaveValue(
    dispatcher.houseId,
  );
  await page.goto(`/#/new?house=${dispatcher.houseId}`);
  await expect(
    page.getByRole("heading", { name: "Очередь обращений", exact: true }),
  ).toBeVisible();
  await expect(page.getByLabel("Текст обращения")).toHaveCount(0);
  expect(state.commands).toHaveLength(0);
  await page.getByLabel("Дом", { exact: true }).selectOption(resident.houseId);
  await expect(
    page.getByRole("link", { name: "Новое обращение", exact: true }),
  ).toHaveAttribute("href", `#/new?house=${resident.houseId}`);
  await expect(
    page
      .locator(".app-nav")
      .getByRole("link", { name: "Обращения", exact: true }),
  ).toBeVisible();
});

test("revoked selected membership cannot survive refresh or a forged house URL", async ({
  page,
}) => {
  const state = await setup(page);
  const [first, revoked] = state.me.memberships;
  await page.goto(`/#/?house=${revoked.houseId}`);
  await expect(page.getByLabel("Дом", { exact: true })).toHaveValue(
    revoked.houseId,
  );
  await page.getByRole("link", { name: "Дома", exact: true }).click();
  state.me.memberships = [first];
  await page
    .getByRole("button", { name: "Обновить доступ", exact: true })
    .click();
  await expect(
    page.getByRole("link", {
      name: new RegExp(revoked.address.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")),
    }),
  ).toHaveCount(0);
  await page.getByRole("link", { name: "Обращения", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "Мои обращения", exact: true }),
  ).toBeVisible();
  await expect(page.getByText(first.address, { exact: true })).toBeVisible();
  const readCount = state.houseReads.length;
  await page.evaluate((house) => {
    window.location.hash = `/?house=${house}`;
  }, revoked.houseId);
  await expect(page.getByText(first.address, { exact: true })).toBeVisible();
  await expect(
    page.getByRole("link", { name: "Новое обращение", exact: true }),
  ).toHaveAttribute("href", `#/new?house=${first.houseId}`);
  expect(state.houseReads.slice(readCount)).not.toContain(revoked.houseId);
  await page
    .getByRole("link", { name: "Новое обращение", exact: true })
    .click();
  await expect(page.getByText(first.address, { exact: true })).toBeVisible();
});
