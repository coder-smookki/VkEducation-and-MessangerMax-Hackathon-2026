import { spawnSync } from "node:child_process";
import { readFileSync, mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
const dir = mkdtempSync(join(tmpdir(), "koren-types-"));
try {
  const result = spawnSync(
    process.execPath,
    [
      "node_modules/openapi-typescript/bin/cli.js",
      "../contracts/author-review.openapi.json",
      "-o",
      join(dir, "schema.d.ts"),
    ],
    { stdio: "inherit" },
  );
  if (result.status !== 0) process.exitCode = 1;
  else if (
    readFileSync(join(dir, "schema.d.ts"), "utf8") !==
    readFileSync("src/api/schema.d.ts", "utf8")
  ) {
    console.error(
      "API types are stale. Run npm run api:generate and review the consumers.",
    );
    process.exitCode = 1;
  }
} finally {
  rmSync(dir, { recursive: true });
}
