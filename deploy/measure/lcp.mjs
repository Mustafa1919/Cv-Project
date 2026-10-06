#!/usr/bin/env node
import { spawnSync } from 'node:child_process';
import {
  existsSync,
  mkdirSync,
  readFileSync,
  writeFileSync,
} from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

process.env.LC_ALL = 'C';

const usage = () => {
  console.log(
    'Usage: node deploy/measure/lcp.mjs --site <origin> [--runs 5] [--out <dir>] [--chrome <path>]',
  );
};

function usageError(message) {
  console.error(`ERROR: ${message}`);
  usage();
  process.exit(2);
}

function fail(message) {
  console.error(`ERROR: ${message}`);
  console.log('DECISION: measurement failed');
  process.exit(1);
}

let site = '';
let runsText = '5';
let out = '';
let chrome;

const args = process.argv.slice(2);
for (let i = 0; i < args.length; i += 1) {
  const flag = args[i];
  if (flag === '--help') {
    usage();
    process.exit(0);
  }
  if (!['--site', '--runs', '--out', '--chrome'].includes(flag)) {
    usageError(`Unknown argument: ${flag}`);
  }
  if (i + 1 >= args.length || args[i + 1] === '') {
    usageError(`Missing value for ${flag}.`);
  }
  const value = args[++i];
  if (flag === '--site') site = value;
  if (flag === '--runs') runsText = value;
  if (flag === '--out') out = value;
  if (flag === '--chrome') chrome = value;
}

if (!site) usageError('--site is required.');
if (!/^[0-9]+$/.test(runsText)) {
  usageError('--runs must be a positive safe integer.');
}
const runs = Number(runsText);
if (!Number.isSafeInteger(runs) || runs < 1) {
  usageError('--runs must be a positive safe integer.');
}

let origin;
try {
  const parsed = new URL(site);
  if (
    !['http:', 'https:'].includes(parsed.protocol) ||
    parsed.username ||
    parsed.password ||
    parsed.search ||
    parsed.hash ||
    parsed.pathname !== '/' ||
    !/^https?:\/\/[^/?#@\s]+\/?$/.test(site)
  ) {
    usageError('--site must be an HTTP(S) origin without credentials.');
  }
  origin = parsed.origin;
} catch {
  usageError('--site must be a valid HTTP(S) origin.');
}

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(scriptDir, '..', '..');
const lighthouseCli = path.join(
  repo,
  'node_modules',
  'lighthouse',
  'cli',
  'index.js',
);

if (!existsSync(lighthouseCli)) {
  fail('Lighthouse CLI is missing; run npm ci in the repository root.');
}

const started = new Date();
const timestamp = started.toISOString().replace(/[-:]/g, '').replace(/\.\d{3}Z$/, 'Z');
if (!out) out = path.join('.', 'measurements', `lcp-${timestamp}`);
const outputDir = path.resolve(out);

const childEnv = { ...process.env, LC_ALL: 'C' };
if (chrome !== undefined) {
  childEnv.CHROME_PATH = chrome;
} else if (process.env.CHROME_PATH) {
  childEnv.CHROME_PATH = process.env.CHROME_PATH;
}

const summary = {
  startedAt: started.toISOString(),
  site: origin,
  runsPerPage: runs,
  preset: 'Lighthouse default mobile',
  scope: 'Static host and network path, not the application server',
  decisionRule: {
    medianLcpMsExclusiveUpperBound: 2500,
    requiredPages: ['/', '/en/'],
  },
  pages: [],
  completed: false,
};

function saveSummary() {
  writeFileSync(
    path.join(outputDir, 'summary.json'),
    `${JSON.stringify(summary, null, 2)}\n`,
    'utf8',
  );
}

function median(values) {
  const sorted = [...values].sort((a, b) => a - b);
  // awk keeps decimal arithmetic consistent across the tools.
  const result = spawnSync(
    'awk',
    [
      '{ value[++n]=$1 } END { if (!n) exit 1; if (n % 2) printf "%.17g\\n", value[(n+1)/2]; else printf "%.17g\\n", (value[n/2]+value[n/2+1])/2 }',
    ],
    {
      shell: false,
      env: childEnv,
      input: `${sorted.join('\n')}\n`,
      encoding: 'utf8',
      stdio: ['pipe', 'pipe', 'pipe'],
    },
  );
  if (result.error || result.status !== 0) {
    throw new Error('Could not calculate the median with awk.');
  }
  const value = Number(result.stdout.trim());
  if (!Number.isFinite(value)) {
    throw new Error('The median calculation returned an invalid value.');
  }
  return value;
}

function metric(value, name) {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 0) {
    throw new Error(`Missing or invalid ${name} metric.`);
  }
  return value;
}

try {
  mkdirSync(outputDir, { recursive: true });
  saveSummary();

  console.log('This measures the static host and network path, not the application server.');
  console.log(`Output: ${out}`);

  for (const page of [
    { name: 'tr', pathname: '/' },
    { name: 'en', pathname: '/en/' },
  ]) {
    const url = new URL(page.pathname, origin).href;
    const pageSummary = {
      page: page.name,
      url,
      samples: [],
    };
    summary.pages.push(pageSummary);

    for (let run = 1; run <= runs; run += 1) {
      const filename = `${page.name}-${run}.json`;
      const reportPath = path.join(outputDir, filename);

      const result = spawnSync(
        process.execPath,
        [
          lighthouseCli,
          url,
          '--output=json',
          `--output-path=${reportPath}`,
          '--only-categories=performance',
          '--quiet',
          '--chrome-flags=--headless=new --no-sandbox',
        ],
        {
          shell: false,
          cwd: repo,
          env: childEnv,
          // CLI diagnostics may contain response data, so do not relay them.
          stdio: ['ignore', 'ignore', 'ignore'],
        },
      );
      if (result.error || result.status !== 0) {
        throw new Error(`Lighthouse failed for ${page.name}, run ${run}.`);
      }

      let report;
      try {
        report = JSON.parse(readFileSync(reportPath, 'utf8'));
      } catch {
        throw new Error(`Could not read ${filename} as a JSON report.`);
      }

      if (!report || typeof report !== 'object' || report.runtimeError) {
        throw new Error(`Lighthouse reported a runtime error in ${filename}.`);
      }

      const lcpMs = metric(
        report.audits?.['largest-contentful-paint']?.numericValue,
        'Largest Contentful Paint',
      );
      const cls = metric(
        report.audits?.['cumulative-layout-shift']?.numericValue,
        'Cumulative Layout Shift',
      );
      const tbtMs = metric(
        report.audits?.['total-blocking-time']?.numericValue,
        'Total Blocking Time',
      );
      const performanceScore = metric(
        report.categories?.performance?.score,
        'performance score',
      );
      if (performanceScore > 1) {
        throw new Error(`Invalid performance score in ${filename}.`);
      }
      if (
        typeof report.lighthouseVersion !== 'string' ||
        !report.lighthouseVersion
      ) {
        throw new Error(`Missing Lighthouse version in ${filename}.`);
      }

      pageSummary.samples.push({
        run,
        report: filename,
        lcpMs,
        cls,
        tbtMs,
        performanceScore,
        lighthouseVersion: report.lighthouseVersion,
      });
      saveSummary();
    }

    pageSummary.medianLcpMs = median(
      pageSummary.samples.map((sample) => sample.lcpMs),
    );
    pageSummary.medianPerformanceScore = median(
      pageSummary.samples.map((sample) => sample.performanceScore),
    );
    pageSummary.pass = pageSummary.medianLcpMs < 2500;
    saveSummary();

    console.log(
      `${url}\n` +
      `  LCP values (ms): ${pageSummary.samples.map((sample) => sample.lcpMs.toFixed(1)).join(', ')}\n` +
      `  Median LCP: ${pageSummary.medianLcpMs.toFixed(1)} ms; median performance score: ${pageSummary.medianPerformanceScore.toFixed(3)}`,
    );
  }

  const failedPages = summary.pages.filter((page) => !page.pass);
  summary.completed = true;
  summary.completedAt = new Date().toISOString();
  summary.decision = failedPages.length === 0
    ? 'pass'
    : `fail: first font loading, then the weight of the first screen; ${failedPages.map((page) => page.url).join(', ')}`;
  saveSummary();
  console.log(`DECISION: ${summary.decision}`);
} catch (error) {
  summary.completed = false;
  summary.decision = 'measurement failed';
  try {
    saveSummary();
  } catch {
    // Preserve the original failure if the output directory is unavailable.
  }
  fail(error instanceof Error ? error.message : 'Measurement failed.');
}
