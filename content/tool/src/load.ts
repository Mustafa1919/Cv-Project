import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { parse } from "yaml";
import type { Issue } from "./issue.ts";
import { compareText, deduplicateIssues, makeIssue } from "./issue.ts";
import { SchemaValidator } from "./schema.ts";
import { errorMessage, isFile, isRecord } from "./value.ts";

export interface ParsedFile {
  readonly file: string;
  readonly value: unknown;
}

export interface LoadResult {
  readonly files: readonly ParsedFile[];
  readonly errors: readonly Issue[];
}

interface FileSpec {
  readonly file: string;
  readonly schema: string;
}

function caseStudyNames(contentDir: string): string[] {
  try {
    return readdirSync(join(contentDir, "case-studies"), { withFileTypes: true })
      .filter((entry) => entry.isFile() && entry.name.endsWith(".yaml"))
      .map((entry) => entry.name)
      .sort(compareText);
  } catch (error: unknown) {
    if (
      isRecord(error) &&
      (error.code === "ENOENT" || error.code === "ENOTDIR")
    ) {
      return [];
    }
    throw error;
  }
}

export function loadContent(contentDir: string, schemaDir: string): LoadResult {
  const validator = new SchemaValidator(schemaDir);
  const specs: FileSpec[] = [
    { file: "profile.yaml", schema: "profile" },
    { file: "skills.yaml", schema: "skills" },
    { file: "evidence.yaml", schema: "evidence" },
    { file: "experience.yaml", schema: "experience" },
    { file: "education.yaml", schema: "education" },
    { file: "certificates.yaml", schema: "certificates" },
    { file: "projects.yaml", schema: "projects" },
    { file: "ui/tr.yaml", schema: "ui" },
    { file: "ui/en.yaml", schema: "ui" }
  ];
  const errors: Issue[] = [];
  const files: ParsedFile[] = [];
  const names = caseStudyNames(contentDir);

  if (names.length === 0) {
    errors.push(makeIssue(
      "MISSING_FILE",
      "case-studies/",
      "",
      "At least one case study YAML file is required"
    ));
  }

  for (const name of names) {
    specs.push({ file: `case-studies/${name}`, schema: "case-study" });
  }

  for (const spec of specs) {
    const absolutePath = join(contentDir, spec.file);
    if (!isFile(absolutePath)) {
      errors.push(makeIssue("MISSING_FILE", spec.file, "", "Required file is missing"));
      continue;
    }

    const text = readFileSync(absolutePath, "utf8");
    let value: unknown;
    try {
      value = parse(text, {
        version: "1.2",
        schema: "core",
        uniqueKeys: true,
        strict: true
      });
    } catch (error: unknown) {
      errors.push(makeIssue(
        "YAML_PARSE",
        spec.file,
        "",
        errorMessage(error).split(/\r?\n/, 1)[0] ?? "YAML parsing failed"
      ));
      continue;
    }

    files.push({ file: spec.file, value });
    if (value === null) {
      errors.push(makeIssue("SCHEMA", spec.file, "", "Empty YAML document"));
      continue;
    }
    errors.push(...validator.validate(spec.file, spec.schema, value));
  }

  return { files, errors: deduplicateIssues(errors) };
}
