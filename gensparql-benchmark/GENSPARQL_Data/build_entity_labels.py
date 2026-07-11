#!/usr/bin/env python3
"""Build a clean <entity_URI>\t<name> label file for FB15k by matching each entity
URI's local name against the MID->name map (entity2text.txt). The FB15k URIs embed
variable-underscore MIDs, so we longest-prefix-match the URI local name against the
known MID bodies."""
import sys, re, os

DS = sys.argv[1] if len(sys.argv) > 1 else "FB15k-237+H"
BASE = os.path.dirname(os.path.abspath(__file__))
DDIR = os.path.join(BASE, DS)
MAP = os.path.join(DDIR, "entity2text.txt")
DATA = os.path.join(DDIR, "train.nt")
OUT = os.path.join(DDIR, "entity_labels.tsv")

# midbody ("07g_0c") -> name
mid2name = {}
with open(MAP, encoding="utf-8") as f:
    for line in f:
        p = line.rstrip("\n").split("\t")
        if len(p) >= 2 and p[0].startswith("/m/"):
            mid2name[p[0][3:].replace("/", "_")] = p[1]
print(f"loaded {len(mid2name)} MID->name entries")

# unique entity URIs from the data
uris = set()
ent_re = re.compile(r"<(http://freebase\.com/entity/[^>]+)>")
with open(DATA, encoding="utf-8") as f:
    for line in f:
        for m in ent_re.findall(line):
            uris.add(m)
print(f"found {len(uris)} unique entity URIs")

def resolve(uri):
    local = uri.rsplit("/", 1)[-1]           # m_07g_0c_The_Weather_Man
    if not local.startswith("m_"):
        return None
    rest = local[2:]                          # 07g_0c_The_Weather_Man
    toks = rest.split("_")
    # longest midbody prefix that is a known MID and is followed by the label
    for k in range(len(toks), 0, -1):
        mb = "_".join(toks[:k])
        if mb in mid2name and (k == len(toks) or True):
            return mid2name[mb]
    return None

n_ok = 0
with open(OUT, "w", encoding="utf-8") as out:
    for uri in sorted(uris):
        name = resolve(uri)
        if name:
            out.write(f"{uri}\t{name}\n")
            n_ok += 1
print(f"resolved {n_ok}/{len(uris)} URIs -> {OUT}")
