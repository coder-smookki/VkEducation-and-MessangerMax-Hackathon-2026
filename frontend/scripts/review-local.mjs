import { chromium, expect } from "@playwright/test";
import { readFileSync } from "node:fs";
const data = JSON.parse(readFileSync(process.argv[2], "utf8"));
const check = process.argv.includes("--check");
const target = new URL(data.appUrl);
if (
  data.testOnly !== true ||
  target.protocol !== "http:" ||
  !["127.0.0.1", "localhost"].includes(target.hostname)
)
  throw new Error(
    "Only a separately prepared HTTP loopback review environment is allowed",
  );
const browser = await chromium.launch({ headless: check });
process.on("SIGINT", async () => {
  await browser.close();
  process.exit(0);
});
try {
  for (const actor of data.actors) {
    const context = await browser.newContext({
      viewport: { width: 390, height: 844 },
    });
    await context.addInitScript((raw) => {
      window.WebApp = { initData: raw };
    }, actor.initData);
    await context.route("https://st.max.ru/js/max-web-app.js", (route) =>
      route.fulfill({ contentType: "application/javascript", body: "" }),
    );
    const page = await context.newPage();
    await page.goto(data.appUrl);
    await expect(
      page.getByRole("heading", {
        name:
          actor.role === "DISPATCHER" ? "Очередь обращений" : "Мои обращения",
        exact: true,
      }),
    ).toBeVisible();
    const me = await context.request.get(data.appUrl + "/api/v1/me");
    if (me.status() !== 200)
      throw new Error("Real Java session was not established");
    expect((await me.json()).memberships).toContainEqual(
      expect.objectContaining({ houseId: actor.houseId, role: actor.role }),
    );
    console.log(
      `PASS: ${actor.label} / ${actor.role} - real Java session and expected screen`,
    );
  }
  if (!check) {
    console.log(
      "Three independent browser sessions are open. Ctrl+C closes them; database data remain. Synthetic launch only, no real MAX client.",
    );
    await new Promise((resolve) => browser.on("disconnected", resolve));
  }
} finally {
  await browser.close();
}
