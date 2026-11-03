#!/usr/bin/env python3
"""Fetch the gold data of the WWW'27 evaluation from Wikidata.

The two graphs in docs/ use Wikidata IRIs, so their entities can be looked up directly.
The script writes three files to eval/www27/gold/:

  wc_positions.json       player QID -> coarse positions from P413 (position played)
  nobel_birth_years.json  laureate QID -> birth years from P569 (date of birth)
  e2_entities.json        for each list query of experiment E2, the entities of the graph
                          that the query targets, with their English labels and aliases;
                          a national team also gets the names of its country (P1532/P17)

Run it once on a machine with Internet access; the cluster jobs only read the files.
"""
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
WC = os.path.join(ROOT, "docs", "worldcup2026.ttl")
NOBEL = os.path.join(ROOT, "docs", "nobel-prize.ttl")
OUT = os.path.join(HERE, "gold")
ENDPOINT = "https://query.wikidata.org/sparql"
UA = "GenSPARQL-eval/1.0 (https://github.com/0sidewalkenforcer0/GenSPARQL)"
BUCKETS = ["Goalkeeper", "Defender", "Midfielder", "Forward"]


def coarse(label):
    """Map a position label to one of four buckets, as in eval/wc2026_x1_position.py."""
    s = label.lower()
    if any(k in s for k in ["goalkeeper", "keeper"]):
        return "Goalkeeper"
    if any(k in s for k in ["back", "defender", "defence", "defense", "libero", "sweeper"]):
        return "Defender"
    if "midfield" in s:
        return "Midfielder"
    if any(k in s for k in ["forward", "striker", "winger", "wing", "attacker"]):
        return "Forward"
    return None


def sparql(query):
    url = ENDPOINT + "?" + urllib.parse.urlencode({"query": query, "format": "json"})
    req = urllib.request.Request(url, headers={"User-Agent": UA,
                                               "Accept": "application/sparql-results+json"})
    for attempt in range(4):
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                return json.load(r)["results"]["bindings"]
        except Exception as e:  # rate limit or timeout: wait and retry
            print(f"  retry {attempt + 1}: {e}", file=sys.stderr)
            time.sleep(5 * (attempt + 1))
    raise RuntimeError("Wikidata query failed four times")


def chunked(qids, size=150):
    for i in range(0, len(qids), size):
        yield qids[i:i + size]


def qid(uri):
    return uri.rsplit("/", 1)[-1]


# ---- the two graphs ------------------------------------------------------------------
wc = open(WC, encoding="utf-8").read()
nobel = open(NOBEL, encoding="utf-8").read()


def typed(text, cls):
    return {m.group(1): m.group(2) for m in
            re.finditer(r'wd:(Q\d+) a ex:' + cls + r' ; rdfs:label "([^"]+)"', text)}


athletes = typed(wc, "Athlete")
teams = typed(wc, "Team")
cities = typed(wc, "City")
venues = typed(wc, "Venue")
argentina = {q: athletes[q] for q in re.findall(r'wd:(Q\d+) a ex:Athlete ; rdfs:label "[^"]+" ; '
                                                r'ex:playsFor wd:Q79800 \.', wc)}
laureates = typed(nobel, "NobelLaureate")
fields = typed(nobel, "NobelField")
physics_2020 = sorted({w for y, w in re.findall(
    r'ex:field wd:Q38104 ; ex:winningYear "(\d{4})"\^\^xsd:gYear ; ex:winner wd:(Q\d+)', nobel)
    if int(y) >= 2020})
print(f"athletes {len(athletes)}, teams {len(teams)}, cities {len(cities)}, venues {len(venues)}, "
      f"Argentina {len(argentina)}, laureates {len(laureates)}, fields {len(fields)}, "
      f"physics since 2020 {len(physics_2020)}", file=sys.stderr)
for name, d in [("athletes", athletes), ("teams", teams), ("cities", cities), ("venues", venues),
                ("laureates", laureates), ("fields", fields)]:
    assert d, f"no {name} parsed"


# ---- E1 gold ---------------------------------------------------------------------------
def positions(qids):
    gold = {}
    for chunk in chunked(qids):
        rows = sparql(f"""SELECT ?p ?l WHERE {{ VALUES ?p {{ {' '.join('wd:' + q for q in chunk)} }}
            ?p wdt:P413 ?pos . ?pos rdfs:label ?l . FILTER(LANG(?l) = "en") }}""")
        for r in rows:
            c = coarse(r["l"]["value"])
            if c:
                gold.setdefault(qid(r["p"]["value"]), set()).add(c)
    return {q: sorted(v) for q, v in gold.items()}


def birth_years(qids):
    gold = {}
    for chunk in chunked(qids):
        rows = sparql(f"""SELECT ?p ?d WHERE {{ VALUES ?p {{ {' '.join('wd:' + q for q in chunk)} }}
            ?p p:P569/psv:P569 [ wikibase:timeValue ?d ; wikibase:timePrecision ?prec ] .
            FILTER(?prec >= 9) }}""")
        for r in rows:
            m = re.match(r"(-?\d+)-", r["d"]["value"])
            if m:
                gold.setdefault(qid(r["p"]["value"]), set()).add(int(m.group(1)))
    return {q: sorted(v) for q, v in gold.items()}


# ---- E2 gold: names of the target entities ------------------------------------------------
def names(qids, with_country=False):
    out = {q: set() for q in qids}
    for chunk in chunked(qids):
        values = " ".join("wd:" + q for q in chunk)
        rows = sparql(f"""SELECT ?e ?n WHERE {{ VALUES ?e {{ {values} }}
            {{ ?e rdfs:label ?n }} UNION {{ ?e skos:altLabel ?n }}
            FILTER(LANG(?n) = "en") }}""")
        if with_country:
            rows += sparql(f"""SELECT ?e ?n WHERE {{ VALUES ?e {{ {values} }}
                ?e wdt:P1532|wdt:P17 ?c .
                {{ ?c rdfs:label ?n }} UNION {{ ?c skos:altLabel ?n }}
                FILTER(LANG(?n) = "en") }}""")
        for r in rows:
            out[qid(r["e"]["value"])].add(r["n"]["value"])
    return out


def target(entities, with_country=False):
    alias = names(sorted(entities), with_country)
    return {q: {"label": label, "names": sorted(alias[q] | {label})} for q, label in entities.items()}


os.makedirs(OUT, exist_ok=True)
pos = positions(sorted(athletes))
print(f"players with a P413 position: {len(pos)} of {len(athletes)}", file=sys.stderr)
years = birth_years(sorted(laureates))
print(f"laureates with a birth year: {len(years)} of {len(laureates)}", file=sys.stderr)
e2 = {
    "wc_teams": target(teams, with_country=True),
    "wc_cities": target(cities),
    "wc_venues": target(venues),
    "wc_argentina": target(argentina),
    "nobel_fields": target(fields),
    "nobel_physics_2020": target({q: laureates[q] for q in physics_2020}),
}
for path, data in [("wc_positions.json", pos), ("nobel_birth_years.json", years),
                   ("e2_entities.json", e2)]:
    with open(os.path.join(OUT, path), "w", encoding="utf-8") as f:
        json.dump(data, f, indent=1, ensure_ascii=False, sort_keys=True)
print("wrote", OUT, file=sys.stderr)
