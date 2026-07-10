#!/usr/bin/env python3
"""
Generate GenSPARQL queries from queries_readable.json files.
Uses machine IDs to generate queries that match the actual RDF data.
"""

import os
import re
import json
import subprocess
import argparse
from pathlib import Path

# Base paths
BASE_DIR = Path(__file__).parent
DATA_DIR = BASE_DIR / "GENSPARQL_Data"
QUERIES_FB = BASE_DIR / "Queries-FB15k-237+H"
QUERIES_NELL = BASE_DIR / "Queries-NELL995+H"

# Namespaces for each dataset
NAMESPACES = {
    "FB15k-237+H": {
        "entity_ns": "http://freebase.com/entity/",
        "relation_ns": "http://freebase.com/relation/",
        "data_file": "train.nt"
    },
    "NELL995+H": {
        "entity_ns": "http://nell.cs.cmu.edu/entity/",
        "relation_ns": "http://nell.cs.cmu.edu/relation/",
        "data_file": "train.nt"
    }
}

# Cache for entity lookups
entity_cache = {}

# Global flag for constrained generation mode
# When True, GENOP prompts include candidate entities from the KG
CONSTRAINED_GENERATION = False
MAX_CANDIDATES = None  # None = include all candidates for the relation


def parse_pattern_code(pattern_name):
    """Extract the 0/1 code from pattern name.

    Example: 'pattern_01' -> [0, 1]
             'pattern_101' -> [1, 0, 1]

    0 = can be matched in KG (use triple pattern)
    1 = needs inference (use GENOP)
    """
    # Extract digits after 'pattern_'
    match = re.search(r'pattern_(\d+)', pattern_name)
    if match:
        code_str = match.group(1)
        return [int(c) for c in code_str]
    return None


def find_entity_uri(entity_id, data_file, entity_ns, dataset):
    """Find the full entity URI from the data file given an entity ID."""
    cache_key = f"{data_file}:{entity_id}"
    if cache_key in entity_cache:
        return entity_cache[cache_key]

    if "NELL" in dataset:
        # NELL: entity names are direct, like concept_clothing_white_shirt
        entity_cache[cache_key] = f"{entity_ns}{entity_id}"
        return entity_cache[cache_key]

    # FB15k-237+H: machine IDs like /m/0214km
    # Clean the machine ID: /m/0214km -> m_0214km
    mid_clean = entity_id.replace('/', '_').lstrip('_')

    # Search for the entity in the data file
    try:
        result = subprocess.run(
            ['grep', '-m', '1', f'{entity_ns}{mid_clean}', str(data_file)],
            capture_output=True, text=True
        )
        if result.stdout:
            # Extract the full entity URI
            match = re.search(rf'<({entity_ns}{mid_clean}[^>]*)>', result.stdout)
            if match:
                entity_cache[cache_key] = match.group(1)
                return entity_cache[cache_key]
    except Exception as e:
        print(f"Error searching for entity {entity_id}: {e}")

    # Fallback: use just the machine ID
    entity_cache[cache_key] = f"{entity_ns}{mid_clean}"
    return entity_cache[cache_key]


def parse_relation(rel_path, dataset):
    """Parse relation path and return (relation_uri_name, is_reverse, natural_language_description).

    The is_reverse flag indicates whether the triple pattern should be reversed:
    - If is_reverse=False: <subject> <rel> ?object
    - If is_reverse=True:  ?object <rel> <subject>  (swap subject and object)

    FB15k: +/music/... -> (forward_music_..., False)
           -/music/... -> (forward_music_..., True)  # Use forward_ but reverse the triple
    NELL: concept:relation -> (concept_relation, False)
          concept:relation_reverse -> (concept_relation, True)  # Remove _reverse, reverse the triple
    """
    if not rel_path:
        return None, False, ""

    if "NELL" in dataset:
        # NELL format: concept:relation or concept:relation_reverse
        rel = rel_path.replace(':', '_')
        if rel.endswith('_reverse'):
            base_rel = rel[:-8]  # Remove _reverse suffix
            # Generate natural language description
            nl_desc = relation_to_natural_language(base_rel, dataset, is_reverse=True)
            return base_rel, True, nl_desc
        nl_desc = relation_to_natural_language(rel, dataset, is_reverse=False)
        return rel, False, nl_desc

    # FB15k-237+H format
    is_reverse = False
    if rel_path.startswith('-'):
        is_reverse = True
        rel_path = rel_path[1:]
    elif rel_path.startswith('+'):
        rel_path = rel_path[1:]

    # Clean up the path: /music/xxx.yyy -> music_xxx._yyy
    rel_path = rel_path.lstrip('/')
    rel_path = rel_path.replace('/', '_')

    # Always use forward_ prefix (since KG only has forward_ relations)
    rel_name = f"forward_{rel_path}"
    nl_desc = relation_to_natural_language(rel_name, dataset, is_reverse)
    return rel_name, is_reverse, nl_desc


def relation_to_natural_language(rel_name, dataset, is_reverse=False):
    """Convert relation name to natural language description for better LLM understanding.

    Examples:
    - concept_agentcollaborateswithagent -> "collaborates with" / "is collaborated with by"
    - forward_film_actor_film._film_performance_film -> "acted in film" / "has actor"
    """
    # Remove prefix
    if rel_name.startswith('forward_'):
        rel_name = rel_name[8:]
    if rel_name.startswith('concept_'):
        rel_name = rel_name[8:]

    # Split by underscores and dots, then join with spaces
    parts = rel_name.replace('.', ' ').replace('_', ' ').split()

    # Filter out common prefixes/noise
    noise_words = {'film', 'music', 'tv', 'award', 'base', 'location', 'people', 'sports'}
    filtered_parts = [p for p in parts if p.lower() not in noise_words or len(parts) <= 2]

    if not filtered_parts:
        filtered_parts = parts

    description = ' '.join(filtered_parts)

    if is_reverse:
        return f"(reverse of) {description}"
    return description


def convert_relation(rel_path, dataset):
    """Legacy wrapper - returns just the relation URI name for backward compatibility."""
    rel_name, _, _ = parse_relation(rel_path, dataset)
    return rel_name


def format_answers_comment(query_data):
    """Format answers as a comment string."""
    answers = query_data.get('answers', [])
    answers_readable = query_data.get('answers_readable', [])
    num_answers = query_data.get('num_answers', len(answers))

    lines = [f"# Expected Answers ({num_answers}):"]

    # Show readable answers if available, otherwise machine IDs
    if answers_readable:
        for i, (ans, ans_readable) in enumerate(zip(answers[:10], answers_readable[:10])):
            lines.append(f"#   {i+1}. {ans_readable} ({ans})")
    else:
        for i, ans in enumerate(answers[:10]):
            lines.append(f"#   {i+1}. {ans}")

    if len(answers) > 10:
        lines.append(f"#   ... and {len(answers) - 10} more")

    return '\n'.join(lines)


def parse_query_readable(query_readable, dataset):
    """Parse the query_readable string to extract entity IDs and relations.
    FB15k format: /m/0214km --[+/music/...]--> ? --[-/music/...]--> ?
    NELL format: concept_entity --[concept:relation]--> ?
    """
    parts = []
    current = query_readable

    # Pattern depends on dataset
    if "NELL" in dataset:
        # NELL: entity names like concept_clothing_white_shirt
        entity_pattern = r'([a-z_0-9]+)\s*--\[([^\]]+)\]-->\s*\?'
    else:
        # FB15k: machine IDs like /m/0214km
        entity_pattern = r'(/[^-\s]+)\s*--\[([^\]]+)\]-->\s*\?'

    # Extract anchor entity and first relation
    match = re.match(entity_pattern, current)
    if match:
        parts.append({
            'type': 'entity',
            'value': match.group(1)
        })
        parts.append({
            'type': 'relation',
            'value': match.group(2)
        })
        current = current[match.end():]

        # Continue extracting subsequent relations
        while True:
            match = re.match(r'\s*--\[([^\]]+)\]-->\s*\?', current)
            if match:
                parts.append({
                    'type': 'relation',
                    'value': match.group(1)
                })
                current = current[match.end():]
            else:
                break

    return parts


def generate_triple_pattern(subject, rel_name, obj, ns, is_reverse):
    """Generate a triple pattern, handling reverse relations correctly.

    If is_reverse=True, swap subject and object positions.
    """
    rel_uri = f"<{ns['relation_ns']}{rel_name}>"
    if is_reverse:
        # Reverse: ?obj rel subject  (swap positions)
        return f"  {obj} {rel_uri} {subject} ."
    else:
        # Normal: subject rel ?obj
        return f"  {subject} {rel_uri} {obj} ."


def generate_genop_prompt(entity_ref, rel_desc, output_var, entity_name=None, candidates=None, max_candidates=200):
    """Generate an improved GENOP prompt with natural language description.

    Args:
        entity_ref: The entity reference (e.g., "{?mid}" or "<http://...>")
        rel_desc: Natural language description of the relation
        output_var: Output variable name (e.g., "answer")
        entity_name: Human-readable entity name if available
        candidates: Optional list of candidate entities for constrained generation
        max_candidates: Maximum number of candidates to include in prompt
    """
    entity_display = entity_name if entity_name else entity_ref

    # Escape inner quotes to avoid parsing issues
    rel_desc_escaped = rel_desc.replace('"', "'")

    # If candidates are provided, use constrained generation
    if candidates and len(candidates) > 0:
        return generate_constrained_prompt(entity_display, rel_desc_escaped, candidates, max_candidates)

    # Build a natural-language prompt that reflects the ACTUAL relation semantics.
    # NOTE: the previous version hard-coded "List movies released on {X} format",
    # which discarded the computed relation description and produced nonsensical
    # prompts (e.g. "movies released on Comedy-GB format", "...on New York City format").
    # We now use the relation description so the LLM is asked the right question,
    # and request a generous number of answers with a neutral example.
    if rel_desc_escaped.startswith("(reverse of)"):
        clean_rel = rel_desc_escaped.replace("(reverse of) ", "").strip()
        # Reverse relation R: find subjects E such that (E, R, entity_display).
        prompt = (
            f"List entities whose '{clean_rel}' is '{entity_display}'. "
            f"Return ONLY a JSON array of up to 50 exact names like ['Name 1', 'Name 2']. No explanations."
        )
    else:
        prompt = (
            f"List entities where '{entity_display}' is their '{rel_desc_escaped}'. "
            f"Return ONLY a JSON array of up to 50 exact names like ['Name 1', 'Name 2']. No explanations."
        )

    return prompt


def generate_constrained_prompt(entity_display, rel_desc, candidates, max_candidates=None):
    """Generate a constrained prompt where LLM must select from candidates.

    This converts the open-domain generation task into a selection task,
    which significantly improves accuracy for closed-domain KG completion.

    Args:
        entity_display: Human-readable entity name
        rel_desc: Natural language description of the relation
        candidates: List of candidate entity labels
        max_candidates: Maximum candidates to include (None = all)
    """
    candidate_list = list(candidates)
    if max_candidates:
        candidate_list = candidate_list[:max_candidates]

    # Include the candidate pool in the prompt
    candidates_str = ', '.join(candidate_list)

    # Domain-neutral selection prompt driven by the relation semantics.
    # NOTE: the previous version hard-coded a clothing/fashion question
    # ("What clothing items ... worn together with X?"), a leftover from the
    # NELL clothing dataset that made this unusable for FB15k (films, etc.).
    # Forcing the model to CHOOSE from real KG entities guarantees that every
    # output is a valid, groundable entity (high grounding rate) and lets the
    # sim-join short-circuit on exact matches instead of calling embeddings.
    rel = rel_desc.replace("(reverse of) ", "").strip()
    prompt = (
        f"From the candidate list below, select ONLY the entities whose '{rel}' is '{entity_display}'. "
        f"Choose exclusively from this list and copy each name EXACTLY as written: [{candidates_str}]. "
        f"Return a JSON array like ['Name 1', 'Name 2']. Return [] if none match."
    )

    return prompt


# Cache for relation candidates extracted from KG
relation_candidates_cache = {}


def extract_candidates_for_relation(data_file, relation_uri, mode='object'):
    """Extract all candidate entities for a given relation from the KG.

    Args:
        data_file: Path to the N-Triples data file
        relation_uri: Full URI of the relation
        mode: 'object' to get all objects, 'subject' to get all subjects

    Returns:
        Set of entity local names
    """
    cache_key = f"{data_file}:{relation_uri}:{mode}"
    if cache_key in relation_candidates_cache:
        return relation_candidates_cache[cache_key]

    candidates = set()
    try:
        with open(data_file, 'r') as f:
            for line in f:
                if relation_uri in line:
                    # Parse N-Triple line: <subject> <predicate> <object> .
                    parts = line.strip().split(' ')
                    if len(parts) >= 3:
                        if mode == 'object':
                            entity_uri = parts[2].strip('<>')
                        else:  # subject
                            entity_uri = parts[0].strip('<>')

                        # Extract local name from URI
                        local_name = entity_uri.split('/')[-1]
                        candidates.add(local_name)
    except Exception as e:
        print(f"Error extracting candidates: {e}")

    relation_candidates_cache[cache_key] = candidates
    print(f"Extracted {len(candidates)} candidates for {relation_uri} (mode={mode})")
    return candidates


def generate_2p_query(query_data, dataset, data_file, pattern_code):
    """Generate GenSPARQL for 2p query based on pattern code.

    pattern_code: list of 0/1, e.g., [0, 1] means:
        - step 1 (rel1): 0 = KG match
        - step 2 (rel2): 1 = GENOP inference
    """
    ns = NAMESPACES[dataset]
    query_readable = query_data.get('query_readable', '')

    parts = parse_query_readable(query_readable, dataset)
    if len(parts) < 3:
        return None

    anchor_mid = parts[0]['value']

    # Parse relations with direction info
    rel1_name, rel1_reverse, rel1_desc = parse_relation(parts[1]['value'], dataset)
    rel2_name, rel2_reverse, rel2_desc = parse_relation(parts[2]['value'], dataset)

    anchor_uri = find_entity_uri(anchor_mid, data_file, ns['entity_ns'], dataset)
    answers_comment = format_answers_comment(query_data)

    # Determine which steps use KG match vs GENOP based on pattern_code
    step1_is_genop = pattern_code[0] == 1 if pattern_code and len(pattern_code) > 0 else False
    step2_is_genop = pattern_code[1] == 1 if pattern_code and len(pattern_code) > 1 else True

    # Build query body based on pattern
    lines = []

    # Extract candidates for constrained generation if enabled
    candidates1 = None
    candidates2 = None
    if CONSTRAINED_GENERATION:
        rel1_uri = ns['relation_ns'] + rel1_name
        rel2_uri = ns['relation_ns'] + rel2_name
        # For relation, get objects (tail entities) as candidates
        candidates1 = extract_candidates_for_relation(data_file, rel1_uri, 'object' if not rel1_reverse else 'subject')
        candidates2 = extract_candidates_for_relation(data_file, rel2_uri, 'object' if not rel2_reverse else 'subject')

    # Step 1: anchor -> ?mid
    if not step1_is_genop:
        # KG match - handle reverse relation
        triple = generate_triple_pattern(f"<{anchor_uri}>", rel1_name, "?mid", ns, rel1_reverse)
        lines.append(triple)
    else:
        # GENOP
        prompt = generate_genop_prompt(f"<{anchor_uri}>", rel1_desc, "mid", anchor_mid,
                                       candidates=candidates1, max_candidates=MAX_CANDIDATES)
        lines.append(f'  GENOP("{prompt}",')
        lines.append(f'        (?mid),')
        lines.append(f'        <model:openrouter:deepseek/deepseek-chat>)')

    # Step 2: ?mid -> ?answer
    if not step2_is_genop:
        # KG match - handle reverse relation
        triple = generate_triple_pattern("?mid", rel2_name, "?answer", ns, rel2_reverse)
        lines.append(triple)
    else:
        # GENOP
        prompt = generate_genop_prompt("{?mid}", rel2_desc, "answer",
                                       candidates=candidates2, max_candidates=MAX_CANDIDATES)
        lines.append(f'  GENOP("{prompt}",')
        lines.append(f'        (?answer),')
        lines.append(f'        <model:openrouter:deepseek/deepseek-chat>)')

    query_body = '\n'.join(lines)
    pattern_str = ''.join(str(c) for c in pattern_code) if pattern_code else 'unknown'

    return f'''# 2p Query (pattern_{pattern_str}): {query_data.get('query_readable_with_names', query_readable)}
# Machine IDs: anchor={anchor_mid}, rel1={parts[1]['value']}, rel2={parts[2]['value']}
# Pattern: {pattern_str} (0=KG match, 1=GENOP inference)
# Relation directions: rel1_reverse={rel1_reverse}, rel2_reverse={rel2_reverse}
{answers_comment}

SELECT ?answer ?mid WHERE {{
{query_body}
}}
'''


def generate_3p_query(query_data, dataset, data_file, pattern_code):
    """Generate GenSPARQL for 3p query based on pattern code.

    pattern_code: list of 0/1, e.g., [0, 0, 1] means:
        - step 1 (rel1): 0 = KG match
        - step 2 (rel2): 0 = KG match
        - step 3 (rel3): 1 = GENOP inference
    """
    ns = NAMESPACES[dataset]
    query_readable = query_data.get('query_readable', '')

    parts = parse_query_readable(query_readable, dataset)
    if len(parts) < 4:
        return None

    anchor_mid = parts[0]['value']

    # Parse relations with direction info
    rel1_name, rel1_reverse, rel1_desc = parse_relation(parts[1]['value'], dataset)
    rel2_name, rel2_reverse, rel2_desc = parse_relation(parts[2]['value'], dataset)
    rel3_name, rel3_reverse, rel3_desc = parse_relation(parts[3]['value'], dataset)

    anchor_uri = find_entity_uri(anchor_mid, data_file, ns['entity_ns'], dataset)
    answers_comment = format_answers_comment(query_data)

    # Get pattern code for each step (default to [0, 0, 1] if not provided)
    code = pattern_code if pattern_code and len(pattern_code) >= 3 else [0, 0, 1]

    # Build query body step by step
    lines = []

    # Step 1: anchor -> ?e1
    if code[0] == 0:
        triple = generate_triple_pattern(f"<{anchor_uri}>", rel1_name, "?e1", ns, rel1_reverse)
        lines.append(triple)
    else:
        prompt = generate_genop_prompt(f"<{anchor_uri}>", rel1_desc, "e1", anchor_mid)
        lines.append(f'  GENOP("{prompt}",')
        lines.append(f'        (?e1),')
        lines.append(f'        <model:openrouter:deepseek/deepseek-chat>)')

    # Step 2: ?e1 -> ?e2
    if code[1] == 0:
        triple = generate_triple_pattern("?e1", rel2_name, "?e2", ns, rel2_reverse)
        lines.append(triple)
    else:
        prompt = generate_genop_prompt("{?e1}", rel2_desc, "e2")
        lines.append(f'  GENOP("{prompt}",')
        lines.append(f'        (?e2),')
        lines.append(f'        <model:openrouter:deepseek/deepseek-chat>)')

    # Step 3: ?e2 -> ?answer
    if code[2] == 0:
        triple = generate_triple_pattern("?e2", rel3_name, "?answer", ns, rel3_reverse)
        lines.append(triple)
    else:
        prompt = generate_genop_prompt("{?e2}", rel3_desc, "answer")
        lines.append(f'  GENOP("{prompt}",')
        lines.append(f'        (?answer),')
        lines.append(f'        <model:openrouter:deepseek/deepseek-chat>)')

    query_body = '\n'.join(lines)
    pattern_str = ''.join(str(c) for c in code)

    return f'''# 3p Query (pattern_{pattern_str}): {query_data.get('query_readable_with_names', query_readable)}
# Machine IDs: anchor={anchor_mid}
# Pattern: {pattern_str} (0=KG match, 1=GENOP inference)
{answers_comment}

SELECT ?answer ?e1 ?e2 WHERE {{
{query_body}
}}
'''


def generate_4p_query(query_data, dataset, data_file, pattern_code):
    """Generate GenSPARQL for 4p query based on pattern code.

    pattern_code: list of 0/1, e.g., [0, 0, 0, 1] means:
        - steps 1-3: KG match
        - step 4: GENOP inference
    """
    ns = NAMESPACES[dataset]
    query_readable = query_data.get('query_readable', '')

    parts = parse_query_readable(query_readable, dataset)
    if len(parts) < 5:
        return None

    anchor_mid = parts[0]['value']

    # Parse relations with direction info
    rel1_name, rel1_reverse, rel1_desc = parse_relation(parts[1]['value'], dataset)
    rel2_name, rel2_reverse, rel2_desc = parse_relation(parts[2]['value'], dataset)
    rel3_name, rel3_reverse, rel3_desc = parse_relation(parts[3]['value'], dataset)
    rel4_name, rel4_reverse, rel4_desc = parse_relation(parts[4]['value'], dataset)

    anchor_uri = find_entity_uri(anchor_mid, data_file, ns['entity_ns'], dataset)
    answers_comment = format_answers_comment(query_data)

    # Get pattern code for each step (default to [0, 0, 0, 1] if not provided)
    code = pattern_code if pattern_code and len(pattern_code) >= 4 else [0, 0, 0, 1]

    # Build query body step by step
    lines = []

    # Step 1: anchor -> ?e1
    if code[0] == 0:
        triple = generate_triple_pattern(f"<{anchor_uri}>", rel1_name, "?e1", ns, rel1_reverse)
        lines.append(triple)
    else:
        prompt = generate_genop_prompt(f"<{anchor_uri}>", rel1_desc, "e1", anchor_mid)
        lines.append(f'  GENOP("{prompt}",')
        lines.append(f'        (?e1),')
        lines.append(f'        <model:openrouter:deepseek/deepseek-chat>)')

    # Step 2: ?e1 -> ?e2
    if code[1] == 0:
        triple = generate_triple_pattern("?e1", rel2_name, "?e2", ns, rel2_reverse)
        lines.append(triple)
    else:
        prompt = generate_genop_prompt("{?e1}", rel2_desc, "e2")
        lines.append(f'  GENOP("{prompt}",')
        lines.append(f'        (?e2),')
        lines.append(f'        <model:openrouter:deepseek/deepseek-chat>)')

    # Step 3: ?e2 -> ?e3
    if code[2] == 0:
        triple = generate_triple_pattern("?e2", rel3_name, "?e3", ns, rel3_reverse)
        lines.append(triple)
    else:
        prompt = generate_genop_prompt("{?e2}", rel3_desc, "e3")
        lines.append(f'  GENOP("{prompt}",')
        lines.append(f'        (?e3),')
        lines.append(f'        <model:openrouter:deepseek/deepseek-chat>)')

    # Step 4: ?e3 -> ?answer
    if code[3] == 0:
        triple = generate_triple_pattern("?e3", rel4_name, "?answer", ns, rel4_reverse)
        lines.append(triple)
    else:
        prompt = generate_genop_prompt("{?e3}", rel4_desc, "answer")
        lines.append(f'  GENOP("{prompt}",')
        lines.append(f'        (?answer),')
        lines.append(f'        <model:openrouter:deepseek/deepseek-chat>)')

    query_body = '\n'.join(lines)
    pattern_str = ''.join(str(c) for c in code)

    return f'''# 4p Query (pattern_{pattern_str}): {query_data.get('query_readable_with_names', query_readable)}
# Machine IDs: anchor={anchor_mid}
# Pattern: {pattern_str} (0=KG match, 1=GENOP inference)
{answers_comment}

SELECT ?answer ?e1 ?e2 ?e3 WHERE {{
{query_body}
}}
'''


def parse_intersection_query(query_readable, dataset, query_readable_with_names=None):
    """Parse intersection query like (E1 --[r1]--> ?) ∩ (E2 --[r2]--> ?)"""
    branches = []

    # Split by intersection symbol
    parts = re.split(r'\s*∩\s*', query_readable)

    # Also parse the readable version for entity names
    name_parts = []
    if query_readable_with_names:
        name_parts = re.split(r'\s*∩\s*', query_readable_with_names)

    # Pattern depends on dataset
    if "NELL" in dataset:
        # NELL: entity names like concept_clothing_white_shirt
        entity_pattern = r'([a-z_0-9]+)\s*--\[([^\]]+)\]-->\s*\?(?:\s*--\[([^\]]+)\]-->\s*\?)?'
        name_pattern = entity_pattern
    else:
        # FB15k: machine IDs like /m/0214km
        entity_pattern = r'(/[^-\s]+)\s*--\[([^\]]+)\]-->\s*\?(?:\s*--\[([^\]]+)\]-->\s*\?)?'
        # For names: like "Comedy-GB" or "DVD"
        name_pattern = r'([A-Za-z0-9_\-\s]+)\s*--\[([^\]]+)\]-->\s*\?(?:\s*--\[([^\]]+)\]-->\s*\?)?'

    for i, part in enumerate(parts):
        # Remove outer parentheses
        part = part.strip()
        if part.startswith('('):
            part = part[1:]
        if part.endswith(')'):
            part = part[:-1]

        # Parse: entity --[relation]--> ? or entity --[r1]--> ? --[r2]--> ?
        match = re.match(entity_pattern, part)
        if match:
            branch = {
                'entity': match.group(1),
                'rel1': match.group(2),
                'rel2': match.group(3) if match.group(3) else None,
                'entity_name': None
            }

            # Try to extract readable entity name
            if i < len(name_parts):
                name_part = name_parts[i].strip()
                if name_part.startswith('('):
                    name_part = name_part[1:]
                if name_part.endswith(')'):
                    name_part = name_part[:-1]
                name_match = re.match(name_pattern, name_part)
                if name_match:
                    branch['entity_name'] = name_match.group(1).strip()

            branches.append(branch)

    return branches


def generate_2i_query(query_data, dataset, data_file, pattern_code):
    """Generate GenSPARQL for 2i query based on pattern code.

    pattern_code: list of 0/1, e.g., [0, 1] means:
        - branch 1 (e1 --[r1]--> ?): 0 = KG match
        - branch 2 (e2 --[r2]--> ?): 1 = GENOP inference
    """
    ns = NAMESPACES[dataset]
    query_readable = query_data.get('query_readable', '')
    query_readable_with_names = query_data.get('query_readable_with_names', '')

    branches = parse_intersection_query(query_readable, dataset, query_readable_with_names)
    if len(branches) < 2:
        return None

    e1_uri = find_entity_uri(branches[0]['entity'], data_file, ns['entity_ns'], dataset)
    e1_name = branches[0].get('entity_name') or branches[0]['entity']
    rel1_name, rel1_reverse, rel1_desc = parse_relation(branches[0]['rel1'], dataset)

    e2_uri = find_entity_uri(branches[1]['entity'], data_file, ns['entity_ns'], dataset)
    e2_name = branches[1].get('entity_name') or branches[1]['entity']
    rel2_name, rel2_reverse, rel2_desc = parse_relation(branches[1]['rel1'], dataset)

    answers_comment = format_answers_comment(query_data)

    # Get pattern code (default to [0, 1] if not provided)
    code = pattern_code if pattern_code and len(pattern_code) >= 2 else [0, 1]

    lines = []

    # Branch 1: e1 --[r1]--> ?answer
    if code[0] == 0:
        triple = generate_triple_pattern(f"<{e1_uri}>", rel1_name, "?answer", ns, rel1_reverse)
        lines.append(triple)
    else:
        prompt = generate_genop_prompt(f"<{e1_uri}>", rel1_desc, "answer", e1_name)
        lines.append(f'  GENOP("{prompt}",')
        lines.append(f'        (?answer),')
        lines.append(f'        <model:openrouter:deepseek/deepseek-chat>)')

    # Branch 2: e2 --[r2]--> ?answer (intersection)
    if code[1] == 0:
        triple = generate_triple_pattern(f"<{e2_uri}>", rel2_name, "?answer", ns, rel2_reverse)
        lines.append(triple)
    else:
        prompt = generate_genop_prompt(f"<{e2_uri}>", rel2_desc, "answer", e2_name)
        lines.append(f'  GENOP("{prompt}",')
        lines.append(f'        (?answer),')
        lines.append(f'        <model:openrouter:deepseek/deepseek-chat>)')

    query_body = '\n'.join(lines)
    pattern_str = ''.join(str(c) for c in code)

    return f'''# 2i Query (pattern_{pattern_str}): {query_data.get('query_readable_with_names', query_readable)}
# Pattern: {pattern_str} (0=KG match, 1=GENOP inference)
{answers_comment}

SELECT ?answer WHERE {{
{query_body}
}}
'''


def generate_3i_query(query_data, dataset, data_file, pattern_code):
    """Generate GenSPARQL for 3i query based on pattern code.

    pattern_code: list of 0/1, e.g., [0, 0, 1] means:
        - branch 1: KG match
        - branch 2: KG match
        - branch 3: GENOP inference
    """
    ns = NAMESPACES[dataset]
    query_readable = query_data.get('query_readable', '')

    branches = parse_intersection_query(query_readable, dataset)
    if len(branches) < 3:
        return None

    e1_uri = find_entity_uri(branches[0]['entity'], data_file, ns['entity_ns'], dataset)
    e1_mid = branches[0]['entity']
    rel1_name, rel1_reverse, rel1_desc = parse_relation(branches[0]['rel1'], dataset)

    e2_uri = find_entity_uri(branches[1]['entity'], data_file, ns['entity_ns'], dataset)
    e2_mid = branches[1]['entity']
    rel2_name, rel2_reverse, rel2_desc = parse_relation(branches[1]['rel1'], dataset)

    e3_uri = find_entity_uri(branches[2]['entity'], data_file, ns['entity_ns'], dataset)
    e3_mid = branches[2]['entity']
    rel3_name, rel3_reverse, rel3_desc = parse_relation(branches[2]['rel1'], dataset)

    answers_comment = format_answers_comment(query_data)

    # Get pattern code (default to [0, 0, 1] if not provided)
    code = pattern_code if pattern_code and len(pattern_code) >= 3 else [0, 0, 1]

    lines = []

    # Branch 1: e1 --[r1]--> ?answer
    if code[0] == 0:
        triple = generate_triple_pattern(f"<{e1_uri}>", rel1_name, "?answer", ns, rel1_reverse)
        lines.append(triple)
    else:
        prompt = generate_genop_prompt(f"<{e1_uri}>", rel1_desc, "answer", e1_mid)
        lines.append(f'  GENOP("{prompt}",')
        lines.append(f'        (?answer),')
        lines.append(f'        <model:openrouter:deepseek/deepseek-chat>)')

    # Branch 2: e2 --[r2]--> ?answer
    if code[1] == 0:
        triple = generate_triple_pattern(f"<{e2_uri}>", rel2_name, "?answer", ns, rel2_reverse)
        lines.append(triple)
    else:
        prompt = generate_genop_prompt(f"<{e2_uri}>", rel2_desc, "answer", e2_mid)
        lines.append(f'  GENOP("{prompt}",')
        lines.append(f'        (?answer),')
        lines.append(f'        <model:openrouter:deepseek/deepseek-chat>)')

    # Branch 3: e3 --[r3]--> ?answer
    if code[2] == 0:
        triple = generate_triple_pattern(f"<{e3_uri}>", rel3_name, "?answer", ns, rel3_reverse)
        lines.append(triple)
    else:
        prompt = generate_genop_prompt(f"<{e3_uri}>", rel3_desc, "answer", e3_mid)
        lines.append(f'  GENOP("{prompt}",')
        lines.append(f'        (?answer),')
        lines.append(f'        <model:openrouter:deepseek/deepseek-chat>)')

    query_body = '\n'.join(lines)
    pattern_str = ''.join(str(c) for c in code)

    return f'''# 3i Query (pattern_{pattern_str}): {query_data.get('query_readable_with_names', query_readable)}
# Pattern: {pattern_str} (0=KG match, 1=GENOP inference)
{answers_comment}

SELECT ?answer WHERE {{
{query_body}
}}
'''


def generate_pi_query(query_data, dataset, data_file, pattern_code):
    """Generate GenSPARQL for pi (path + intersection) query based on pattern code.

    Structure: (E1 --[r1]--> ? --[r2]--> ?) ∩ (E2 --[r3]--> ?)
    pattern_code: [r1, r2, r3], e.g., [0, 1, 0] means:
        - step 1 (r1): KG match
        - step 2 (r2): GENOP
        - branch 2 (r3): KG match
    """
    ns = NAMESPACES[dataset]
    query_readable = query_data.get('query_readable', '')

    branches = parse_intersection_query(query_readable, dataset)
    if len(branches) < 2 or not branches[0].get('rel2'):
        return None

    e1_uri = find_entity_uri(branches[0]['entity'], data_file, ns['entity_ns'], dataset)
    e1_mid = branches[0]['entity']
    e2_uri = find_entity_uri(branches[1]['entity'], data_file, ns['entity_ns'], dataset)
    e2_mid = branches[1]['entity']

    # Parse relations with reverse handling
    rel1_name, rel1_reverse, rel1_desc = parse_relation(branches[0]['rel1'], dataset)
    rel2_name, rel2_reverse, rel2_desc = parse_relation(branches[0]['rel2'], dataset)
    rel3_name, rel3_reverse, rel3_desc = parse_relation(branches[1]['rel1'], dataset)

    answers_comment = format_answers_comment(query_data)

    # Get pattern code (default to [0, 0, 1] if not provided)
    code = pattern_code if pattern_code and len(pattern_code) >= 3 else [0, 0, 1]

    lines = []

    # Step 1: e1 --[r1]--> ?mid
    if code[0] == 0:
        triple = generate_triple_pattern(f"<{e1_uri}>", rel1_name, "?mid", ns, rel1_reverse)
        lines.append(triple)
    else:
        prompt = generate_genop_prompt(f"<{e1_uri}>", rel1_desc, "mid", e1_mid)
        lines.append(f'  GENOP("{prompt}",')
        lines.append(f'        (?mid),')
        lines.append(f'        <model:openrouter:deepseek/deepseek-chat>)')

    # Step 2: ?mid --[r2]--> ?answer
    if code[1] == 0:
        triple = generate_triple_pattern("?mid", rel2_name, "?answer", ns, rel2_reverse)
        lines.append(triple)
    else:
        prompt = generate_genop_prompt("{{?mid}}", rel2_desc, "answer")
        lines.append(f'  GENOP("{prompt}",')
        lines.append(f'        (?answer),')
        lines.append(f'        <model:openrouter:deepseek/deepseek-chat>)')

    # Branch 2: e2 --[r3]--> ?answer (intersection)
    if code[2] == 0:
        triple = generate_triple_pattern(f"<{e2_uri}>", rel3_name, "?answer", ns, rel3_reverse)
        lines.append(triple)
    else:
        prompt = generate_genop_prompt(f"<{e2_uri}>", rel3_desc, "answer", e2_mid)
        lines.append(f'  GENOP("{prompt}",')
        lines.append(f'        (?answer),')
        lines.append(f'        <model:openrouter:deepseek/deepseek-chat>)')

    query_body = '\n'.join(lines)
    pattern_str = ''.join(str(c) for c in code)

    return f'''# pi Query (pattern_{pattern_str}): {query_data.get('query_readable_with_names', query_readable)}
# Pattern: {pattern_str} (0=KG match, 1=GENOP inference)
{answers_comment}

SELECT ?answer ?mid WHERE {{
{query_body}
}}
'''


def parse_ip_up_query(query_readable, dataset):
    """Parse ip/up query like ((E1 --[r1]--> ?) ∩/∪ (E2 --[r2]--> ?)) --[r3]--> ?"""
    # Pattern depends on dataset
    if "NELL" in dataset:
        # NELL: entity names like concept_clothing_white_shirt
        entity_pattern = r'\(\(([a-z_0-9]+)\s*--\[([^\]]+)\]-->\s*\?\)\s*[∩∪]\s*\(([a-z_0-9]+)\s*--\[([^\]]+)\]-->\s*\?\)\)\s*--\[([^\]]+)\]-->\s*\?'
    else:
        # FB15k: machine IDs like /m/0214km
        entity_pattern = r'\(\((/[^-\s]+)\s*--\[([^\]]+)\]-->\s*\?\)\s*[∩∪]\s*\((/[^-\s]+)\s*--\[([^\]]+)\]-->\s*\?\)\)\s*--\[([^\]]+)\]-->\s*\?'

    match = re.match(entity_pattern, query_readable)

    if match:
        return {
            'e1': match.group(1),
            'rel1': match.group(2),
            'e2': match.group(3),
            'rel2': match.group(4),
            'rel3': match.group(5)
        }
    return None


def generate_ip_query(query_data, dataset, data_file, pattern_code):
    """Generate GenSPARQL for ip (intersection + path) query based on pattern code.

    Structure: ((E1 --[r1]--> ?) ∩ (E2 --[r2]--> ?)) --[r3]--> ?
    pattern_code: [r1, r2, r3], e.g., [0, 0, 1] means:
        - branch 1 (r1): KG match
        - branch 2 (r2): KG match
        - final step (r3): GENOP
    """
    ns = NAMESPACES[dataset]
    query_readable = query_data.get('query_readable', '')

    parsed = parse_ip_up_query(query_readable, dataset)
    if not parsed:
        return None

    e1_uri = find_entity_uri(parsed['e1'], data_file, ns['entity_ns'], dataset)
    e1_mid = parsed['e1']
    e2_uri = find_entity_uri(parsed['e2'], data_file, ns['entity_ns'], dataset)
    e2_mid = parsed['e2']

    # Parse relations with reverse handling
    rel1_name, rel1_reverse, rel1_desc = parse_relation(parsed['rel1'], dataset)
    rel2_name, rel2_reverse, rel2_desc = parse_relation(parsed['rel2'], dataset)
    rel3_name, rel3_reverse, rel3_desc = parse_relation(parsed['rel3'], dataset)

    answers_comment = format_answers_comment(query_data)

    # Get pattern code (default to [0, 0, 1] if not provided)
    code = pattern_code if pattern_code and len(pattern_code) >= 3 else [0, 0, 1]

    lines = []

    # Branch 1: e1 --[r1]--> ?mid
    if code[0] == 0:
        triple = generate_triple_pattern(f"<{e1_uri}>", rel1_name, "?mid", ns, rel1_reverse)
        lines.append(triple)
    else:
        prompt = generate_genop_prompt(f"<{e1_uri}>", rel1_desc, "mid", e1_mid)
        lines.append(f'  GENOP("{prompt}",')
        lines.append(f'        (?mid),')
        lines.append(f'        <model:openrouter:deepseek/deepseek-chat>)')

    # Branch 2: e2 --[r2]--> ?mid (intersection)
    if code[1] == 0:
        triple = generate_triple_pattern(f"<{e2_uri}>", rel2_name, "?mid", ns, rel2_reverse)
        lines.append(triple)
    else:
        prompt = generate_genop_prompt(f"<{e2_uri}>", rel2_desc, "mid", e2_mid)
        lines.append(f'  GENOP("{prompt}",')
        lines.append(f'        (?mid),')
        lines.append(f'        <model:openrouter:deepseek/deepseek-chat>)')

    # Final step: ?mid --[r3]--> ?answer
    if code[2] == 0:
        triple = generate_triple_pattern("?mid", rel3_name, "?answer", ns, rel3_reverse)
        lines.append(triple)
    else:
        prompt = generate_genop_prompt("{{?mid}}", rel3_desc, "answer")
        lines.append(f'  GENOP("{prompt}",')
        lines.append(f'        (?answer),')
        lines.append(f'        <model:openrouter:deepseek/deepseek-chat>)')

    query_body = '\n'.join(lines)
    pattern_str = ''.join(str(c) for c in code)

    return f'''# ip Query (pattern_{pattern_str}): {query_data.get('query_readable_with_names', query_readable)}
# Pattern: {pattern_str} (0=KG match, 1=GENOP inference)
{answers_comment}

SELECT ?answer ?mid WHERE {{
{query_body}
}}
'''


def generate_up_query(query_data, dataset, data_file, pattern_code):
    """Generate GenSPARQL for up (union + path) query based on pattern code.

    Structure: ((E1 --[r1]--> ?) ∪ (E2 --[r2]--> ?)) --[r3]--> ?
    pattern_code: [r1, r2, r3], e.g., [0, 0, 1] means:
        - branch 1 (r1): KG match
        - branch 2 (r2): KG match
        - final step (r3): GENOP
    """
    ns = NAMESPACES[dataset]
    query_readable = query_data.get('query_readable', '')

    parsed = parse_ip_up_query(query_readable, dataset)
    if not parsed:
        return None

    e1_uri = find_entity_uri(parsed['e1'], data_file, ns['entity_ns'], dataset)
    e1_mid = parsed['e1']
    e2_uri = find_entity_uri(parsed['e2'], data_file, ns['entity_ns'], dataset)
    e2_mid = parsed['e2']

    # Parse relations with reverse handling
    rel1_name, rel1_reverse, rel1_desc = parse_relation(parsed['rel1'], dataset)
    rel2_name, rel2_reverse, rel2_desc = parse_relation(parsed['rel2'], dataset)
    rel3_name, rel3_reverse, rel3_desc = parse_relation(parsed['rel3'], dataset)

    answers_comment = format_answers_comment(query_data)

    # Get pattern code (default to [0, 0, 1] if not provided)
    code = pattern_code if pattern_code and len(pattern_code) >= 3 else [0, 0, 1]

    # For UNION queries, we need to handle each branch
    # Note: UNION structure makes it complex - if one branch needs GENOP,
    # we need to handle it differently

    union_parts = []
    final_step_lines = []

    # Branch 1 in UNION
    if code[0] == 0:
        # Generate triple pattern with extra indentation for UNION block
        triple = generate_triple_pattern(f"<{e1_uri}>", rel1_name, "?mid", ns, rel1_reverse)
        union_parts.append("  " + triple)  # Extra indent for UNION
    else:
        prompt = generate_genop_prompt(f"<{e1_uri}>", rel1_desc, "mid", e1_mid)
        union_parts.append(f'    GENOP("{prompt}",')
        union_parts.append(f'          (?mid),')
        union_parts.append(f'          <model:openrouter:deepseek/deepseek-chat>)')

    # Branch 2 in UNION
    branch2_lines = []
    if code[1] == 0:
        triple = generate_triple_pattern(f"<{e2_uri}>", rel2_name, "?mid", ns, rel2_reverse)
        branch2_lines.append("  " + triple)  # Extra indent for UNION
    else:
        prompt = generate_genop_prompt(f"<{e2_uri}>", rel2_desc, "mid", e2_mid)
        branch2_lines.append(f'    GENOP("{prompt}",')
        branch2_lines.append(f'          (?mid),')
        branch2_lines.append(f'          <model:openrouter:deepseek/deepseek-chat>)')

    # Final step: ?mid --[r3]--> ?answer
    if code[2] == 0:
        triple = generate_triple_pattern("?mid", rel3_name, "?answer", ns, rel3_reverse)
        final_step_lines.append(triple)
    else:
        prompt = generate_genop_prompt("{{?mid}}", rel3_desc, "answer")
        final_step_lines.append(f'  GENOP("{prompt}",')
        final_step_lines.append(f'        (?answer),')
        final_step_lines.append(f'        <model:openrouter:deepseek/deepseek-chat>)')

    pattern_str = ''.join(str(c) for c in code)

    union_block1 = '\n'.join(union_parts)
    union_block2 = '\n'.join(branch2_lines)
    final_step = '\n'.join(final_step_lines)

    return f'''# up Query (pattern_{pattern_str}): {query_data.get('query_readable_with_names', query_readable)}
# Pattern: {pattern_str} (0=KG match, 1=GENOP inference)
{answers_comment}

SELECT ?answer ?mid WHERE {{
  {{
{union_block1}
  }} UNION {{
{union_block2}
  }}
{final_step}
}}
'''


def generate_query(query_type, query_data, dataset, data_file, pattern_code):
    """Generate GenSPARQL query based on query type and pattern code."""
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
        return generator(query_data, dataset, data_file, pattern_code)
    return None


def process_dataset(dataset_name, data_dir, queries_dir):
    """Process all patterns in a dataset."""
    count = 0
    errors = []
    ns = NAMESPACES[dataset_name]
    data_file = data_dir / ns['data_file']

    for query_type_dir in data_dir.iterdir():
        if not query_type_dir.is_dir():
            continue

        query_type = query_type_dir.name
        if query_type not in ['2p', '3p', '4p', '2i', '3i', 'pi', 'ip', 'up']:
            continue

        for pattern_dir in query_type_dir.iterdir():
            if not pattern_dir.is_dir() or not pattern_dir.name.startswith('pattern_'):
                continue

            json_file = pattern_dir / 'queries_readable.json'
            if not json_file.exists():
                continue

            try:
                with open(json_file, 'r', encoding='utf-8') as f:
                    data = json.load(f)

                queries = data.get('queries', [])
                if not queries:
                    continue

                # Get first query
                query_data = queries[0]

                # Parse pattern code from directory name (e.g., 'pattern_01' -> [0, 1])
                pattern_code = parse_pattern_code(pattern_dir.name)

                # Generate GenSPARQL with pattern code
                gensparql = generate_query(query_type, query_data, dataset_name, data_file, pattern_code)
                if not gensparql:
                    errors.append(f"Failed to generate: {json_file}")
                    continue

                # Write to output directory
                output_dir = queries_dir / query_type / pattern_dir.name
                output_dir.mkdir(parents=True, exist_ok=True)
                output_file = output_dir / 'query1.sparql'

                with open(output_file, 'w', encoding='utf-8') as f:
                    f.write(gensparql)

                count += 1
                print(f"Generated: {output_file}")

            except Exception as e:
                errors.append(f"Error processing {json_file}: {e}")

    return count, errors


def main():
    global CONSTRAINED_GENERATION, MAX_CANDIDATES

    parser = argparse.ArgumentParser(
        description='Generate GenSPARQL queries from JSON files.',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog='''
Examples:
  # Generate standard queries (open-domain LLM generation)
  python generate_gensparql_queries_v2.py

  # Generate constrained queries (LLM selects from candidate entities)
  python generate_gensparql_queries_v2.py --constrained

  # Limit candidates per prompt
  python generate_gensparql_queries_v2.py --constrained --max-candidates 100
        '''
    )
    parser.add_argument(
        '--constrained', '-c',
        action='store_true',
        help='Enable constrained generation: include candidate entities from KG in prompts'
    )
    parser.add_argument(
        '--max-candidates', '-m',
        type=int,
        default=0,
        help='Maximum number of candidates to include per prompt (0 = no limit, default: 0)'
    )
    parser.add_argument(
        '--dataset', '-d',
        choices=['fb15k', 'nell', 'both'],
        default='both',
        help='Dataset to process (default: both)'
    )

    args = parser.parse_args()

    # Set global flags
    CONSTRAINED_GENERATION = args.constrained
    MAX_CANDIDATES = args.max_candidates if args.max_candidates > 0 else None

    print("=" * 60)
    print("Generating GenSPARQL queries (v2) from JSON files")
    if CONSTRAINED_GENERATION:
        if MAX_CANDIDATES:
            print(f"Mode: CONSTRAINED GENERATION (max {MAX_CANDIDATES} candidates)")
        else:
            print("Mode: CONSTRAINED GENERATION (ALL candidates)")
    else:
        print("Mode: Standard (open-domain generation)")
    print("=" * 60)

    # Process FB15k-237+H
    if args.dataset in ['fb15k', 'both']:
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
    if args.dataset in ['nell', 'both']:
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
