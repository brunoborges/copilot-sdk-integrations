#!/usr/bin/env node
// Static documentation site generator: renders site/markdown/*.md into site/dist/.
// Usage: npm ci && npm run build (from python/fastapi/site/).
import { cpSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { Marked } from "marked";

const here = dirname(fileURLToPath(import.meta.url));
const markdownDir = join(here, "markdown");
const assetsDir = join(here, "assets");
const outDir = join(here, "dist");

const pyproject = readFileSync(join(here, "..", "..", "core", "pyproject.toml"), "utf8");
const sdkVersion = /"github-copilot-sdk([^"]*)"/.exec(pyproject)?.[1];
if (!sdkVersion) throw new Error("No github-copilot-sdk dependency in python/core/pyproject.toml");
const version = "Preview";

const site = {
  name: "Copilot for FastAPI",
  description: "Guides and reference for using the GitHub Copilot SDK for Python with FastAPI: injectable agents, typed prompts, tools, permissions, and testing.",
  links: [
    { name: "GitHub", href: "https://github.com/brunoborges/copilot-sdk-integrations/tree/main/python/fastapi" },
    { name: "Copilot Python SDK", href: "https://github.com/github/copilot-sdk/tree/main/python" },
  ],
  menus: [
    {
      name: "Start here",
      items: [
        ["Documentation", "index"],
        ["Getting started", "getting-started"],
        ["Configuration", "configuration"],
      ],
    },
    {
      name: "Build",
      items: [
        ["Agents and dependencies", "agents"],
        ["Prompts and typed results", "prompts"],
        ["Tools", "tools"],
        ["Permissions", "permissions"],
        ["Conversations and identity", "conversations"],
      ],
    },
    {
      name: "Operate",
      items: [
        ["Startup, errors and health", "operations"],
        ["Testing", "testing"],
        ["Example project", "example"],
      ],
    },
  ],
  footer: `Copilot SDK integration for FastAPI &middot; preview &middot; Python 3.11+ &middot; FastAPI 0.115+ &middot; github-copilot-sdk ${sdkVersion.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")}`,
};

// ---------------------------------------------------------------------------
// Syntax highlighting: a tiny tokenizer emitting highlight.js-compatible classes.

const escapeHtml = (s) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
const span = (cls, text) => `<span class="hljs-${cls}">${escapeHtml(text)}</span>`;

const CS_KEYWORDS = new Set(
  ("abstract as async await base bool break byte case catch char checked class const continue decimal default delegate do double " +
    "else enum event explicit extern finally fixed float for foreach get goto if implicit in init int interface internal is lock long " +
    "nameof namespace new object operator out override params partial private protected public readonly record ref required return " +
    "sbyte sealed set short sizeof stackalloc static string struct switch this throw try typeof uint ulong unchecked unsafe ushort " +
    "using var virtual void volatile when where while with yield").split(" "),
);
const CS_LITERALS = new Set(["true", "false", "null"]);

const JS_KEYWORDS = new Set(
  ("abstract as async await break case catch class const continue declare default delete do else enum export extends " +
    "for from function get if implements import in instanceof interface keyof let new of private protected public readonly " +
    "return satisfies set static super switch this throw try type typeof using var void while yield").split(" "),
);
const JS_LITERALS = new Set(["true", "false", "null", "undefined"]);

const PY_KEYWORDS = new Set(
  ("and as assert async await break class continue def del elif else except finally for from global if import in is " +
    "lambda nonlocal not or pass raise return try while with yield match case").split(" "),
);
const PY_LITERALS = new Set(["True", "False", "None", "self", "cls"]);

const LANGUAGES = {
  py: {
    pattern:
      /(#[^\n]*)|((?:[rRbBfF]{0,2})(?:"""[\s\S]*?"""|'''[\s\S]*?'''|"(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*'))|(@[A-Za-z_][\w.]*)|\b(\d[\d_]*(?:\.\d+)?)\b|([A-Za-z_][\w]*)(?=\s*\()|([A-Za-z_][\w]*)/g,
    render(m) {
      const [, comment, string, decorator, number, call, word] = m;
      if (comment) return span("comment", comment);
      if (string) return span("string", string);
      if (decorator) return span("meta", decorator);
      if (number) return span("number", number);
      if (call) return PY_KEYWORDS.has(call) ? span("keyword", call) : span("title", call);
      if (PY_KEYWORDS.has(word)) return span("keyword", word);
      if (PY_LITERALS.has(word)) return span("literal", word);
      return escapeHtml(word);
    },
  },
  ts: {
    pattern:
      /(\/\/[^\n]*|\/\*[\s\S]*?\*\/)|("(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*'|`(?:\\[\s\S]|[^`\\])*`)|(@[A-Za-z_$][\w$]*)|\b(\d[\d_]*(?:\.\d+)?)\b|([A-Za-z_$][\w$]*)(?=\s*(?:<[^<>()]*>)?\()|([A-Za-z_$][\w$]*)/g,
    render(m) {
      const [, comment, string, decorator, number, call, word] = m;
      if (comment) return span("comment", comment);
      if (string) return span("string", string);
      if (decorator) return span("meta", decorator);
      if (number) return span("number", number);
      if (call) return JS_KEYWORDS.has(call) ? span("keyword", call) : span("title", call);
      if (JS_KEYWORDS.has(word)) return span("keyword", word);
      if (JS_LITERALS.has(word)) return span("literal", word);
      return escapeHtml(word);
    },
  },
  cs: {
    pattern:
      /(\/\/[^\n]*|\/\*[\s\S]*?\*\/)|(\$?@?"(?:\\.|""|[^"\\\n])*"|'(?:\\.|[^'\\\n])')|(\[[A-Z][\w.]*(?=[\](]))|\b(\d[\d_]*(?:\.\d+)?[mMdDfFlL]?)\b|([A-Za-z_][\w]*)(?=\s*(?:<[^<>()]*>)?\()|([A-Za-z_][\w]*)/g,
    render(m) {
      const [, comment, string, attribute, number, call, word] = m;
      if (comment) return span("comment", comment);
      if (string) return span("string", string);
      if (attribute) return escapeHtml("[") + span("meta", attribute.slice(1));
      if (number) return span("number", number);
      if (call) return CS_KEYWORDS.has(call) ? span("keyword", call) : span("title", call);
      if (CS_KEYWORDS.has(word)) return span("keyword", word);
      if (CS_LITERALS.has(word)) return span("literal", word);
      return escapeHtml(word);
    },
  },
  xml: {
    pattern: /(<!--[\s\S]*?-->)|(<\/?)([\w.:-]+)|([\w.:-]+)(=)("[^"]*")|(\/?>)/g,
    render(m) {
      const [, comment, open, tag, attr, eq, value, close] = m;
      if (comment) return span("comment", comment);
      if (tag) return escapeHtml(open) + span("name", tag);
      if (attr) return span("attr", attr) + escapeHtml(eq) + span("string", value);
      return escapeHtml(close);
    },
  },
  json: {
    pattern: /("(?:\\.|[^"\\])*")(\s*:)|("(?:\\.|[^"\\])*")|(-?\b\d+(?:\.\d+)?(?:[eE][+-]?\d+)?\b)|\b(true|false|null)\b/g,
    render(m) {
      const [, key, colon, string, number, literal] = m;
      if (key) return span("attr", key) + escapeHtml(colon);
      if (string) return span("string", string);
      if (number) return span("number", number);
      return span("literal", literal);
    },
  },
  sh: {
    pattern: /(^|\n)([ \t]*)([A-Za-z0-9_./-]+)|(#[^\n]*)|("(?:\\.|[^"\\])*"|'[^']*')|(\s)(--?[A-Za-z][\w-]*)/g,
    render(m) {
      const [, nl, indent, command, comment, string, space, flag] = m;
      if (command !== undefined) return escapeHtml(nl + indent) + span("built_in", command);
      if (comment) return span("comment", comment);
      if (string) return span("string", string);
      return escapeHtml(space) + span("attr", flag);
    },
  },
};
const ALIASES = { python: "py", toml: "sh", csharp: "cs", "c#": "cs", xml: "xml", csproj: "xml", msbuild: "xml", typescript: "ts", js: "ts", javascript: "ts", bash: "sh", shell: "sh", pwsh: "sh", powershell: "sh", console: "sh", jsonc: "json" };

function highlight(code, lang) {
  const language = LANGUAGES[ALIASES[lang] ?? lang];
  if (!language) return escapeHtml(code);
  let out = "";
  let last = 0;
  for (const m of code.matchAll(language.pattern)) {
    out += escapeHtml(code.slice(last, m.index)) + language.render(m);
    last = m.index + m[0].length;
  }
  return out + escapeHtml(code.slice(last));
}

// ---------------------------------------------------------------------------
// Markdown rendering

const slugify = (text) =>
  text
    .toLowerCase()
    .replace(/<[^>]+>/g, "")
    .replace(/&[a-z]+;|&#\d+;/g, "")
    .replace(/[^a-z0-9\s-]/g, "")
    .trim()
    .replace(/\s+/g, "-");

function createMarked() {
  return new Marked({
    gfm: true,
    renderer: {
      code({ text, lang }) {
        const language = (lang ?? "").trim().split(/\s+/)[0];
        const cls = language ? ` class="hljs language-${escapeHtml(language)}"` : ` class="hljs"`;
        return `<pre><code${cls}>${highlight(text, language)}</code></pre>\n`;
      },
      heading({ tokens, depth }) {
        const html = this.parser.parseInline(tokens);
        return `<h${depth} id="${slugify(html)}">${html}</h${depth}>\n`;
      },
      link({ href, title, tokens }) {
        const text = this.parser.parseInline(tokens);
        let target = href;
        if (!/^[a-z]+:/i.test(target)) target = target.replace(/\.md(?=$|#)/, ".html");
        const titleAttr = title ? ` title="${escapeHtml(title)}"` : "";
        return `<a href="${escapeHtml(target)}"${titleAttr}>${text}</a>`;
      },
    },
  });
}

function parseFrontMatter(source) {
  const match = /^---\n([\s\S]*?)\n---\n/.exec(source);
  if (!match) return { meta: {}, body: source };
  const meta = Object.fromEntries(
    match[1]
      .split("\n")
      .map((line) => /^(\w+):\s*(.*)$/.exec(line))
      .filter(Boolean)
      .map(([, k, v]) => [k, v.replace(/^["']|["']$/g, "")]),
  );
  return { meta, body: source.slice(match[0].length) };
}

// ---------------------------------------------------------------------------
// Template

const THEME_HEAD = `<script>
  (() => {
    const param = new URLSearchParams(window.location.search).get("theme");
    const modes = ["system", "light", "dark"];
    let saved;
    try {
      saved = window.localStorage.getItem("copilot-docs-theme");
    } catch (error) {
      console.warn("Saved documentation theme is unavailable; using the URL or system preference.", error);
    }
    const mode = modes.includes(param) ? param : modes.includes(saved) ? saved : "system";
    const theme = mode === "system"
      ? (window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light")
      : mode;
    document.documentElement.setAttribute("data-theme-mode", mode);
    document.documentElement.setAttribute("data-theme", theme);
  })();
</script>
<style>
:root {
  color-scheme: light;
  --cp-bg: #f7f4ef;
  --cp-bg-elevated: #fcfbf8;
  --cp-surface: #ffffff;
  --cp-surface-soft: #f5f5f5;
  --cp-border: #dedede;
  --cp-border-strong: #919191;
  --cp-text: #242424;
  --cp-text-muted: #5c5c5c;
  --cp-text-soft: #6f6f6f;
  --cp-accent: #00695c;
  --cp-accent-hover: #004d40;
  --cp-accent-soft: rgba(0, 105, 92, 0.08);
  --cp-accent-fg: #ffffff;
  --cp-success: #16a34a;
  --cp-danger: #dc2626;
  --cp-warning: #f59e0b;
  --cp-link: #0078d4;
  --cp-shadow: 0 18px 48px rgba(0, 0, 0, 0.12);
  --cp-overlay: rgba(255, 255, 255, 0.8);
  --cp-panel: rgba(255, 255, 255, 0.86);
  --cp-panel-strong: rgba(255, 255, 255, 0.96);
  --cp-sheen: rgba(255, 255, 255, 0.55);
  --cp-highlight: rgba(0, 105, 92, 0.12);
  --cp-syntax-keyword: var(--cp-accent);
  --cp-syntax-comment: var(--cp-text-soft);
  --cp-syntax-string: #21673b;
  --cp-syntax-number: #875000;
  --cp-syntax-function: #155a8a;
}
html[data-theme="dark"] {
  color-scheme: dark;
  --cp-bg: #3d3b3a;
  --cp-bg-elevated: #343231;
  --cp-surface: #292929;
  --cp-surface-soft: #2e2e2e;
  --cp-border: #474747;
  --cp-border-strong: #5f5f5f;
  --cp-text: #dedede;
  --cp-text-muted: #919191;
  --cp-text-soft: #b0b0b0;
  --cp-accent: #2fbfa8;
  --cp-accent-hover: #5fd3c0;
  --cp-accent-soft: rgba(47, 191, 168, 0.14);
  --cp-accent-fg: #1a1a1a;
  --cp-success: #4ade80;
  --cp-danger: #f87171;
  --cp-warning: #fbbf24;
  --cp-link: #4da6ff;
  --cp-shadow: 0 18px 48px rgba(0, 0, 0, 0.32);
  --cp-overlay: rgba(41, 41, 41, 0.88);
  --cp-panel: rgba(41, 41, 41, 0.72);
  --cp-panel-strong: rgba(41, 41, 41, 0.96);
  --cp-sheen: rgba(255, 255, 255, 0.04);
  --cp-highlight: rgba(47, 191, 168, 0.12);
  --cp-syntax-keyword: var(--cp-accent);
  --cp-syntax-comment: var(--cp-text-soft);
  --cp-syntax-string: #91d6a7;
  --cp-syntax-number: #e5bd83;
  --cp-syntax-function: #8fc7eb;
}
</style>`;

function renderNav(current) {
  return site.menus
    .map((menu) => {
      const items = menu.items
        .map(([label, page]) =>
          page === current
            ? `          <li><strong aria-current="page">${escapeHtml(label)}</strong></li>`
            : `          <li><a href="./${page}.html">${escapeHtml(label)}</a></li>`,
        )
        .join("\n");
      return `        <p class="nav-heading">${escapeHtml(menu.name)}</p>\n        <ul>\n${items}\n        </ul>`;
    })
    .join("\n");
}

function renderPage({ page, title, body }) {
  const links = site.links.map((l) => `      <a href="${escapeHtml(l.href)}">${escapeHtml(l.name)}</a>`).join("\n");
  const fullTitle = page === "index" ? site.name : `${title} – ${site.name}`;
  return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8"/>
  <meta name="viewport" content="width=device-width, initial-scale=1"/>
  <meta name="description" content="${escapeHtml(site.description)}"/>
${THEME_HEAD}
  <script defer="defer" src="./js/site.js"></script>
  <title>${escapeHtml(fullTitle)}</title>
  <link rel="stylesheet" href="./css/site.css"/>
</head>
<body>
  <header id="banner">
    <a id="bannerLeft" href="./index.html">${escapeHtml(site.name)}</a>
    <div class="theme-picker" hidden>
      <label for="theme-mode">Theme</label>
      <select id="theme-mode">
        <option value="system">System</option>
        <option value="light">Light</option>
        <option value="dark">Dark</option>
      </select>
    </div>
  </header>
  <div id="breadcrumbs">
    <div class="breadcrumb-trail">
      <a class="landing-link" href="../index.html">&larr; All integrations</a>
      <span>Version ${escapeHtml(version)}</span>
    </div>
    <div class="project-links">
${links}
    </div>
  </div>
  <div class="docs-layout">
    <aside id="leftColumn">
      <div id="navcolumn">
${renderNav(page)}
      </div>
    </aside>
    <main id="bodyColumn">
      <article id="contentBox">
${body}
      </article>
    </main>
  </div>
  <footer id="footer"><p>${site.footer}</p></footer>
</body>
</html>
`;
}

// ---------------------------------------------------------------------------
// Link check: every relative link must point to a generated page and an existing id.

function checkLinks(pages) {
  const ids = new Map();
  for (const [file, html] of pages) {
    ids.set(file, new Set([...html.matchAll(/\sid="([^"]+)"/g)].map((m) => m[1])));
  }
  const errors = [];
  for (const [file, html] of pages) {
    for (const [, raw] of html.matchAll(/\s(?:href|src)="([^"]+)"/g)) {
      const href = raw.replace(/&amp;/g, "&");
      if (/^[a-z]+:/i.test(href) || href.startsWith("//")) continue;
      // Links outside this site (e.g. the shared landing page) are not generated here.
      if (href.startsWith("../")) continue;
      const [path, anchor] = href.split("#");
      const target = path === "" ? file : path.replace(/^\.\//, "");
      if (target.startsWith("css/") || target.startsWith("js/")) {
        if (!existsSync(join(outDir, target))) errors.push(`${file}: missing asset ${href}`);
        continue;
      }
      if (!ids.has(target)) {
        errors.push(`${file}: broken link ${href}`);
      } else if (anchor && !ids.get(target).has(anchor)) {
        errors.push(`${file}: missing anchor ${href}`);
      }
    }
  }
  return errors;
}

// ---------------------------------------------------------------------------

function build() {
  const sources = readdirSync(markdownDir).filter((f) => f.endsWith(".md"));
  const navPages = site.menus.flatMap((m) => m.items.map(([, page]) => page));
  const missing = navPages.filter((page) => !sources.includes(`${page}.md`));
  if (missing.length > 0) throw new Error(`Navigation references missing pages: ${missing.join(", ")}`);

  rmSync(outDir, { recursive: true, force: true });
  mkdirSync(outDir, { recursive: true });
  cpSync(assetsDir, outDir, { recursive: true });

  const pages = new Map();
  for (const source of sources) {
    const page = source.slice(0, -3);
    const { meta, body: markdown } = parseFrontMatter(readFileSync(join(markdownDir, source), "utf8"));
    const body = createMarked().parse(markdown);
    const title = meta.title ?? /<h1[^>]*>(.*?)<\/h1>/.exec(body)?.[1]?.replace(/<[^>]+>/g, "") ?? page;
    const html = renderPage({ page, title, body });
    pages.set(`${page}.html`, html);
    writeFileSync(join(outDir, `${page}.html`), html);
  }

  const errors = checkLinks(pages);
  if (errors.length > 0) {
    console.error(errors.join("\n"));
    throw new Error(`${errors.length} broken link(s)`);
  }
  console.log(`Built ${pages.size} pages into ${outDir} (links checked)`);
}

try {
  build();
} catch (error) {
  console.error(`Site build failed: ${error.message}`);
  process.exit(1);
}
