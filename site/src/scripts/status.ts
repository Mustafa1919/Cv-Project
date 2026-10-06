function isHealthy(body: unknown): boolean {
  return (
    typeof body === "object" &&
    body !== null &&
    "status" in body &&
    body.status === "ok"
  );
}

async function updateBadge(badge: HTMLElement): Promise<void> {
  const text = badge.querySelector<HTMLElement>("[data-status-text]");
  const origin = badge.dataset["apiOrigin"];
  const upLabel = badge.dataset["labelUp"];
  const downLabel = badge.dataset["labelDown"];

  if (
    text === null ||
    origin === undefined ||
    upLabel === undefined ||
    downLabel === undefined
  ) {
    return;
  }

  const controller = new AbortController();
  const timeout = window.setTimeout(() => controller.abort(), 3000);
  let up = false;

  try {
    const endpoint = new URL("/v1/public/status", origin);
    const response = await fetch(endpoint, {
      method: "GET",
      credentials: "omit",
      cache: "no-store",
      mode: "cors",
      signal: controller.signal,
    });

    if (response.status === 200) {
      const body: unknown = await response.json();
      up = isHealthy(body);
    }
  } catch {
    up = false;
  } finally {
    window.clearTimeout(timeout);
  }

  badge.dataset["state"] = up ? "up" : "down";
  text.textContent = up ? upLabel : downLabel;
}

for (const badge of document.querySelectorAll<HTMLElement>("[data-status-badge]")) {
  void updateBadge(badge);
}

export {};
