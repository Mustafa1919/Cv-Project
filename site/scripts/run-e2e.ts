import { readFileSync, rmSync } from "node:fs";
import { join } from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const siteDir = fileURLToPath(new URL("../", import.meta.url));
const reportPath = join(siteDir, "test-results", "report.json");

function record(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function count(value: unknown, label: string): number {
  if (
    typeof value !== "number" ||
    !Number.isSafeInteger(value) ||
    value < 0
  ) {
    throw new Error(`Invalid test count: ${label}`);
  }
  return value;
}

function quote(argument: string): string {
  if (/[\u0000\r\n]/u.test(argument)) {
    throw new Error("CLI arguments must not contain NUL or line breaks.");
  }
  if (process.platform === "win32") {
    // These arguments are interpreted by cmd.exe before reaching npx.
    // Reject expansion characters rather than silently changing their value.
    if (/["%]/u.test(argument)) {
      throw new Error("Windows CLI arguments must not contain quotes or percent signs.");
    }
    return `"${argument}"`;
  }
  return `'${argument.replace(/'/g, "'\\''")}'`;
}

function run(command: string, env: NodeJS.ProcessEnv): void {
  const result = spawnSync(command, {
    cwd: siteDir,
    env,
    shell: true,
    stdio: "inherit",
  });
  if (result.error !== undefined) {
    throw result.error;
  }
  if (result.status !== 0) {
    throw new Error(
      `${command} failed (${result.signal ?? result.status ?? "unknown status"}).`,
    );
  }
}

function main(): void {
  const env: NodeJS.ProcessEnv = {
    ...process.env,
    VITRIN_SITE_ORIGIN: "http://127.0.0.1:4173",
    PUBLIC_VITRIN_API_ORIGIN: "http://127.0.0.1:4174",
    ASTRO_TELEMETRY_DISABLED: "1",
  };
  delete env["VITRIN_CONTENT_DIR"];
  delete env["VITRIN_NOINDEX"];

  const manifest: unknown = JSON.parse(
    readFileSync(join(siteDir, "expected-tests.json"), "utf8"),
  );
  if (!record(manifest)) {
    throw new Error("Invalid expected-tests.json.");
  }
  const expected = count(manifest["tests"], "manifest.tests");
  const extraArguments = process.argv.slice(2).map(quote).join(" ");

  run("npm run build", env);
  rmSync(reportPath, { force: true });
  run(`npx playwright test${extraArguments === "" ? "" : ` ${extraArguments}`}`, env);

  const report: unknown = JSON.parse(readFileSync(reportPath, "utf8"));
  if (!record(report) || !record(report["stats"])) {
    throw new Error("The Playwright report has no valid stats object.");
  }
  const stats = report["stats"];
  const passed = count(stats["expected"], "stats.expected");
  const unexpected = count(stats["unexpected"], "stats.unexpected");
  const flaky = count(stats["flaky"], "stats.flaky");
  const skipped = count(stats["skipped"], "stats.skipped");

  if (passed !== expected) {
    throw new Error(`expected ${expected} tests to run, ${passed} ran`);
  }
  if (unexpected !== 0 || flaky !== 0 || skipped !== 0) {
    throw new Error(
      `Invalid test results: unexpected=${unexpected}, flaky=${flaky}, skipped=${skipped}`,
    );
  }
}

try {
  main();
} catch (error: unknown) {
  console.error(error instanceof Error ? error.message : String(error));
  process.exitCode = 1;
}
