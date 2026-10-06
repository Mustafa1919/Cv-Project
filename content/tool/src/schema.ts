import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { Ajv2020 } from "ajv/dist/2020.js";
import type { ErrorObject } from "ajv";
import type { Issue, IssueCode } from "./issue.ts";
import { compareText, deduplicateIssues, makeIssue } from "./issue.ts";
import { isRecord, pointerSegment } from "./value.ts";

type SchemaError = ErrorObject<string, Record<string, unknown>, unknown>;

function parameter(error: SchemaError, name: string): string | undefined {
  const value: unknown = error.params[name];
  return typeof value === "string" ? value : undefined;
}

function classify(file: string, error: SchemaError): IssueCode {
  const additional = parameter(error, "additionalProperty");
  const missing = parameter(error, "missingProperty");

  if (
    error.keyword === "additionalProperties" &&
    additional !== undefined &&
    ["level", "percent", "percentage", "proficiency", "rating", "score"]
      .includes(additional)
  ) {
    return "FORBIDDEN_FIELD";
  }

  if (
    !file.startsWith("ui/") &&
    (
      (error.keyword === "required" && (missing === "tr" || missing === "en")) ||
      /\/(?:tr|en)$/.test(error.instancePath)
    )
  ) {
    return "MISSING_TRANSLATION";
  }

  if (
    file === "skills.yaml" &&
    (
      (error.keyword === "minItems" && /^\/items\/\d+\/evidence$/.test(error.instancePath)) ||
      (error.keyword === "required" && missing === "evidence" &&
        /^\/items\/\d+$/.test(error.instancePath))
    )
  ) {
    return "SKILL_WITHOUT_EVIDENCE";
  }

  if (
    file.startsWith("case-studies/") &&
    error.keyword === "required" &&
    error.instancePath === "" &&
    missing !== undefined &&
    ["problem", "decision", "cost", "result"].includes(missing)
  ) {
    return "CASE_STUDY_SECTION_MISSING";
  }

  if (
    file.startsWith("case-studies/") &&
    error.keyword === "required" &&
    missing === "source" &&
    /^\/metrics\/\d+$/.test(error.instancePath)
  ) {
    return "NUMERIC_CLAIM_WITHOUT_SOURCE";
  }

  return "SCHEMA";
}

function schemaIssue(file: string, error: SchemaError): Issue {
  let path = error.instancePath;
  const property = error.keyword === "required"
    ? parameter(error, "missingProperty")
    : error.keyword === "additionalProperties"
      ? parameter(error, "additionalProperty")
      : undefined;

  if (property !== undefined) {
    path += `/${pointerSegment(property)}`;
  }

  return makeIssue(
    classify(file, error),
    file,
    path,
    error.message ?? `Schema validation failed (${error.keyword})`
  );
}

export class SchemaValidator {
  private readonly ajv: Ajv2020;

  constructor(schemaDir: string) {
    this.ajv = new Ajv2020({ allErrors: true, strict: true });
    const files = readdirSync(schemaDir, { withFileTypes: true })
      .filter((entry) => entry.isFile() && entry.name.endsWith(".schema.json"))
      .map((entry) => entry.name)
      .sort(compareText);

    for (const file of files) {
      const schema: unknown = JSON.parse(readFileSync(join(schemaDir, file), "utf8"));
      if (!isRecord(schema)) {
        throw new Error(`Schema ${file} must be an object`);
      }
      this.ajv.addSchema(schema, file);
    }
  }

  validate(file: string, schemaName: string, value: unknown): Issue[] {
    const validate = this.ajv.getSchema(`${schemaName}.schema.json`);
    if (validate === undefined) {
      throw new Error(`Missing schema: ${schemaName}.schema.json`);
    }
    if (validate(value)) {
      return [];
    }
    return deduplicateIssues(
      (validate.errors ?? []).map((error) => schemaIssue(file, error))
    );
  }
}
