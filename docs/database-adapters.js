(() => {
  "use strict";

  const DEMO = window.DEMO_DATA;
  const FIFA = window.FIFA2026_DATA;

  function cloneGraph(graph){
    return {
      nodes: graph.nodes.map(n => ({...n, aliases:[...(n.aliases || [n.label])]})),
      links: graph.links.map(l => ({...l}))
    };
  }

  const databases = {
    fifa2026:{
      key:"fifa2026",
      label:"FIFA 2026",
      source:"worldcup2026.ttl",
      graph:cloneGraph(FIFA)
    }
  };

  const scenarios = DEMO.scenarios.map(s => ({
    ...s,
    name:
      s.id === "entity" ? "Entity completion" :
      s.id === "attribute" ? "Attribute completion" :
      "Composition + planning"
  }));

  window.GENSPARQL_ADAPTERS = {
    live:DEMO.live,
    databases,
    scenarios
  };
})();
