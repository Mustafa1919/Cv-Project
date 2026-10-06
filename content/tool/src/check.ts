import { analyzeContent } from "./analyze.ts";
import type { Issue } from "./issue.ts";

export interface CheckOptions {
  readonly contentDir: string;
  readonly schemaDir: string;
}

export interface CheckResult {
  readonly errors: readonly Issue[];
  readonly warnings: readonly Issue[];
}

export function checkContent(options: CheckOptions): CheckResult {
  const analysis = analyzeContent(options.contentDir, options.schemaDir);
  return { errors: analysis.errors, warnings: analysis.warnings };
}
