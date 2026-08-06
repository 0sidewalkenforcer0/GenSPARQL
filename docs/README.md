# GenSPARQL FIFA 2026 demo

This static package presents three GenSPARQL workflows over the FIFA 2026 knowledge graph:

1. Ground entities
2. Generate attributes
3. Compose + defer

## Data

- `worldcup2026.ttl` is the source knowledge graph.
- `fifa2026-data.js` is the browser-ready graph generated from that TTL file.
- `demo-data.js` contains the fixed sample queries, generated examples, evaluation rows, composition metrics, and default Live-mode endpoint settings. It is not exposed as a second database.

Every entity IRI and label comes from the TTL file. Grounding and attribute
generation display only recorded or live-computed values.

One known exception: the bundled evaluation records the Compose + defer result
as an aggregate (41 of 77) and not as a row-level result set, so the graph view
materialises a deterministic 41-row stand-in over the real Group A players. The
Defender assignments drawn there are illustrative, not recorded results. See
`focusCompositionContext` in `app.js`.

The composition counts in `demo-data.js` are checked against the KG: 825 is the
number of `ex:Athlete` nodes, and 77 is the number of `?p` bindings that survive
the Group A patterns.

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
