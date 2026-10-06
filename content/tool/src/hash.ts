import { createHash } from "node:crypto";

export function normalizeText(text: string): string {
  const lines = text
    .normalize("NFC")
    .replace(/\r\n?/g, "\n")
    .split("\n")
    .map((line) => line.replace(/[ \t]+$/g, ""));

  let start = 0;
  let end = lines.length;
  while (start < end && lines[start] === "") {
    start += 1;
  }
  while (end > start && lines[end - 1] === "") {
    end -= 1;
  }
  return lines.slice(start, end).join("\n");
}

export function hashText(text: string): string {
  return createHash("sha256")
    .update(normalizeText(text), "utf8")
    .digest("hex");
}
