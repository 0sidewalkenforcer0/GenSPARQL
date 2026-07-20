#!/usr/bin/env python3
"""
Build a 2026 FIFA World Cup knowledge graph (Turtle) from Wikidata.

Pre-kickoff / structural facts + the real 26-man squads:
  48 teams, 12 groups, host cities (+ metro aliases), venues, and each team's
  official 2026 squad (Wikidata `participant` on the "X at the 2026 FIFA World Cup"
  items). No match results / winner. Source: Wikidata SPARQL (real QIDs + clean
  rdfs:label) — verifiable and reproducible, not LLM-generated.

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
EX = "http://example.org/"
WDENT = "http://www.wikidata.org/entity/"

# Stadium municipality -> common metropolitan name (added as an extra rdfs:label so the
# popular host-city name grounds too, e.g. "Dallas" as well as "Arlington").
CITY_ALIAS = {
    "Arlington": "Dallas", "Inglewood": "Los Angeles", "Santa Clara": "San Francisco",
    "Zapopan": "Guadalajara", "Miami Gardens": "Miami", "Foxborough": "Boston",
    "East Rutherford": "New York", "Paradise": "Las Vegas", "Guadalupe": "Monterrey",
}


def sparql(query, retries=3):
    url = WD + "?" + urllib.parse.urlencode({"query": query, "format": "json"})
    last = None
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA,
                                                       "Accept": "application/sparql-results+json"})
            with urllib.request.urlopen(req, timeout=120) as r:
                return json.load(r)["results"]["bindings"]
        except Exception as e:  # noqa
            last = e
            time.sleep(2 + attempt * 2)
    raise RuntimeError(f"SPARQL failed after {retries}: {last}\nquery={query[:200]}")


def qid(uri):
    return uri.rsplit("/", 1)[-1]


TEAM_SUFFIX_RE = re.compile(
    r"\s+(men's\s+)?national\s+(association\s+)?(football|soccer)\s+team$", re.I)
LABEL_FIX = {"Canadian": "Canada"}


def clean_team(label):
    lab = LABEL_FIX.get(label, label)
    return LABEL_FIX.get(TEAM_SUFFIX_RE.sub("", lab).strip(), TEAM_SUFFIX_RE.sub("", lab).strip())


def esc(s):
    return s.replace("\\", "\\\\").replace('"', '\\"')


# ---- 1. Groups A-L, their national teams, and each team's nation key ------------
print("[1/4] groups + teams ...", file=sys.stderr)
group_rows = sparql(f"""
SELECT ?g ?gLabel ?t ?tLabel ?nation WHERE {{
  {WC} wdt:P527 ?g .
  ?g rdfs:label ?gLabel . FILTER(LANG(?gLabel)="en" && CONTAINS(?gLabel,"Group"))
  ?g wdt:P1923 ?t .                   # participating national team
  ?t rdfs:label ?tLabel . FILTER(LANG(?tLabel)="en")
  OPTIONAL {{ ?t wdt:P1532 ?nation . }}   # country for sport (stable join key)
}}""")

groups = {}                 # gqid -> label
teams = {}                  # tqid -> {label, group, nation}
nation2team = {}            # nationQID -> tqid
for r in group_rows:
    g, gl = qid(r["g"]["value"]), r["gLabel"]["value"]
    if "Group" not in gl:
        continue
    t = qid(r["t"]["value"])
    tl = clean_team(r["tLabel"]["value"])
    nation = qid(r["nation"]["value"]) if "nation" in r else None
    groups[g] = gl
    teams[t] = {"label": tl, "group": g, "nation": nation}
    if nation:
        nation2team[nation] = t
print(f"      groups={len(groups)} teams={len(teams)} nation-keys={len(nation2team)}", file=sys.stderr)

# ---- 2. Venues + host cities ----------------------------------------------------
print("[2/4] venues + host cities ...", file=sys.stderr)
venue_rows = sparql(f"""
SELECT DISTINCT ?v ?vLabel ?c ?cLabel WHERE {{
  {WC} wdt:P276 ?v .
  OPTIONAL {{ ?v wdt:P131 ?c . ?c wdt:P31/wdt:P279* wd:Q515 . }}
  SERVICE wikibase:label {{ bd:serviceParam wikibase:language "en". }}
}}""")
venues, cities = {}, {}
for r in venue_rows:
    v, vl = qid(r["v"]["value"]), r["vLabel"]["value"]
    c = qid(r["c"]["value"]) if "c" in r else None
    if c:
        cities[c] = r["cLabel"]["value"]
    venues.setdefault(v, {"label": vl, "city": c})
print(f"      venues={len(venues)} cities={len(cities)}", file=sys.stderr)

# ---- 3. Real 2026 squads (participant P710 on the participation items) -----------
print("[3/4] official 2026 squads ...", file=sys.stderr)
squad_rows = sparql(f"""
SELECT ?nation ?player ?playerLabel WHERE {{
  {WC} wdt:P1923 ?part .
  ?part wdt:P1532 ?nation .            # participation's nation (join key)
  ?part wdt:P710 ?player .             # squad member
  ?player wdt:P106 wd:Q937857 .        # occupation: association football player
  SERVICE wikibase:label {{ bd:serviceParam wikibase:language "en". }}
}}""")
players = {}          # pqid -> label
plays_for = []        # (pqid, tqid)
unlinked = 0
for r in squad_rows:
    nation = qid(r["nation"]["value"])
    p, pl = qid(r["player"]["value"]), r["playerLabel"]["value"]
    if pl.startswith("Q") and pl[1:].isdigit():
        continue
    t = nation2team.get(nation)
    if not t:
        unlinked += 1
        continue
    players[p] = pl
    plays_for.append((p, t))
print(f"      players={len(players)} playsFor={len(plays_for)} unlinked={unlinked}", file=sys.stderr)

# ---- 4. Emit Turtle -------------------------------------------------------------
print("[4/4] writing Turtle ...", file=sys.stderr)
out = ["@prefix ex: <%s> ." % EX, "@prefix wd: <%s> ." % WDENT,
       "@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .",
       "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .", "",
       "# 2026 FIFA World Cup KG from Wikidata (Q5020214): structural facts + real 26-man squads.",
       "# Real Wikidata IRIs + clean rdfs:label. No match results / winner.", ""]

out.append("# --- Groups (%d) ---" % len(groups))
for g, gl in sorted(groups.items()):
    out.append('wd:%s a ex:Group ; rdfs:label "%s" .' % (g, esc(gl)))
out.append("")

out.append("# --- National teams (%d) ---" % len(teams))
for t, info in sorted(teams.items(), key=lambda kv: kv[1]["label"]):
    out.append('wd:%s a ex:Team ; rdfs:label "%s" ; ex:inGroup wd:%s .'
               % (t, esc(info["label"]), info["group"]))
out.append("")

out.append("# --- Host cities (%d; +metro alias label where applicable) ---" % len(cities))
for c, cl in sorted(cities.items(), key=lambda kv: kv[1]):
    labels = 'rdfs:label "%s"' % esc(cl)
    if cl in CITY_ALIAS:
        labels += ' ; rdfs:label "%s"' % esc(CITY_ALIAS[cl])
    out.append('wd:%s a ex:City ; %s .' % (c, labels))
out.append("")

out.append("# --- Venues / stadiums (%d) ---" % len(venues))
for v, info in sorted(venues.items(), key=lambda kv: kv[1]["label"]):
    line = 'wd:%s a ex:Venue ; rdfs:label "%s"' % (v, esc(info["label"]))
    if info["city"]:
        line += ' ; ex:venueCity wd:%s' % info["city"]
    out.append(line + " .")
out.append("")

out.append("# --- 2026 squad players (%d) ---" % len(players))
byplayer = {}
for p, t in plays_for:
    byplayer.setdefault(p, []).append(t)
for p, pl in sorted(players.items(), key=lambda kv: kv[1]):
    line = 'wd:%s a ex:Athlete ; rdfs:label "%s"' % (p, esc(pl))
    for t in byplayer.get(p, []):
        line += " ; ex:playsFor wd:%s" % t
    out.append(line + " .")
out.append("")

path = __file__.rsplit("/", 1)[0] + "/worldcup2026.ttl"
with open(path, "w") as f:
    f.write("\n".join(out) + "\n")
total = len(groups) + len(teams) + len(cities) + len(venues) + len(players)
print("WROTE %s" % path, file=sys.stderr)
print("ENTITIES groups=%d teams=%d cities=%d venues=%d players=%d TOTAL=%d"
      % (len(groups), len(teams), len(cities), len(venues), len(players), total), file=sys.stderr)
