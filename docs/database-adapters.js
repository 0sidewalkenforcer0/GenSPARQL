(() => {
  "use strict";

  const DEMO = window.DEMO_DATA;
  const FIFA = window.FIFA2026_DATA;
  const NOBEL = window.NOBEL_PRIZE_DATA;

  function cloneGraph(graph){
    return {
      nodes: graph.nodes.map(n => ({...n, aliases:[...(n.aliases || [n.label])]})),
      links: graph.links.map(l => ({...l}))
    };
  }

  // The award nodes of the Nobel graph have no rdfs:label, so the page shows the field and
  // the year instead of the local name award_Q<laureate>_Q<field>_<year>.
  function labelNobelAwards(graph){
    const labelById = new Map(graph.nodes.map(n => [n.id, n.label]));
    graph.nodes.forEach(node => {
      const m = /^award_Q\d+_Q(\d+)_(\d{4})$/.exec(node.label || "");
      if (node.type !== "NobelAward" || !m) return;
      const field = labelById.get("http://www.wikidata.org/entity/Q" + m[1]) || "";
      const short = /Economic Sciences/.test(field) ? "Economic Sciences" : field.replace(/^Nobel Prize in /, "");
      node.label = (short ? short + " " : "Nobel Prize ") + m[2];
    });
    return graph;
  }

  const databases = {
    fifa2026:{
      key:"fifa2026",
      label:"World Cup 2026",
      source:"worldcup2026.ttl",
      graph:cloneGraph(FIFA)
    },
    nobelPrize:{
      key:"nobelPrize",
      label:"Nobel Prize",
      source:"nobel-prize.ttl",
      graph:labelNobelAwards(cloneGraph(NOBEL))
    }
  };

  const scenarios = DEMO.scenarios.map(s => ({
    ...s,
    name:
      s.id === "entity" ? "Entity completion" :
      s.id === "attribute" ? "Attribute completion" :
      "Composition + planning"
  }));

  const nobelScenarios = (DEMO.nobelScenarios || []).map(s => ({...s}));

  window.GENSPARQL_ADAPTERS = {
    live:DEMO.live,
    databases,
    scenarios,
    scenariosByDatabase:{fifa2026:scenarios,nobelPrize:nobelScenarios}
  };
})();
