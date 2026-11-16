#!/usr/bin/env python3
"""The bge-large-en-v1.5 similarity of each name that an LLM listed in E2 to each entity of the
graph that the query asks for, for the recorded mode of the workbench (from the repository root):

  embedding_scores.py inputs IN.json [QUERY]          the names and labels to embed
  sbatch eval/www27/embed_texts.sbatch IN.json VECTORS.json   (on the cluster, no GPU needed)
  embedding_scores.py scores VECTORS.json [QUERY]     writes results/e2-embedding-scores/<QUERY>.json

QUERY is e2_wc_teams (the default: the team names of Mistral-7B and the 48 teams) or
e2_nobel_fields (the field names of Qwen3-30B and the 6 Nobel fields), the two queries whose
names the workbench maps.

The score of a name and a team is the one that the similarity join of the engine computes
(SimScoreEvaluator with EmbeddingSimText): the maximum, over the labels of the team, of 1 for an
equal label and otherwise the cosine of the two embeddings clamped to [0, 1]. The labels are the rdfs:label values of the team, as GraphLabels reads them.

The script then checks the scores against the embedding runs of the E2 replay: at every
threshold, the teams that some name reaches must be the teams that the query returned. The
workbench draws only the best team of each name; below 0.85 a name can reach several teams, and
the check reports how many teams the best matches alone give.
"""
import json
import math
import os
import sys

import rdflib
from rdflib.namespace import RDF, RDFS

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
EX = rdflib.Namespace("http://example.org/")
# The model whose names the workbench shows, the graph and the class of the entities, and the key
# of the entities in the output (e2_wc_teams.json keeps its key "teams").
QUERIES = {
    "e2_wc_teams": ("mistralai_Mistral-7B-Instruct-v0.3", "worldcup2026.ttl", EX.Team, "teams"),
    "e2_nobel_fields": ("Qwen_Qwen3-30B-A3B-Instruct-2507", "nobel-prize.ttl", EX.NobelField, "entities"),
}
QUERY = "e2_wc_teams"


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def names():
    """The names that the model generated, as make_recorded_data.py shows them."""
    model = QUERIES[QUERY][0]
    rows = load(os.path.join(HERE, "results", model, QUERY + ".json"))["generated_before"]["rows"]
    return [r["x"] for r in rows if r.get("x", "").strip() not in ("", "[]", '[""]')]


def team_labels():
    """The labels of every entity of the class of the query, by IRI."""
    _, graph, cls, _ = QUERIES[QUERY]
    g = rdflib.Graph()
    g.parse(os.path.join(ROOT, "docs", graph), format="turtle")
    return {str(t): sorted(str(l) for l in g.objects(t, RDFS.label))
            for t in sorted(g.subjects(RDF.type, cls), key=str)}


def similarity(a, b, vec):
    """EmbeddingSimText.similarity: 1 for equal strings, else the clamped cosine."""
    if a == b:
        return 1.0
    x, y = vec[a], vec[b]
    dot = sum(p * q for p, q in zip(x, y))
    norm = math.sqrt(sum(p * p for p in x)) * math.sqrt(sum(q * q for q in y))
    return 0.0 if norm == 0 else max(0.0, min(1.0, dot / norm))


def inputs(path):
    labels = team_labels()
    texts = sorted(set(names()) | {l for ls in labels.values() for l in ls})
    with open(path, "w", encoding="utf-8") as f:
        json.dump({"texts": texts}, f, indent=1, ensure_ascii=False)
    print(f"wrote {len(texts)} texts to {path}")


def scores(vectors_path):
    emb = load(vectors_path)
    vec = dict(zip(emb["texts"], emb["vectors"]))
    labels = team_labels()
    generated = names()
    score = {n: {t: max(similarity(n, l, vec) for l in ls) for t, ls in labels.items()} for n in generated}
    best = []
    for n in generated:
        t = max(labels, key=lambda t: (score[n][t], t))
        best.append({"name": n, "team": t, "label": labels[t][0], "score": round(score[n][t], 6)})

    # Each embedding run of the replay against the teams that the scores reach.
    check = []
    replay = os.path.join(HERE, "results", "e2-replay-20261113", QUERIES[QUERY][0], QUERY + ".json")
    for run in load(replay)["runs"]:
        if run["similarity"] != "embedding":
            continue
        theta = run["theta"]
        returned = {r["x"] for r in run["rows"]}
        joined = {t for n in generated for t in labels if score[n][t] >= theta}
        shown = {b["team"] for b in best if score[b["name"]][b["team"]] >= theta}
        check.append({"theta": theta, "returned": len(returned), "joined": len(joined), "shown": len(shown),
                      "joinedEqual": joined == returned, "shownEqual": shown == returned,
                      "onlyReturned": sorted(returned - joined), "onlyJoined": sorted(joined - returned)})
        print(f"theta {theta}: query {len(returned)} teams, scores {len(joined)} (equal: {joined == returned}),"
              f" best matches {len(shown)} (equal: {shown == returned})")

    out = {"model": emb["model"],
           "source": f"{emb.get('runtime', '')}, job {emb.get('job', '')}",
           "names": generated, QUERIES[QUERY][3]: labels,
           "scores": {n: {t: round(s, 6) for t, s in score[n].items()} for n in generated},
           "best": best, "check": check}
    path = os.path.join(HERE, "results", "e2-embedding-scores", QUERY + ".json")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(out, f, indent=1, ensure_ascii=False)
    print("wrote", path)
    if QUERY == "e2_wc_teams":
        print("United States vs USA:", round(similarity("United States", "USA", vec), 6)
              if "USA" in vec and "United States" in vec else "not embedded")
    for b in best:
        print(f"  {b['name']:22s} -> {b['label']:22s} {b['score']:.4f}")
    if not all(c["joinedEqual"] for c in check):
        sys.exit("the scores do not reproduce the embedding runs of E2")


if __name__ == "__main__":
    if len(sys.argv) not in (3, 4) or sys.argv[1] not in ("inputs", "scores") \
            or (len(sys.argv) == 4 and sys.argv[3] not in QUERIES):
        sys.exit(__doc__)
    if len(sys.argv) == 4:
        QUERY = sys.argv[3]
    (inputs if sys.argv[1] == "inputs" else scores)(sys.argv[2])
