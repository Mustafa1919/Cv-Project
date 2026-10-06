import { marked, Renderer } from "marked";

function escapeHtml(text: string): string {
  return text
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

function safeUrl(value: string): boolean {
  const compact = value.replace(/[\u0000-\u0020\u007f]/g, "");
  if (compact.startsWith("//") || compact.includes("\\")) {
    return false;
  }
  const scheme = /^([a-z][a-z0-9+.-]*):/i.exec(compact);
  return (
    scheme === null ||
    /^(https?|mailto):/i.test(compact)
  );
}

const renderer = new Renderer();

renderer.html = ({ text }) => escapeHtml(text);

renderer.link = ({ href, title, tokens }) => {
  const label = renderer.parser.parseInline(tokens);
  if (!safeUrl(href)) {
    return label;
  }
  const titleAttribute =
    title === null || title === undefined
      ? ""
      : ` title="${escapeHtml(title)}"`;
  return `<a href="${escapeHtml(href)}"${titleAttribute}>${label}</a>`;
};

renderer.image = ({ href, title, text }) => {
  // Markdown images are local only; the status badge is the only remote request.
  if (
    !safeUrl(href) ||
    /^(?:[a-z][a-z0-9+.-]*:|\/\/)/i.test(href.trim())
  ) {
    return escapeHtml(text);
  }

  const titleAttribute =
    title === null || title === undefined
      ? ""
      : ` title="${escapeHtml(title)}"`;
  return `<img src="${escapeHtml(href)}" alt="${escapeHtml(text)}"${titleAttribute}>`;
};

export function renderMarkdown(source: string): string {
  return marked.parse(source, {
    async: false,
    gfm: true,
    renderer,
  });
}

export function renderInline(source: string): string {
  return marked.parseInline(source, {
    async: false,
    gfm: true,
    renderer,
  });
}
