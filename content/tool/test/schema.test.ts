import assert from "node:assert/strict";
import { test } from "node:test";
import {
  array,
  assertClean,
  assertOnly,
  copyFixture,
  editYaml,
  listItem,
  metric,
  record,
  removeFile,
  run,
  writeText
} from "./helpers.ts";

const CASE_FILE = "case-studies/queue-rewrite.yaml";

test("valid fixture has no errors or warnings", (t) => {
  assertClean(run(copyFixture(t)));
});

const requiredFiles = [
  "profile.yaml",
  "skills.yaml",
  "evidence.yaml",
  "experience.yaml",
  "education.yaml",
  "certificates.yaml",
  "projects.yaml",
  "ui/tr.yaml",
  "ui/en.yaml"
];

for (const file of requiredFiles) {
  test(`missing required file: ${file}`, (t) => {
    const dir = copyFixture(t);
    removeFile(dir, file);
    const result = run(dir);
    assertOnly(result, "MISSING_FILE", file, "");
    assert.equal(result.errors.length, 1);
    assert.deepEqual(result.warnings, []);
  });
}

test("missing every case study reports the directory", (t) => {
  const dir = copyFixture(t);
  removeFile(dir, "case-studies");
  const result = run(dir);
  assertOnly(result, "MISSING_FILE", "case-studies/", "");
  assert.equal(result.errors.length, 1);
});

test("syntactically broken YAML is a parse error", (t) => {
  const dir = copyFixture(t);
  writeText(dir, "profile.yaml", "name: [unterminated\n");
  const result = run(dir);
  assertOnly(result, "YAML_PARSE", "profile.yaml", "");
  assert.equal(result.errors.length, 1);
  assert.ok(!result.errors[0]?.message.includes("\n"));
});

test("duplicate YAML mapping keys are parse errors", (t) => {
  const dir = copyFixture(t);
  writeText(dir, "profile.yaml", "name: Deniz Örnek\nname: Deniz Örnek\n");
  const result = run(dir);
  assertOnly(result, "YAML_PARSE", "profile.yaml", "");
  assert.equal(result.errors.length, 1);
});

test("an empty YAML document is a schema error", (t) => {
  const dir = copyFixture(t);
  writeText(dir, "profile.yaml", "");
  const result = run(dir);
  assertOnly(result, "SCHEMA", "profile.yaml", "");
  assert.equal(result.errors.length, 1);
});

for (const field of [
  "level",
  "percent",
  "percentage",
  "proficiency",
  "rating",
  "score"
]) {
  test(`skill claim rejects forbidden field ${field}`, (t) => {
    const dir = copyFixture(t);
    editYaml(dir, "skills.yaml", (document) => {
      listItem(document)[field] = 3;
    });
    assertOnly(
      run(dir),
      "FORBIDDEN_FIELD",
      "skills.yaml",
      `/items/0/${field}`
    );
  });
}

test("experience rejects a forbidden level field", (t) => {
  const dir = copyFixture(t);
  editYaml(dir, "experience.yaml", (document) => {
    listItem(document).level = "expert";
  });
  assertOnly(run(dir), "FORBIDDEN_FIELD", "experience.yaml", "/items/0/level");
});

test("an unknown nickname field is SCHEMA, not FORBIDDEN_FIELD", (t) => {
  const dir = copyFixture(t);
  editYaml(dir, "skills.yaml", (document) => {
    listItem(document).nickname = "fictional";
  });
  assertOnly(run(dir), "SCHEMA", "skills.yaml", "/items/0/nickname");
});

for (const language of ["en", "tr"] as const) {
  test(`missing profile summary ${language} is a missing translation`, (t) => {
    const dir = copyFixture(t);
    editYaml(dir, "profile.yaml", (document) => {
      delete record(document.summary)[language];
    });
    assertOnly(
      run(dir),
      "MISSING_TRANSLATION",
      "profile.yaml",
      `/summary/${language}`
    );
  });
}

test("empty English text is a missing translation", (t) => {
  const dir = copyFixture(t);
  editYaml(dir, "profile.yaml", (document) => {
    record(document.summary).en = "";
  });
  assertOnly(run(dir), "MISSING_TRANSLATION", "profile.yaml", "/summary/en");
});

test("whitespace-only Turkish text is a missing translation", (t) => {
  const dir = copyFixture(t);
  editYaml(dir, "profile.yaml", (document) => {
    record(document.summary).tr = " \t\n ";
  });
  assertOnly(run(dir), "MISSING_TRANSLATION", "profile.yaml", "/summary/tr");
});

test("a case study section requires both languages", (t) => {
  const dir = copyFixture(t);
  editYaml(dir, CASE_FILE, (document) => {
    delete record(document.problem).en;
  });
  assertOnly(run(dir), "MISSING_TRANSLATION", CASE_FILE, "/problem/en");
});

test("a highlight text requires both languages", (t) => {
  const dir = copyFixture(t);
  editYaml(dir, "profile.yaml", (document) => {
    delete record(record(array(document.highlights)[0]).text).tr;
  });
  assertOnly(
    run(dir),
    "MISSING_TRANSLATION",
    "profile.yaml",
    "/highlights/0/text/tr"
  );
});

test("an empty skill evidence array is rejected", (t) => {
  const dir = copyFixture(t);
  editYaml(dir, "skills.yaml", (document) => {
    listItem(document).evidence = [];
  });
  assertOnly(
    run(dir),
    "SKILL_WITHOUT_EVIDENCE",
    "skills.yaml",
    "/items/0/evidence"
  );
});

test("a missing skill evidence key is rejected", (t) => {
  const dir = copyFixture(t);
  editYaml(dir, "skills.yaml", (document) => {
    delete listItem(document).evidence;
  });
  assertOnly(
    run(dir),
    "SKILL_WITHOUT_EVIDENCE",
    "skills.yaml",
    "/items/0/evidence"
  );
});

for (const section of ["problem", "decision", "cost", "result"]) {
  test(`missing case study section ${section}`, (t) => {
    const dir = copyFixture(t);
    editYaml(dir, CASE_FILE, (document) => {
      delete document[section];
    });
    assertOnly(
      run(dir),
      "CASE_STUDY_SECTION_MISSING",
      CASE_FILE,
      `/${section}`
    );
  });
}

test("a metric must have a source", (t) => {
  const dir = copyFixture(t);
  editYaml(dir, CASE_FILE, (document) => {
    delete metric(document).source;
  });
  assertOnly(
    run(dir),
    "NUMERIC_CLAIM_WITHOUT_SOURCE",
    CASE_FILE,
    "/metrics/0/source"
  );
});

test("HTTP links are rejected", (t) => {
  const dir = copyFixture(t);
  editYaml(dir, "profile.yaml", (document) => {
    record(array(document.links)[0]).url = "http://example.com/deniz";
  });
  assertOnly(run(dir), "SCHEMA", "profile.yaml", "/links/0/url");
});

test("uppercase IDs are rejected", (t) => {
  const dir = copyFixture(t);
  editYaml(dir, "skills.yaml", (document) => {
    listItem(document).id = "TypeScript";
  });
  assertOnly(run(dir), "SCHEMA", "skills.yaml", "/items/0/id");
});

for (const years of [61, 2.5]) {
  test(`invalid skill years: ${years}`, (t) => {
    const dir = copyFixture(t);
    editYaml(dir, "skills.yaml", (document) => {
      listItem(document).years = years;
    });
    assertOnly(run(dir), "SCHEMA", "skills.yaml", "/items/0/years");
  });
}

test("four highlights are rejected", (t) => {
  const dir = copyFixture(t);
  editYaml(dir, "profile.yaml", (document) => {
    const highlights = array(document.highlights);
    highlights.push(structuredClone(highlights[0]));
  });
  assertOnly(run(dir), "SCHEMA", "profile.yaml", "/highlights");
});

test("zero highlights are rejected", (t) => {
  const dir = copyFixture(t);
  editYaml(dir, "profile.yaml", (document) => {
    document.highlights = [];
  });
  assertOnly(run(dir), "SCHEMA", "profile.yaml", "/highlights");
});

test("an invalid year-month is rejected", (t) => {
  const dir = copyFixture(t);
  editYaml(dir, "experience.yaml", (document) => {
    listItem(document).start = "2024-13";
  });
  assertOnly(run(dir), "SCHEMA", "experience.yaml", "/items/0/start");
});

test("repository evidence requires a URL", (t) => {
  const dir = copyFixture(t);
  editYaml(dir, "evidence.yaml", (document) => {
    delete listItem(document, 5).url;
  });
  assertOnly(run(dir), "SCHEMA", "evidence.yaml", "/items/5/url");
});

test("project evidence cannot use URL instead of ref", (t) => {
  const dir = copyFixture(t);
  editYaml(dir, "evidence.yaml", (document) => {
    const item = listItem(document);
    delete item.ref;
    item.url = "https://example.com/projects/queue-service";
  });
  assertOnly(run(dir), "SCHEMA", "evidence.yaml", "/items/0/ref");
});

test("a metric source cannot contain both report and URL", (t) => {
  const dir = copyFixture(t);
  editYaml(dir, CASE_FILE, (document) => {
    record(metric(document).source).url = "https://example.com/measurements/queue";
  });
  assertOnly(run(dir), "SCHEMA", CASE_FILE, "/metrics/0/source");
});

test("an empty UI leaf is rejected", (t) => {
  const dir = copyFixture(t);
  editYaml(dir, "ui/en.yaml", (document) => {
    record(document.navigation).profile = "";
  });
  assertOnly(run(dir), "SCHEMA", "ui/en.yaml", "/navigation/profile");
});

test("a UI key starting with uppercase is rejected", (t) => {
  const dir = copyFixture(t);
  editYaml(dir, "ui/en.yaml", (document) => {
    const navigation = record(document.navigation);
    navigation.Profile = navigation.profile;
    delete navigation.profile;
  });
  assertOnly(run(dir), "SCHEMA", "ui/en.yaml");
});

test("phase one errors hide semantic and lock errors", (t) => {
  const dir = copyFixture(t);
  editYaml(dir, "profile.yaml", (document) => {
    // One document mutation deliberately combines a phase-one and phase-two defect.
    delete record(document.summary).en;
    record(array(document.highlights)[0]).evidence = "missing-evidence";
  });
  const result = run(dir);
  assertOnly(result, "MISSING_TRANSLATION", "profile.yaml", "/summary/en");
  assert.equal(result.errors.length, 1);
  assert.deepEqual(result.warnings, []);
});

// Exact test registrations in this file: 48.
