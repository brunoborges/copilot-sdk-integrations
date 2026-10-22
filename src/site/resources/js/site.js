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

  let screenTheme;
  window.addEventListener("beforeprint", () => {
    screenTheme = document.documentElement.getAttribute("data-theme");
    document.documentElement.setAttribute("data-theme", "light");
  });
  window.addEventListener("afterprint", () => {
    document.documentElement.setAttribute("data-theme", screenTheme || "light");
  });

  const theme = new URLSearchParams(window.location.search).get("clawpilotTheme");
  if (theme === "light" || theme === "dark") {
    for (const link of document.querySelectorAll("a[href]")) {
      if (link.getAttribute("href").startsWith("#")) continue;
      const target = new URL(link.href, window.location.href);
      if (target.origin === window.location.origin && target.pathname.endsWith(".html")) {
        target.searchParams.set("clawpilotTheme", theme);
        link.href = target.href;
      }
    }
  }
})();
