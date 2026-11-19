(() => {
  "use strict";

  const params = new URLSearchParams(window.location.search);
  const mode = params.get("mode") === "live" ? "live" : "demo";

  window.GENSPARQL_PAGE_MODE = mode;
  document.documentElement.dataset.pageMode = mode;
  // ?print lays the graph out for a figure: larger text, no controls (styles.css, app.js).
  if (params.has("print")) document.documentElement.dataset.print = "";

  document.addEventListener("DOMContentLoaded", () => {
    const title = document.getElementById("pageTitle");
    const tagline = document.getElementById("pageTagline");
    const links = {
      demo: document.getElementById("demoModeLink"),
      live: document.getElementById("liveModeLink"),
    };

    if (mode === "live") {
      // The name keeps its wordmark (styles.css); only the word after it is added.
      if (title) title.append(" Workbench");
      if (tagline) tagline.textContent = "Run SPARQL and GENOP queries with the local Java engine";
      document.title = "GenSPARQL · Local Workbench";
    }

    // The two modes are separate pages; the link of the open mode stays put.
    for (const [linkMode, link] of Object.entries(links)) {
      if (!link) continue;
      if (linkMode === mode) {
        link.setAttribute("aria-current", "page");
        link.addEventListener("click", event => event.preventDefault());
      }
    }
  });
})();
