import type { Locale } from "./i18n.ts";

export function formatPeriod(
  start: string | number,
  end: string | number | undefined,
  locale: Locale,
  presentLabel: string,
): string {
  const formatter = new Intl.NumberFormat(
    locale === "tr" ? "tr-TR" : "en-US",
    { useGrouping: false },
  );

  function year(value: string | number): number {
    if (typeof value === "number") {
      if (!Number.isInteger(value)) {
        throw new Error(`Invalid year: ${value}`);
      }
      return value;
    }
    const match = /^(\d{4})(?:-\d{2})?$/.exec(value);
    const result = match?.[1];
    if (result === undefined) {
      throw new Error(`Invalid period date: ${value}`);
    }
    return Number(result);
  }

  const startYear = year(start);
  const startText = formatter.format(startYear);

  if (end === undefined) {
    return `${startText} — ${presentLabel}`;
  }

  const endYear = year(end);
  return startYear === endYear
    ? startText
    : `${startText} — ${formatter.format(endYear)}`;
}
