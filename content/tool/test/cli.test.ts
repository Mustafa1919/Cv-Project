import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import {
  assertClean,
  changeSummary,
  copyFixture,
  editYaml,
  record,
  removeFile,
  run,
  SCHEMA_DIR
} from "./helpers.ts";

const CLI = fileURLToPath(new URL("../src/cli.ts", import.meta.url));

function cli(args: readonly string[]) {
  const result = spawnSync(
    process.execPath,
    [
      "--experimental-strip-types",
      "--disable-warning=ExperimentalWarning",
      CLI,
      ...args
    ],
    { encoding: "utf8" }
  );
  assert.equal(result.error, undefined);
  assert.equal(result.signal, null);
  return result;
}

test("valid fixture is clean and CLI check exits zero", (t) => {
  const dir = copyFixture(t);
  assertClean(run(dir));
  // Omitting --schema exercises the entry-point-relative default.
  const result = cli(["check", "--content", dir]);
  assert.equal(result.status, 0);
  assert.equal(result.stderr, "");
  const lines = result.stdout.trimEnd().split("\n");
  assert.equal(lines.at(-1), "0 errors, 0 warnings");
  assert.equal(result.stdout, "0 errors, 0 warnings\n");
});

test("CLI check reports a missing translation and exits one", (t) => {
  const dir = copyFixture(t);
  editYaml(dir, "profile.yaml", (document) => {
    delete record(document.summary).en;
  });
  const result = cli([
    "check",
    "--content",
    dir,
    "--schema",
    SCHEMA_DIR
  ]);
  assert.equal(result.status, 1);
  assert.equal(result.stderr, "");
  assert.match(result.stdout, /^error MISSING_TRANSLATION /m);
  assert.equal(result.stdout.trimEnd().split("\n").at(-1), "1 errors, 0 warnings");
});

test("CLI JSON check returns errors and warnings arrays", (t) => {
  const dir = copyFixture(t);
  const result = cli(["check", "--content", dir, "--format", "json"]);
  assert.equal(result.status, 0);
  assert.equal(result.stderr, "");
  const value: unknown = JSON.parse(result.stdout);
  const document = record(value);
  assert.ok(Array.isArray(document.errors));
  assert.ok(Array.isArray(document.warnings));
  assert.deepEqual(document, { errors: [], warnings: [] });
});

test("CLI approve creates a missing lock and exits zero", (t) => {
  const dir = copyFixture(t);
  removeFile(dir, "i18n.lock");
  const result = cli(["approve", "--content", dir]);
  assert.equal(result.status, 0);
  assert.equal(result.stderr, "");
  assert.ok(existsSync(join(dir, "i18n.lock")));
  assert.match(
    result.stdout,
    /^\d+ added, 0 updated, 0 accepted, 0 removed\n$/
  );
  assertClean(run(dir));
});

test("CLI approve accepts a listed one-sided change", (t) => {
  const dir = copyFixture(t);
  changeSummary(dir, ["tr"]);
  const result = cli([
    "approve",
    "--content",
    dir,
    "--accept-one-sided",
    "profile.yaml#/summary"
  ]);
  assert.equal(result.status, 0);
  assert.equal(result.stderr, "");
  assert.equal(result.stdout, "0 added, 0 updated, 1 accepted, 0 removed\n");
  assertClean(run(dir));
});

const usageCases: readonly {
  readonly name: string;
  readonly args: readonly string[];
}[] = [
  { name: "no command", args: [] },
  { name: "unknown command", args: ["unknown"] },
  { name: "unknown option", args: ["check", "--unknown"] },
  { name: "invalid format", args: ["check", "--format", "xml"] },
  {
    name: "accept-one-sided on check",
    args: ["check", "--accept-one-sided", "x"]
  }
];

for (const row of usageCases) {
  test(`CLI usage error: ${row.name}`, () => {
    const result = cli(row.args);
    assert.equal(result.status, 2);
    assert.notEqual(result.stderr.trim(), "");
    assert.equal(result.stdout, "");
  });
}

// Exact test registrations per file:
// helpers.ts: 0
// schema.test.ts: 48
// rules.test.ts: 26
// lock.test.ts: 30
// cli.test.ts: 10
// Total: 114. Set expected-tests.json "tests" to 114 for this suite.
