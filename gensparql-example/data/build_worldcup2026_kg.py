#!/usr/bin/env python3
"""
Build a 2026 FIFA World Cup knowledge graph (Turtle) from Wikidata.

Pre-kickoff STRUCTURAL facts only (teams, groups, host cities, venues) plus a small
set of NOTABLE players per team (ranked by Wikipedia sitelink count) — no match
results / winners. Source: Wikidata SPARQL (real QIDs + clean rdfs:label), so the KG
is verifiable and reproducible, not LLM-generated.

Output: worldcup2026.ttl next to this script.
"""
import json
import re
import sys
import time
import urllib.parse
import urllib.request

WD = "https://query.wikidata.org/sparql"
UA = "GenSPARQL-KG-Builder/1.0 (research; ac-claude-3@ki.uni-stuttgart.de)"
WC = "wd:Q5020214"  # 2026 FIFA World Cup
PLAYERS_PER_TEAM = 8

EX = "http://example.org/"
WDENT = "http://www.wikidata.org/entity/"


def sparql(query, retries=3):
    url = WD + "?" + urllib.parse.urlencode({"query": query, "format": "json"})
    last = None
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA,
                                                       "Accept": "application/sparql-results+json"})
            with urllib.request.urlopen(req, timeout=90) as r:
                return json.load(r)["results"]["bindings"]
        except Exception as e:  # noqa
            last = e
            time.sleep(2 + attempt * 2)
    raise RuntimeError(f"SPARQL failed after {retries}: {last}\nquery={query[:200]}")


def qid(uri):
    return uri.rsplit("/", 1)[-1]


TEAM_SUFFIX_RE = re.compile(
    r"\s+(men's\s+)?national\s+(association\s+)?(football|soccer)\s+team$", re.I)


# Fix Wikidata label quirks (e.g. the Canada men's team's English label is a demonym).
LABEL_FIX = {"Canadian": "Canada"}


def clean_team(label):
    lab = TEAM_SUFFIX_RE.sub("", label).strip()
    return LABEL_FIX.get(lab, lab)


def esc(s):
    return s.replace("\\", "\\\\").replace('"', '\\"')


# ---- 1. Groups A-L and their national teams -------------------------------------
print("[1/4] groups + teams ...", file=sys.stderr)
group_rows = sparql(f"""
SELECT ?g ?gLabel ?t ?tLabel WHERE {{
  {WC} wdt:P527 ?g .
  ?g wdt:P31 wd:Q1867571 .            # instance of: group of a sports competition
  ?g wdt:P1923 ?t .                   # participating team (national team)
  SERVICE wikibase:label {{ bd:serviceParam wikibase:language "en". }}
}}""")

# Fallback: if the P31 filter yields nothing, match by label "... Group X"
if not group_rows:
    group_rows = sparql(f"""
    SELECT ?g ?gLabel ?t ?tLabel WHERE {{
      {WC} wdt:P527 ?g . ?g wdt:P1923 ?t .
      ?g rdfs:label ?gLabel . FILTER(LANG(?gLabel)="en" && CONTAINS(?gLabel,"Group"))
      ?t rdfs:label ?tLabel . FILTER(LANG(?tLabel)="en")
    }}""")

groups = {}       # gqid -> label
teams = {}        # tqid -> {label, group}
for r in group_rows:
    g, gl = qid(r["g"]["value"]), r["gLabel"]["value"]
    if "Group" not in gl:
        continue
    t, tl = qid(r["t"]["value"]), clean_team(r["tLabel"]["value"])
    groups[g] = gl
    teams[t] = {"label": tl, "group": g}
print(f"      groups={len(groups)} teams={len(teams)}", file=sys.stderr)

# ---- 2. Venues + host cities ----------------------------------------------------
print("[2/4] venues + host cities ...", file=sys.stderr)
venue_rows = sparql(f"""
SELECT DISTINCT ?v ?vLabel ?c ?cLabel WHERE {{
  {WC} wdt:P276 ?v .
  OPTIONAL {{ ?v wdt:P131 ?c . ?c wdt:P31/wdt:P279* wd:Q515 . }}   # located in a city
  SERVICE wikibase:label {{ bd:serviceParam wikibase:language "en". }}
}}""")
venues = {}   # vqid -> {label, city}
cities = {}   # cqid -> label
for r in venue_rows:
    v, vl = qid(r["v"]["value"]), r["vLabel"]["value"]
    c = qid(r["c"]["value"]) if "c" in r else None
    if c:
        cities[c] = r["cLabel"]["value"]
    venues.setdefault(v, {"label": vl, "city": c})
print(f"      venues={len(venues)} cities={len(cities)}", file=sys.stderr)

# ---- 3. Notable players per team ------------------------------------------------
print("[3/4] notable players (top %d/team by sitelinks) ..." % PLAYERS_PER_TEAM, file=sys.stderr)
players = {}          # pqid -> label
plays_for = []        # (pqid, tqid)
for i, (t, info) in enumerate(sorted(teams.items())):
    rows = sparql(f"""
    SELECT ?p ?pLabel ?sl WHERE {{
      ?p wdt:P54 wd:{t} ; wdt:P106 wd:Q937857 .   # member of team; occupation: footballer
      ?p wikibase:sitelinks ?sl .
      SERVICE wikibase:label {{ bd:serviceParam wikibase:language "en". }}
    }} ORDER BY DESC(?sl) LIMIT {PLAYERS_PER_TEAM}""")
    for r in rows:
        p, pl = qid(r["p"]["value"]), r["pLabel"]["value"]
        if pl.startswith("Q") and pl[1:].isdigit():
            continue  # skip label-less items
        players[p] = pl
        plays_for.append((p, t))
    if (i + 1) % 12 == 0:
        print(f"      {i+1}/{len(teams)} teams done", file=sys.stderr)
    time.sleep(0.2)
print(f"      players={len(players)} playsFor={len(plays_for)}", file=sys.stderr)

# ---- 4. Emit Turtle -------------------------------------------------------------
print("[4/4] writing Turtle ...", file=sys.stderr)
out = []
out.append("@prefix ex: <%s> ." % EX)
out.append("@prefix wd: <%s> ." % WDENT)
out.append("@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .")
out.append("@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .")
out.append("")
out.append("# 2026 FIFA World Cup KG — built from Wikidata (%s)." % WC.replace("wd:", ""))
out.append("# Structural pre-kickoff facts + notable players. Real Wikidata IRIs + rdfs:label.")
out.append("")

out.append("# --- Groups (%d) ---" % len(groups))
for g, gl in sorted(groups.items()):
    out.append('wd:%s a ex:Group ; rdfs:label "%s" .' % (g, esc(gl)))
out.append("")

out.append("# --- National teams (%d) ---" % len(teams))
for t, info in sorted(teams.items(), key=lambda kv: kv[1]["label"]):
    out.append('wd:%s a ex:Team ; rdfs:label "%s" ; ex:inGroup wd:%s .'
               % (t, esc(info["label"]), info["group"]))
out.append("")

out.append("# --- Host cities (%d) ---" % len(cities))
for c, cl in sorted(cities.items(), key=lambda kv: kv[1]):
    out.append('wd:%s a ex:City ; rdfs:label "%s" .' % (c, esc(cl)))
out.append("")

out.append("# --- Venues / stadiums (%d) ---" % len(venues))
for v, info in sorted(venues.items(), key=lambda kv: kv[1]["label"]):
    line = 'wd:%s a ex:Venue ; rdfs:label "%s"' % (v, esc(info["label"]))
    if info["city"]:
        line += ' ; ex:venueCity wd:%s' % info["city"]
    out.append(line + " .")
out.append("")

out.append("# --- Notable players (%d) ---" % len(players))
byteam = {}
for p, t in plays_for:
    byteam.setdefault(p, []).append(t)
for p, pl in sorted(players.items(), key=lambda kv: kv[1]):
    line = 'wd:%s a ex:Athlete ; rdfs:label "%s"' % (p, esc(pl))
    ts = byteam.get(p, [])
    if ts:
        line += " ; " + " ; ".join("ex:playsFor wd:%s" % t for t in ts)
    out.append(line + " .")
out.append("")

path = __file__.rsplit("/", 1)[0] + "/worldcup2026.ttl"
with open(path, "w") as f:
    f.write("\n".join(out) + "\n")

total = len(groups) + len(teams) + len(cities) + len(venues) + len(players)
print("WROTE %s" % path, file=sys.stderr)
print("ENTITIES groups=%d teams=%d cities=%d venues=%d players=%d TOTAL=%d"
      % (len(groups), len(teams), len(cities), len(venues), len(players), total), file=sys.stderr)
