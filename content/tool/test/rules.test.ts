import assert from "node:assert/strict";
import { copyFileSync } from "node:fs";
import { join } from "node:path";
import { test } from "node:test";
import {
  approve,
  array,
  assertClean,
  assertHas,
  assertOnly,
  codes,
  copyFixture,
  editYaml,
  listItem,
  metric,
  record,
  removeFile,
  run
} from "./helpers.ts";

const CASE_FILE = "case-studies/queue-rewrite.yaml";

test("valid fixture has no errors or warnings", (t) => {
  assertClean(run(copyFixture(t)));
});

test("duplicate skill IDs are rejected", (t) => {
  const dir = copyFixture(t);
  editYaml(dir, "skills.yaml", (document) => {
    array(document.items).push({ ...listItem(document) });
  });
  // A skill claim has no localized text, and appending a copy leaves every reference intact.
  assertOnly(run(dir), "DUPLICATE_ID", "skills.yaml", "/items/4/id");
});

test("duplicate metric IDs are rejected", (t) => {
  const dir = copyFixture(t);
  editYaml(dir, CASE_FILE, (document) => {
    metric(document, 1).id = metric(document).id;
  });
  // Metric IDs determine unit keys, so duplicate IDs also produce lock issues.
  assertHas(run(dir), "DUPLICATE_ID", CASE_FILE, "/metrics/1/id");
});

test("duplicate profile link IDs are rejected", (t) => {
  const dir = copyFixture(t);
  editYaml(dir, "profile.yaml", (document) => {
    const links = array(document.links);
    links.push(structuredClone(links[0]));
  });
  // The duplicate has identical localized text and the same unit key.
  assertOnly(run(dir), "DUPLICATE_ID", "profile.yaml", "/links/1/id");
});

test("two case study files cannot share an ID", (t) => {
  const dir = copyFixture(t);
  const secondFile = "case-studies/queue-rewrite-copy.yaml";
  copyFileSync(join(dir, CASE_FILE), join(dir, secondFile));
  const result = run(dir);
  // Copying adds translation units and also makes the second filename disagree.
  assertHas(result, "DUPLICATE_ID", CASE_FILE, "/id");
  assertHas(result, "ID_FILENAME_MISMATCH", secondFile, "/id");
  assert.ok(result.errors.some((issue) => issue.code === "LOCK_OUT_OF_DATE"));
});

test("case study ID must match its filename", (t) => {
  const dir = copyFixture(t);
  editYaml(dir, CASE_FILE, (document) => {
    document.id = "different-case";
  });
  // The original evidence reference is now unresolved; localized unit keys stay unchanged.
  const result = run(dir);
  assertHas(result, "ID_FILENAME_MISMATCH", CASE_FILE, "/id");
  assertHas(result, "EVIDENCE_TARGET_UNRESOLVED", "evidence.yaml", "/items/1/ref");
  assert.deepEqual(codes(result), [
    "EVIDENCE_TARGET_UNRESOLVED",
    "ID_FILENAME_MISMATCH"
  ]);
});

test("a skill must reference existing evidence", (t) => {
  const dir = copyFixture(t);
  editYaml(dir, "skills.yaml", (document) => {
    array(listItem(document).evidence)[0] = "missing-evidence";
  });
  assertOnly(run(dir), "UNKNOWN_REFERENCE", "skills.yaml", "/items/0/evidence/0");
});

test("a highlight must reference existing evidence", (t) => {
  const dir = copyFixture(t);
  editYaml(dir, "profile.yaml", (document) => {
    record(array(document.highlights)[0]).evidence = "missing-evidence";
  });
  assertOnly(run(dir), "UNKNOWN_REFERENCE", "profile.yaml", "/highlights/0/evidence");
});

test("experience must reference existing skills", (t) => {
  const dir = copyFixture(t);
  editYaml(dir, "experience.yaml", (document) => {
    array(listItem(document, 1).skills)[0] = "missing-skill";
  });
  assertOnly(run(dir), "UNKNOWN_REFERENCE", "experience.yaml", "/items/1/skills/0");
});

test("a project must reference existing skills", (t) => {
  const dir = copyFixture(t);
  editYaml(dir, "projects.yaml", (document) => {
    array(listItem(document).skills)[0] = "missing-skill";
  });
  assertOnly(run(dir), "UNKNOWN_REFERENCE", "projects.yaml", "/items/0/skills/0");
});

test("a case study must reference an existing project", (t) => {
  const dir = copyFixture(t);
  editYaml(dir, CASE_FILE, (document) => {
    document.project = "missing-project";
  });
  assertOnly(run(dir), "UNKNOWN_REFERENCE", CASE_FILE, "/project");
});

for (const row of [
  { type: "project", index: 0 },
  { type: "case-study", index: 1 },
  { type: "certificate", index: 2 }
]) {
  test(`internal evidence resolves its ${row.type} target`, (t) => {
    const dir = copyFixture(t);
    editYaml(dir, "evidence.yaml", (document) => {
      listItem(document, row.index).ref = "missing-target";
    });
    assertOnly(
      run(dir),
      "EVIDENCE_TARGET_UNRESOLVED",
      "evidence.yaml",
      `/items/${row.index}/ref`
    );
  });
}

test("decision-record evidence requires its target file", (t) => {
  const dir = copyFixture(t);
  removeFile(dir, "decisions/kk-001.yaml");
  assertOnly(
    run(dir),
    "EVIDENCE_TARGET_UNRESOLVED",
    "evidence.yaml",
    "/items/3/ref"
  );
});

test("measurement-report evidence requires its target file", (t) => {
  const dir = copyFixture(t);
  removeFile(dir, "measurements/o-01.yaml");
  const result = run(dir);
  // The same report is also a metric source, so both semantic errors are expected.
  assertHas(result, "EVIDENCE_TARGET_UNRESOLVED", "evidence.yaml", "/items/4/ref");
  assertHas(result, "NUMERIC_CLAIM_WITHOUT_SOURCE", CASE_FILE, "/metrics/0/source/report");
  assert.deepEqual(codes(result), [
    "EVIDENCE_TARGET_UNRESOLVED",
    "NUMERIC_CLAIM_WITHOUT_SOURCE"
  ]);
});

test("a metric report source must resolve to a file", (t) => {
  const dir = copyFixture(t);
  editYaml(dir, CASE_FILE, (document) => {
    record(metric(document).source).report = "missing-report";
  });
  assertOnly(
    run(dir),
    "NUMERIC_CLAIM_WITHOUT_SOURCE",
    CASE_FILE,
    "/metrics/0/source/report"
  );
});

test("experience end cannot be earlier than start", (t) => {
  const dir = copyFixture(t);
  editYaml(dir, "experience.yaml", (document) => {
    listItem(document, 1).end = "2021-06";
  });
  assertOnly(run(dir), "INVALID_PERIOD", "experience.yaml", "/items/1/end");
});

test("education endYear cannot be earlier than startYear", (t) => {
  const dir = copyFixture(t);
  editYaml(dir, "education.yaml", (document) => {
    listItem(document).endYear = 2016;
  });
  assertOnly(run(dir), "INVALID_PERIOD", "education.yaml", "/items/0/endYear");
});

test("experience end equal to start is allowed", (t) => {
  const dir = copyFixture(t);
  editYaml(dir, "experience.yaml", (document) => {
    listItem(document, 1).end = listItem(document, 1).start;
  });
  assertClean(run(dir));
});

for (const language of ["en", "tr"] as const) {
  test(`UI leaf removed from ${language} reports that dictionary`, (t) => {
    const dir = copyFixture(t);
    editYaml(dir, `ui/${language}.yaml`, (document) => {
      delete record(document.navigation).profile;
    });
    // Removing a shared leaf also removes its translation unit.
    const result = run(dir);
    assertHas(result, "UI_KEY_MISMATCH", `ui/${language}.yaml`, "navigation.profile");
    assertHas(result, "LOCK_OUT_OF_DATE", "i18n.lock", "ui#/navigation.profile");
    assert.deepEqual(codes(result), ["LOCK_OUT_OF_DATE", "UI_KEY_MISMATCH"]);
  });
}

test("a UI leaf and group at the same location do not match", (t) => {
  const dir = copyFixture(t);
  editYaml(dir, "ui/en.yaml", (document) => {
    record(document.navigation).profile = { title: "Profile" };
  });
  // The former shared leaf disappears from the lock's current units.
  const result = run(dir);
  assertHas(result, "UI_KEY_MISMATCH", "ui/en.yaml", "navigation.profile");
  assertHas(result, "UI_KEY_MISMATCH", "ui/tr.yaml", "navigation.profile.title");
  assertHas(result, "LOCK_OUT_OF_DATE", "i18n.lock", "ui#/navigation.profile");
  assert.deepEqual(codes(result), [
    "LOCK_OUT_OF_DATE",
    "UI_KEY_MISMATCH",
    "UI_KEY_MISMATCH"
  ]);
});

test("UI placeholder sets must match", (t) => {
  const dir = copyFixture(t);
  editYaml(dir, "ui/en.yaml", (document) => {
    record(document.messages).greeting = "Hello, {person}!";
  });
  // Renaming a placeholder changes English text and makes this unit one-sided.
  const result = run(dir);
  assertHas(result, "UI_PLACEHOLDER_MISMATCH", "ui/en.yaml", "messages.greeting");
  assertHas(result, "STALE_TRANSLATION", "ui/tr.yaml", "ui#/messages.greeting");
  assert.deepEqual(codes(result), ["STALE_TRANSLATION", "UI_PLACEHOLDER_MISMATCH"]);
});

test("a digit in one section language produces exactly one warning", (t) => {
  const dir = copyFixture(t);
  editYaml(dir, CASE_FILE, (document) => {
    const problem = record(document.problem);
    problem.tr = `${String(problem.tr)} Deneme değeri 123.`;
  });
  // This one-sided text change also produces a stale-translation error.
  const result = run(dir);
  assertOnly(result, "STALE_TRANSLATION", CASE_FILE, `${CASE_FILE}#/problem`);
  assert.equal(result.warnings.length, 1);
  const warning = result.warnings[0];
  assert.ok(warning);
  assert.equal(warning.code, "NUMBER_IN_FREE_TEXT");
  assert.equal(warning.severity, "warning");
  assert.equal(warning.file, CASE_FILE);
  assert.equal(warning.path, "/problem/tr");
  assert.match(warning.message, /\b123\b/);
});

test("approved digits in both languages are warnings, not errors", (t) => {
  const dir = copyFixture(t);
  editYaml(dir, CASE_FILE, (document) => {
    const problem = record(document.problem);
    problem.tr = `${String(problem.tr)} Deneme değeri 123.`;
    problem.en = `${String(problem.en)} Trial value 123.`;
  });
  // Both languages changed together, so approval can refresh this unit.
  const approved = approve(dir);
  assert.equal(approved.written, true);
  assert.deepEqual(approved.errors, []);
  const result = run(dir);
  assert.deepEqual(result.errors, []);
  assert.equal(result.warnings.length, 2);
  assert.deepEqual(
    result.warnings.map((issue) => ({
      code: issue.code,
      severity: issue.severity,
      file: issue.file,
      path: issue.path
    })),
    [
      {
        code: "NUMBER_IN_FREE_TEXT",
        severity: "warning",
        file: CASE_FILE,
        path: "/problem/en"
      },
      {
        code: "NUMBER_IN_FREE_TEXT",
        severity: "warning",
        file: CASE_FILE,
        path: "/problem/tr"
      }
    ]
  );
  for (const warning of result.warnings) {
    assert.match(warning.message, /\b123\b/);
  }
});

// Exact test registrations in this file: 26.
