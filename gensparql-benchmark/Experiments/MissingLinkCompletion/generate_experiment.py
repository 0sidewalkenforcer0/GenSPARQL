#!/usr/bin/env python3
"""
Missing Link Completion Experiment Generator

Purpose: Demonstrate that GenSPARQL can use LLM's Open World Knowledge
to repair missing links in Closed World KG.

Task: 1-hop Link Prediction
Query: (e1, r1, ?target)
Filter: Select triples that exist in test.txt but NOT in train.txt

Approaches:
1. Standard SPARQL: Direct TDB query (baseline, should return 0%)
2. Pure LLM (Zero-shot): "Who is the {r1} of {e1}?"
3. GenSPARQL: GENOP generates names -> string matching to TDB IDs

Metrics:
- Recall (Hits@1): Did we find the correct answer?
- Grounding Rate: Is the returned result a valid KB ID?
"""

import os
import sys
import json
import random
import argparse
from pathlib import Path
from collections import defaultdict
from typing import Dict, List, Set, Tuple

# Base directories
SCRIPT_DIR = Path(__file__).parent
BENCHMARK_DIR = SCRIPT_DIR.parent.parent
DATA_DIR = BENCHMARK_DIR / "GENSPARQL_Data"


def load_triples(filepath: Path) -> Set[Tuple[str, str, str]]:
    """Load triples from a TSV file."""
    triples = set()
    with open(filepath, 'r', encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith('#'):
                continue
            parts = line.split('\t')
            if len(parts) >= 3:
                head, rel, tail = parts[0], parts[1], parts[2]
                # Skip reverse relations for primary test set
                if not rel.endswith('_reverse'):
                    triples.add((head, rel, tail))
    return triples


def load_all_entities(train_path: Path, test_path: Path) -> Set[str]:
    """Load all unique entities from train and test data."""
    entities = set()
    for filepath in [train_path, test_path]:
        with open(filepath, 'r', encoding='utf-8') as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith('#'):
                    continue
                parts = line.split('\t')
                if len(parts) >= 3:
                    entities.add(parts[0])  # head
                    entities.add(parts[2])  # tail
    return entities


def load_relation_tail_entities(train_path: Path, test_path: Path) -> Dict[str, Set[str]]:
    """Load tail entities grouped by relation (for relation-specific candidates)."""
    relation_tails = defaultdict(set)
    for filepath in [train_path, test_path]:
        with open(filepath, 'r', encoding='utf-8') as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith('#'):
                    continue
                parts = line.split('\t')
                if len(parts) >= 3:
                    rel, tail = parts[1], parts[2]
                    if not rel.endswith('_reverse'):
                        relation_tails[rel].add(tail)
    return dict(relation_tails)


def find_missing_links(train_triples: Set[Tuple[str, str, str]],
                       test_triples: Set[Tuple[str, str, str]]) -> Set[Tuple[str, str, str]]:
    """Find triples in test but not in train (missing links)."""
    return test_triples - train_triples


def relation_to_natural_language(relation: str) -> str:
    """Convert relation URI to natural language question."""
    # Extract relation name from concept:relationName format
    if ':' in relation:
        rel_name = relation.split(':')[1]
    else:
        rel_name = relation

    # Convert camelCase/underscore to words
    import re
    words = re.sub(r'([A-Z])', r' \1', rel_name)
    words = words.replace('_', ' ').lower().strip()

    # Common relation mappings for better natural language
    relation_templates = {
        'teamplaysagainstteam': 'Which team does {entity} play against?',
        'citylocatedingeopoliticallocation': 'What country/region is {entity} located in?',
        'agentcollaborateswithagent': 'Who does {entity} collaborate with?',
        'worksfor': 'What organization does {entity} work for?',
        'organizationheadquarteredincity': 'In which city is {entity} headquartered?',
        'athleteplayssport': 'What sport does {entity} play?',
        'athleteplaysforteam': 'What team does {entity} play for?',
        'teamplaysinleague': 'What league does {entity} play in?',
        'personbelongstoorganization': 'What organization does {entity} belong to?',
        'musicartistgenre': 'What music genre is {entity} associated with?',
        'clothingtogowithclothing': 'What clothing items go well with {entity}?',
        'politicianusendorsedbypoliticianus': 'Who endorsed {entity}?',
        'companyeconomicsector': 'What economic sector is {entity} in?',
        'personhasresidenceingeopoliticallocation': 'Where does {entity} reside?',
    }

    rel_key = rel_name.lower().replace('_', '')
    if rel_key in relation_templates:
        return relation_templates[rel_key]

    # Default template
    return f'What is the {words} of {{entity}}?'


def entity_to_readable(entity: str) -> str:
    """Convert entity ID to readable form."""
    # Remove concept_ prefix and replace underscores
    if entity.startswith('concept_'):
        entity = entity[8:]  # Remove 'concept_'

    # Handle patterns like category_name
    if '_' in entity:
        parts = entity.split('_', 1)
        if len(parts) == 2:
            # Keep category for context, format name
            category, name = parts
            name = name.replace('_', ' ')
            return f"{name} ({category})"

    return entity.replace('_', ' ')


def generate_sparql_query(head: str, relation: str, expected_tail: str) -> str:
    """Generate standard SPARQL query."""
    # Convert to full URIs
    head_uri = f"<http://nell.cs.cmu.edu/entity/{head}>"
    rel_uri = f"<http://nell.cs.cmu.edu/relation/{relation.replace(':', '_')}>"

    query = f"""# Standard SPARQL Query (1-hop Link Prediction)
# Expected Answer: {expected_tail}
# This query should return EMPTY (link is missing from train KG)

SELECT ?target WHERE {{
  {head_uri} {rel_uri} ?target .
}}
"""
    return query


def generate_pure_llm_query(head: str, relation: str, expected_tail: str) -> str:
    """Generate Pure LLM (zero-shot) query."""
    template = relation_to_natural_language(relation)
    readable_entity = entity_to_readable(head)
    question = template.replace('{entity}', readable_entity)

    query = f"""# Pure LLM Query (Zero-shot)
# Expected Answer: {expected_tail}
# No grounding - LLM generates freely

PROMPT: {question}

Return your answer as a JSON array: ["answer1", "answer2", ...]
If you don't know, return [].
"""
    return query


def generate_gensparql_query(head: str, relation: str, expected_tail: str,
                             relation_tails: Dict[str, Set[str]],
                             all_entities: Set[str], max_candidates: int = 500) -> str:
    """Generate GenSPARQL query with entity grounding."""
    # Convert to full URIs
    head_uri = f"<http://nell.cs.cmu.edu/entity/{head}>"
    rel_uri = f"<http://nell.cs.cmu.edu/relation/{relation.replace(':', '_')}>"

    # Create natural language question
    template = relation_to_natural_language(relation)
    readable_entity = entity_to_readable(head)
    question = template.replace('{entity}', readable_entity)

    # Use relation-specific tail entities as candidates (IMPORTANT!)
    # This ensures candidates are valid entity types for this relation
    if relation in relation_tails:
        candidate_list = sorted(list(relation_tails[relation]))
        print(f"    Using {len(candidate_list)} relation-specific candidates for {relation}")
    else:
        # Fallback to all entities
        candidate_list = sorted(list(all_entities))
        print(f"    Fallback: using {len(candidate_list)} total entities")

    # Sample if too many
    if len(candidate_list) > max_candidates:
        # Ensure expected_tail is included
        if expected_tail in candidate_list:
            candidate_list.remove(expected_tail)
        candidates = random.sample(candidate_list, min(max_candidates - 1, len(candidate_list)))
        candidates.append(expected_tail)
        random.shuffle(candidates)
    else:
        candidates = candidate_list
        # Ensure expected_tail is in candidates (for fair evaluation)
        if expected_tail not in candidates:
            candidates.append(expected_tail)

    candidates_str = ', '.join(candidates[:max_candidates])

    query = f"""# GenSPARQL Query (1-hop Link Prediction with Grounding)
# Expected Answer: {expected_tail}
# LLM selects from KG entities, ensuring grounding

SELECT ?target WHERE {{
  GENOP("{question} Choose from this list: [{candidates_str}]. Return JSON array of matching items like ['item1', 'item2']. Return [] if none.",
        (?target),
        <model:openrouter:deepseek/deepseek-chat>)
}}
"""
    return query


def generate_experiment_cases(dataset: str, num_cases: int = 50,
                              max_candidates: int = 300,
                              seed: int = 42) -> Dict:
    """Generate experiment cases for a dataset."""
    random.seed(seed)

    dataset_dir = DATA_DIR / dataset
    train_path = dataset_dir / "train.txt"
    test_path = dataset_dir / "test.txt"

    if not train_path.exists() or not test_path.exists():
        raise FileNotFoundError(f"Dataset files not found in {dataset_dir}")

    print(f"Loading dataset: {dataset}")
    train_triples = load_triples(train_path)
    test_triples = load_triples(test_path)
    all_entities = load_all_entities(train_path, test_path)
    relation_tails = load_relation_tail_entities(train_path, test_path)

    print(f"  Train triples: {len(train_triples)}")
    print(f"  Test triples: {len(test_triples)}")
    print(f"  Total entities: {len(all_entities)}")
    print(f"  Relations with tail entities: {len(relation_tails)}")

    # Find missing links
    missing_links = find_missing_links(train_triples, test_triples)
    print(f"  Missing links (test - train): {len(missing_links)}")

    if len(missing_links) == 0:
        print("  WARNING: No missing links found!")
        return None

    # Sample cases
    sampled_links = random.sample(list(missing_links), min(num_cases, len(missing_links)))

    # Group by relation for analysis
    by_relation = defaultdict(list)
    for head, rel, tail in sampled_links:
        by_relation[rel].append((head, rel, tail))

    print(f"  Relations covered: {len(by_relation)}")
    for rel, triples in sorted(by_relation.items(), key=lambda x: -len(x[1]))[:5]:
        print(f"    {rel}: {len(triples)} cases")

    # Generate queries for each case
    cases = []
    for i, (head, rel, tail) in enumerate(sampled_links):
        print(f"  Generating case {i+1}: ({head}, {rel}, ?)")
        case = {
            'id': i + 1,
            'head': head,
            'relation': rel,
            'expected_tail': tail,
            'sparql_query': generate_sparql_query(head, rel, tail),
            'pure_llm_query': generate_pure_llm_query(head, rel, tail),
            'gensparql_query': generate_gensparql_query(head, rel, tail, relation_tails, all_entities, max_candidates)
        }
        cases.append(case)

    return {
        'dataset': dataset,
        'num_cases': len(cases),
        'num_missing_links': len(missing_links),
        'num_entities': len(all_entities),
        'relations': list(by_relation.keys()),
        'cases': cases
    }


def save_experiment(experiment: Dict, output_dir: Path):
    """Save experiment to files."""
    output_dir.mkdir(parents=True, exist_ok=True)

    # Save metadata
    metadata = {k: v for k, v in experiment.items() if k != 'cases'}
    metadata['cases_count'] = len(experiment['cases'])

    with open(output_dir / 'metadata.json', 'w') as f:
        json.dump(metadata, f, indent=2)

    # Create subdirectories for each approach
    sparql_dir = output_dir / 'sparql_queries'
    llm_dir = output_dir / 'pure_llm_queries'
    gensparql_dir = output_dir / 'gensparql_queries'

    for d in [sparql_dir, llm_dir, gensparql_dir]:
        d.mkdir(exist_ok=True)

    # Save individual query files
    for case in experiment['cases']:
        case_id = case['id']

        # SPARQL
        with open(sparql_dir / f'query_{case_id:03d}.sparql', 'w') as f:
            f.write(case['sparql_query'])

        # Pure LLM
        with open(llm_dir / f'query_{case_id:03d}.txt', 'w') as f:
            f.write(case['pure_llm_query'])

        # GenSPARQL
        with open(gensparql_dir / f'query_{case_id:03d}.sparql', 'w') as f:
            f.write(case['gensparql_query'])

    # Save expected answers for evaluation
    answers = []
    for case in experiment['cases']:
        answers.append({
            'id': case['id'],
            'head': case['head'],
            'relation': case['relation'],
            'expected_tail': case['expected_tail']
        })

    with open(output_dir / 'expected_answers.json', 'w') as f:
        json.dump(answers, f, indent=2)

    print(f"\nExperiment saved to: {output_dir}")
    print(f"  - {len(experiment['cases'])} query cases generated")
    print(f"  - SPARQL queries: {sparql_dir}")
    print(f"  - Pure LLM queries: {llm_dir}")
    print(f"  - GenSPARQL queries: {gensparql_dir}")


def main():
    parser = argparse.ArgumentParser(description='Generate Missing Link Completion Experiment')
    parser.add_argument('--dataset', type=str, default='NELL995+H',
                        help='Dataset name (default: NELL995+H)')
    parser.add_argument('--num-cases', type=int, default=50,
                        help='Number of test cases to generate (default: 50)')
    parser.add_argument('--max-candidates', type=int, default=300,
                        help='Max candidates for GenSPARQL (default: 300)')
    parser.add_argument('--seed', type=int, default=42,
                        help='Random seed (default: 42)')
    parser.add_argument('--output-dir', type=str, default=None,
                        help='Output directory (default: auto)')

    args = parser.parse_args()

    print("=" * 60)
    print("Missing Link Completion Experiment Generator")
    print("=" * 60)

    # Generate experiment
    experiment = generate_experiment_cases(
        args.dataset,
        num_cases=args.num_cases,
        max_candidates=args.max_candidates,
        seed=args.seed
    )

    if experiment is None:
        print("ERROR: Could not generate experiment")
        sys.exit(1)

    # Save experiment
    output_dir = Path(args.output_dir) if args.output_dir else SCRIPT_DIR / args.dataset
    save_experiment(experiment, output_dir)

    print("\n" + "=" * 60)
    print("Experiment generated successfully!")
    print("=" * 60)


if __name__ == '__main__':
    main()
