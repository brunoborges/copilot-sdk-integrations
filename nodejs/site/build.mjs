#!/usr/bin/env node
// Static documentation site generator: renders site/markdown/*.md into one site per framework
// (site/dist/nestjs, site/dist/fastify) plus redirect stubs for the former combined site (site/dist/nodejs).
// Usage: node site/build.mjs (from nodejs/), or `npm run site`.
//
// Pages are shared unless their front matter lists `sites: nestjs` or `sites: fastify`; `page:` overrides the
// output name (e.g. `index-nestjs.md` -> `index.html`). Inside shared pages, wrap framework-specific Markdown
// in `<!-- only:nestjs -->` ... `<!-- /only -->` (or `only:fastify`).
import { cpSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { Marked } from "marked";

const here = dirname(fileURLToPath(import.meta.url));
const markdownDir = join(here, "markdown");
const assetsDir = join(here, "assets");
const distDir = join(here, "dist");

const corePackage = JSON.parse(readFileSync(join(here, "..", "packages", "core", "package.json"), "utf8"));
const version = corePackage.version;
const sdkVersion = corePackage.dependencies["@github/copilot-sdk"].replace(/^\^/, "");

const sharedMenus = [
  {
    name: "Build agents",
    items: [
      ["Agents", "agents"],
      ["Tools", "tools"],
      ["Tool governance and hooks", "governance"],
      ["Conversations", "conversations"],
      ["Context and per-user identity", "context-and-identity"],
    ],
  },
  {
    name: "Reference",
    items: [
      ["Streaming", "streaming"],
      ["Vercel AI SDK", "ai-sdk"],
      ["Configuration", "configuration"],
      ["Testing", "testing"],
    ],
  },
  {
    name: "Operate",
    items: [
      ["Example applications", "examples"],
      ["Operations", "operations"],
    ],
  },
];

const sites = {
  nestjs: {
    name: "Copilot Agents for NestJS",
    description: "Guides and API reference for building GitHub Copilot agents in NestJS applications.",
    links: [
      { name: "GitHub", href: "https://github.com/brunoborges/copilot-sdk-integrations/tree/main/nodejs/packages/nestjs" },
      { name: "Copilot Node SDK", href: "https://github.com/github/copilot-sdk/tree/main/nodejs" },
    ],
    accent: { light: ["#e0234e", "#c41d43", "rgba(224, 35, 78, 0.08)", "rgba(224, 35, 78, 0.12)"], dark: ["#ff6b88", "#ff5577", "rgba(255, 107, 136, 0.14)", "rgba(255, 107, 136, 0.12)"] },
    menus: [
      {
        name: "Start here",
        items: [
          ["Documentation", "index"],
          ["Getting started", "getting-started"],
          ["Agent Core without a framework", "core"],
        ],
      },
      ...sharedMenus,
    ],
    footer: `Copilot Agents for NestJS &middot; ${version} &middot; Node.js 20.19+ / 22.12+ &middot; NestJS 11/12 &middot; AI SDK 7 &middot; Copilot SDK ${sdkVersion}`,
  },
  fastify: {
    name: "Copilot Agents for Fastify",
    description: "Guides and API reference for building GitHub Copilot agents in Fastify applications.",
    links: [
      { name: "GitHub", href: "https://github.com/brunoborges/copilot-sdk-integrations/tree/main/nodejs/packages/fastify" },
      { name: "Copilot Node SDK", href: "https://github.com/github/copilot-sdk/tree/main/nodejs" },
    ],
    accent: { light: ["#1f1f1f", "#000000", "rgba(0, 0, 0, 0.06)", "rgba(0, 0, 0, 0.08)"], dark: ["#f2f2f2", "#ffffff", "rgba(255, 255, 255, 0.1)", "rgba(255, 255, 255, 0.08)"] },
    menus: [
      {
        name: "Start here",
        items: [
          ["Documentation", "index"],
          ["Fastify plugin", "fastify"],
          ["Agent Core without a framework", "core"],
        ],
      },
      ...sharedMenus,
    ],
    footer: `Copilot Agents for Fastify &middot; ${version} &middot; Node.js 20.19+ / 22.12+ &middot; Fastify 5 &middot; AI SDK 7 &middot; Copilot SDK ${sdkVersion}`,
  },
};

// Former combined site (/nodejs/): every page redirects to its NestJS page, except the Fastify chapter.
const legacyRedirects = { fastify: "../fastify/fastify.html" };

// ---------------------------------------------------------------------------
// Syntax highlighting: a tiny tokenizer emitting highlight.js-compatible classes.

const escapeHtml = (s) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
const span = (cls, text) => `<span class="hljs-${cls}">${escapeHtml(text)}</span>`;

const JS_KEYWORDS = new Set(
  ("abstract as async await break case catch class const continue declare default delete do else enum export extends " +
    "for from function get if implements import in instanceof interface keyof let new of private protected public readonly " +
    "return satisfies set static super switch this throw try type typeof using var void while yield").split(" "),
);
const JS_LITERALS = new Set(["true", "false", "null", "undefined"]);

const LANGUAGES = {
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
const ALIASES = { typescript: "ts", tsx: "ts", js: "ts", javascript: "ts", jsx: "ts", mjs: "ts", bash: "sh", shell: "sh", jsonc: "json" };

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

const themeHead = (accent) => `<script>
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
  --cp-accent: ${accent.light[0]};
  --cp-accent-hover: ${accent.light[1]};
  --cp-accent-soft: ${accent.light[2]};
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
  --cp-highlight: ${accent.light[3]};
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
  --cp-accent: ${accent.dark[0]};
  --cp-accent-hover: ${accent.dark[1]};
  --cp-accent-soft: ${accent.dark[2]};
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
  --cp-highlight: ${accent.dark[3]};
  --cp-syntax-keyword: var(--cp-accent);
  --cp-syntax-comment: var(--cp-text-soft);
  --cp-syntax-string: #91d6a7;
  --cp-syntax-number: #e5bd83;
  --cp-syntax-function: #8fc7eb;
}
</style>`;

function renderNav(site, current) {
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

function renderPage(site, { page, title, body }) {
  const links = site.links.map((l) => `      <a href="${escapeHtml(l.href)}">${escapeHtml(l.name)}</a>`).join("\n");
  const fullTitle = page === "index" ? site.name : `${title} – ${site.name}`;
  return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8"/>
  <meta name="viewport" content="width=device-width, initial-scale=1"/>
  <meta name="description" content="${escapeHtml(site.description)}"/>
${themeHead(site.accent)}
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
    <span>Version ${escapeHtml(version)}</span>
    <div class="project-links">
${links}
    </div>
  </div>
  <div class="docs-layout">
    <aside id="leftColumn">
      <div id="navcolumn">
${renderNav(site, page)}
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

function checkLinks(pages, outDir) {
  const ids = new Map();
  for (const [file, html] of pages) {
    ids.set(file, new Set([...html.matchAll(/\sid="([^"]+)"/g)].map((m) => m[1])));
  }
  const errors = [];
  for (const [file, html] of pages) {
    for (const [, raw] of html.matchAll(/\s(?:href|src)="([^"]+)"/g)) {
      const href = raw.replace(/&amp;/g, "&");
      if (/^[a-z]+:/i.test(href) || href.startsWith("//")) continue;
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

// `<!-- only:<site> -->` ... `<!-- /only -->` blocks are kept for that site and dropped for the others.
function filterForSite(markdown, siteId) {
  return markdown.replace(/<!-- only:(\w+) -->\n?([\s\S]*?)<!-- \/only -->\n?/g, (_, only, content) =>
    only === siteId ? content : "",
  );
}

function readSources() {
  return readdirSync(markdownDir)
    .filter((f) => f.endsWith(".md"))
    .map((source) => {
      const { meta, body } = parseFrontMatter(readFileSync(join(markdownDir, source), "utf8"));
      const sitesOf = meta.sites ? meta.sites.split(/[,\s]+/).filter(Boolean) : Object.keys(sites);
      for (const id of sitesOf) {
        if (!sites[id]) throw new Error(`${source}: unknown site "${id}"`);
      }
      return { source, page: meta.page ?? source.slice(0, -3), meta, markdown: body, sites: sitesOf };
    });
}

function buildSite(siteId, sources) {
  const site = sites[siteId];
  const outDir = join(distDir, siteId);
  const selected = sources.filter((s) => s.sites.includes(siteId));
  const pageNames = selected.map((s) => s.page);
  const duplicate = pageNames.find((p, i) => pageNames.indexOf(p) !== i);
  if (duplicate) throw new Error(`${siteId}: more than one source renders ${duplicate}.html`);
  const navPages = site.menus.flatMap((m) => m.items.map(([, page]) => page));
  const missing = navPages.filter((page) => !pageNames.includes(page));
  if (missing.length > 0) throw new Error(`${siteId}: navigation references missing pages: ${missing.join(", ")}`);

  mkdirSync(outDir, { recursive: true });
  cpSync(assetsDir, outDir, { recursive: true });

  const pages = new Map();
  for (const { page, meta, markdown } of selected) {
    const filtered = filterForSite(markdown, siteId);
    if (/<!-- \/?only/.test(filtered)) throw new Error(`${siteId}: unbalanced only block in ${page}`);
    const body = createMarked().parse(filtered);
    const title = meta.title ?? /<h1[^>]*>(.*?)<\/h1>/.exec(body)?.[1]?.replace(/<[^>]+>/g, "") ?? page;
    const html = renderPage(site, { page, title, body });
    pages.set(`${page}.html`, html);
    writeFileSync(join(outDir, `${page}.html`), html);
  }

  const errors = checkLinks(pages, outDir);
  if (errors.length > 0) {
    console.error(errors.map((e) => `${siteId}/${e}`).join("\n"));
    throw new Error(`${errors.length} broken link(s) in the ${siteId} site`);
  }
  console.log(`Built ${pages.size} pages into ${outDir} (links checked)`);
  return pages;
}

function redirectPage(target) {
  const href = escapeHtml(target);
  return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8"/>
  <meta http-equiv="refresh" content="0; url=${href}"/>
  <link rel="canonical" href="${href}"/>
  <title>Moved</title>
</head>
<body><p>This page moved to <a href="${href}">${href}</a>.</p></body>
</html>
`;
}

function buildLegacyRedirects(nestjsPages) {
  const outDir = join(distDir, "nodejs");
  mkdirSync(outDir, { recursive: true });
  const legacy = new Set([...nestjsPages.keys()].map((f) => f.slice(0, -5)));
  for (const page of Object.keys(legacyRedirects)) legacy.add(page);
  for (const page of legacy) {
    writeFileSync(join(outDir, `${page}.html`), redirectPage(legacyRedirects[page] ?? `../nestjs/${page}.html`));
  }
  console.log(`Wrote ${legacy.size} redirects into ${outDir}`);
}

function build() {
  const sources = readSources();
  rmSync(distDir, { recursive: true, force: true });
  const built = Object.fromEntries(Object.keys(sites).map((id) => [id, buildSite(id, sources)]));
  buildLegacyRedirects(built.nestjs);
}

try {
  build();
} catch (error) {
  console.error(`Site build failed: ${error.message}`);
  process.exit(1);
}
