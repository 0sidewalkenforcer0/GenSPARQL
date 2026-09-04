#!/usr/bin/env python3
"""
Build a Nobel Prize knowledge graph (Turtle) from Wikidata.

Nobel Prize structure + laureates:
  Nobel Prize root, Nobel fields/categories, laureates, winning year,
  citizenship, and gender. Source: Wikidata SPARQL (real QIDs + clean
  rdfs:label) — verifiable and reproducible, not LLM-generated.

Output: nobel_prize_kg.ttl next to this script.
"""
import json
import re
import sys
import time
import urllib.parse
import urllib.request

WD = "https://query.wikidata.org/sparql"
UA = "GenSPARQL-KG-Builder/1.0 (research; ac-claude-3@ki.uni-stuttgart.de)"
NOBEL = "wd:Q7191"  # Nobel Prize
EX = "http://example.org/"
WDENT = "http://www.wikidata.org/entity/"

YEAR_RE = re.compile(r"^[+-]?(\d{4,})-")


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


def award_year(value):
    m = YEAR_RE.match(value)
    if not m:
        raise ValueError("Cannot read award year from Wikidata time: %s" % value)
    return int(m.group(1))


def esc(s):
    return (s.replace("\\", "\\\\")
             .replace('"', '\\"')
             .replace("\n", "\\n")
             .replace("\r", "\\r"))


def award_id(laureate, field, year):
    return "award_%s_%s_%d" % (laureate, field, year)


# ---- 1. Nobel Prize root + fields ----------------------------------------------
print("[1/4] Nobel Prize + fields ...", file=sys.stderr)
field_rows = sparql(f"""
SELECT DISTINCT ?rootLabel ?field ?fieldLabel WHERE {{
  BIND({NOBEL} AS ?root)

  {{
    ?field wdt:P31 ?root .
  }}
  UNION
  {{
    ?field wdt:P361 ?root .
  }}
  UNION
  {{
    ?field p:P279 ?statement .
    ?statement ps:P279 ?root .
  }}

  FILTER(?field != ?root)

  FILTER EXISTS {{
    {{
      ?laureate p:P166 ?awardStatement .
      ?awardStatement ps:P166 ?field .
    }}
    UNION
    {{
      ?laureate wdt:P166 ?edition .
      ?edition wdt:P31 ?field .
    }}
  }}

  SERVICE wikibase:label {{ bd:serviceParam wikibase:language "en,mul". }}
}}
ORDER BY ?fieldLabel
""")

root_label = "Q7191"
fields = {}                 # fieldQID -> label
for r in field_rows:
    if "rootLabel" in r:
        root_label = r["rootLabel"]["value"]
    f, fl = qid(r["field"]["value"]), r["fieldLabel"]["value"]
    fields[f] = fl

if not fields:
    raise RuntimeError("Wikidata returned no Nobel fields for Q7191")

print(f"      root={root_label} fields={len(fields)}", file=sys.stderr)


# ---- 2. Nobel laureates + field/year award records -----------------------------
print("[2/4] laureates + Nobel awards ...", file=sys.stderr)
field_values = " ".join("wd:%s" % f for f in fields)

award_rows = sparql(f"""
SELECT DISTINCT ?laureate ?laureateLabel ?field ?awardDate WHERE {{
  VALUES ?field {{ {field_values} }}

  {{
    ?laureate p:P166 ?awardStatement .
    ?awardStatement ps:P166 ?field ;
                    pq:P585 ?awardDate .
  }}
  UNION
  {{
    ?laureate p:P166 ?awardStatement .
    ?awardStatement ps:P166 ?field ;
                    pq:P805 ?edition .
    ?edition wdt:P585 ?awardDate .
  }}
  UNION
  {{
    ?laureate wdt:P166 ?edition .
    ?edition wdt:P31 ?field ;
             wdt:P585 ?awardDate .
  }}

  SERVICE wikibase:label {{ bd:serviceParam wikibase:language "en,mul". }}
}}
ORDER BY ?awardDate ?laureateLabel
""")

laureates = {}              # laureateQID -> label
awards = []                 # (laureateQID, fieldQID, year)
seen_awards = set()
for r in award_rows:
    l, ll = qid(r["laureate"]["value"]), r["laureateLabel"]["value"]
    f = qid(r["field"]["value"])
    y = award_year(r["awardDate"]["value"])

    if ll.startswith("Q") and ll[1:].isdigit():
        continue
    if f not in fields:
        continue

    laureates[l] = ll
    key = (l, f, y)
    if key not in seen_awards:
        seen_awards.add(key)
        awards.append(key)

print(f"      laureates={len(laureates)} awards={len(awards)}", file=sys.stderr)


# ---- 3. Citizenship + gender ---------------------------------------------------
print("[3/4] citizenship + gender ...", file=sys.stderr)

citizenships = {}           # citizenshipQID -> label
genders = {}                # genderQID -> label
citizen_of = []             # (laureateQID, citizenshipQID)
has_gender = []             # (laureateQID, genderQID)

laureate_ids = sorted(laureates)
batch_size = 80

for start in range(0, len(laureate_ids), batch_size):
    batch = laureate_ids[start:start + batch_size]
    laureate_values = " ".join("wd:%s" % l for l in batch)

    enrichment_rows = sparql(f"""
SELECT DISTINCT ?laureate ?citizenship ?citizenshipLabel ?gender ?genderLabel WHERE {{
  VALUES ?laureate {{ {laureate_values} }}

  OPTIONAL {{ ?laureate wdt:P27 ?citizenship . }}
  OPTIONAL {{ ?laureate wdt:P21 ?gender . }}

  SERVICE wikibase:label {{ bd:serviceParam wikibase:language "en,mul". }}
}}
""")

    for r in enrichment_rows:
        l = qid(r["laureate"]["value"])

        if "citizenship" in r:
            c, cl = qid(r["citizenship"]["value"]), r["citizenshipLabel"]["value"]
            citizenships[c] = cl
            pair = (l, c)
            if pair not in citizen_of:
                citizen_of.append(pair)

        if "gender" in r:
            g, gl = qid(r["gender"]["value"]), r["genderLabel"]["value"]
            genders[g] = gl
            pair = (l, g)
            if pair not in has_gender:
                has_gender.append(pair)

print(f"      citizenships={len(citizenships)} citizenOf={len(citizen_of)} "
      f"genders={len(genders)} hasGender={len(has_gender)}", file=sys.stderr)


# ---- 4. Emit Turtle -------------------------------------------------------------
print("[4/4] writing Turtle ...", file=sys.stderr)
out = ["@prefix ex: <%s> ." % EX, "@prefix wd: <%s> ." % WDENT,
       "@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .",
       "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .",
       "@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .", "",
       "# Nobel Prize KG from Wikidata (Q7191).", ""]

out.append("# --- Nobel Prize root ---")
root = 'wd:Q7191 a ex:NobelPrize ; rdfs:label "%s"' % esc(root_label)
for f in sorted(fields, key=lambda x: fields[x]):
    root += " ; ex:hasField wd:%s" % f
out.append(root + " .")
out.append("")

out.append("# --- Nobel fields (%d) ---" % len(fields))
byfield = {}
for l, f, y in awards:
    byfield.setdefault(f, []).append((l, y))
for f, fl in sorted(fields.items(), key=lambda kv: kv[1]):
    line = 'wd:%s a ex:NobelField ; rdfs:label "%s" ; ex:partOf wd:Q7191' % (f, esc(fl))
    for l, y in byfield.get(f, []):
        line += " ; ex:hasAward ex:%s" % award_id(l, f, y)
    out.append(line + " .")
out.append("")

out.append("# --- Citizenship entities (%d) ---" % len(citizenships))
for c, cl in sorted(citizenships.items(), key=lambda kv: kv[1]):
    out.append('wd:%s a ex:CountryOrCitizenship ; rdfs:label "%s" .' % (c, esc(cl)))
out.append("")

out.append("# --- Gender entities (%d) ---" % len(genders))
for g, gl in sorted(genders.items(), key=lambda kv: kv[1]):
    out.append('wd:%s a ex:Gender ; rdfs:label "%s" .' % (g, esc(gl)))
out.append("")

out.append("# --- Nobel laureates (%d) ---" % len(laureates))
bylaureate_citizenship = {}
for l, c in citizen_of:
    bylaureate_citizenship.setdefault(l, []).append(c)

bylaureate_gender = {}
for l, g in has_gender:
    bylaureate_gender.setdefault(l, []).append(g)

bylaureate_award = {}
for l, f, y in awards:
    bylaureate_award.setdefault(l, []).append((f, y))

for l, ll in sorted(laureates.items(), key=lambda kv: kv[1]):
    line = 'wd:%s a ex:NobelLaureate ; rdfs:label "%s"' % (l, esc(ll))
    for c in bylaureate_citizenship.get(l, []):
        line += " ; ex:citizenship wd:%s" % c
    for g in bylaureate_gender.get(l, []):
        line += " ; ex:gender wd:%s" % g
    for f, y in bylaureate_award.get(l, []):
        line += " ; ex:wonNobelPrize ex:%s" % award_id(l, f, y)
    out.append(line + " .")
out.append("")

out.append("# --- Nobel award records (%d) ---" % len(awards))
for l, f, y in sorted(awards, key=lambda x: (x[2], fields[x[1]], laureates[x[0]])):
    out.append('ex:%s a ex:NobelAward ; ex:field wd:%s ; ex:winningYear "%d"^^xsd:gYear ; ex:winner wd:%s .'
               % (award_id(l, f, y), f, y, l))
out.append("")

path = __file__.rsplit("/", 1)[0] + "/nobel_prize_kg.ttl"
with open(path, "w") as f:
    f.write("\n".join(out) + "\n")

total = 1 + len(fields) + len(citizenships) + len(genders) + len(laureates) + len(awards)
print("WROTE %s" % path, file=sys.stderr)
print("ENTITIES root=1 fields=%d citizenships=%d genders=%d laureates=%d awards=%d TOTAL=%d"
      % (len(fields), len(citizenships), len(genders), len(laureates), len(awards), total),
      file=sys.stderr)
