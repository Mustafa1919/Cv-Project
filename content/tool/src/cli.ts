import { resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { parseArgs } from "node:util";
import { approveContent } from "./approve.ts";
import { checkContent } from "./check.ts";
import { approveText, checkText } from "./report.ts";
import { errorMessage } from "./value.ts";

interface CliOptions {
  readonly command: "check" | "approve";
  readonly contentDir: string;
  readonly schemaDir: string;
  readonly format: "text" | "json";
  readonly acceptOneSided: readonly string[];
}

function parseCli(argv: readonly string[]): CliOptions {
  const parsed = parseArgs({
    args: [...argv],
    strict: true,
    allowPositionals: true,
    options: {
      content: { type: "string" },
      schema: { type: "string" },
      format: { type: "string" },
      "accept-one-sided": { type: "string", multiple: true }
    }
  });

  const command = parsed.positionals[0];
  if (
    parsed.positionals.length !== 1 ||
    (command !== "check" && command !== "approve")
  ) {
    throw new Error(
      "Usage: cli.ts <check|approve> [--content <dir>] [--schema <dir>] [--format text|json] [--accept-one-sided <key>]..."
    );
  }

  const format = parsed.values.format ?? "text";
  if (format !== "text" && format !== "json") {
    throw new Error("--format must be text or json");
  }
  if (command === "check" && parsed.values["accept-one-sided"] !== undefined) {
    throw new Error("--accept-one-sided is only valid with approve");
  }

  return {
    command,
    contentDir: parsed.values.content ?? fileURLToPath(new URL("../../", import.meta.url)),
    schemaDir: parsed.values.schema ?? fileURLToPath(new URL("../../schema/", import.meta.url)),
    format,
    acceptOneSided: parsed.values["accept-one-sided"] ?? []
  };
}

export function main(argv: readonly string[]): number {
  let options: CliOptions;
  try {
    options = parseCli(argv);
  } catch (error: unknown) {
    process.stderr.write(`${errorMessage(error)}\n`);
    return 2;
  }

  try {
    if (options.command === "check") {
      const result = checkContent({
        contentDir: options.contentDir,
        schemaDir: options.schemaDir
      });
      process.stdout.write(
        options.format === "json"
          ? `${JSON.stringify(result, null, 2)}\n`
          : checkText(result)
      );
      return result.errors.length === 0 ? 0 : 1;
    }

    const result = approveContent({
      contentDir: options.contentDir,
      schemaDir: options.schemaDir,
      acceptOneSided: options.acceptOneSided
    });
    process.stdout.write(
      options.format === "json"
        ? `${JSON.stringify(result, null, 2)}\n`
        : approveText(result)
    );
    return result.errors.length === 0 ? 0 : 1;
  } catch (error: unknown) {
    process.stderr.write(`${errorMessage(error)}\n`);
    return 2;
  }
}

const entry = process.argv[1];
if (entry !== undefined && resolve(entry) === fileURLToPath(import.meta.url)) {
  process.exitCode = main(process.argv.slice(2));
}
