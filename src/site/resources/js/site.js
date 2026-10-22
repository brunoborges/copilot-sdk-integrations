(() => {
  const navigation = document.getElementById("leftColumn");
  const main = document.getElementById("bodyColumn");
  if (!navigation || !main) return;

  navigation.setAttribute("role", "navigation");
  navigation.setAttribute("aria-label", "Documentation chapters");
  main.setAttribute("role", "main");
  main.setAttribute("tabindex", "-1");

  const skip = document.createElement("a");
  skip.href = "#bodyColumn";
  skip.className = "skip-link";
  skip.textContent = "Skip to content";
  document.body.prepend(skip);

  const toggle = document.createElement("button");
  toggle.type = "button";
  toggle.className = "docs-nav-toggle";
  toggle.textContent = "Chapters";
  toggle.setAttribute("aria-controls", "leftColumn");
  navigation.before(toggle);

  const mobile = window.matchMedia("(max-width: 700px)");
  const setCollapsed = (collapsed) => {
    document.body.dataset.navCollapsed = String(collapsed);
    toggle.setAttribute("aria-expanded", String(!collapsed));
  };
  setCollapsed(mobile.matches);
  mobile.addEventListener("change", () => setCollapsed(mobile.matches));
  toggle.addEventListener("click", () => {
    setCollapsed(document.body.dataset.navCollapsed !== "true");
  });

  for (const table of main.querySelectorAll("table")) {
    table.setAttribute("tabindex", "0");
  }

  if (!window.hljs) {
    console.error("Documentation syntax highlighting library failed to load.");
  } else {
    for (const code of main.querySelectorAll("pre code")) {
      if (code.matches(".nohighlight, .no-highlight")) continue;
      const languageClass = [...code.classList].find(name => name.startsWith("language-"));
      if (!languageClass) continue;
      const language = languageClass.slice("language-".length);
      if (!window.hljs.getLanguage(language)) {
        console.warn(`No syntax highlighter registered for documentation language: ${language}`);
        continue;
      }
      window.hljs.highlightElement(code);
    }
  }

  const root = document.documentElement;
  const themeSelect = document.getElementById("theme-mode");
  const systemTheme = window.matchMedia("(prefers-color-scheme: dark)");
  let themeMode = root.getAttribute("data-theme-mode");
  const applyTheme = () => {
    root.setAttribute("data-theme-mode", themeMode);
    root.setAttribute("data-theme", themeMode === "system"
      ? (systemTheme.matches ? "dark" : "light") : themeMode);
    if (themeSelect) themeSelect.value = themeMode;
    for (const link of document.querySelectorAll("a[href]")) {
      if (link.getAttribute("href").startsWith("#")) continue;
      const target = new URL(link.href, window.location.href);
      if (target.origin === window.location.origin && target.pathname.endsWith(".html")) {
        if (themeMode === "system") target.searchParams.delete("theme");
        else target.searchParams.set("theme", themeMode);
        link.href = target.href;
      }
    }
  };
  applyTheme();
  systemTheme.addEventListener("change", () => {
    if (themeMode === "system") applyTheme();
  });
  if (themeSelect) {
    themeSelect.parentElement.hidden = false;
    themeSelect.addEventListener("change", () => {
      themeMode = themeSelect.value;
      try {
        window.localStorage.setItem("copilot-docs-theme", themeMode);
      } catch (error) {
        console.warn("Documentation theme could not be saved; the choice is retained in page URLs.", error);
      }
      const url = new URL(window.location.href);
      if (themeMode === "system") url.searchParams.delete("theme");
      else url.searchParams.set("theme", themeMode);
      window.history.replaceState(window.history.state, "", url);
      applyTheme();
    });
  }

  window.addEventListener("beforeprint", () => root.setAttribute("data-theme", "light"));
  window.addEventListener("afterprint", applyTheme);
})();
