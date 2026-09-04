# GenSPARQL World Cup 2026 demo

This package keeps the three existing pre-recorded GenSPARQL workflows and adds an expandable demo area, a Live free-query editor, browser-side KG visualization, and execution through the Java GenSPARQL engine.

## What changed

- **Pre-recorded demo**: the existing Ground entities, Generate attributes, and Compose + defer query structure and execution logic are unchanged. Compose counts are updated to the corrected FIFA KG and FIFA position ground truth.
- **Live mode**: includes a large editable **Query** box. `Ctrl/Cmd + Enter` runs the current query, renders its bindings in a result table, and highlights matching KG entities in the graph.
- **Upper bar**: adds **Upload KG ↑** beside the Database selector. `.ttl` / `.turtle` files are parsed in the browser, added to the Database menu, and made the active KG.
- Uploaded KGs hide the built-in pre-recorded demo automatically; Query and graph exploration remain available. Selecting World Cup or Nobel Prize restores the matching pre-recorded workflows.
- The default FIFA database is regenerated directly from the corrected `worldcup2026.ttl`.
- **Nobel Prize** is also built in and can be selected without uploading a file. It uses the corrected Nobel dataset through 2025.
- Switching to Nobel Prize also switches the pre-recorded area to Nobel entity grounding, generated laureate contributions, and a Physics-2020+ Compose + defer workflow that generates missing award motivations; switching back restores the three FIFA workflows.
- Nobel Compose + defer animates distinct intermediate graphs for GENOP-first and Planner-first, and every execution-step button can replay its corresponding graph state.


## Corrected FIFA data

- `worldcup2026.ttl` contains 48 final squads × 26 players = **1,248 Athlete nodes**.
- Group A is **Czechia, Mexico, South Africa, Korea Republic** = **104 players**.
- Position remains intentionally absent from the KG, preserving the attribute-completion task.
- For the pre-recorded Compose filter result, FIFA Squad Lists `POS=DF` is used as external ground truth: **35 Group A defenders** = Czechia 9 + Mexico 7 + South Africa 10 + Korea Republic 9.
- GENOP-first therefore models **1,248 LLM calls**; Planner models **104 LLM calls**, a **12.0×** reduction.

## Live query execution

Live SPARQL and GenSPARQL queries are sent to the configured Java engine. Parsing, planning, standard SPARQL operators, extension functions, and `GENOP` execution therefore use the real Java implementation.

The UI now exposes the recorded research demo and the local Java workbench as two
separate modes:

- `index.html` is the backend-free recorded demo used by GitHub Pages.
- `index.html?mode=live` is the local query workbench. It initializes the Java
  engine controls and live-query controller; the recorded workflow is not shown.

Both modes share the dataset picker and knowledge-graph renderer, but only the
active mode is initialized by `app.js`.

## Files

- `index.html`
- `styles.css`
- `app.js`
- `database-adapters.js`
- `demo-data.js`
- `fifa2026-data.js`
- `worldcup2026.ttl`
- `nobel-prize-data.js`
- `nobel-prize.ttl`
- `gensparql-icon.png`

## Visualization

The knowledge graph uses the local `native-graph.js` SVG renderer for layout, zoom, pan, drag, and collision handling. It has no D3 dependency. The stylesheet still imports Cormorant Garamond from Google Fonts; KG rendering, parsing, and local query execution otherwise run in the browser.

## Start locally

```bash
python3 -m http.server 8000
```

Then open `http://localhost:8000/`.

## Compose step visualization

The Compose + defer query structure is unchanged; its counts now reflect the corrected FIFA KG and FIFA defender ground truth. The execution-order cards are interactive graph snapshots:

- GENOP-first: Athlete bindings → GENOP → KG selection → FILTER → Result.
- Planner: KG selection → GENOP → FILTER → Result.
- Athlete bindings shows every Athlete node in the KG.
- GENOP attaches a generated yellow `?pos` node to every binding reaching GENOP.
- KG selection narrows the visualization to Group A bindings.
- FILTER keeps the recorded 35 Defender bindings and visibly marks the other 69 Group A bindings as dropped.
- Clicking any execution step replays that intermediate graph; Run plays the steps in order automatically.

The Live Free Query result table is rendered in the right column directly below the knowledge graph.


## Planner visualization update

The Planner path has four visual steps: `KG selection → GENOP → FILTER → Result`.
There is no separate Survivors step. The GENOP stage preserves the Group A
selection graph and adds one yellow generated `?pos` node beside each of the 104
surviving player bindings. The pre-recorded query structure and execution logic are unchanged; the counts/results are the corrected 1,248 / 104 / 35 values above.


## Compose position visualization

- `fifa2026-positions.js` stores the pre-recorded FIFA squad `POS` value for each of the 1,248 players. These values are visualization/evaluation data only and are not added to the KG.
- GENOP-first Step 2 keeps the exact Step-1 Athlete-bindings layout and adds one yellow Position point beside every player.
- Hover a yellow point to see the player name, full Position and FIFA code.
- Group-A selection, FILTER, Result, and Planner GENOP/FILTER/Result use the same hoverable Position points.

## Latest Live-mode layout adjustment

- Free Query occupies its own full-width row and uses the same editor height as Sample Query.
- Chat endpoint/model, grounding controls, and API key are stacked below the query rather than sharing the query row.
- API key remains the last Live-mode setting.
- Compose generated Position nodes remain text-free yellow dots; hover shows Position details and click pins the tooltip.

## Live demo

The built-in databases are **World Cup 2026** and **Nobel Prize**. The Live panel now includes a short in-page tutorial. The API-key field is immediately above the Chat endpoint/model fields.

Query runs through the Java GenSPARQL engine. The result table renders the first 250 rows and the graph focuses on returned entity IRIs or literal labels that match KG entities.

Ordinary local SPARQL queries do not require an API key. The current Free Query executor still does not execute GENOP; GENOP remains in the pre-recorded workflows. API-backed embedding grounding requires an appropriate key for the configured endpoint.

## Live query results and graph exploration

Query bindings remain available in the result panel below the graph. Matching entities and nearby KG context are also presented in the graph; generated GENOP values are attached to their matched source entity when possible.

## Live GENOP

The Live Free Query executor now accepts a single-output `GENOP(prompt, (?var), <model>)` operator.
The browser substitutes `{?name}`-style placeholders from the current solution binding and calls the configured OpenAI-compatible `/chat/completions` endpoint. The configured Chat model field is used for `<model>`.

For safety/cost in this static research demo, one Run is capped at 200 GENOP calls and uses concurrency 4. Generated literal values appear as ordinary cells in the separate result table.

OpenAI defaults in this build:
- Chat endpoint: `https://api.groq.com/openai/v1`
- Chat model: `openai/gpt-oss-120b`

Errors are duplicated directly below the Run-query controls so API/permission/CORS failures are visible without scrolling.

## Java engine integration

This build now supports the real Java `gensparql-engine` as the default executor for **Live → Free query**.

The browser no longer needs to reproduce Java GENOP/planner semantics when **Java GenSPARQL engine** is selected. The UI sends the complete GenSPARQL query unchanged to the backend and uses the returned bindings for the result table.

### UI behavior

- **Java GenSPARQL engine** is the only Live query executor.
- **Query graph presentation.** Returned entity IRIs and literal labels matching KG entities are highlighted with nearby context, while every returned binding remains available in the result table.
- **Java engine URL** defaults to `http://localhost:8080` and is stored in `localStorage` after editing.
- **Test engine** calls `GET /api/gensparql/health`.
- **API key**, **OpenAI-compatible base URL**, and **Chat model** are sent with each Java query. They override the model URI/provider for that request only; the key is not stored in `localStorage`.
- The built-in `worldcup2026.ttl` is uploaded to Java automatically on the first Java query in the page session.
- A user-uploaded Turtle KG is still parsed locally for graph visualization and is also uploaded to the Java backend while Java mode is active.
- The returned backend `datasetId` is stored on that in-memory database entry and reused by later queries.

### Backend contract

The Java host needs these endpoints:

```text
GET  /api/gensparql/health
POST /api/gensparql/datasets
POST /api/gensparql/query
```

The query request may include a request-local model configuration:

```json
{
  "datasetId": "...",
  "query": "SELECT ...",
  "llm": {
    "apiKey": "sk-...",
    "baseUrl": "https://api.openai.com/v1",
    "model": "gpt-5"
  }
}
```

Dataset upload:

```http
POST /api/gensparql/datasets
Content-Type: multipart/form-data
file=<RDF file>
```

Expected response:

```json
{
  "datasetId": "abc123",
  "name": "worldcup2026.ttl",
  "tripleCount": 5000
}
```

Query request:

```json
{
  "datasetId": "abc123",
  "query": "SELECT ... WHERE { ... GENOP(...) ... }",
  "includeTrace": true
}
```

The query response can be standard SPARQL Results JSON:

```json
{
  "head": {"vars": ["player", "pos"]},
  "results": {
    "bindings": [
      {
        "player": {"type": "uri", "value": "http://example.org/player/1"},
        "pos": {"type": "literal", "value": "Forward"}
      }
    ]
  }
}
```

or simplified JSON:

```json
{
  "vars": ["player", "pos"],
  "rows": [
    {"player": "http://example.org/player/1", "pos": "Forward"}
  ],
  "genopCalls": 1,
  "plan": "BGP -> GENOP -> FILTER"
}
```

Optional `trace`, `operators`, `generatedVars`, `genopCalls`, and `plan` metadata are displayed/used when returned.

### Important deployment note

If the static UI and Java backend are on different origins, the Java backend must allow CORS for the UI origin. For example, a page served at `http://localhost:8000` must be allowed to call a backend at `http://localhost:8080`.
