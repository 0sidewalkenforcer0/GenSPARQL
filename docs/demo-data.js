/* GenSPARQL live demo — pre-recorded data (Route A: static playground).
 *
 * All results here are REAL, taken from the repository:
 *   - Attribute completion: eval/wc2026_x1_results.json (Qwen3-30B-A3B-Instruct-2507).
 *   - Entity completion:     the 2026 World Cup KG team set (gensparql-example/data/worldcup2026.ttl).
 *                            Grounding is computed live in the browser with a real
 *                            character-trigram Jaccard similarity, so the theta slider
 *                            genuinely re-runs the grounding step.
 *   - Composition:           the Group-A composition query from the paper.
 *
 * The engine itself is not run in the page (GitHub Pages is static). Numbers match
 * the paper and the committed evaluation artifacts.
 */
window.DEMO_DATA = {
  // The 48 national teams present in the KG. Used as grounding targets for the
  // entity-completion scenario. Non-qualifiers (Italy, Russia, ...) are absent by design.
  kgTeams: ["Algeria","Argentina","Australia","Austria","Belgium","Bosnia and Herzegovina","Brazil","Canada","Cape Verde","Colombia","Croatia","Curaçao","Czechia","DR Congo","Ecuador","Egypt","England","France","Germany","Ghana","Haiti","Iran","Iraq","Ivory Coast","Japan","Jordan","Mexico","Morocco","Netherlands","New Zealand","Norway","Panama","Paraguay","Portugal","Qatar","Saudi Arabia","Scotland","Senegal","South Africa","South Korea","Spain","Sweden","Switzerland","Tunisia","Turkey","United States","Uruguay","Uzbekistan"],

  scenarios: [
    {
      id: "attribute",
      tab: "Attribute completion",
      title: "Fill an attribute the KG never modeled",
      blurb: "The KG stores no player position, so plain SPARQL returns nothing. GENOP asks the LLM per player and binds the answer as a new column. Values are generated, not grounded, so they are only as reliable as the model.",
      usesTheta: false,
      model: "Qwen/Qwen3-30B-A3B-Instruct-2507 (self-hosted, temp 0)",
      caption: "Real run over 825 squad players. Scored against Wikidata P413 (coarse 4-class, chance 25%).",
      query:
`SELECT ?name ?position WHERE {
  ?p a ex:Athlete ; rdfs:label ?name .
  GENOP("What position does {?name} play? Answer Goalkeeper,
         Defender, Midfielder, Forward, or Unknown.",
        (?position),
        <model:openai:Qwen/Qwen3-30B-A3B-Instruct-2507>)
}`,
      // Real records from eval/wc2026_x1_results.json.
      rows: [
        { name: "Folarin Balogun",  gen: "Forward",    gold: "Forward",    state: "correct" },
        { name: "Piero Hincapié",   gen: "Defender",   gold: "Defender",   state: "correct" },
        { name: "Lionel Messi",     gen: "Midfielder", gold: "Forward / Midfielder", state: "correct" },
        { name: "Jude Bellingham",  gen: "Midfielder", gold: "Midfielder", state: "correct" },
        { name: "Jamal Musiala",    gen: "Midfielder", gold: "Midfielder", state: "correct" },
        { name: "Vinícius Júnior",  gen: "Forward",    gold: "Forward",    state: "correct" },
        { name: "Fabian Rieder",    gen: "Forward",    gold: "Midfielder", state: "wrong"   },
        { name: "Ange-Yoan Bonny",  gen: "Defender",   gold: "Forward",    state: "wrong"   },
        { name: "Mojmír Chytil",    gen: "Defender",   gold: "Forward",    state: "wrong"   },
        { name: "Zineddine Belaïd", gen: "Unknown",    gold: "Defender",   state: "abstain" },
        { name: "Luiz Henrique",    gen: "Unknown",    gold: "Forward",    state: "abstain" }
      ],
      stats: [
        { k: "SPARQL-only answers", v: "0" },
        { k: "Answer rate", v: "77%" },
        { k: "Accuracy (answered, vs P413)", v: "70.4%" },
        { k: "4-class chance", v: "25%" }
      ]
    },

    {
      id: "entity",
      tab: "Entity completion + grounding",
      title: "Generate entities, then ground them to the KG",
      blurb: "In base mode a single call generates a list of names. A type-aware similarity join keeps only the names that match a real KG node above the threshold theta, dropping everything else. Move the slider: raising theta favours precision, lowering it recovers surface variants at the risk of over-matching.",
      usesTheta: true,
      model: "deepseek/deepseek-chat (base mode)",
      caption: "Grounding is recomputed live against the 48 KG teams with character-trigram Jaccard similarity.",
      query:
`SELECT ?team WHERE {
  ?team a ex:Team .
  GENOP("List national teams in the 2026 FIFA World Cup.
         Return ONLY a JSON array of names.",
        (?team),
        <model:openrouter:deepseek/deepseek-chat>, {theta})
}`,
      // A realistic base-mode generation: real qualifiers, surface variants, and
      // non-qualifiers absent from the KG. Grounding decided live by the slider.
      generated: [
        "Brazil", "Argentina", "France", "Japan", "United States", "Mexico",
        "Türkiye", "Czech Republic", "Italy", "Russia", "Serbia"
      ],
      note: "Türkiye and Czech Republic are surface variants of KG entities (Turkey, Czechia); Italy, Russia and Serbia are not in the KG at all."
    },

    {
      id: "composition",
      tab: "Composition + cost",
      title: "Compose GENOP with JOIN and FILTER, cheaply",
      blurb: "Because GENOP lives in the query algebra, it joins and filters like any pattern, and the planner defers it behind selective KG patterns so the LLM runs only on surviving bindings. Here a KG join over Group A, the generated position, and a FILTER run as one query.",
      usesTheta: false,
      model: "Qwen/Qwen3-30B-A3B-Instruct-2507",
      caption: "Group A holds 77 squad players across Czechia, Mexico and South Korea.",
      query:
`SELECT ?name WHERE {
  ?t ex:inGroup ?g . ?g rdfs:label "Group A" .
  ?p ex:playsFor ?t ; rdfs:label ?name .
  GENOP("What position does {?name} play?", (?pos),
        <model:openai:Qwen/Qwen3-30B-A3B-Instruct-2507>)
  FILTER(?pos = "Defender")
}`,
      result: { defenders: 41, total: 77 },
      cost: {
        blurb: "For a single-team variant of this query, a GENOP-first plan would generate for all 825 players. The planner runs GENOP only on the 26 that survive the team pattern.",
        genopFirst: 825,
        planned: 26,
        factor: "31x"
      },
      stats: [
        { k: "SPARQL-only answers", v: "0" },
        { k: "Group A players", v: "77" },
        { k: "Labeled Defender", v: "41" },
        { k: "LLM-call reduction", v: "31x" }
      ]
    }
  ]
};
