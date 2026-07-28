/* GenSPARQL demo data.
 * Pre-recorded results are REAL (eval/wc2026_x1_results.json, worldcup2026.ttl).
 * In Live (BYOK) mode the grounding query is executed for real from the browser
 * with the viewer's own API key. The Java engine is not run on this static page.
 */
window.DEMO_DATA = {

  // Default OpenAI-compatible endpoints for Live mode (all editable in the UI).
  live: {
    chatBase: "https://openrouter.ai/api/v1",
    chatModel: "deepseek/deepseek-chat",
    embBase: "https://api.openai.com/v1",
    embModels: ["text-embedding-3-small", "text-embedding-3-large"]
  },

  scenarios: [
    {
      id: "entity",
      tab: "Ground entities",
      hero: true,                 // uses the KG graph + Live mode
      query:
`SELECT ?team WHERE {
  ?team a ex:Team .
  GENOP("List national teams in the
         2026 FIFA World Cup.",
        (?team), <model>, {theta})
}`,
      prompt: "List national teams competing at the 2026 FIFA World Cup. Return ONLY a JSON array of team names, nothing else.",
      // Pre-recorded base-mode generation (real qualifiers + variants + non-qualifiers).
      generated: ["Brazil","Argentina","France","Japan","United States","Mexico",
                  "Türkiye","Czech Republic","Italy","Russia","Serbia"]
    },
    {
      id: "attribute",
      tab: "Generate attributes",
      hero: false,
      query:
`SELECT ?name ?pos WHERE {
  ?p a ex:Athlete ; rdfs:label ?name .
  GENOP("Position of {?name}?",
        (?pos), <model>)
}`,
      rows: [
        { name: "Folarin Balogun", gen: "Forward",    state: "correct" },
        { name: "Piero Hincapié",  gen: "Defender",   state: "correct" },
        { name: "Lionel Messi",    gen: "Midfielder", state: "correct" },
        { name: "Jude Bellingham", gen: "Midfielder", state: "correct" },
        { name: "Jamal Musiala",   gen: "Midfielder", state: "correct" },
        { name: "Fabian Rieder",   gen: "Forward",    state: "wrong"   },
        { name: "Ange-Yoan Bonny", gen: "Defender",   state: "wrong"   },
        { name: "Zineddine Belaïd",gen: "Unknown",    state: "abstain" }
      ],
      stat: { a: "77%", b: "70%" }   // answer rate / accuracy
    },
    {
      id: "composition",
      tab: "Compose + defer",
      hero: false,
      query:
`SELECT ?name WHERE {
  ?t ex:inGroup ?g .
  ?g rdfs:label "Group A" .
  ?p ex:playsFor ?t ; rdfs:label ?name .
  GENOP("Position of {?name}?", (?pos), <model>)
  FILTER(?pos = "Defender")
}`,
      result: { defenders: 41, total: 77 },
      cost: { genopFirst: 825, planned: 26, factor: "31×" }
    }
  ]
};
