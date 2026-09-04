(() => {
  "use strict";

  const DEFAULTS = {
    apiBase: "http://localhost:8080",
    datasetEndpoint: "/api/gensparql/datasets",
    queryEndpoint: "/api/gensparql/query",
    healthEndpoint: "/api/gensparql/health",
    timeoutMs: 120000
  };

  const supplied = window.GENSPARQL_ENGINE_CONFIG || {};
  const config = {
    ...DEFAULTS,
    ...supplied,
    apiBase: localStorage.getItem("gensparql.engine.apiBase") || supplied.apiBase || DEFAULTS.apiBase
  };

  function cleanBase(value) {
    return String(value || "").trim().replace(/\/+$/, "");
  }

  function url(path) {
    if (/^https?:\/\//i.test(path)) return path;
    return cleanBase(config.apiBase) + path;
  }

  function setApiBase(value) {
    config.apiBase = cleanBase(value);
    localStorage.setItem("gensparql.engine.apiBase", config.apiBase);
  }

  function getApiBase() {
    return cleanBase(config.apiBase);
  }

  async function fetchWithTimeout(target, options = {}) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), Number(config.timeoutMs) || DEFAULTS.timeoutMs);
    try {
      return await fetch(target, { ...options, signal: controller.signal });
    } finally {
      clearTimeout(timer);
    }
  }

  async function readError(response) {
    const type = response.headers.get("content-type") || "";
    try {
      if (type.includes("application/json")) {
        const data = await response.json();
        return data.message || data.error || JSON.stringify(data);
      }
      return (await response.text()) || `${response.status} ${response.statusText}`;
    } catch (_) {
      return `${response.status} ${response.statusText}`;
    }
  }

  async function health() {
    if (!getApiBase()) throw new Error("Java engine URL is empty.");
    const response = await fetchWithTimeout(url(config.healthEndpoint), {
      method: "GET",
      headers: { Accept: "application/json" }
    });
    if (!response.ok) throw new Error(await readError(response));
    const body = await response.json().catch(() => ({}));
    return body;
  }

  async function uploadFile(file) {
    if (!getApiBase()) throw new Error("Java engine URL is empty.");
    const form = new FormData();
    form.append("file", file, file.name || "dataset.ttl");
    const response = await fetchWithTimeout(url(config.datasetEndpoint), {
      method: "POST",
      body: form
    });
    if (!response.ok) throw new Error(await readError(response));
    const data = await response.json();
    if (!data.datasetId) throw new Error("Java backend did not return datasetId.");
    return data;
  }

  async function uploadUrl(sourceUrl, fileName = "dataset.ttl") {
    const source = await fetch(sourceUrl);
    if (!source.ok) throw new Error(`Could not read local dataset ${sourceUrl}: HTTP ${source.status}`);
    const blob = await source.blob();
    const file = new File([blob], fileName, { type: blob.type || "text/turtle" });
    return uploadFile(file);
  }

  function toTerm(term) {
    if (term == null) return null;
    if (typeof term === "object" && term.kind && Object.prototype.hasOwnProperty.call(term, "value")) {
      return term;
    }
    if (typeof term === "object" && Object.prototype.hasOwnProperty.call(term, "value")) {
      if (term.type === "uri" || term.type === "iri") return { kind: "iri", value: String(term.value) };
      const out = { kind: "literal", value: String(term.value) };
      if (term["xml:lang"] || term.lang) out.lang = term["xml:lang"] || term.lang;
      if (term.datatype) out.datatype = term.datatype;
      return out;
    }
    if (typeof term === "object" && term.uri) return { kind: "iri", value: String(term.uri) };
    return { kind: "literal", value: String(term) };
  }

  function detectGeneratedVars(query) {
    const vars = [];
    const re = /GENOP\s*\([\s\S]*?\(\s*(\?[A-Za-z_][\w-]*)\s*(?:,\s*\?[A-Za-z_][\w-]*\s*)*\)\s*,/gi;
    let m;
    while ((m = re.exec(String(query || "")))) {
      const tuple = m[0].match(/\(\s*((?:\?[A-Za-z_][\w-]*\s*,?\s*)+)\)\s*,\s*[^)]*$/i);
      const source = tuple ? tuple[1] : m[1];
      const matches = String(source).match(/\?([A-Za-z_][\w-]*)/g) || [];
      matches.forEach(v => {
        const name = v.slice(1);
        if (!vars.includes(name)) vars.push(name);
      });
    }
    return vars;
  }

  function normalize(payload, query, prefixes = {}) {
    let vars = [];
    let rows = [];

    if (payload && payload.head && payload.results && Array.isArray(payload.results.bindings)) {
      vars = Array.isArray(payload.head.vars) ? payload.head.vars.slice() : [];
      rows = payload.results.bindings.map(binding => {
        const out = {};
        vars.forEach(v => { out[v] = toTerm(binding[v]); });
        return out;
      });
    } else if (payload && Array.isArray(payload.rows)) {
      vars = Array.isArray(payload.vars) ? payload.vars.map(v => String(v).replace(/^\?/, "")) : [];
      if (!vars.length && payload.rows.length) {
        vars = [...new Set(payload.rows.flatMap(row => Object.keys(row || {}).map(v => v.replace(/^\?/, ""))))];
      }
      rows = payload.rows.map(row => {
        const out = {};
        vars.forEach(v => { out[v] = toTerm(row[v] ?? row[`?${v}`]); });
        return out;
      });
    } else if (payload && typeof payload.boolean === "boolean") {
      vars = ["ASK"];
      rows = [{ ASK: { kind: "literal", value: String(payload.boolean) } }];
    } else {
      throw new Error("Unsupported JSON response from Java engine.");
    }

    const trace = payload.trace || payload.meta || {};
    const generatedVars = payload.generatedVars || trace.generatedVars || detectGeneratedVars(query);
    const genopCalls = Number(
      payload.genopCalls ?? trace.genopCalls ?? trace.llmCalls ?? trace.generationCalls ?? 0
    ) || 0;
    const operators = payload.operators || trace.operators || [];
    const plan = payload.plan || trace.plan || trace.executionOrder || "Java GenSPARQL engine";
    const total = Number.isFinite(payload.total) ? payload.total : rows.length;

    return {
      vars,
      rows,
      total,
      prefixes,
      generatedVars,
      genopCalls,
      operators: Array.isArray(operators) ? operators : [],
      plan: Array.isArray(plan) ? plan.join(" → ") : String(plan),
      trace,
      raw: payload
    };
  }

  async function query(queryText, { datasetId = null, prefixes = {}, includeTrace = true, llm = null, similarity = null } = {}) {
    if (!getApiBase()) throw new Error("Java engine URL is empty.");
    const request = { query: String(queryText || ""), includeTrace };
    if (datasetId) request.datasetId = datasetId;
    if (llm) request.llm = llm;
    if (similarity) request.similarity = similarity;

    const response = await fetchWithTimeout(url(config.queryEndpoint), {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json" },
      body: JSON.stringify(request)
    });
    if (!response.ok) throw new Error(await readError(response));
    const payload = await response.json();
    return normalize(payload, queryText, prefixes);
  }

  window.GENSPARQL_ENGINE = {
    config,
    setApiBase,
    getApiBase,
    health,
    uploadFile,
    uploadUrl,
    query,
    normalize
  };
})();
