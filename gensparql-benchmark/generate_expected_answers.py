#!/usr/bin/env python3
"""
Generate expected_answers.json files for each query directory.
This extracts the expected answers from the queries_readable.json files.
"""

import os
import json
from pathlib import Path

# Base paths
BASE_DIR = Path(__file__).parent
DATA_DIR = BASE_DIR / "GENSPARQL_Data"


def generate_expected_answers():
    """Generate expected_answers.json for all query folders."""
    datasets = [
        ("FB15k-237+H", "Queries-FB15k-237+H"),
        ("NELL995+H", "Queries-NELL995+H"),
    ]

    count = 0

    for dataset_name, queries_dir_name in datasets:
        data_dir = DATA_DIR / dataset_name
        queries_dir = BASE_DIR / queries_dir_name

        if not data_dir.exists():
            print(f"Warning: {data_dir} does not exist, skipping...")
            continue

        if not queries_dir.exists():
            print(f"Warning: {queries_dir} does not exist, skipping...")
            continue

        # Iterate through query types
        for query_type in ['2p', '3p', '4p', '2i', '3i', 'ip', 'pi', 'up']:
            query_type_dir = queries_dir / query_type
            data_type_dir = data_dir / query_type
            
            if not query_type_dir.exists() or not data_type_dir.exists():
                continue

            # Iterate through patterns
            for pattern_dir in query_type_dir.iterdir():
                if not pattern_dir.is_dir() or not pattern_dir.name.startswith('pattern_'):
                    continue
                
                pattern = pattern_dir.name
                
                # Find corresponding queries_readable.json in data directory
                json_path = data_type_dir / pattern / 'queries_readable.json'
                
                if not json_path.exists():
                    print(f"Warning: {json_path} does not exist, skipping...")
                    continue
                
                with open(json_path, 'r', encoding='utf-8') as f:
                    data = json.load(f)
                
                # Get the first query's answers (query1.sparql corresponds to queries[0])
                queries = data.get('queries', [])
                if not queries:
                    print(f"Warning: No queries in {json_path}")
                    continue
                
                first_query = queries[0]
                
                # Extract answers
                answers = first_query.get('answers', [])
                answers_readable = first_query.get('answers_readable', [])
                num_answers = first_query.get('num_answers', len(answers))
                
                # Create expected_answers.json in the query directory
                expected_data = {
                    'num_answers': num_answers,
                    'answers': answers,
                    'answers_readable': answers_readable if answers_readable else [],
                    'query_readable': first_query.get('query_readable', ''),
                    'query_readable_with_names': first_query.get('query_readable_with_names', '')
                }
                
                output_file = pattern_dir / 'expected_answers.json'
                with open(output_file, 'w', encoding='utf-8') as f:
                    json.dump(expected_data, f, ensure_ascii=False, indent=2)
                
                count += 1
                print(f"Generated: {output_file}")
    
    return count


def main():
    print("=" * 60)
    print("Generating expected_answers.json files")
    print("=" * 60)

    count = generate_expected_answers()

    print("\n" + "=" * 60)
    print(f"Generated {count} expected_answers.json files")
    print("=" * 60)


if __name__ == "__main__":
    main()
