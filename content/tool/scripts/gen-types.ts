import { mkdir, readdir, rm, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { compileFromFile } from "json-schema-to-typescript";

const schemaDirectory = fileURLToPath(new URL("../../schema/", import.meta.url));
const generatedDirectory = fileURLToPath(new URL("../src/generated/", import.meta.url));

async function main(): Promise<void> {
  const entries = await readdir(schemaDirectory, { withFileTypes: true });
  const schemaNames = entries
    .filter((entry) => entry.isFile() && entry.name.endsWith(".schema.json"))
    .map((entry) => entry.name)
    .sort();

  await rm(generatedDirectory, { recursive: true, force: true });
  await mkdir(generatedDirectory, { recursive: true });

  for (const schemaName of schemaNames) {
    const base = schemaName.slice(0, -".schema.json".length);
    const source = await compileFromFile(join(schemaDirectory, schemaName), {
      cwd: schemaDirectory,
      additionalProperties: false,
      bannerComment: `/**\n * Generated from content/schema/${schemaName}. Do not edit.\n */`
    });
    await writeFile(join(generatedDirectory, `${base}.ts`), source, "utf8");
  }
}

try {
  await main();
} catch (error: unknown) {
  const message = error instanceof Error ? error.message : String(error);
  console.error(`Type generation failed: ${message}`);
  process.exitCode = 1;
}
