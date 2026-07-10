#!/usr/bin/env python3
"""
Generate run.sh scripts for each GenSPARQL query.
Each script will execute the corresponding query1.sparql file.
"""

import os
from pathlib import Path

# Base paths
BASE_DIR = Path(__file__).parent
PROJECT_ROOT = BASE_DIR.parent

# Script template
SCRIPT_TEMPLATE = '''#!/bin/bash
# Auto-generated run script for {query_type} {pattern}
# Dataset: {dataset}

# Get the directory where this script is located
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# Navigate up 4 levels: pattern_XX -> query_type -> Queries-Dataset -> gensparql-benchmark -> project_root
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../../.." && pwd)"

cd "$PROJECT_ROOT"

# Set OpenRouter API Key (if not already set)
if [ -z "$OPENROUTER_API_KEY" ]; then
    export OPENROUTER_API_KEY=YOUR_OPENROUTER_API_KEY
fi

# Ensure project is compiled
echo "Compiling project..."
mvn compile -DskipTests -q

# Build classpath using Maven
echo "Building classpath..."
mvn dependency:build-classpath -Dmdep.outputFile=/tmp/classpath.txt -q

CLASSPATH=""
if [ -f /tmp/classpath.txt ]; then
    CLASSPATH="$(cat /tmp/classpath.txt)"
fi

# Add compiled classes
CLASSPATH="$PROJECT_ROOT/gensparql-core/target/classes:$CLASSPATH"
CLASSPATH="$PROJECT_ROOT/gensparql-llm/target/classes:$CLASSPATH"
CLASSPATH="$PROJECT_ROOT/gensparql-parser/target/classes:$CLASSPATH"
CLASSPATH="$PROJECT_ROOT/gensparql-engine/target/classes:$CLASSPATH"
CLASSPATH="$PROJECT_ROOT/gensparql-functions/target/classes:$CLASSPATH"
CLASSPATH="$PROJECT_ROOT/gensparql-example/target/classes:$CLASSPATH"

# Data file and query file
DATA_FILE="$PROJECT_ROOT/gensparql-benchmark/GENSPARQL_Data/{dataset}/{data_file}"
QUERY_FILE="$SCRIPT_DIR/query1.sparql"

echo "=========================================="
echo "Dataset: {dataset}"
echo "Query Type: {query_type}"
echo "Pattern: {pattern}"
echo "=========================================="

# Run the query
java -cp "$CLASSPATH" org.gensparql.example.GenSPARQLExample "$DATA_FILE" "$QUERY_FILE"
'''


def generate_run_scripts():
    """Generate run.sh scripts for all query folders."""
    datasets = [
        ("Queries-FB15k-237+H", "FB15k-237+H", "train.nt"),
        ("Queries-NELL995+H", "NELL995+H", "train.nt"),
    ]

    count = 0

    for queries_dir, dataset, data_file in datasets:
        queries_path = BASE_DIR / queries_dir

        if not queries_path.exists():
            print(f"Warning: {queries_path} does not exist, skipping...")
            continue

        # Iterate through query types (2p, 3p, 4p, 2i, 3i, pi, ip, up)
        for query_type_dir in queries_path.iterdir():
            if not query_type_dir.is_dir():
                continue

            query_type = query_type_dir.name

            # Iterate through patterns
            for pattern_dir in query_type_dir.iterdir():
                if not pattern_dir.is_dir() or not pattern_dir.name.startswith('pattern_'):
                    continue

                pattern = pattern_dir.name

                # Check if query1.sparql exists
                query_file = pattern_dir / 'query1.sparql'
                if not query_file.exists():
                    continue

                # Generate run.sh
                run_script = pattern_dir / 'run.sh'
                script_content = SCRIPT_TEMPLATE.format(
                    query_type=query_type,
                    pattern=pattern,
                    dataset=dataset,
                    data_file=data_file
                )

                with open(run_script, 'w') as f:
                    f.write(script_content)

                # Make executable
                os.chmod(run_script, 0o755)

                count += 1
                print(f"Generated: {run_script}")

    return count


def main():
    print("=" * 60)
    print("Generating run.sh scripts for each query")
    print("=" * 60)

    count = generate_run_scripts()

    print("\n" + "=" * 60)
    print(f"Generated {count} run scripts")
    print("=" * 60)


if __name__ == "__main__":
    main()
