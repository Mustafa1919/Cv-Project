import assert from "node:assert/strict";
import { cpSync, mkdtempSync, rmSync, writeFileSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import type { TestContext } from "node:test";
import { fileURLToPath } from "node:url";
import { parse, stringify } from "yaml";
import { checkContent } from "../src/check.ts";
import type { CheckResult } from "../src/check.ts";
import { approveContent } from "../src/approve.ts";
import type { ApproveResult } from "../src/approve.ts";
import type { IssueCode } from "../src/issue.ts";

export const SCHEMA_DIR = fileURLToPath(
  new URL("../../schema/", import.meta.url)
);
const FIXTURE_DIR = fileURLToPath(
  new URL("./fixtures/valid/", import.meta.url)
);

export function copyFixture(t: TestContext): string {
  const dir = mkdtempSync(join(tmpdir(), "vitrin-content-"));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  cpSync(FIXTURE_DIR, dir, { recursive: true });
  return dir;
}

export function record(value: unknown): Record<string, unknown> {
  assert.ok(typeof value === "object" && value !== null && !Array.isArray(value));
  return value as Record<string, unknown>;
}

export function array(value: unknown): unknown[] {
  assert.ok(Array.isArray(value));
  return value as unknown[];
}

export function listItem(
  document: Record<string, unknown>,
  index = 0
): Record<string, unknown> {
  return record(array(document.items)[index]);
}

export function metric(
  document: Record<string, unknown>,
  index = 0
): Record<string, unknown> {
  return record(array(document.metrics)[index]);
}

export function editYaml(
  dir: string,
  file: string,
  mutate: (document: Record<string, unknown>) => void
): void {
  const path = join(dir, file);
  const value: unknown = parse(readFileSync(path, "utf8"), {
    version: "1.2",
    schema: "core",
    uniqueKeys: true,
    strict: true
  });
  const document = record(value);
  mutate(document);
  writeFileSync(path, stringify(document), "utf8");
}

export function writeText(dir: string, file: string, text: string): void {
  writeFileSync(join(dir, file), text, "utf8");
}

export function removeFile(dir: string, file: string): void {
  rmSync(join(dir, file), { recursive: true, force: true });
}

export function run(dir: string): CheckResult {
  return checkContent({ contentDir: dir, schemaDir: SCHEMA_DIR });
}

export function approve(
  dir: string,
  acceptOneSided?: readonly string[]
): ApproveResult {
  return approveContent({
    contentDir: dir,
    schemaDir: SCHEMA_DIR,
    ...(acceptOneSided === undefined ? {} : { acceptOneSided })
  });
}

type ErrorResult = Pick<CheckResult, "errors">;

export function codes(result: ErrorResult): IssueCode[] {
  return result.errors.map((issue) => issue.code).sort();
}

export function assertOnly(
  result: ErrorResult,
  code: IssueCode,
  file?: string,
  path?: string
): void {
  assert.ok(result.errors.length > 0, `Expected at least one ${code} error`);
  assert.ok(
    result.errors.every((issue) => issue.code === code),
    `Expected only ${code}, received ${JSON.stringify(result.errors)}`
  );
  if (file !== undefined || path !== undefined) {
    assert.ok(
      result.errors.some((issue) =>
        issue.code === code &&
        (file === undefined || issue.file === file) &&
        (path === undefined || issue.path === path)
      ),
      `Expected ${code} at ${file ?? "*"} ${path ?? "*"}`
    );
  }
}

export function assertHas(
  result: ErrorResult,
  code: IssueCode,
  file: string,
  path: string
): void {
  assert.ok(
    result.errors.some((issue) =>
      issue.code === code && issue.file === file && issue.path === path
    ),
    `Expected ${code} at ${file} ${path}; received ${JSON.stringify(result.errors)}`
  );
}

export function assertClean(result: CheckResult): void {
  assert.deepEqual(result.errors, []);
  assert.deepEqual(result.warnings, []);
}

export function lockBytes(dir: string): string {
  return readFileSync(join(dir, "i18n.lock"), "utf8");
}

export function lockDocument(dir: string): Record<string, unknown> {
  const value: unknown = JSON.parse(lockBytes(dir));
  return record(value);
}

export function lockKeys(dir: string): string[] {
  return Object.keys(record(lockDocument(dir).units)).sort();
}

export function changeSummary(
  dir: string,
  languages: readonly ("tr" | "en")[]
): void {
  editYaml(dir, "profile.yaml", (document) => {
    const summary = record(document.summary);
    for (const language of languages) {
      const text = summary[language];
      assert.equal(typeof text, "string");
      summary[language] = `${String(text)} Additional fictional context.`;
    }
  });
}

export function fixturePath(file: string): string {
  return join(FIXTURE_DIR, file);
}

export const TEST_DIRECTORY = dirname(fileURLToPath(import.meta.url));
