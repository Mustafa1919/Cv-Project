import { spawn } from "node:child_process";
import type { ChildProcess } from "node:child_process";
import { randomUUID } from "node:crypto";
import { rename, rm, stat, writeFile } from "node:fs/promises";
import { createServer } from "node:net";
import { dirname, join, relative, sep } from "node:path";
import { setTimeout as delay } from "node:timers/promises";
import { fileURLToPath } from "node:url";
import { chromium } from "@playwright/test";
import type { Browser } from "@playwright/test";
import { PDFDocument } from "@cantoo/pdf-lib";
import { inspectFonts } from "./pdf-inspect.ts";

const siteDirectory = fileURLToPath(new URL("../", import.meta.url));
const outputDirectory = join(siteDirectory, "dist");

interface PrintDocument {
  readonly path: string;
  readonly htmlFile: string;
  readonly pdfFile: string;
}

interface PageMetadata {
  readonly title: string;
  readonly author: string;
  readonly subject: string;
  readonly language: string;
}

const documents: readonly PrintDocument[] = [
  {
    path: "/cv/",
    htmlFile: join(outputDirectory, "cv", "index.html"),
    pdfFile: join(outputDirectory, "cv.pdf"),
  },
  {
    path: "/en/cv/",
    htmlFile: join(outputDirectory, "en", "cv", "index.html"),
    pdfFile: join(outputDirectory, "en", "cv.pdf"),
  },
];

function relativePath(path: string): string {
  return relative(siteDirectory, path).split(sep).join("/");
}

async function requirePrintPages(): Promise<void> {
  for (const document of documents) {
    let isFile = false;
    try {
      isFile = (await stat(document.htmlFile)).isFile();
    } catch (error: unknown) {
      const code =
        error !== null && typeof error === "object" && "code" in error
          ? error.code
          : undefined;
      if (code !== "ENOENT" && code !== "ENOTDIR") {
        throw error;
      }
    }

    if (!isFile) {
      throw new Error(
        `PDF build: missing ${relativePath(document.htmlFile)}. Run astro build first.`,
      );
    }
  }
}

async function freePort(): Promise<number> {
  const server = createServer();

  await new Promise<void>((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      server.removeListener("error", reject);
      resolve();
    });
  });

  try {
    const address = server.address();
    if (address === null || typeof address === "string") {
      throw new Error("PDF build: could not determine the static server port.");
    }
    return address.port;
  } finally {
    await new Promise<void>((resolve, reject) => {
      server.close((error) => {
        if (error !== undefined) {
          reject(error);
        } else {
          resolve();
        }
      });
    });
  }
}

async function waitForServer(
  origin: string,
  assertRunning: () => void,
): Promise<void> {
  const deadline = Date.now() + 15_000;

  while (Date.now() < deadline) {
    assertRunning();

    try {
      const response = await fetch(`${origin}/`, {
        signal: AbortSignal.timeout(
          Math.max(1, Math.min(1_000, deadline - Date.now())),
        ),
        redirect: "manual",
      });
      const ready = response.status === 200;
      await response.body?.cancel();
      assertRunning();
      if (ready) {
        return;
      }
    } catch {
      assertRunning();
    }

    const remaining = deadline - Date.now();
    if (remaining > 0) {
      await delay(Math.min(100, remaining));
    }
  }

  assertRunning();
  throw new Error(
    `PDF build: static server at ${origin} did not answer 200 within 15 seconds.`,
  );
}

async function waitForClose(
  closed: Promise<void>,
  timeout: number,
): Promise<boolean> {
  let timer: ReturnType<typeof setTimeout> | undefined;

  try {
    return await Promise.race([
      closed.then(() => true),
      new Promise<boolean>((resolve) => {
        timer = setTimeout(() => resolve(false), timeout);
      }),
    ]);
  } finally {
    if (timer !== undefined) {
      clearTimeout(timer);
    }
  }
}

async function stopServer(
  child: ChildProcess,
  closed: Promise<void>,
  hasClosed: () => boolean,
): Promise<void> {
  if (hasClosed()) {
    return;
  }

  child.kill("SIGTERM");
  if (await waitForClose(closed, 3_000)) {
    return;
  }

  child.kill("SIGKILL");
  if (!(await waitForClose(closed, 3_000))) {
    throw new Error("PDF build: static server did not terminate after SIGKILL.");
  }
}

function assertNoProblems(problems: readonly string[], path: string): void {
  if (problems.length > 0) {
    throw new Error(
      `PDF build: rendering ${path} failed:\n${problems.join("\n")}`,
    );
  }
}

async function renderPdf(
  browser: Browser,
  origin: string,
  path: string,
  assertRunning: () => void,
): Promise<Uint8Array> {
  assertRunning();
  const page = await browser.newPage();
  const problems: string[] = [];

  page.on("console", (message) => {
    if (message.type() === "error") {
      problems.push(`Console error: ${message.text()}`);
    }
  });

  page.on("pageerror", (error) => {
    problems.push(`Page error: ${error.message}`);
  });

  page.on("requestfailed", (request) => {
    if (new URL(request.url()).origin === origin) {
      problems.push(
        `Request failed: ${request.url()} (${request.failure()?.errorText ?? "unknown error"})`,
      );
    }
  });

  page.on("response", (response) => {
    if (
      new URL(response.url()).origin === origin &&
      response.status() >= 400
    ) {
      problems.push(`HTTP ${response.status()}: ${response.url()}`);
    }
  });

  try {
    assertRunning();
    const response = await page.goto(`${origin}${path}`, {
      waitUntil: "load",
      timeout: 15_000,
    });
    assertRunning();

    if (response === null || response.status() !== 200) {
      throw new Error(
        `PDF build: ${path} returned ${response?.status() ?? "no response"}; expected 200.`,
      );
    }

    await page.evaluate(async () => {
      await document.fonts.ready;
    });
    assertRunning();
    assertNoProblems(problems, path);

    await page.emulateMedia({ media: "print" });
    await page.evaluate(async () => {
      await document.fonts.ready;
    });

    const metadata: PageMetadata = await page.evaluate(() => ({
      title: document.title,
      author:
        document.querySelector('meta[name="author"]')?.getAttribute("content") ??
        "",
      subject:
        document
          .querySelector('meta[name="description"]')
          ?.getAttribute("content") ?? "",
      language: document.documentElement.lang,
    }));

    if (metadata.title.trim() === "" || metadata.author.trim() === "") {
      throw new Error(`PDF build: ${path} must provide a non-empty Title and Author.`);
    }

    assertRunning();
    assertNoProblems(problems, path);

    const bytes = await page.pdf({
      format: "A4",
      printBackground: true,
      tagged: true,
      preferCSSPageSize: true,
    });

    assertRunning();
    assertNoProblems(problems, path);

    const pdf = await PDFDocument.load(bytes, { updateMetadata: false });
    pdf.setTitle(metadata.title);
    pdf.setAuthor(metadata.author);
    pdf.setSubject(metadata.subject);
    pdf.setLanguage(metadata.language);
    pdf.setCreator("vitrin");

    return await pdf.save();
  } finally {
    await page.close();
  }
}

async function validatePdf(bytes: Uint8Array, path: string): Promise<number> {
  const document = await PDFDocument.load(bytes, { updateMetadata: false });
  const pageCount = document.getPageCount();

  if (pageCount !== 1) {
    throw new Error(
      `PDF build: ${path} has ${pageCount} pages; exactly one A4 page is required.`,
    );
  }

  if (
    (document.getTitle() ?? "").trim() === "" ||
    (document.getAuthor() ?? "").trim() === ""
  ) {
    throw new Error(`PDF build: ${path} has an empty Title or Author.`);
  }

  const { fonts, descriptors } = await inspectFonts(bytes);

  for (const font of fonts) {
    if (font.subtype === "Type3") {
      throw new Error(
        `PDF build: ${path} contains a Type3 font (${font.baseFont ?? "unnamed"}). Use static embedded fonts.`,
      );
    }
  }

  for (const descriptor of descriptors) {
    if (!descriptor.embedded) {
      throw new Error(
        `PDF build: ${path} contains an unembedded font (${descriptor.fontName ?? "unnamed"}).`,
      );
    }
  }

  return pageCount;
}

async function writeAtomically(
  destination: string,
  bytes: Uint8Array,
): Promise<void> {
  const temporary = join(
    dirname(destination),
    `.${relativePath(destination).split("/").pop() ?? "cv.pdf"}.${randomUUID()}.tmp`,
  );

  try {
    await writeFile(temporary, bytes, { flag: "wx" });
    await rename(temporary, destination);
  } finally {
    await rm(temporary, { force: true });
  }
}

async function main(): Promise<void> {
  await requirePrintPages();
  const port = await freePort();
  const origin = `http://127.0.0.1:${port}`;

  const child = spawn(
    process.execPath,
    [
      "--experimental-strip-types",
      "--disable-warning=ExperimentalWarning",
      join(siteDirectory, "scripts", "serve.ts"),
      "--port",
      String(port),
      "--host",
      "127.0.0.1",
      "--dir",
      outputDirectory,
    ],
    {
      cwd: siteDirectory,
      stdio: ["ignore", "pipe", "pipe"],
    },
  );

  let serverOutput = "";
  let serverFailure: Error | undefined;
  let serverClosed = false;
  let shuttingDown = false;

  const captureOutput = (chunk: string): void => {
    serverOutput = (serverOutput + chunk).slice(-16_384);
  };

  child.stdout?.setEncoding("utf8");
  child.stderr?.setEncoding("utf8");
  child.stdout?.on("data", captureOutput);
  child.stderr?.on("data", captureOutput);

  child.once("error", (error) => {
    serverFailure = new Error(
      `PDF build: could not start the static server: ${error.message}`,
    );
  });

  child.once("exit", (code, signal) => {
    if (!shuttingDown && serverFailure === undefined) {
      const details = serverOutput.trim();
      serverFailure = new Error(
        `PDF build: static server exited early with exit code ${code ?? "null"}${signal === null ? "" : ` (signal ${signal})`}.${details === "" ? "" : `\n${details}`}`,
      );
    }
  });

  const closed = new Promise<void>((resolve) => {
    child.once("close", () => {
      serverClosed = true;
      resolve();
    });
  });

  const assertRunning = (): void => {
    if (serverFailure !== undefined) {
      throw serverFailure;
    }
    if (serverClosed) {
      throw new Error("PDF build: static server closed unexpectedly.");
    }
  };

  try {
    await waitForServer(origin, assertRunning);
    const browser = await chromium.launch();

    try {
      assertRunning();

      // Render and validate both documents before publishing either one.
      const results: {
        document: PrintDocument;
        bytes: Uint8Array;
        pageCount: number;
      }[] = [];

      for (const document of documents) {
        const bytes = await renderPdf(
          browser,
          origin,
          document.path,
          assertRunning,
        );
        const pageCount = await validatePdf(bytes, relativePath(document.pdfFile));
        assertRunning();
        results.push({ document, bytes, pageCount });
      }

      for (const { document, bytes, pageCount } of results) {
        assertRunning();
        await writeAtomically(document.pdfFile, bytes);
        console.log(
          `${relativePath(document.pdfFile)} — ${bytes.byteLength} bytes — ${pageCount} page`,
        );
      }

      assertRunning();
    } finally {
      await browser.close();
    }

    assertRunning();
  } catch (error: unknown) {
    throw serverFailure ?? error;
  } finally {
    shuttingDown = true;
    await stopServer(child, closed, () => serverClosed);
  }
}

main().catch((error: unknown) => {
  console.error(error instanceof Error ? error.message : String(error));
  process.exitCode = 1;
});
