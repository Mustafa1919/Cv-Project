import { createReadStream } from "node:fs";
import { readFile, realpath, stat } from "node:fs/promises";
import { createServer } from "node:http";
import type { IncomingMessage, ServerResponse } from "node:http";
import { basename, dirname, extname, isAbsolute, relative, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";

interface Options {
  readonly port: number;
  readonly host: string;
  readonly directory: string;
}

interface HeaderBlock {
  readonly pattern: string;
  readonly headers: readonly (readonly [string, string])[];
}

interface Entry {
  readonly path: string;
  readonly directory: boolean;
  readonly size: number;
}

const siteDir = fileURLToPath(new URL("../", import.meta.url));

function options(): Options {
  let port = 4173;
  let host = "127.0.0.1";
  let directory = resolve(siteDir, "dist");
  const args = process.argv.slice(2);

  for (let index = 0; index < args.length; index += 2) {
    const flag = args[index];
    const value = args[index + 1];

    if (value === undefined || value.startsWith("--")) {
      throw new Error(`Missing value for ${flag ?? "argument"}.`);
    }

    switch (flag) {
      case "--port":
        if (!/^\d+$/.test(value)) {
          throw new Error(`Invalid port: ${value}`);
        }
        port = Number(value);
        if (!Number.isInteger(port) || port < 1 || port > 65535) {
          throw new Error(`Invalid port: ${value}`);
        }
        break;
      case "--host":
        if (value.length === 0) {
          throw new Error("The host must not be empty.");
        }
        host = value;
        break;
      case "--dir":
        directory = resolve(siteDir, value);
        break;
      default:
        throw new Error(`Unknown argument: ${flag ?? ""}`);
    }
  }

  return { port, host, directory };
}

function inside(root: string, path: string): boolean {
  const remainder = relative(root, path);
  return (
    remainder === "" ||
    (
      remainder !== ".." &&
      !remainder.startsWith(`..${sep}`) &&
      !isAbsolute(remainder)
    )
  );
}

function parseHeaders(source: string): HeaderBlock[] {
  const blocks: HeaderBlock[] = [];
  let pattern: string | undefined;
  let headers: [string, string][] = [];

  function flush(): void {
    if (pattern !== undefined) {
      blocks.push({ pattern, headers });
    }
    pattern = undefined;
    headers = [];
  }

  for (const line of source.split(/\r?\n/)) {
    if (line.trim() === "" || line.trimStart().startsWith("#")) {
      continue;
    }

    if (!/^\s/.test(line)) {
      flush();
      pattern = line.trim();
      continue;
    }

    if (pattern === undefined) {
      throw new Error("A header line appears before its path pattern.");
    }

    const match = /^\s+([^:]+):\s*(.*)$/.exec(line);
    const name = match?.[1]?.trim();
    const value = match?.[2];
    if (name === undefined || value === undefined || name === "") {
      throw new Error(`Invalid _headers line: ${line}`);
    }
    headers.push([name, value]);
  }

  flush();
  return blocks;
}

function applyHeaders(
  response: ServerResponse,
  path: string,
  blocks: readonly HeaderBlock[],
): void {
  for (const block of blocks) {
    const matches = block.pattern.endsWith("*")
      ? path.startsWith(block.pattern.slice(0, -1))
      : path === block.pattern;
    if (matches) {
      for (const [name, value] of block.headers) {
        response.setHeader(name, value);
      }
    }
  }
}

const mimeTypes: Readonly<Record<string, string>> = {
  ".html": "text/html; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".mjs": "text/javascript; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".xml": "application/xml; charset=utf-8",
  ".txt": "text/plain; charset=utf-8",
  ".svg": "image/svg+xml; charset=utf-8",
  ".png": "image/png",
  ".ico": "image/x-icon",
  ".woff": "font/woff",
  ".woff2": "font/woff2",
  ".pdf": "application/pdf",
  ".webmanifest": "application/manifest+json; charset=utf-8",
};

function sendText(
  request: IncomingMessage,
  response: ServerResponse,
  status: number,
  text: string,
): void {
  response.statusCode = status;
  response.setHeader("Content-Type", "text/plain; charset=utf-8");
  response.setHeader("Content-Length", Buffer.byteLength(text));
  response.end(request.method === "HEAD" ? undefined : text);
}

async function main(): Promise<void> {
  const settings = options();
  const root = await realpath(settings.directory);
  const rootInfo = await stat(root);
  if (!rootInfo.isDirectory()) {
    throw new Error(`Not a directory: ${settings.directory}`);
  }

  let blocks: HeaderBlock[] = [];
  try {
    blocks = parseHeaders(await readFile(resolve(root, "_headers"), "utf8"));
  } catch (error: unknown) {
    if (
      !(error instanceof Error && "code" in error && error.code === "ENOENT")
    ) {
      throw error;
    }
  }

  async function entry(path: string): Promise<Entry | undefined> {
    if (!inside(root, path)) {
      return undefined;
    }

    try {
      const actualPath = await realpath(path);
      if (!inside(root, actualPath) || basename(actualPath) === "_headers") {
        return undefined;
      }

      const info = await stat(actualPath);
      if (!info.isDirectory() && !info.isFile()) {
        return undefined;
      }

      return {
        path: actualPath,
        directory: info.isDirectory(),
        size: info.size,
      };
    } catch (error: unknown) {
      if (
        error instanceof Error &&
        "code" in error &&
        ["ENOENT", "ENOTDIR", "EACCES", "ELOOP"].includes(String(error.code))
      ) {
        return undefined;
      }
      throw error;
    }
  }

  async function nearestNotFound(start: string): Promise<Entry | undefined> {
    let directory = start;

    while (inside(root, directory)) {
      const candidate = await entry(resolve(directory, "404.html"));
      if (candidate !== undefined && !candidate.directory) {
        return candidate;
      }
      if (directory === root) {
        break;
      }
      directory = dirname(directory);
    }
    return undefined;
  }

  function serveFile(
    request: IncomingMessage,
    response: ServerResponse,
    file: Entry,
    status: number,
  ): void {
    response.statusCode = status;
    response.setHeader(
      "Content-Type",
      mimeTypes[extname(file.path).toLowerCase()] ?? "application/octet-stream",
    );
    response.setHeader("Content-Length", file.size);

    if (request.method === "HEAD") {
      response.end();
      return;
    }

    const stream = createReadStream(file.path);
    stream.on("error", () => {
      if (!response.headersSent) {
        response.removeHeader("Content-Length");
        sendText(request, response, 500, "Internal server error.\n");
      } else {
        response.destroy();
      }
    });
    response.on("close", () => stream.destroy());
    stream.pipe(response);
  }

  async function handle(
    request: IncomingMessage,
    response: ServerResponse,
  ): Promise<void> {
    const target = request.url ?? "/";
    const queryIndex = target.indexOf("?");
    const rawPath = queryIndex < 0 ? target : target.slice(0, queryIndex);
    const query = queryIndex < 0 ? "" : target.slice(queryIndex);

    let path: string;
    try {
      path = decodeURIComponent(rawPath);
    } catch {
      applyHeaders(response, "/", blocks);
      sendText(request, response, 400, "Invalid request path.\n");
      return;
    }

    if (!path.startsWith("/") || /[\u0000\\]/u.test(path)) {
      applyHeaders(response, "/", blocks);
      sendText(request, response, 400, "Invalid request path.\n");
      return;
    }

    applyHeaders(response, path, blocks);

    if (request.method !== "GET" && request.method !== "HEAD") {
      response.setHeader("Allow", "GET, HEAD");
      sendText(request, response, 405, "Method not allowed.\n");
      return;
    }

    const candidatePath = resolve(root, `.${path}`);
    if (!inside(root, candidatePath)) {
      sendText(request, response, 403, "Forbidden.\n");
      return;
    }

    const forbiddenHeaders = basename(candidatePath) === "_headers";
    const candidate = forbiddenHeaders
      ? undefined
      : await entry(candidatePath);
    let file: Entry | undefined;
    let searchDirectory = path.endsWith("/")
      ? candidatePath
      : dirname(candidatePath);

    if (candidate?.directory === true) {
      searchDirectory = candidatePath;
      const index = await entry(resolve(candidatePath, "index.html"));

      if (index !== undefined && !index.directory) {
        if (!path.endsWith("/")) {
          const relativePath = relative(root, candidatePath);
          const canonicalPath = relativePath === ""
            ? "/"
            : `/${relativePath.split(sep).map(encodeURIComponent).join("/")}/`;
          response.statusCode = 308;
          response.setHeader("Location", `${canonicalPath}${query}`);
          response.setHeader("Content-Length", "0");
          response.end();
          return;
        }
        file = index;
      }
    } else if (candidate !== undefined && !path.endsWith("/")) {
      file = candidate;
    }

    if (file !== undefined) {
      serveFile(request, response, file, 200);
      return;
    }

    const notFound = await nearestNotFound(searchDirectory);
    if (notFound !== undefined) {
      serveFile(request, response, notFound, 404);
    } else {
      sendText(request, response, 404, "Not found.\n");
    }
  }

  const server = createServer((request, response) => {
    void handle(request, response).catch(() => {
      if (!response.headersSent) {
        sendText(request, response, 500, "Internal server error.\n");
      } else {
        response.destroy();
      }
    });
  });

  server.on("error", (error: Error) => {
    console.error(error.message);
    process.exitCode = 1;
  });

  server.listen(settings.port, settings.host, () => {
    const address = server.address();
    const port = typeof address === "object" && address !== null
      ? address.port
      : settings.port;
    const host = settings.host.includes(":")
      ? `[${settings.host}]`
      : settings.host;
    console.log(`Serving ${root} at http://${host}:${port}`);
  });

  let closing = false;
  function shutdown(): void {
    if (closing) {
      return;
    }
    closing = true;
    server.close((error?: Error) => {
      if (error !== undefined) {
        console.error(error.message);
        process.exitCode = 1;
      }
    });
    server.closeIdleConnections();
  }

  process.once("SIGINT", shutdown);
  process.once("SIGTERM", shutdown);
}

main().catch((error: unknown) => {
  console.error(error instanceof Error ? error.message : String(error));
  process.exitCode = 1;
});
