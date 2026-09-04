(() => {
  "use strict";

  const params = new URLSearchParams(window.location.search);
  const mode = params.get("mode") === "live" ? "live" : "demo";

  window.GENSPARQL_PAGE_MODE = mode;
  document.documentElement.dataset.pageMode = mode;

  document.addEventListener("DOMContentLoaded", () => {
    const livePanel = document.getElementById("liveBox");
    const demoPanel = document.getElementById("prerecordedBox");
    const title = document.querySelector(".hero h1");
    const subtitle = document.querySelector(".hero p");
    const modeLabel = document.querySelector(".topbar-meta");
    const demoSummary = demoPanel?.querySelector(":scope > summary");
    const liveSummary = livePanel?.querySelector(":scope > summary");

    if (mode === "live") {
      if (livePanel) livePanel.open = true;
      if (demoPanel) demoPanel.open = false;
      if (title) title.textContent = "GenSPARQL Workbench";
      if (subtitle) subtitle.textContent = "Run SPARQL and GENOP queries with the local Java engine";
      if (modeLabel) modeLabel.textContent = "Local workbench";
      document.title = "GenSPARQL · Local Workbench";
    } else {
      if (demoPanel) demoPanel.open = true;
      if (livePanel) livePanel.open = false;
    }

    demoSummary?.setAttribute("aria-current", mode === "demo" ? "page" : "false");
    liveSummary?.setAttribute("aria-current", mode === "live" ? "page" : "false");

    demoSummary?.addEventListener("click", event => {
      event.preventDefault();
      if (mode !== "demo") window.location.assign("index.html");
    });
    liveSummary?.addEventListener("click", event => {
      event.preventDefault();
      if (mode !== "live") window.location.assign("index.html?mode=live");
    });
  });
})();
