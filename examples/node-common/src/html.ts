export const escapeHtml = (value: string) => value.replace(/[&<>"']/g, (character) => ({
  "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
})[character]!);

const theme = `
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
  --cp-accent: #b11f4b;
  --cp-accent-hover: #9a1a41;
  --cp-accent-soft: rgba(177, 31, 75, 0.08);
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
  --cp-highlight: rgba(177, 31, 75, 0.12);
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
  --cp-accent: #fd8ea1;
  --cp-accent-hover: #fb7b91;
  --cp-accent-soft: rgba(253, 142, 161, 0.14);
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
  --cp-highlight: rgba(253, 142, 161, 0.12);
}`;

export function renderHtml(result: Record<string, unknown>, target?: string) {
  let title: string;
  let content: string;
  if ("narrative" in result) {
    title = String(result.title);
    content = `<p>${escapeHtml(String(result.narrative))}</p><h2>Explore together</h2><ol>${
      (result.questions as string[]).map((question) => `<li>${escapeHtml(question)}</li>`).join("")
    }</ol><p>Approved fact IDs: ${escapeHtml((result.factIds as number[]).join(", "))}</p>`;
  } else if ("findings" in result) {
    title = "Accessibility review";
    const findings = result.findings as { criterion: string; issue: string; evidence: string; remediation: string }[];
    content = `<p>Requested target: <span>${escapeHtml(target ?? "")}</span></p>
<label for="filter">Filter findings by text or criterion</label>
<input id="filter" type="search" aria-controls="findings" aria-describedby="count">
<p id="count" role="status" aria-live="polite" aria-atomic="true">${findings.length} findings shown</p>
<section id="findings" aria-label="Candidate findings">${findings.map((finding, index) => `<article class="finding">
<h2>${index + 1}. WCAG ${escapeHtml(finding.criterion)}</h2><p>${escapeHtml(finding.issue)}</p>
<h3>Snapshot evidence</h3><pre>${escapeHtml(finding.evidence)}</pre>
<h3>Remediation</h3><p>${escapeHtml(finding.remediation)}</p></article>`).join("")}</section>
<h2>Review limits</h2><p>${escapeHtml(String(result.reviewLimits))}</p>`;
  } else {
    title = "Unapproved museum research";
    content = `<ol>${(result.notes as { claim: string; sourceUrl: string }[]).map((note) =>
      `<li><p>${escapeHtml(note.claim)}</p><p>Source: ${escapeHtml(note.sourceUrl)}</p></li>`).join("")}</ol>`;
  }
  return `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'">
<title>${escapeHtml(title)}</title>
<script>
  (() => {
    const param = new URLSearchParams(window.location.search).get("clawpilotTheme");
    const theme =
      param || (window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light");
    document.documentElement.setAttribute("data-theme", theme);
  })();
</script>
<style>${theme}
body { margin: 0; background: var(--cp-bg); color: var(--cp-text); font: 1rem/1.6 "Segoe UI", Aptos, Calibri, -apple-system, BlinkMacSystemFont, sans-serif; }
main { max-width: 64rem; margin: auto; padding: 2rem 1rem; overflow-wrap: anywhere; }
h1 { line-height: 1.2; } h2 { font-size: 1.3rem; } h3 { font-size: 1rem; }
article { background: var(--cp-surface); border: 1px solid var(--cp-border); border-radius: 16px; padding: 1.5rem; margin: 1rem 0; }
input { display: block; box-sizing: border-box; width: 100%; padding: .75rem; border: 1px solid var(--cp-border-strong); border-radius: .625rem; background: var(--cp-surface); color: var(--cp-text); font: inherit; }
input:focus-visible { outline: 3px solid var(--cp-accent); outline-offset: 4px; }
pre { white-space: pre-wrap; font-family: Consolas, "Courier New", Courier, monospace; }
.review { padding: 1rem; border: 1px solid var(--cp-border); border-radius: .625rem; background: var(--cp-accent-soft); }
[hidden] { display: none !important; }
</style></head><body><main><h1>${escapeHtml(title)}</h1>
<p class="review">${escapeHtml(String(result.review))}</p>${content}</main>
<script>
const input = document.getElementById("filter");
if (input) input.addEventListener("input", () => {
  const query = input.value.trim().toLocaleLowerCase();
  const findings = Array.from(document.querySelectorAll(".finding"));
  for (const finding of findings) finding.hidden = !finding.textContent.toLocaleLowerCase().includes(query);
  document.getElementById("count").textContent = findings.filter(finding => !finding.hidden).length + " of " + findings.length + " findings shown";
});
</script></body></html>`;
}
