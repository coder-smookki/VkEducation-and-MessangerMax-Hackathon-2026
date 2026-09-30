import { test, expect } from "@playwright/test";
import type { Page } from "@playwright/test";
import type { Me } from "../src/api/client";
import meFixture from "../../contracts/examples/me-response.json" with { type: "json" };

const avatar = "https://i.oneme.ru/synthetic-profile-test.png";
const pixel = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/l9sAAAAASUVORK5CYII=",
  "base64",
);
async function profile(page: Page, fields: Partial<Me>) {
  const me = {
    ...structuredClone(meFixture),
    username: null,
    avatarUrl: null,
    ...fields,
  };
  await page.route("https://st.max.ru/**", (route) =>
    route.fulfill({ body: "", contentType: "application/javascript" }),
  );
  await page.route("**/api/v1/me", (route) => route.fulfill({ json: me }));
  return me;
}

test("profile displays verified MAX name, username and a loaded photo without a referrer", async ({
  page,
}) => {
  await profile(page, {
    displayName: "Анна Котова",
    username: "anna_test",
    avatarUrl: avatar,
  });
  let referer: string | undefined;
  await page.route(avatar, (route) => {
    referer = route.request().headers().referer;
    return route.fulfill({ contentType: "image/png", body: pixel });
  });
  await page.goto("/#/profile");
  await expect(
    page.getByRole("heading", { name: "Анна Котова", exact: true }),
  ).toBeVisible();
  await expect(page.getByText("@anna_test", { exact: true })).toBeVisible();
  const photo = page.getByRole("img", { name: "Фото профиля Анна Котова" });
  await expect
    .poll(() =>
      photo.evaluate(
        (image: HTMLImageElement) => image.complete && image.naturalWidth > 0,
      ),
    )
    .toBe(true);
  expect(referer).toBeUndefined();
  await page.reload();
  await expect(photo).toBeVisible();
  await expect(page.getByText("@anna_test", { exact: true })).toBeVisible();
});

test("unavailable MAX photo keeps the profile usable with its SVG fallback", async ({
  page,
}) => {
  await profile(page, { displayName: "Анна Котова", avatarUrl: avatar });
  await page.route(avatar, (route) => route.abort());
  await page.goto("/#/profile");
  await expect(
    page.getByRole("heading", { name: "Анна Котова", exact: true }),
  ).toBeVisible();
  await expect(page.locator(".profile-avatar img")).toHaveCount(0);
  await expect(page.locator(".profile-avatar svg")).toBeVisible();
  await expect(
    page.getByRole("link", { name: "Подключить дом", exact: true }),
  ).toBeVisible();
});

test("profile handles a missing photo and long username without overflow at enlarged text", async ({
  page,
}) => {
  const username = "synthetic_".repeat(12);
  await profile(page, { displayName: null, username });
  await page.setViewportSize({ width: 320, height: 844 });
  await page.goto("/#/profile");
  await expect(
    page.getByRole("heading", { name: `@${username}`, exact: true }),
  ).toBeVisible();
  await page.evaluate(() => {
    document.documentElement.style.fontSize = "36px";
  });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await expect(page.locator(".profile-avatar svg")).toBeVisible();
  await expect(page.locator(".profile-avatar img")).toHaveCount(0);
});
