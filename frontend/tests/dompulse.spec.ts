import { test, expect } from "@playwright/test";
import type { Page } from "@playwright/test";
import type { Case } from "../src/api/client";
import meFixture from "../../contracts/examples/me-response.json" with { type: "json" };
import caseFixture from "../../contracts/examples/case-view-target.json" with { type: "json" };

const firstHouse = meFixture.memberships[0].houseId;
const secondHouse = meFixture.memberships[1].houseId;
function caseData(number: number, symptom: string): Case {
  const value = structuredClone(caseFixture) as Case;
  value.caseId = `00000000-0000-0000-0000-${String(number).padStart(12, "0")}`;
  value.displayNumber = `ТЕСТ-${number}`;
  value.sourceText = symptom;
  value.items[0].symptom = symptom;
  return value;
}
async function session(page: Page) {
  const me = structuredClone(meFixture);
  me.memberships.forEach((membership) => (membership.role = "RESIDENT"));
  await page.route("https://st.max.ru/**", (route) =>
    route.fulfill({ body: "", contentType: "application/javascript" }),
  );
  await page.route("**/api/v1/me", (route) => route.fulfill({ json: me }));
  return me;
}

for (const dispatcher of [false, true]) {
  test(`search exposes a matching second item and excludes unrelated cards: ${dispatcher ? "dispatcher" : "resident"}`, async ({
    page,
  }) => {
    const me = await session(page);
    if (dispatcher) {
      me.memberships[0].role = "DISPATCHER";
      await page.route("**/api/v1/me", (route) => route.fulfill({ json: me }));
    }
    const mixed = caseData(81, "Дверь не закрывается");
    mixed.items.reverse();
    mixed.items[0].symptom = "Дверь не закрывается";
    mixed.items[1].symptom = "Не горит свет";
    mixed.items[1].location = "Третий этаж";
    mixed.sourceText = "Дверь сломана, свет не горит";
    const draft = caseData(82, "Сломана дверь. Не горит свет в коридоре.");
    draft.submissionStatus = "DRAFT";
    draft.items = [];
    const unrelated = caseData(83, "Течёт кран");
    unrelated.items = [unrelated.items[0]];
    unrelated.items[0].category = "OTHER";
    unrelated.items[0].location = "Кухня";
    await page.route("**/api/v1/cases?**", (route) =>
      route.fulfill({
        json: { items: [mixed, draft, unrelated], nextCursor: null },
      }),
    );
    await page.goto("/");
    await page
      .getByRole("button", { name: "Поиск обращений", exact: true })
      .click();
    const search = page.getByRole("searchbox", {
      name: "Поиск по загруженным обращениям",
    });
    const links = page.locator(
      ".case-list h2 a, .queue-table td:first-child > a",
    );
    await search.fill("  СВЕТ  ");
    await expect(links).toHaveCount(2);
    await expect(
      page.getByRole("link", { name: "Не горит свет", exact: true }),
    ).toBeVisible();
    await expect(
      page.getByText("Пункт 2 · Освещение", { exact: false }),
    ).toBeVisible();
    await expect(
      page.getByRole("link", { name: "Течёт кран", exact: true }),
    ).toHaveCount(0);
    await search.fill("третий");
    await expect(links).toHaveCount(1);
    await expect(
      page.getByRole("link", { name: "Не горит свет", exact: true }),
    ).toBeVisible();
    await search.fill("ТЕСТ-82");
    await expect(links).toHaveCount(1);
    await search.fill("освещение");
    await expect(links).toHaveCount(1);
    await search.fill("течет");
    await expect(links).toHaveCount(1);
    await expect(
      page.getByRole("link", { name: "Течёт кран", exact: true }),
    ).toBeVisible();
    await search.fill("несуществующая проблема");
    await expect(links).toHaveCount(0);
    await expect(
      page.getByRole("heading", {
        name: "Под этот фильтр пока ничего не подходит",
      }),
    ).toBeVisible();
    await search.fill("");
    await expect(links).toHaveCount(3);
  });
}

test("step and navigation labels keep whole words at narrow widths and enlarged text", async ({
  page,
}) => {
  await session(page);
  await page.setViewportSize({ width: 320, height: 844 });
  await page.goto(`/#/new?house=${firstHouse}`);
  await expect(
    page.getByRole("heading", { name: "Что случилось?", exact: true }),
  ).toBeVisible();
  for (const fontSize of [16, 20, 36]) {
    await page.evaluate((size) => {
      document.documentElement.style.fontSize = `${size}px`;
    }, fontSize);
    const wrappedWords = await page.evaluate(() => {
      const issues: string[] = [];
      const labels = document.querySelectorAll(
        ".creation-steps li > span:last-child, .app-nav a > span",
      );
      for (const label of labels) {
        const text = label.firstChild;
        if (!text || text.nodeType !== Node.TEXT_NODE) continue;
        for (const word of text.textContent!.matchAll(/\S+/g)) {
          const range = document.createRange();
          range.setStart(text, word.index!);
          range.setEnd(text, word.index! + word[0].length);
          const lines = new Set(
            Array.from(range.getClientRects(), (rect) => Math.round(rect.top)),
          );
          if (lines.size > 1) issues.push(word[0]);
        }
      }
      return issues;
    });
    expect(wrappedWords, `font ${fontSize}px`).toEqual([]);
    expect(
      await page.evaluate(
        () =>
          document.documentElement.scrollWidth -
          document.documentElement.clientWidth,
      ),
    ).toBeLessThanOrEqual(1);
    for (const label of ["Обращения", "Доступ к дому", "Профиль"]) {
      const link = page
        .locator(".app-nav")
        .getByRole("link", { name: label, exact: true });
      await link.scrollIntoViewIfNeeded();
      await expect
        .poll(() =>
          link.evaluate((element) => {
            const rect = element.getBoundingClientRect();
            const hit = document.elementFromPoint(
              rect.x + rect.width / 2,
              rect.y + rect.height / 2,
            );
            return !!hit && element.contains(hit);
          }),
        )
        .toBe(true);
    }
  }
});

test("search text clears its icon at narrow widths and enlarged text", async ({
  page,
}) => {
  await session(page);
  const data = caseData(26, "Не работает освещение");
  await page.route("**/api/v1/cases?**", (route) =>
    route.fulfill({ json: { items: [data], nextCursor: null } }),
  );
  await page.goto("/");
  await page
    .getByRole("button", { name: "Поиск обращений", exact: true })
    .click();
  const search = page.getByRole("searchbox", {
    name: "Поиск по загруженным обращениям",
  });
  await expect(search).toHaveAttribute("placeholder", "Номер, тема или место");

  for (const width of [320, 390, 1440]) {
    await page.setViewportSize({ width, height: 844 });
    for (const fontSize of [16, 20, 36]) {
      await page.evaluate((size) => {
        document.documentElement.style.fontSize = `${size}px`;
      }, fontSize);
      await search.scrollIntoViewIfNeeded();
      const geometry = await search.evaluate((element) => {
        const input = element.getBoundingClientRect();
        const style = getComputedStyle(element);
        const icon = element
          .parentElement!.querySelector("svg")!
          .getBoundingClientRect();
        return {
          gap:
            input.left +
            parseFloat(style.borderLeftWidth) +
            parseFloat(style.paddingLeft) -
            icon.right,
          centerOffset: Math.abs(
            icon.top + icon.height / 2 - (input.top + input.height / 2),
          ),
          right: input.right,
        };
      });
      expect(
        geometry.gap,
        `${width}px, font ${fontSize}px`,
      ).toBeGreaterThanOrEqual(8);
      expect(geometry.centerOffset).toBeLessThanOrEqual(1);
      expect(geometry.right).toBeLessThanOrEqual(width);
    }
  }

  await search.fill("освещение");
  await expect(search).toBeFocused();
  await expect(
    page.getByRole("link", { name: "Не работает освещение", exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Обновить", exact: true }).click();
  await expect(search).toHaveValue("освещение");
});

test("local search keeps pagination reachable and counts only loaded cases", async ({
  page,
}) => {
  await session(page);
  const draft = caseData(21, "Черновик о двери");
  draft.submissionStatus = "DRAFT";
  draft.receipt = { confirmedAt: null, acceptedAt: null, acceptedBy: null };
  const withdrawn = caseData(22, "Свет восстановился, дверь отозвана");
  withdrawn.progress = {
    total: 2,
    confirmed: 1,
    remaining: 0,
    withdrawn: 1,
    allConfirmed: false,
  };
  withdrawn.items[0].observation = "RESTORED";
  withdrawn.items[1].administrativeStatus = "WITHDRAWN";
  withdrawn.items[1].administrativeReason = "Тестовый отзыв автора";
  const restored = caseData(23, "Уборка лестницы");
  restored.progress = {
    total: 2,
    confirmed: 2,
    remaining: 0,
    withdrawn: 0,
    allConfirmed: true,
  };
  restored.items.forEach((item) => (item.observation = "RESTORED"));
  const reads: URL[] = [];
  await page.route("**/api/v1/cases?**", (route) => {
    const url = new URL(route.request().url());
    reads.push(url);
    return route.fulfill({
      json: url.searchParams.has("cursor")
        ? { items: [restored], nextCursor: null }
        : { items: [draft, withdrawn], nextCursor: "opaque-next-page" },
    });
  });
  await page.goto("/");
  await expect(
    page.getByRole("button", { name: /^Восстановлено\s*0$/ }),
  ).toBeVisible();
  await expect(
    page.getByText("Поиск и счётчики относятся к загруженным карточкам.", {
      exact: false,
    }),
  ).toBeVisible();
  // StrictMode may cancel and restart the initial request. Count only new
  // traffic caused by the interactions below, independently of that startup.
  const initialReads = reads.length;
  await page.getByRole("button", { name: /^Восстановлено/ }).click();
  await expect(
    page.getByRole("heading", {
      name: "Под этот фильтр пока ничего не подходит",
    }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Загрузить ещё" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Сбросить фильтр" }).click();
  await page
    .getByRole("button", { name: "Поиск обращений", exact: true })
    .click();
  await page
    .getByRole("searchbox", { name: "Поиск по загруженным обращениям" })
    .fill("УБОРКА");
  await expect(
    page.getByRole("heading", {
      name: "Под этот фильтр пока ничего не подходит",
    }),
  ).toBeVisible();
  expect(reads).toHaveLength(initialReads);
  await page.getByRole("button", { name: "Загрузить ещё" }).click();
  await expect(
    page.getByRole("link", { name: "Уборка лестницы", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: /^Восстановлено\s*1$/ }),
  ).toBeVisible();
  await expect(page.getByRole("button", { name: "Загрузить ещё" })).toHaveCount(
    0,
  );
  expect(reads).toHaveLength(initialReads + 1);
  expect(reads.at(-1)!.searchParams.get("cursor")).toBe("opaque-next-page");
  expect(
    reads.every(
      (url) =>
        url.searchParams.get("houseId") === firstHouse &&
        !url.searchParams.has("queueState") &&
        !url.searchParams.has("search"),
    ),
  ).toBe(true);
  // Closing search removes the local text filter, without another server request.
  await page
    .getByRole("button", { name: "Поиск обращений", exact: true })
    .click();
  await expect(page.getByRole("searchbox")).toBeHidden();
  await expect(
    page.getByRole("link", { name: "Черновик о двери", exact: true }),
  ).toBeVisible();
  expect(reads).toHaveLength(initialReads + 1);
});

test("changing house clears local filters and never searches another house's cached cards", async ({
  page,
}) => {
  await session(page);
  const first = caseData(31, "Не горит свет в первом доме");
  const second = caseData(32, "Не закрывается дверь во втором доме");
  second.houseId = secondHouse;
  await page.route("**/api/v1/cases?**", (route) =>
    route.fulfill({
      json: {
        items: [
          new URL(route.request().url()).searchParams.get("houseId") ===
          firstHouse
            ? first
            : second,
        ],
        nextCursor: null,
      },
    }),
  );
  await page.goto("/");
  await page
    .getByRole("button", { name: "Поиск обращений", exact: true })
    .click();
  await page.getByRole("searchbox").fill("первом");
  await page.getByRole("button", { name: /^Черновики/ }).click();
  await expect(
    page.getByRole("heading", {
      name: "Под этот фильтр пока ничего не подходит",
    }),
  ).toBeVisible();
  await page
    .getByRole("combobox", { name: "Дом", exact: true })
    .selectOption(secondHouse);
  await expect(page.getByRole("searchbox")).toHaveValue("");
  await expect(page.getByRole("button", { name: /^Все\s*1$/ })).toHaveAttribute(
    "aria-pressed",
    "true",
  );
  await expect(
    page.getByRole("link", { name: second.items[0].symptom, exact: true }),
  ).toBeVisible();
  await expect(
    page.getByText(first.items[0].symptom, { exact: true }),
  ).toHaveCount(0);
});

test("loading is animated only while pending and respects system and user reduced motion", async ({
  page,
}) => {
  const me = await session(page);
  let release!: () => void;
  let pending = new Promise<void>((resolve) => (release = resolve));
  await page.route("**/api/v1/me", async (route) => {
    await pending;
    await route.fulfill({ json: me });
  });
  await page.route("**/api/v1/cases?**", (route) =>
    route.fulfill({ json: { items: [], nextCursor: null } }),
  );
  await page.emulateMedia({ reducedMotion: "no-preference" });
  await page.goto("/");
  const loader = page.locator(".brand-loading-track > span");
  await expect(page.getByRole("status")).toContainText("Загружаем ваш комфорт");
  await expect(loader).toBeVisible();
  await expect
    .poll(() =>
      loader.evaluate((element) => getComputedStyle(element).animationName),
    )
    .not.toBe("none");
  await page.emulateMedia({ reducedMotion: "reduce" });
  await expect
    .poll(() =>
      loader.evaluate((element) => getComputedStyle(element).animationName),
    )
    .toBe("none");
  release();
  await expect(
    page.getByRole("heading", { name: "Мои обращения", exact: true }),
  ).toBeVisible();
  await expect(loader).toHaveCount(0);
  await page.emulateMedia({ reducedMotion: "no-preference" });
  await page.evaluate(() =>
    localStorage.setItem(
      "koren:preferences",
      JSON.stringify({ theme: "light", large: false, reduceMotion: true }),
    ),
  );
  pending = new Promise<void>((resolve) => (release = resolve));
  await page.reload();
  await expect(loader).toBeVisible();
  await expect
    .poll(() =>
      loader.evaluate((element) => getComputedStyle(element).animationName),
    )
    .toBe("none");
  release();
  await expect(
    page.getByRole("heading", { name: "Мои обращения", exact: true }),
  ).toBeVisible();
  await expect(loader).toHaveCount(0);
});

test("review settings and final action remain tappable at 320px with enlarged text", async ({
  page,
}) => {
  await session(page);
  const data = caseData(25, "Не горит свет");
  data.submissionStatus = "DRAFT";
  await page.route(`**/api/v1/cases/${data.caseId}`, (route) =>
    route.fulfill({ json: data }),
  );
  await page.setViewportSize({ width: 320, height: 844 });
  await page.goto(`/#/cases/${data.caseId}/review`);
  await expect(page.getByRole("heading", { name: "Всё верно?" })).toBeVisible();
  await page
    .getByRole("button", { name: "Настройки вида", exact: true })
    .click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await page.keyboard.press("Escape");
  await page.evaluate(() => {
    document.documentElement.style.fontSize = "36px";
  });
  const edit = page.getByRole("link", {
    name: "Исправить пункты",
    exact: true,
  });
  await expect
    .poll(async () => {
      await page.evaluate(() =>
        window.scrollTo(0, document.documentElement.scrollHeight),
      );
      return edit.evaluate((element) => {
        const rect = element.getBoundingClientRect();
        const hit = document.elementFromPoint(
          rect.x + rect.width / 2,
          rect.y + rect.height / 2,
        );
        return !!hit && element.contains(hit);
      });
    })
    .toBe(true);
  await edit.click();
  await expect(page).toHaveURL(new RegExp(`/cases/${data.caseId}/edit$`));
});
