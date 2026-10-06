import { spawn } from "node:child_process";
import { readFile, readdir } from "node:fs/promises";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const toolDirectory = fileURLToPath(new URL("../", import.meta.url));
const testDirectory = join(toolDirectory, "test");
const summaryNames = ["tests", "pass", "fail", "cancelled", "skipped", "todo"] as const;
type SummaryName = (typeof summaryNames)[number];

async function discoverTests(directory: string): Promise<string[]> {
  const entries = await readdir(directory, { withFileTypes: true });
  entries.sort((left, right) => left.name.localeCompare(right.name));

  const files: string[] = [];
  for (const entry of entries) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) {
      files.push(...await discoverTests(path));
    } else if (entry.isFile() && entry.name.endsWith(".test.ts")) {
      files.push(path);
    }
  }
  return files;
}

async function readExpectedCount(): Promise<number> {
  const value: unknown = JSON.parse(
    await readFile(join(toolDirectory, "expected-tests.json"), "utf8")
  );
  if (
    typeof value !== "object" ||
    value === null ||
    !("tests" in value) ||
    typeof value.tests !== "number" ||
    !Number.isSafeInteger(value.tests) ||
    value.tests < 0
  ) {
    throw new Error("expected-tests.json must contain a non-negative integer tests count");
  }
  return value.tests;
}

async function main(): Promise<void> {
  const expected = await readExpectedCount();
  const files = await discoverTests(testDirectory);

  if (files.length === 0) {
    if (expected !== 0) {
      console.error(`expected ${expected} tests to run, 0 ran`);
      process.exitCode = 1;
    }
    return;
  }

  let output = "";
  const child = spawn(
    process.execPath,
    [
      "--experimental-strip-types",
      "--disable-warning=ExperimentalWarning",
      "--test",
      "--test-reporter=tap",
      ...files
    ],
    {
      cwd: toolDirectory,
      stdio: ["inherit", "pipe", "pipe"]
    }
  );

  child.stdout.setEncoding("utf8");
  child.stderr.setEncoding("utf8");
  child.stdout.on("data", (chunk: string) => {
    output += chunk;
    process.stdout.write(chunk);
  });
  child.stderr.on("data", (chunk: string) => {
    process.stderr.write(chunk);
  });

  const status = await new Promise<{ code: number | null; signal: string | null }>(
    (resolve, reject) => {
      child.once("error", reject);
      child.once("close", (code, signal) => resolve({ code, signal }));
    }
  );

  let failed = false;
  if (status.code !== 0 || status.signal !== null) {
    console.error(
      `Test runner failed: ${status.signal !== null ? `signal ${status.signal}` : `exit code ${status.code}`}`
    );
    failed = true;
  }

  const summary: Partial<Record<SummaryName, number>> = {};
  for (const name of summaryNames) {
    const pattern = new RegExp(`^# ${name} (\\d+)\\s*$`, "gm");
    for (const match of output.matchAll(pattern)) {
      summary[name] = Number(match[1]);
    }
  }

  if (summaryNames.some((name) => summary[name] === undefined)) {
    console.error("Test runner TAP summary is missing or incomplete");
    process.exitCode = 1;
    return;
  }

  for (const name of ["fail", "cancelled", "skipped", "todo"] as const) {
    if (summary[name] !== 0) {
      console.error(`Test runner reported ${summary[name]} ${name} tests`);
      failed = true;
    }
  }

  if (summary.pass !== expected) {
    console.error(`expected ${expected} tests to run, ${summary.pass} ran`);
    failed = true;
  }

  if (failed) {
    process.exitCode = 1;
  }
}

try {
  await main();
} catch (error: unknown) {
  const message = error instanceof Error ? error.message : String(error);
  console.error(`Test execution failed: ${message}`);
  process.exitCode = 1;
}
