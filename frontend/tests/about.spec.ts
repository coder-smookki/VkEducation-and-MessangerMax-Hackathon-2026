import { test, expect } from "@playwright/test";
import type { Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import meFixture from "../../contracts/examples/me-response.json" with { type: "json" };

async function setup(page: Page, authenticated = true) {
  const me = structuredClone(meFixture);
  me.memberships[0].address = "Учебный дом №1";
  const writes: string[] = [];
  await page.route("https://st.max.ru/**", (route) =>
    route.fulfill({ body: "", contentType: "application/javascript" }),
  );
  await page.route("**/api/v1/**", (route) => {
    if (route.request().method() !== "GET") writes.push(route.request().url());
    if (new URL(route.request().url()).pathname === "/api/v1/me") {
      return authenticated
        ? route.fulfill({ json: me })
        : route.fulfill({ status: 401, json: { code: "AUTH_REQUIRED" } });
    }
    return route.fulfill({ status: 404, json: { code: "NOT_FOUND" } });
  });
  return writes;
}

test("about explains actual data, deletion and emergency limits without issuing commands", async ({
  page,
}) => {
  const writes = await setup(page);
  await page.goto("/#/about");
  await expect(
    page.getByRole("heading", { name: "О сервисе", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByText("ДомПульс не является аварийной службой"),
  ).toBeVisible();
  await expect(
    page.getByRole("link", { name: "112", exact: true }),
  ).toHaveAttribute("href", "tel:112");
  await expect(
    page.getByRole("link", { name: "smookki@yandex.ru", exact: true }),
  ).toHaveAttribute("href", "mailto:smookki@yandex.ru");
  await expect(
    page.getByRole("link", { name: "Написать команде" }),
  ).toHaveAttribute("href", "mailto:smookki@yandex.ru");
  const deletion = page.getByRole("link", {
    name: "Запросить удаление по почте",
  });
  const href = await deletion.getAttribute("href");
  expect(href?.split("?")[0]).toBe("mailto:smookki@yandex.ru");
  expect(new URL(href!).searchParams.get("subject")).toBe(
    "ДомПульс: запрос на удаление данных",
  );
  expect(new URL(href!).searchParams.has("body")).toBe(false);
  await expect(
    page.getByText(
      "Архив, отзыв обращения и выход из аккаунта не удаляют сохранённые данные.",
      { exact: false },
    ),
  ).toBeVisible();
  await expect(
    page.getByText(
      "Не отправляйте пароли, токены и служебные данные входа (initData).",
      { exact: false },
    ),
  ).toBeVisible();
  await expect(page.getByRole("button", { name: /Удалить/ })).toHaveCount(0);
  expect(writes).toEqual([]);
});

test("about is available before MAX sign-in and the training notice follows only the build flag", async ({
  page,
}) => {
  await setup(page, false);
  await page.goto("/#/about");
  await expect(
    page.getByRole("heading", { name: "О сервисе", exact: true }),
  ).toBeVisible();
  const banner = page.getByRole("region", { name: "Тестовый стенд" });
  if (process.env.VITE_TRAINING_MODE === "true") {
    await expect(banner).toBeVisible();
    await expect(
      banner.getByText("Учебный дом, данные тестовые"),
    ).toBeVisible();
  } else {
    await expect(banner).toHaveCount(0);
  }
});

test("about remains readable at 320px in both themes and with large text", async ({
  page,
}) => {
  await setup(page);
  await page.setViewportSize({ width: 320, height: 740 });
  await page.goto("/#/about");
  for (const theme of ["light", "dark"] as const) {
    await page.evaluate((selected) => {
      localStorage.setItem(
        "koren:preferences",
        JSON.stringify({
          theme: selected,
          large: true,
          reduceMotion: true,
        }),
      );
    }, theme);
    await page.reload();
    await expect(
      page.getByRole("heading", { name: "О сервисе", exact: true }),
    ).toBeVisible();
    await expect(page.locator("html")).toHaveAttribute("data-theme", theme);
    for (const fontSize of [20, 36]) {
      await page.evaluate((value) => {
        document.documentElement.style.fontSize = `${value}px`;
      }, fontSize);
      const geometry = await page.evaluate(() => ({
        viewport: document.documentElement.clientWidth,
        page: document.documentElement.scrollWidth,
        cards: [...document.querySelectorAll(".about-page .card")].map(
          (element) => ({
            width: element.clientWidth,
            contents: element.scrollWidth,
          }),
        ),
      }));
      expect(geometry.page).toBeLessThanOrEqual(geometry.viewport);
      for (const card of geometry.cards)
        expect(card.contents).toBeLessThanOrEqual(card.width);
      const email = page.getByRole("link", {
        name: "Запросить удаление по почте",
      });
      await email.scrollIntoViewIfNeeded();
      await email.focus();
      await expect(email).toBeFocused();
      await expect(email).toBeVisible();
    }
    const audit = await new AxeBuilder({ page })
      .include(".about-page")
      .withTags(["wcag2a", "wcag2aa", "wcag21aa"])
      .analyze();
    expect(audit.violations).toEqual([]);
  }
});
