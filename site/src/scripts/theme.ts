const root = document.documentElement;
const buttons = Array.from(
  document.querySelectorAll<HTMLButtonElement>("[data-theme-toggle]"),
);
const systemTheme = window.matchMedia("(prefers-color-scheme: dark)");

function isDark(): boolean {
  const theme = root.dataset["theme"];
  return theme === "dark" || (theme !== "light" && systemTheme.matches);
}

function updateButtons(): void {
  const pressed = String(isDark());
  for (const button of buttons) {
    button.setAttribute("aria-pressed", pressed);
  }
}

for (const button of buttons) {
  button.hidden = false;
  button.addEventListener("click", () => {
    const theme = isDark() ? "light" : "dark";
    root.dataset["theme"] = theme;
    try {
      localStorage.setItem("vitrin-theme", theme);
    } catch {
      // The selected theme still works when storage is unavailable.
    }
    updateButtons();
  });
}

systemTheme.addEventListener("change", updateButtons);
window.addEventListener("storage", (event: StorageEvent) => {
  if (event.key !== "vitrin-theme" && event.key !== null) {
    return;
  }

  if (event.newValue === "light" || event.newValue === "dark") {
    root.dataset["theme"] = event.newValue;
  } else {
    delete root.dataset["theme"];
  }
  updateButtons();
});

updateButtons();

export {};
