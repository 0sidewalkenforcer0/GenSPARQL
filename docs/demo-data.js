/* GenSPARQL demo data.
 * Pre-recorded results are REAL (eval/wc2026_x1_results.json, worldcup2026.ttl).
 * In Live (BYOK) mode the grounding query is executed for real from the browser
 * with the viewer's own API key. The Java engine is not run on this static page.
 */
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
      result: { defenders: 35, total: 104,
        defenderIds: ["http://www.wikidata.org/entity/Q84312754", "http://www.wikidata.org/entity/Q13360752", "http://www.wikidata.org/entity/Q107647351", "http://www.wikidata.org/entity/Q15113326", "http://www.wikidata.org/entity/Q114713721", "http://www.wikidata.org/entity/Q27919903", "http://www.wikidata.org/entity/Q96070453", "http://www.wikidata.org/entity/Q6160707", "http://www.wikidata.org/entity/Q96613620", "http://www.wikidata.org/entity/Q24034870", "http://www.wikidata.org/entity/Q20816441", "http://www.wikidata.org/entity/Q27859514", "http://www.wikidata.org/entity/Q39161128", "http://www.wikidata.org/entity/Q107739233", "http://www.wikidata.org/entity/Q124420330", "http://www.wikidata.org/entity/Q22092356", "http://www.wikidata.org/entity/Q127324870", "http://www.wikidata.org/entity/Q137299823", "http://www.wikidata.org/entity/Q26405966", "http://www.wikidata.org/entity/Q133464757", "http://www.wikidata.org/entity/Q133794425", "http://www.wikidata.org/entity/Q58580967", "http://www.wikidata.org/entity/Q58810793", "http://www.wikidata.org/entity/Q127514195", "http://www.wikidata.org/entity/Q122207713", "http://www.wikidata.org/entity/Q117790110", "http://www.wikidata.org/entity/Q106779330", "http://www.wikidata.org/entity/Q28699707", "http://www.wikidata.org/entity/Q79904514", "http://www.wikidata.org/entity/Q61100362", "http://www.wikidata.org/entity/Q119432409", "http://www.wikidata.org/entity/Q31146726", "http://www.wikidata.org/entity/Q69696842", "http://www.wikidata.org/entity/Q97205258", "http://www.wikidata.org/entity/Q98829565"]
      },
      // genopFirst = every ex:Athlete in worldcup2026.ttl (1,248).
      // planned    = the ?p bindings that survive the Group A patterns (104),
      //              i.e. the same 104 the recorded result is reported over.
      // factor     = 1248 / 104.
      cost: { genopFirst: 1248, planned: 104, factor: "12.0×" }
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
      query:
`SELECT ?field WHERE {
  ?field a ex:NobelField .
  GENOP("List the official Nobel Prize fields.",
        (?field), <model>, {theta})
}`,
      prompt: "List the official Nobel Prize fields. Return ONLY a JSON array of field names.",
      generated: [
        "Nobel Prize in Physics",
        "Nobel Prize in Chemistry",
        "Nobel Prize in Physiology or Medicine",
        "Nobel Prize in Literature",
        "Nobel Peace Prize",
        "Sveriges Riksbank Prize in Economic Sciences in Memory of Alfred Nobel",
        "Nobel Prize in Mathematics"
      ]
    },
    {
      id: "attribute",
      tab: "Generate attributes",
      name: "Generate laureate contributions",
      hero: false,
      sourceType: "NobelLaureate",
      generatedPredicate: "?contribution",
      generatedTitle: "Generated laureate contributions",
      metricLabel: "Recorded sample accuracy",
      note: "The contribution is generated by GENOP and is not stored in the Nobel KG.",
      query:
`SELECT ?name ?contribution WHERE {
  ?laureate a ex:NobelLaureate ;
            rdfs:label ?name .
  GENOP("Main contribution of {?name}?",
        (?contribution), <model>)
}`,
      rows: [
        {name:"Marie Curie",gen:"Radioactivity",state:"correct"},
        {name:"Albert Einstein",gen:"Photoelectric effect",state:"correct"},
        {name:"Alexander Fleming",gen:"Penicillin",state:"correct"},
        {name:"Donna Strickland",gen:"Chirped pulse amplification",state:"correct"},
        {name:"Andrea M. Ghez",gen:"Galactic-center black hole",state:"correct"},
        {name:"Anne L'Huillier",gen:"Attosecond light pulses",state:"correct"},
        {name:"Bob Dylan",gen:"Literature and song",state:"correct"},
        {name:"Niels Bohr",gen:"Unknown",state:"abstain"}
      ],
      stat:{a:"88%",b:"100%"}
    },
    {
      id: "composition",
      tab: "Compose + defer",
      name: "Compose Nobel selection + generated motivations",
      hero: false,
      query:
`PREFIX wd: <http://www.wikidata.org/entity/>
PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>

SELECT ?name ?motivation WHERE {
  ?award ex:field wd:Q38104 ;
         ex:year ?year ;
         ex:winner ?laureate .
  FILTER(xsd:integer(?year) >= 2020)
  ?laureate rdfs:label ?name .
  GENOP(
    "What contribution earned {?name} the Nobel Prize in Physics? Return one concise sentence.",
    (?motivation), <model>
  )
}`,
      result:{
        matches:17,total:17
      },
      cost:{genopFirst:1018,planned:17,factor:"59.9×"},
      bindingLabel:"Nobel laureates",
      selectionLabel:"Physics awards since 2020",
      generatedVariable:"?motivation",
      generatedLabel:"Award motivation",
      sinceYear:2020,
      hasFilter:false,
      resultLabel:"generated motivations"
    }
  ]
};
