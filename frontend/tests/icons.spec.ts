import { test, expect } from "@playwright/test";
import type { Case } from "../src/api/client";
import meFixture from "../../contracts/examples/me-response.json" with { type: "json" };
import caseFixture from "../../contracts/examples/case-view-target.json" with { type: "json" };
import reportFixture from "../../contracts/examples/report-view.json" with { type: "json" };
import suggestionsFixture from "../../contracts/examples/report-suggestions-ready.json" with { type: "json" };

for (const outcome of ["ready", "error"] as const) {
  test(`report analysis stays honest while pending, then ${outcome}, without blocking manual selection`, async ({
    page,
  }) => {
    const me = structuredClone(meFixture);
    me.userId = reportFixture.actorId;
    me.memberships[0].role = "DISPATCHER";
    const analysis = structuredClone(suggestionsFixture);
    analysis.result.reportId = reportFixture.reportId;
    let release!: () => void;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    await page.route("https://st.max.ru/**", (route) =>
      route.fulfill({ body: "", contentType: "application/javascript" }),
    );
    await page.route("**/api/v1/**", async (route) => {
      const path = new URL(route.request().url()).pathname;
      if (path.endsWith("/suggestions")) {
        await gate;
        return outcome === "ready"
          ? route.fulfill({ json: analysis })
          : route.fulfill({ status: 503, json: { code: "HTTP_ERROR" } });
      }
      return route.fulfill({
        json:
          path === "/api/v1/me"
            ? me
            : path.includes("/reports/")
              ? reportFixture
              : caseFixture,
      });
    });
    try {
      await page.goto(
        `/#/cases/${caseFixture.caseId}/reports/${reportFixture.reportId}`,
      );
      const pending = page.getByRole("heading", {
        name: "Проверяем готовность подсказки",
      });
      await expect(pending).toBeVisible();
      await expect(
        page.getByText("Автоматическая подсказка недоступна.", {
          exact: false,
        }),
      ).toHaveCount(0);
      const manualChoice = page.getByRole("checkbox", {
        name: /Дверь не закрывается/,
      });
      await expect(manualChoice).toBeEnabled();
      await manualChoice.check();
      release();
      await expect(pending).toHaveCount(0);
      await expect(manualChoice).toBeChecked();
      await expect(
        page.getByRole("button", {
          name: "Проверить выбранные связи",
          exact: true,
        }),
      ).toBeEnabled();
      if (outcome === "ready")
        await expect(
          page.getByText("Подсказка анализа требует вашей проверки.", {
            exact: false,
          }),
        ).toBeVisible();
      else {
        await expect(page.getByRole("alert")).toBeVisible();
        await expect(
          page.getByRole("button", { name: "Повторить загрузку", exact: true }),
        ).toBeEnabled();
      }
    } finally {
      release();
    }
  });
}

for (const administrativeStatus of ["ARCHIVED", "WITHDRAWN"] as const) {
  test(`${administrativeStatus} explanation remains readable with enlarged mobile text`, async ({
    page,
  }) => {
    const data = structuredClone(caseFixture) as Case;
    const reason =
      "Плановые работы перенесены на следующий месяц. Сведения сохранены для проверки.";
    data.items[0].administrativeStatus = administrativeStatus;
    data.items[0].administrativeReason = reason;
    await page.route("https://st.max.ru/**", (route) =>
      route.fulfill({ body: "", contentType: "application/javascript" }),
    );
    await page.route("**/api/v1/**", (route) => {
      const path = new URL(route.request().url()).pathname;
      return route.fulfill({
        json: path === "/api/v1/me" ? meFixture : data,
      });
    });
    await page.goto(`/#/cases/${data.caseId}`);
    const notice = page.locator(".item-card .notice.warning");
    const label =
      administrativeStatus === "ARCHIVED" ? "В архиве" : "Отозван автором";
    await expect(notice.getByText(label, { exact: true })).toBeVisible();
    await expect(notice.getByText(reason, { exact: true })).toBeVisible();
    await expect(
      notice.getByText("Это решение не подтверждает ремонт."),
    ).toBeVisible();

    for (const width of [320, 360]) {
      await page.setViewportSize({ width, height: 844 });
      for (const fontSize of [20, 36]) {
        await page.evaluate((size) => {
          document.documentElement.style.fontSize = `${size}px`;
        }, fontSize);
        const geometry = await notice.evaluate((element) => {
          const title = element.querySelector("strong")!;
          const text = title.firstChild!;
          const titleText = text.textContent!;
          const word = titleText
            .split(/\s+/)
            .sort((a, b) => b.length - a.length)[0];
          const start = titleText.indexOf(word);
          const range = document.createRange();
          range.setStart(text, start);
          range.setEnd(text, start + word.length);
          return {
            wordLines: range.getClientRects().length,
            overflow: element.scrollWidth - element.clientWidth,
            right: element.getBoundingClientRect().right,
          };
        });
        expect(geometry.wordLines, `${width}px, font ${fontSize}px`).toBe(1);
        expect(geometry.overflow).toBeLessThanOrEqual(1);
        expect(geometry.right).toBeLessThanOrEqual(width);
      }
    }
  });
}

test("an overdue next step does not claim that newer work information is absent", async ({
  page,
}) => {
  const data = structuredClone(caseFixture) as Case;
  data.items[0].workStatus = "REPORTED_DONE";
  data.items[0].workRound = 1;
  data.items[0].nextAction!.updateDueAt = "2000-01-01T00:00:00Z";
  await page.route("https://st.max.ru/**", (route) =>
    route.fulfill({ body: "", contentType: "application/javascript" }),
  );
  await page.route("**/api/v1/**", (route) =>
    route.fulfill({
      json:
        new URL(route.request().url()).pathname === "/api/v1/me"
          ? meFixture
          : data,
    }),
  );
  await page.goto(`/#/cases/${data.caseId}`);
  await expect(
    page.getByText("Сообщили о выполнении", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByText("Срок обновления по этому шагу прошёл.", { exact: false }),
  ).toBeVisible();
  await expect(
    page.getByText("Новых сведений пока нет.", { exact: false }),
  ).toHaveCount(0);
});

test("SVG disclosures preserve keyboard access, readable labels and lazy history", async ({
  page,
}) => {
  let historyRequests = 0;
  await page.route("https://st.max.ru/**", (route) =>
    route.fulfill({ body: "", contentType: "application/javascript" }),
  );
  await page.route("**/api/v1/**", (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path === "/api/v1/me") return route.fulfill({ json: meFixture });
    if (path.endsWith("/history")) {
      historyRequests++;
      return route.fulfill({
        json: {
          items: [
            {
              eventId: "00000000-0000-0000-0000-000000000099",
              eventType: "CASE_CREATED",
              actorId: meFixture.userId,
              createdAt: caseFixture.createdAt,
              details: {},
            },
          ],
          nextCursor: null,
        },
      });
    }
    if (path === `/api/v1/cases/${caseFixture.caseId}`)
      return route.fulfill({ json: caseFixture });
    return route.fulfill({ status: 404, json: { code: "NOT_FOUND" } });
  });
  await page.setViewportSize({ width: 320, height: 844 });
  await page.goto(`/#/cases/${caseFixture.caseId}`);
  await expect(
    page.getByRole("heading", { name: /Обращение №/ }),
  ).toBeVisible();
  await page.evaluate(() => {
    document.documentElement.style.fontSize = "36px";
  });

  const source = page.locator("summary").filter({
    hasText: "Исходный текст и квитанция",
  });
  const history = page
    .locator("summary")
    .filter({ hasText: "История обращения" });
  for (const summary of [source, history]) {
    await expect(summary.locator("svg[aria-hidden='true']")).toHaveCount(1);
    await summary.scrollIntoViewIfNeeded();
    const geometry = await summary.evaluate((element) => {
      const icon = element.querySelector("svg")!.getBoundingClientRect();
      const label = element.querySelector("span")!.getBoundingClientRect();
      const styles = getComputedStyle(element);
      return {
        marker: styles.listStyleType,
        gap: label.left - icon.right,
        right: label.right,
      };
    });
    expect(geometry.marker).toBe("none");
    expect(geometry.gap).toBeGreaterThanOrEqual(8);
    expect(geometry.right).toBeLessThanOrEqual(320);
  }

  expect(historyRequests).toBe(0);
  await source.focus();
  await page.keyboard.press("Enter");
  await expect(page.locator(".source-details")).toHaveAttribute("open", "");
  await expect(
    page.getByText(caseFixture.sourceText, { exact: true }),
  ).toBeVisible();
  await page.keyboard.press("Space");
  await expect(page.locator(".source-details")).not.toHaveAttribute("open", "");
  expect(historyRequests).toBe(0);

  await history.focus();
  await page.keyboard.press("Enter");
  await expect(page.locator(".timeline")).toHaveAttribute("open", "");
  await expect(
    page.getByText("Обращение создано", { exact: true }),
  ).toBeVisible();
  await expect.poll(() => historyRequests).toBe(1);
  await page.keyboard.press("Space");
  await expect(page.locator(".timeline")).not.toHaveAttribute("open", "");
  await expect(
    page.getByText("Обращение создано", { exact: true }),
  ).toBeHidden();
  expect(historyRequests).toBe(1);

  const back = page.getByRole("link", { name: "К обращениям", exact: true });
  await expect(back.locator("svg[aria-hidden='true']")).toHaveCount(1);
  await expect(back).toHaveAccessibleName("К обращениям");
});
