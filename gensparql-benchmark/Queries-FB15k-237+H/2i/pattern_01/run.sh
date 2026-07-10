#!/bin/bash
# Auto-generated run script for 2i pattern_01
# Dataset: FB15k-237+H

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
DATA_FILE="$PROJECT_ROOT/gensparql-benchmark/GENSPARQL_Data/FB15k-237+H/train.nt"
QUERY_FILE="$SCRIPT_DIR/query1.sparql"

echo "=========================================="
echo "Dataset: FB15k-237+H"
echo "Query Type: 2i"
echo "Pattern: pattern_01"
echo "=========================================="

# Run the query
java -cp "$CLASSPATH" org.gensparql.example.GenSPARQLExample "$DATA_FILE" "$QUERY_FILE"
