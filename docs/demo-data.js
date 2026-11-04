/* GenSPARQL demo data.
 * The recorded mode replays answers of the evaluation of the WWW'27 demo paper: the
 * answers, counts and rates come from docs/recorded-runs.js, which
 * eval/www27/make_recorded_data.py writes from eval/www27/results. The queries below are
 * the queries of that evaluation.
 * In Live (BYOK) mode the grounding query is executed for real from the browser
 * with the viewer's own API key. The Java engine is not run on this static page.
 */
(function(){
  const R = window.RECORDED_RUNS;
  const factor = (a, b) => `${(a / b).toFixed(1)}×`;
  const short = model => model.split("/").pop();
  const POSITION_PROMPT =
`"Which position does the footballer {?name}
          primarily play: goalkeeper, defender,
          midfielder or forward?"`;

  window.DEMO_DATA = {

    // Default OpenAI-compatible endpoints for Live mode (all editable in the UI).
    live: {
      chatBase: "https://api.groq.com/openai/v1",
      chatModel: "openai/gpt-oss-120b",
      embBase: "https://api.openai.com/v1",
      embModels: ["text-embedding-3-small", "text-embedding-3-large"]
    },

    scenarios: [
      {
        id: "entity",
        tab: "Ground entities",
        hero: true,                 // uses the KG graph + Live mode
        recordedModel: short(R.wcEntity.model),
        query:
`SELECT ?team WHERE {
  ?team a ex:Team .
  GENOP("List the national teams that play
         at the 2026 FIFA World Cup.",
        (?team), <model>, {theta})
}`,
        prompt: R.wcEntity.prompt,
        // The names that Mistral-7B listed in the evaluation (E2).
        generated: R.wcEntity.generated
      },
      {
        id: "attribute",
        tab: "Generate attributes",
        hero: false,
        recordedModel: short(R.wcAttribute.model),
        query:
`SELECT ?name ?pos WHERE {
  ?p ex:playsFor ?t .
  ?t rdfs:label "Argentina" .
  ?p rdfs:label ?name .
  GENOP(${POSITION_PROMPT},
        (?pos), <model>)
}`,
        rows: R.wcAttribute.rows,
        stat: R.wcAttribute.stat,
        metricLabel: `Accuracy vs Wikidata P413 (all ${R.wcAttribute.players.toLocaleString("en")} players)`,
        note: `Answers of ${short(R.wcAttribute.model)} through GENOP in the evaluation over all ${R.wcAttribute.players.toLocaleString("en")} players. Answer rate and accuracy refer to all players; the rows are the 26 Argentine players. SPARQL alone returns no position, because the graph leaves out the positions that Wikidata records.`
      },
      {
        id: "composition",
        tab: "Compose + defer",
        hero: false,
        recordedModel: short(R.wcComposition.model),
        query:
`SELECT ?name ?pos WHERE {
  ?t ex:inGroup ?g .
  ?g rdfs:label "Group A" .
  ?p ex:playsFor ?t ; rdfs:label ?name .
  GENOP(${POSITION_PROMPT},
        (?pos), <model>)
  FILTER(LCASE(STR(?pos)) = "defender")
}`,
        filterLabel: 'LCASE(STR(?pos)) = "defender"',
        result: { defenders: R.wcComposition.defenders, total: R.wcComposition.total,
                  defenderIds: R.wcComposition.defenderIds },
        // Measured through the service: GENOP written before the Group A patterns makes
        // genopFirst calls with the reordering switched off and planned calls with it on.
        cost: { genopFirst: R.wcComposition.genopFirst, planned: R.wcComposition.planned,
                factor: factor(R.wcComposition.genopFirst, R.wcComposition.planned) }
      }
    ],

    nobelScenarios: [
      {
        id: "entity",
        tab: "Ground Nobel fields",
        name: "Ground Nobel Prize fields",
        hero: true,
        targetType: "NobelField",
        targetLabel: "Nobel fields",
        recordedModel: short(R.nobelEntity.model),
        query:
`SELECT ?field WHERE {
  ?field a ex:NobelField .
  GENOP("List the fields in which the Nobel
         Prize is awarded.",
        (?field), <model>, {theta})
}`,
        prompt: R.nobelEntity.prompt,
        // The fields that Qwen3-30B listed in the evaluation (E2). Their labels in the graph
        // read "Nobel Prize in Physics" and so on, so the short names need a low threshold.
        generated: R.nobelEntity.generated
      },
      {
        id: "attribute",
        tab: "Generate attributes",
        name: "Generate laureate birth years",
        hero: false,
        sourceType: "NobelLaureate",
        recordedModel: short(R.nobelAttribute.model),
        generatedPredicate: "?year",
        generatedTitle: "Generated birth years",
        metricLabel: `Accuracy vs Wikidata P569 (all ${R.nobelAttribute.laureates.toLocaleString("en")} laureates)`,
        note: `Answers of ${short(R.nobelAttribute.model)} through GENOP in the evaluation over all ${R.nobelAttribute.laureates.toLocaleString("en")} laureates. Answer rate and accuracy refer to all laureates; the rows are the Physics laureates since 2020.`,
        query:
`SELECT ?name ?year WHERE {
  ?laureate a ex:NobelLaureate ;
            rdfs:label ?name .
  GENOP("In which year was the Nobel laureate
         {?name} born? Answer with the year only.",
        (?year), <model>)
}`,
        rows: R.nobelAttribute.rows,
        stat: R.nobelAttribute.stat
      },
      {
        id: "composition",
        tab: "Compose + defer",
        name: "Compose Physics laureates since 2020 with generated contributions",
        hero: false,
        recordedModel: short(R.nobelComposition.model),
        query:
`PREFIX wd: <http://www.wikidata.org/entity/>
PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>

SELECT ?name ?c WHERE {
  ?a ex:field wd:Q38104 ;
     ex:winningYear ?y ;
     ex:winner ?l .
  ?l rdfs:label ?name .
  FILTER(xsd:integer(STR(?y)) >= 2020)
  GENOP("For which contribution did {?name}
         receive the Nobel Prize in Physics?
         Answer with one short phrase.",
        (?c), <model>)
}`,
        result: { matches: R.nobelComposition.answered, total: R.nobelComposition.total,
                  answeredNames: R.nobelComposition.answeredNames },
        // Measured through the service: with the reordering switched off the FILTER runs
        // after GENOP (genopFirst calls), with it on before GENOP (planned calls).
        cost: { genopFirst: R.nobelComposition.genopFirst, planned: R.nobelComposition.planned,
                factor: factor(R.nobelComposition.genopFirst, R.nobelComposition.planned) },
        bindingLabel: "Physics award bindings",
        selectionLabel: "Physics awards since 2020",
        generatedVariable: "?c",
        generatedLabel: "Contribution",
        sinceYear: 2020,
        hasFilter: false,
        resultLabel: "generated contributions"
      }
    ]
  };
})();
