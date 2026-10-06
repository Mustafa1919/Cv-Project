import assert from "node:assert/strict";
import { test } from "node:test";
import { hashText, normalizeText } from "../src/hash.ts";
import {
  approve,
  array,
  assertClean,
  assertOnly,
  changeSummary,
  copyFixture,
  editYaml,
  listItem,
  lockBytes,
  lockDocument,
  lockKeys,
  record,
  removeFile,
  run,
  writeText
} from "./helpers.ts";

const SUMMARY_KEY = "profile.yaml#/summary";
const AVAILABILITY_KEY = "profile.yaml#/availability";

test("valid fixture has no errors or warnings", (t) => {
  assertClean(run(copyFixture(t)));
});

const normalizationCases = [
  {
    name: "CRLF and CR become LF",
    input: "first\r\nsecond\rthird",
    expected: "first\nsecond\nthird"
  },
  {
    name: "trailing spaces and tabs are stripped per line",
    input: "first \t\n  second\t \nthird  ",
    expected: "first\n  second\nthird"
  },
  {
    name: "leading and trailing blank lines are stripped",
    input: "\n \t\nfirst\nsecond\n\t\n\n",
    expected: "first\nsecond"
  },
  {
    name: "inner blank lines are kept",
    input: "\nfirst\n \t\n\nsecond\n",
    expected: "first\n\n\nsecond"
  },
  {
    name: "NFD text becomes NFC",
    input: "s\u0327",
    expected: "ş"
  }
];

for (const row of normalizationCases) {
  test(`normalizeText: ${row.name}`, () => {
    assert.equal(normalizeText(row.input), row.expected);
    assert.equal(normalizeText(normalizeText(row.input)), row.expected);
  });
}

test("hashText returns 64 lowercase hexadecimal characters", () => {
  assert.match(hashText("Kurgusal içerik"), /^[a-f0-9]{64}$/);
});

test("hashText agrees for texts that normalize equally", () => {
  assert.equal(
    hashText("\r\ns\u0327 \t\r\nikinci\t\r\n"),
    hashText("ş\nikinci")
  );
});

test("hashText differs for different normalized texts", () => {
  assert.notEqual(hashText("first"), hashText("second"));
});

test("a missing lock reports every current unit and nothing else", (t) => {
  const dir = copyFixture(t);
  const expectedKeys = lockKeys(dir);
  removeFile(dir, "i18n.lock");
  const result = run(dir);
  assertOnly(result, "LOCK_OUT_OF_DATE");
  assert.equal(result.errors.length, expectedKeys.length);
  assert.deepEqual(result.errors.map((issue) => issue.path).sort(), expectedKeys);
  assert.ok(result.errors.every((issue) => issue.message === "new unit, run approve"));
  assert.deepEqual(result.warnings, []);
});

for (const language of ["tr", "en"] as const) {
  test(`only ${language} changed leaves the other translation stale`, (t) => {
    const dir = copyFixture(t);
    changeSummary(dir, [language]);
    const result = run(dir);
    assertOnly(result, "STALE_TRANSLATION", "profile.yaml", SUMMARY_KEY);
    assert.equal(result.errors.length, 1);
    assert.deepEqual(result.warnings, []);
    const other = language === "tr" ? "en" : "tr";
    assert.equal(
      result.errors[0]?.message,
      `${language} changed; ${other} was left behind`
    );
  });
}

test("both changed languages require approval, not stale-translation handling", (t) => {
  const dir = copyFixture(t);
  changeSummary(dir, ["tr", "en"]);
  const result = run(dir);
  assertOnly(result, "LOCK_OUT_OF_DATE", "profile.yaml", SUMMARY_KEY);
  assert.equal(result.errors.length, 1);
  assert.equal(result.errors[0]?.message, "both languages changed, run approve");
});

test("trailing spaces that normalize away do not change the lock", (t) => {
  const dir = copyFixture(t);
  editYaml(dir, "profile.yaml", (document) => {
    const summary = record(document.summary);
    const text = summary.tr;
    assert.equal(typeof text, "string");
    summary.tr = String(text)
      .split("\n")
      .map((line) => `${line} \t`)
      .join("\n");
  });
  assertClean(run(dir));
});

test("a one-sided UI leaf change uses its dotted unit key", (t) => {
  const dir = copyFixture(t);
  editYaml(dir, "ui/en.yaml", (document) => {
    record(document.navigation).profile = "Owner profile";
  });
  const result = run(dir);
  assertOnly(result, "STALE_TRANSLATION", "ui/tr.yaml", "ui#/navigation.profile");
  assert.equal(result.errors.length, 1);
});

test("removing an optional localized value reports its old lock entry", (t) => {
  const dir = copyFixture(t);
  editYaml(dir, "profile.yaml", (document) => {
    delete document.availability;
  });
  const result = run(dir);
  assertOnly(result, "LOCK_OUT_OF_DATE", "i18n.lock", AVAILABILITY_KEY);
  assert.equal(result.errors.length, 1);
});

for (const file of ["skills.yaml", "experience.yaml"]) {
  test(`reordering ${file} items does not change translation units`, (t) => {
    const dir = copyFixture(t);
    editYaml(dir, file, (document) => {
      array(document.items).reverse();
    });
    assertClean(run(dir));
  });
}

const malformedLockCases = [
  {
    name: "invalid JSON",
    mutate: (dir: string): void => {
      writeText(dir, "i18n.lock", "{ invalid JSON\n");
    }
  },
  {
    name: "wrong version",
    mutate: (dir: string): void => {
      const lock = lockDocument(dir);
      lock.version = 2;
      writeText(dir, "i18n.lock", `${JSON.stringify(lock, null, 2)}\n`);
    }
  },
  {
    name: "invalid hash",
    mutate: (dir: string): void => {
      const lock = lockDocument(dir);
      record(record(lock.units)[SUMMARY_KEY]).tr = "not-a-sha256-hash";
      writeText(dir, "i18n.lock", `${JSON.stringify(lock, null, 2)}\n`);
    }
  }
];

for (const row of malformedLockCases) {
  test(`malformed lock: ${row.name}`, (t) => {
    const dir = copyFixture(t);
    row.mutate(dir);
    const result = run(dir);
    assertOnly(result, "LOCK_OUT_OF_DATE", "i18n.lock", "");
    assert.equal(result.errors.length, 1);
    assert.deepEqual(result.warnings, []);
  });
}

test("approve without a lock adds all units and makes check clean", (t) => {
  const dir = copyFixture(t);
  const expectedKeys = lockKeys(dir);
  removeFile(dir, "i18n.lock");
  const result = approve(dir);
  assert.equal(result.written, true);
  assert.deepEqual(result.errors, []);
  assert.deepEqual(result.added, expectedKeys);
  assert.deepEqual(result.updated, []);
  assert.deepEqual(result.acceptedOneSided, []);
  assert.deepEqual(result.removed, []);
  assert.deepEqual(lockKeys(dir), expectedKeys);
  assertClean(run(dir));
});

test("approve refuses an unaccepted one-sided change without writing", (t) => {
  const dir = copyFixture(t);
  const before = lockBytes(dir);
  changeSummary(dir, ["tr"]);
  const result = approve(dir);
  assert.equal(result.written, false);
  assertOnly(result, "STALE_TRANSLATION", "profile.yaml", SUMMARY_KEY);
  assert.equal(result.errors.length, 1);
  assert.deepEqual(result.added, []);
  assert.deepEqual(result.updated, []);
  assert.deepEqual(result.acceptedOneSided, []);
  assert.deepEqual(result.removed, []);
  assert.equal(lockBytes(dir), before);
});

test("approve accepts an explicitly listed one-sided unit", (t) => {
  const dir = copyFixture(t);
  changeSummary(dir, ["tr"]);
  const result = approve(dir, [SUMMARY_KEY]);
  assert.equal(result.written, true);
  assert.deepEqual(result.errors, []);
  assert.deepEqual(result.added, []);
  assert.deepEqual(result.updated, []);
  assert.deepEqual(result.acceptedOneSided, [SUMMARY_KEY]);
  assert.deepEqual(result.removed, []);
  assertClean(run(dir));
});

test("approve rejects an acceptance key that is not one-sided", (t) => {
  const dir = copyFixture(t);
  const before = lockBytes(dir);
  const result = approve(dir, [SUMMARY_KEY]);
  assert.equal(result.written, false);
  assertOnly(result, "LOCK_OUT_OF_DATE", "profile.yaml", SUMMARY_KEY);
  assert.equal(result.errors.length, 1);
  assert.equal(result.errors[0]?.message, "not a one-sided change");
  assert.equal(lockBytes(dir), before);
});

test("approve lists a both-changed unit as updated", (t) => {
  const dir = copyFixture(t);
  changeSummary(dir, ["tr", "en"]);
  const result = approve(dir);
  assert.equal(result.written, true);
  assert.deepEqual(result.errors, []);
  assert.deepEqual(result.added, []);
  assert.deepEqual(result.updated, [SUMMARY_KEY]);
  assert.deepEqual(result.acceptedOneSided, []);
  assert.deepEqual(result.removed, []);
  assertClean(run(dir));
});

test("approve prunes removed units and lists their keys", (t) => {
  const dir = copyFixture(t);
  editYaml(dir, "profile.yaml", (document) => {
    delete document.availability;
  });
  const result = approve(dir);
  assert.equal(result.written, true);
  assert.deepEqual(result.errors, []);
  assert.deepEqual(result.added, []);
  assert.deepEqual(result.updated, []);
  assert.deepEqual(result.acceptedOneSided, []);
  assert.deepEqual(result.removed, [AVAILABILITY_KEY]);
  assert.ok(!lockKeys(dir).includes(AVAILABILITY_KEY));
  assertClean(run(dir));
});

test("approve refuses a blocking reference error and preserves lock bytes", (t) => {
  const dir = copyFixture(t);
  const before = lockBytes(dir);
  editYaml(dir, "skills.yaml", (document) => {
    array(listItem(document).evidence)[0] = "missing-evidence";
  });
  const result = approve(dir);
  assert.equal(result.written, false);
  assertOnly(result, "UNKNOWN_REFERENCE", "skills.yaml", "/items/0/evidence/0");
  assert.equal(result.errors.length, 1);
  assert.deepEqual(result.added, []);
  assert.deepEqual(result.updated, []);
  assert.deepEqual(result.acceptedOneSided, []);
  assert.deepEqual(result.removed, []);
  assert.equal(lockBytes(dir), before);
});

test("approve treats a malformed lock as blocking and does not overwrite it", (t) => {
  const dir = copyFixture(t);
  writeText(dir, "i18n.lock", "{ malformed\n");
  const before = lockBytes(dir);
  const result = approve(dir);
  assert.equal(result.written, false);
  assertOnly(result, "LOCK_OUT_OF_DATE", "i18n.lock", "");
  assert.equal(result.errors.length, 1);
  assert.deepEqual(result.added, []);
  assert.deepEqual(result.updated, []);
  assert.deepEqual(result.acceptedOneSided, []);
  assert.deepEqual(result.removed, []);
  assert.equal(lockBytes(dir), before);
});

test("approval output is deterministic, sorted, indented, and LF-terminated", (t) => {
  const dir = copyFixture(t);
  removeFile(dir, "i18n.lock");
  const first = approve(dir);
  assert.equal(first.written, true);
  assert.deepEqual(first.errors, []);
  const firstBytes = lockBytes(dir);

  const second = approve(dir);
  assert.equal(second.written, true);
  assert.deepEqual(second.errors, []);
  assert.deepEqual(second.added, []);
  assert.deepEqual(second.updated, []);
  assert.deepEqual(second.acceptedOneSided, []);
  assert.deepEqual(second.removed, []);
  assert.equal(lockBytes(dir), firstBytes);

  const document = lockDocument(dir);
  const keys = Object.keys(record(document.units));
  assert.deepEqual(keys, [...keys].sort());
  assert.equal(document.version, 1);
  assert.equal(firstBytes, `${JSON.stringify(document, null, 2)}\n`);
  assert.ok(firstBytes.endsWith("\n"));
  assert.ok(!firstBytes.endsWith("\n\n"));
  assert.ok(!firstBytes.includes("\r"));
  assertClean(run(dir));
});

// Exact test registrations in this file: 30.
