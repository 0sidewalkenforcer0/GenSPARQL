# GenSPARQL FIFA 2026 demo

This static package presents three GenSPARQL workflows over the FIFA 2026 knowledge graph:

1. Ground entities
2. Generate attributes
3. Compose + defer

## Data

- `worldcup2026.ttl` is the source knowledge graph. It holds 825 players over 32 of the 48
  teams: 25 teams have a 26-player squad, 7 have 25, and 16 have none yet. Player counts in
  the demo are counts over those 825.
- `fifa2026-data.js` is the browser-ready graph generated from that TTL file.
- `demo-data.js` contains the fixed sample queries, generated examples, evaluation rows, composition metrics, and default Live-mode endpoint settings. It is not exposed as a second database.

Every entity IRI and label comes from the TTL file.

`demo-data.js` records the Compose + defer outcome as an aggregate, so the graph
view expands it into per-player rows over the Group A squads for display.

The composition counts are derived from the KG: 825 is the number of
`ex:Athlete` nodes, and 77 is the number of `?p` bindings that survive the
Group A patterns.

## Operators

`GENOP` is the one generative construct a query writes. A trailing threshold on it stands for
grounding the generated values by a similarity join at that threshold; the join is chosen by
the engine and has no surface syntax of its own, so queries never name it and the editor does
not highlight it as a keyword.

A generated value that resembles nothing in the graph has no grounding target and simply gets
no relation drawn. That is the ordinary case, not a special one: of the sample generated names,
`Italy` shares no trigram with any team label in this graph.

## Interface notes

- The database selector contains only FIFA 2026.
- The shared Run hint has been removed from all three scenarios.
- Compose + defer no longer displays the former two-line execution-status label.
- The Compose free-query editor has been removed.
- The main `GenSPARQL` heading uses the same Cormorant Garamond typeface as the upper-bar wordmark.
- The upper bar is slightly taller.
- The Live-mode API-key field uses the compact column width rather than spanning the full panel.
- Text-based graph labels display `similarity` instead of `Text similarity`; embedding mode still displays `Embedding similarity`.

## Files

- `index.html`
- `styles.css`
- `app.js`
- `database-adapters.js`
- `demo-data.js`
- `fifa2026-data.js`
- `worldcup2026.ttl`
- `gensparql-icon.png`
- `vendor/d3.v7.9.0.min.js`

## Dependencies

d3 v7.9.0 is vendored under `vendor/`. The page loads no third-party script at
runtime, so it works offline and no external origin shares a document with the
Live-mode API-key field.

## Start locally

```bash
python3 -m http.server 8000
```

Open `http://localhost:8000/`.
