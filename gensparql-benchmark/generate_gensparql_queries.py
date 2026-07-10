#!/usr/bin/env python3
"""
Generate GenSPARQL queries from queries_readable.txt files.
For each dataset pattern, extract Query 1 and convert to GenSPARQL format.
"""

import os
import re
from pathlib import Path

# Base paths
BASE_DIR = Path(__file__).parent
DATA_DIR = BASE_DIR / "GENSPARQL_Data"
QUERIES_FB = BASE_DIR / "Queries-FB15k-237+H"
QUERIES_NELL = BASE_DIR / "Queries-NELL995+H"

# Namespaces for each dataset
NAMESPACES = {
    "FB15k-237+H": {
        "entity": "fbe",
        "entity_uri": "http://freebase.com/entity/",
        "relation": "fbr",
        "relation_uri": "http://freebase.com/relation/"
    },
    "NELL995+H": {
        "entity": "ne",
        "entity_uri": "http://nell.com/entity/",
        "relation": "nr",
        "relation_uri": "http://nell.com/relation/"
    }
}

def parse_query_1(file_path):
    """Parse the first query from queries_readable.txt file."""
    with open(file_path, 'r', encoding='utf-8') as f:
        content = f.read()

    # Extract query type and pattern
    query_type_match = re.search(r'Query Type: (\w+)', content)
    pattern_match = re.search(r'Pattern: (\d+)', content)

    query_type = query_type_match.group(1) if query_type_match else None
    pattern = pattern_match.group(1) if pattern_match else None

    # Extract Query 1
    query_1_match = re.search(r'\[Query 1\]\s*\n\s*Query:\s*(.+?)\n\s*Answers', content, re.DOTALL)
    if not query_1_match:
        return None, None, None

    query_str = query_1_match.group(1).strip()
    return query_type, pattern, query_str


def parse_relation(rel_str):
    """Parse relation string like '[→film.genre]', '[←film.genre]', or '[concept:relation]'."""
    # FB15k format: [→relation] or [←relation]
    match = re.match(r'\[(→|←)(.+?)\]', rel_str)
    if match:
        direction = match.group(1)
        relation = match.group(2)
        # Convert to fwd_ or rev_ prefix
        if direction == '→':
            return f"fwd_{relation.replace('.', '_')}"
        else:
            return f"rev_{relation.replace('.', '_')}"

    # NELL format: [concept:relation] or just [relation]
    match = re.match(r'\[(.+?)\]', rel_str)
    if match:
        relation = match.group(1)
        # Handle NELL concept: prefix
        if relation.startswith('concept:'):
            return relation.replace('concept:', '')
        return relation

    return None


def entity_to_uri(entity_name, ns_prefix):
    """Convert entity name to URI format."""
    # Clean up entity name for URI
    safe_name = entity_name.replace(' ', '_').replace("'", "%27").replace('"', '%22')
    safe_name = safe_name.replace('(', '%28').replace(')', '%29')
    safe_name = safe_name.replace(',', '%2C').replace('/', '%2F')
    return f"{ns_prefix}:{safe_name}"


def generate_2p_query(query_str, dataset):
    """Generate GenSPARQL for 2p (2-hop path) query.
    Format: Entity --[rel1]--> ? --[rel2]--> ?
    """
    ns = NAMESPACES[dataset]

    # Parse: Entity --[rel1]--> ? --[rel2]--> ?
    match = re.match(r'(.+?)\s+--\[(.+?)\]-->\s*\?\s*--\[(.+?)\]-->\s*\?', query_str)
    if not match:
        return None

    entity = match.group(1).strip()
    rel1 = parse_relation(f"[{match.group(2)}]")
    rel2 = parse_relation(f"[{match.group(3)}]")

    if not rel1 or not rel2:
        return None

    return f'''# 2p Query: {query_str}
PREFIX {ns["entity"]}: <{ns["entity_uri"]}>
PREFIX {ns["relation"]}: <{ns["relation_uri"]}>

SELECT ?answer WHERE {{
  {ns["entity"]}:{entity.replace(' ', '_')} {ns["relation"]}:{rel1} ?mid .
  GENOP("Given the entity {{?mid}}, find entities connected via {rel2}. Return as JSON array with 'answer' field.",
        (?answer),
        <model:openrouter:xiaomi/mimo-v2-flash:free>)
}}
'''


def generate_3p_query(query_str, dataset):
    """Generate GenSPARQL for 3p (3-hop path) query.
    Format: Entity --[rel1]--> ? --[rel2]--> ? --[rel3]--> ?
    """
    ns = NAMESPACES[dataset]

    # Parse: Entity --[rel1]--> ? --[rel2]--> ? --[rel3]--> ?
    match = re.match(r'(.+?)\s+--\[(.+?)\]-->\s*\?\s*--\[(.+?)\]-->\s*\?\s*--\[(.+?)\]-->\s*\?', query_str)
    if not match:
        return None

    entity = match.group(1).strip()
    rel1 = parse_relation(f"[{match.group(2)}]")
    rel2 = parse_relation(f"[{match.group(3)}]")
    rel3 = parse_relation(f"[{match.group(4)}]")

    return f'''# 3p Query: {query_str}
PREFIX {ns["entity"]}: <{ns["entity_uri"]}>
PREFIX {ns["relation"]}: <{ns["relation_uri"]}>

SELECT ?answer WHERE {{
  {ns["entity"]}:{entity.replace(' ', '_')} {ns["relation"]}:{rel1} ?e1 .
  ?e1 {ns["relation"]}:{rel2} ?e2 .
  GENOP("Given the entity {{?e2}}, find entities connected via {rel3}. Return as JSON array with 'answer' field.",
        (?answer),
        <model:openrouter:xiaomi/mimo-v2-flash:free>)
}}
'''


def generate_4p_query(query_str, dataset):
    """Generate GenSPARQL for 4p (4-hop path) query."""
    ns = NAMESPACES[dataset]

    # Parse: Entity --[rel1]--> ? --[rel2]--> ? --[rel3]--> ? --[rel4]--> ?
    match = re.match(r'(.+?)\s+--\[(.+?)\]-->\s*\?\s*--\[(.+?)\]-->\s*\?\s*--\[(.+?)\]-->\s*\?\s*--\[(.+?)\]-->\s*\?', query_str)
    if not match:
        return None

    entity = match.group(1).strip()
    rel1 = parse_relation(f"[{match.group(2)}]")
    rel2 = parse_relation(f"[{match.group(3)}]")
    rel3 = parse_relation(f"[{match.group(4)}]")
    rel4 = parse_relation(f"[{match.group(5)}]")

    return f'''# 4p Query: {query_str}
PREFIX {ns["entity"]}: <{ns["entity_uri"]}>
PREFIX {ns["relation"]}: <{ns["relation_uri"]}>

SELECT ?answer WHERE {{
  {ns["entity"]}:{entity.replace(' ', '_')} {ns["relation"]}:{rel1} ?e1 .
  ?e1 {ns["relation"]}:{rel2} ?e2 .
  ?e2 {ns["relation"]}:{rel3} ?e3 .
  GENOP("Given the entity {{?e3}}, find entities connected via {rel4}. Return as JSON array with 'answer' field.",
        (?answer),
        <model:openrouter:xiaomi/mimo-v2-flash:free>)
}}
'''


def generate_2i_query(query_str, dataset):
    """Generate GenSPARQL for 2i (2-way intersection) query.
    Format: (Entity1 --[rel1]--> ?) ∩ (Entity2 --[rel2]--> ?)
    """
    ns = NAMESPACES[dataset]

    # Parse: (Entity1 --[rel1]--> ?) ∩ (Entity2 --[rel2]--> ?)
    match = re.match(r'\((.+?)\s+--\[(.+?)\]-->\s*\?\)\s*∩\s*\((.+?)\s+--\[(.+?)\]-->\s*\?\)', query_str)
    if not match:
        return None

    entity1 = match.group(1).strip()
    rel1 = parse_relation(f"[{match.group(2)}]")
    entity2 = match.group(3).strip()
    rel2 = parse_relation(f"[{match.group(4)}]")

    return f'''# 2i Query: {query_str}
PREFIX {ns["entity"]}: <{ns["entity_uri"]}>
PREFIX {ns["relation"]}: <{ns["relation_uri"]}>

SELECT ?answer WHERE {{
  {ns["entity"]}:{entity1.replace(' ', '_')} {ns["relation"]}:{rel1} ?answer .
  GENOP("Given entities connected to {entity2} via {rel2}, check if {{?answer}} is among them. Return as JSON array with 'answer' field if match.",
        (?answer),
        <model:openrouter:xiaomi/mimo-v2-flash:free>)
}}
'''


def generate_3i_query(query_str, dataset):
    """Generate GenSPARQL for 3i (3-way intersection) query.
    Format: (E1 --[r1]--> ?) ∩ (E2 --[r2]--> ?) ∩ (E3 --[r3]--> ?)
    """
    ns = NAMESPACES[dataset]

    # Parse: (E1 --[r1]--> ?) ∩ (E2 --[r2]--> ?) ∩ (E3 --[r3]--> ?)
    match = re.match(r'\((.+?)\s+--\[(.+?)\]-->\s*\?\)\s*∩\s*\((.+?)\s+--\[(.+?)\]-->\s*\?\)\s*∩\s*\((.+?)\s+--\[(.+?)\]-->\s*\?\)', query_str)
    if not match:
        return None

    entity1 = match.group(1).strip()
    rel1 = parse_relation(f"[{match.group(2)}]")
    entity2 = match.group(3).strip()
    rel2 = parse_relation(f"[{match.group(4)}]")
    entity3 = match.group(5).strip()
    rel3 = parse_relation(f"[{match.group(6)}]")

    return f'''# 3i Query: {query_str}
PREFIX {ns["entity"]}: <{ns["entity_uri"]}>
PREFIX {ns["relation"]}: <{ns["relation_uri"]}>

SELECT ?answer WHERE {{
  {ns["entity"]}:{entity1.replace(' ', '_')} {ns["relation"]}:{rel1} ?answer .
  {ns["entity"]}:{entity2.replace(' ', '_')} {ns["relation"]}:{rel2} ?answer .
  GENOP("Given entities connected to {entity3} via {rel3}, check if {{?answer}} is among them. Return as JSON array with 'answer' field if match.",
        (?answer),
        <model:openrouter:xiaomi/mimo-v2-flash:free>)
}}
'''


def generate_pi_query(query_str, dataset):
    """Generate GenSPARQL for pi (path + intersection) query.
    Format: (E1 --[r1]--> ? --[r2]--> ?) ∩ (E2 --[r3]--> ?)
    """
    ns = NAMESPACES[dataset]

    # Parse: (E1 --[r1]--> ? --[r2]--> ?) ∩ (E2 --[r3]--> ?)
    match = re.match(r'\((.+?)\s+--\[(.+?)\]-->\s*\?\s*--\[(.+?)\]-->\s*\?\)\s*∩\s*\((.+?)\s+--\[(.+?)\]-->\s*\?\)', query_str)
    if not match:
        return None

    entity1 = match.group(1).strip()
    rel1 = parse_relation(f"[{match.group(2)}]")
    rel2 = parse_relation(f"[{match.group(3)}]")
    entity2 = match.group(4).strip()
    rel3 = parse_relation(f"[{match.group(5)}]")

    return f'''# pi Query: {query_str}
PREFIX {ns["entity"]}: <{ns["entity_uri"]}>
PREFIX {ns["relation"]}: <{ns["relation_uri"]}>

SELECT ?answer WHERE {{
  {ns["entity"]}:{entity1.replace(' ', '_')} {ns["relation"]}:{rel1} ?mid .
  ?mid {ns["relation"]}:{rel2} ?answer .
  GENOP("Given entities connected to {entity2} via {rel3}, check if {{?answer}} is among them. Return as JSON array with 'answer' field if match.",
        (?answer),
        <model:openrouter:xiaomi/mimo-v2-flash:free>)
}}
'''


def generate_ip_query(query_str, dataset):
    """Generate GenSPARQL for ip (intersection + path) query.
    Format: ((E1 --[r1]--> ?) ∩ (E2 --[r2]--> ?)) --[r3]--> ?
    """
    ns = NAMESPACES[dataset]

    # Parse: ((E1 --[r1]--> ?) ∩ (E2 --[r2]--> ?)) --[r3]--> ?
    match = re.match(r'\(\((.+?)\s+--\[(.+?)\]-->\s*\?\)\s*∩\s*\((.+?)\s+--\[(.+?)\]-->\s*\?\)\)\s*--\[(.+?)\]-->\s*\?', query_str)
    if not match:
        return None

    entity1 = match.group(1).strip()
    rel1 = parse_relation(f"[{match.group(2)}]")
    entity2 = match.group(3).strip()
    rel2 = parse_relation(f"[{match.group(4)}]")
    rel3 = parse_relation(f"[{match.group(5)}]")

    return f'''# ip Query: {query_str}
PREFIX {ns["entity"]}: <{ns["entity_uri"]}>
PREFIX {ns["relation"]}: <{ns["relation_uri"]}>

SELECT ?answer WHERE {{
  {ns["entity"]}:{entity1.replace(' ', '_')} {ns["relation"]}:{rel1} ?mid .
  {ns["entity"]}:{entity2.replace(' ', '_')} {ns["relation"]}:{rel2} ?mid .
  GENOP("Given the entity {{?mid}}, find entities connected via {rel3}. Return as JSON array with 'answer' field.",
        (?answer),
        <model:openrouter:xiaomi/mimo-v2-flash:free>)
}}
'''


def generate_up_query(query_str, dataset):
    """Generate GenSPARQL for up (union + path) query.
    Format: ((E1 --[r1]--> ?) ∪ (E2 --[r2]--> ?)) --[r3]--> ?
    """
    ns = NAMESPACES[dataset]

    # Parse: ((E1 --[r1]--> ?) ∪ (E2 --[r2]--> ?)) --[r3]--> ?
    match = re.match(r'\(\((.+?)\s+--\[(.+?)\]-->\s*\?\)\s*∪\s*\((.+?)\s+--\[(.+?)\]-->\s*\?\)\)\s*--\[(.+?)\]-->\s*\?', query_str)
    if not match:
        return None

    entity1 = match.group(1).strip()
    rel1 = parse_relation(f"[{match.group(2)}]")
    entity2 = match.group(3).strip()
    rel2 = parse_relation(f"[{match.group(4)}]")
    rel3 = parse_relation(f"[{match.group(5)}]")

    return f'''# up Query: {query_str}
PREFIX {ns["entity"]}: <{ns["entity_uri"]}>
PREFIX {ns["relation"]}: <{ns["relation_uri"]}>

SELECT ?answer WHERE {{
  {{
    {ns["entity"]}:{entity1.replace(' ', '_')} {ns["relation"]}:{rel1} ?mid .
  }} UNION {{
    {ns["entity"]}:{entity2.replace(' ', '_')} {ns["relation"]}:{rel2} ?mid .
  }}
  GENOP("Given the entity {{?mid}}, find entities connected via {rel3}. Return as JSON array with 'answer' field.",
        (?answer),
        <model:openrouter:xiaomi/mimo-v2-flash:free>)
}}
'''


def generate_query(query_type, query_str, dataset):
    """Generate GenSPARQL query based on query type."""
    generators = {
        '2p': generate_2p_query,
        '3p': generate_3p_query,
        '4p': generate_4p_query,
        '2i': generate_2i_query,
        '3i': generate_3i_query,
        'pi': generate_pi_query,
        'ip': generate_ip_query,
        'up': generate_up_query,
    }

    generator = generators.get(query_type)
    if generator:
        return generator(query_str, dataset)
    return None


def process_dataset(dataset_name, data_dir, queries_dir):
    """Process all patterns in a dataset."""
    count = 0
    errors = []

    for query_type_dir in data_dir.iterdir():
        if not query_type_dir.is_dir():
            continue

        query_type = query_type_dir.name
        if query_type not in ['2p', '3p', '4p', '2i', '3i', 'pi', 'ip', 'up']:
            continue

        for pattern_dir in query_type_dir.iterdir():
            if not pattern_dir.is_dir() or not pattern_dir.name.startswith('pattern_'):
                continue

            readable_file = pattern_dir / 'queries_readable.txt'
            if not readable_file.exists():
                continue

            # Parse Query 1
            q_type, pattern, query_str = parse_query_1(readable_file)
            if not query_str:
                errors.append(f"Failed to parse: {readable_file}")
                continue

            # Generate GenSPARQL
            gensparql = generate_query(q_type, query_str, dataset_name)
            if not gensparql:
                errors.append(f"Failed to generate: {readable_file} - Query: {query_str}")
                continue

            # Write to output directory
            output_dir = queries_dir / query_type / pattern_dir.name
            output_dir.mkdir(parents=True, exist_ok=True)
            output_file = output_dir / 'query1.sparql'

            with open(output_file, 'w', encoding='utf-8') as f:
                f.write(gensparql)

            count += 1
            print(f"Generated: {output_file}")

    return count, errors


def main():
    print("=" * 60)
    print("Generating GenSPARQL queries from Query 1 of each pattern")
    print("=" * 60)

    # Process FB15k-237+H
    print("\n[FB15k-237+H]")
    fb_data = DATA_DIR / "FB15k-237+H"
    if fb_data.exists():
        count, errors = process_dataset("FB15k-237+H", fb_data, QUERIES_FB)
        print(f"Generated {count} queries")
        if errors:
            print(f"Errors ({len(errors)}):")
            for e in errors[:5]:
                print(f"  - {e}")

    # Process NELL995+H
    print("\n[NELL995+H]")
    nell_data = DATA_DIR / "NELL995+H"
    if nell_data.exists():
        count, errors = process_dataset("NELL995+H", nell_data, QUERIES_NELL)
        print(f"Generated {count} queries")
        if errors:
            print(f"Errors ({len(errors)}):")
            for e in errors[:5]:
                print(f"  - {e}")

    print("\n" + "=" * 60)
    print("Done!")


if __name__ == "__main__":
    main()
