#!/bin/bash
# Quantitative Evaluation Script for GenSPARQL Benchmark
#
# Evaluates queries where:
# - Pattern digit 0 = data in train (use BGP)
# - Pattern digit 1 = data NOT in train (use GENOP/LLM)
#
# Usage:
#   ./run_quantitative_eval.sh
#   DATASET=NELL995+H QUERY_TYPE=2p PATTERN=01 MAX_QUERIES=10 ./run_quantitative_eval.sh

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

# Configuration with defaults
DATASET=${DATASET:-"NELL995+H"}
QUERY_TYPE=${QUERY_TYPE:-"2p"}
PATTERN=${PATTERN:-"all"}
MAX_QUERIES=${MAX_QUERIES:-10}
GROUNDING=${GROUNDING:-"true"}
MODEL=${MODEL:-"deepseek/deepseek-r1-0528:free"}
GROUNDING_THRESHOLD=${GROUNDING_THRESHOLD:-0.4}

# Set OpenRouter API Key
if [ -z "$OPENROUTER_API_KEY" ]; then
    export OPENROUTER_API_KEY=YOUR_OPENROUTER_API_KEY
fi

# Paths
DATA_DIR="$PROJECT_ROOT/gensparql-benchmark/GENSPARQL_Data/$DATASET"
DATA_FILE="$DATA_DIR/train.nt"
RESULTS_DIR="$SCRIPT_DIR/results"

echo "============================================"
echo "GenSPARQL Quantitative Evaluation"
echo "============================================"
echo "Dataset:    $DATASET"
echo "Query Type: $QUERY_TYPE"
echo "Pattern:    $PATTERN"
echo "Max Queries: $MAX_QUERIES"
echo "Grounding:  $GROUNDING"
echo "Model:      $MODEL"
echo "============================================"
echo ""

# Verify data file exists
if [ ! -f "$DATA_FILE" ]; then
    echo "Error: Data file not found: $DATA_FILE"
    exit 1
fi

# Build project
echo "Building project..."
cd "$PROJECT_ROOT"
mvn compile -q -DskipTests 2>/dev/null || mvn compile -DskipTests

# Get classpath
echo "Getting classpath..."
DEP_CP=$(mvn -pl gensparql-example -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout 2>/dev/null)
DEP_CP=$(echo "$DEP_CP" | tr ':' '\n' | grep -v "gensparql" | tr '\n' ':')
CP="$PROJECT_ROOT/gensparql-core/target/classes"
CP="$CP:$PROJECT_ROOT/gensparql-llm/target/classes"
CP="$CP:$PROJECT_ROOT/gensparql-parser/target/classes"
CP="$CP:$PROJECT_ROOT/gensparql-engine/target/classes"
CP="$CP:$PROJECT_ROOT/gensparql-example/target/classes"
CP="$CP:$DEP_CP"

# Create results directory
mkdir -p "$RESULTS_DIR"

# Results file
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
RESULTS_FILE="$RESULTS_DIR/${DATASET}_${QUERY_TYPE}_${PATTERN}_${TIMESTAMP}.txt"

# Function to run evaluation for a specific pattern
run_pattern_eval() {
    local pattern_dir="$1"
    local pattern_name=$(basename "$pattern_dir")
    local queries_json="$pattern_dir/queries_readable.json"

    if [ ! -f "$queries_json" ]; then
        echo "Skipping $pattern_name - no queries_readable.json found"
        return
    fi

    echo ""
    echo "========================================"
    echo "Evaluating: $QUERY_TYPE / $pattern_name"
    echo "========================================"

    java -cp "$CP" \
        -Dgensparql.grounding.enabled=$GROUNDING \
        -Dgensparql.grounding.threshold=$GROUNDING_THRESHOLD \
        org.gensparql.example.QuantitativeEvaluator \
        "$DATA_FILE" \
        "$queries_json" \
        "$MAX_QUERIES" \
        "$GROUNDING" 2>&1 | tee -a "$RESULTS_FILE"
}

# Run evaluation
echo "" | tee "$RESULTS_FILE"
echo "Evaluation started at: $(date)" | tee -a "$RESULTS_FILE"
echo "" | tee -a "$RESULTS_FILE"

if [ "$PATTERN" = "all" ]; then
    # Run for all patterns of the specified query type
    PATTERN_DIR="$DATA_DIR/$QUERY_TYPE"

    if [ ! -d "$PATTERN_DIR" ]; then
        echo "Error: Query type directory not found: $PATTERN_DIR"
        exit 1
    fi

    for pattern_path in "$PATTERN_DIR"/pattern_*; do
        if [ -d "$pattern_path" ]; then
            run_pattern_eval "$pattern_path"
        fi
    done
else
    # Run for specific pattern
    PATTERN_PATH="$DATA_DIR/$QUERY_TYPE/pattern_$PATTERN"

    if [ ! -d "$PATTERN_PATH" ]; then
        echo "Error: Pattern directory not found: $PATTERN_PATH"
        exit 1
    fi

    run_pattern_eval "$PATTERN_PATH"
fi

echo ""
echo "========================================"
echo "Evaluation completed at: $(date)"
echo "Results saved to: $RESULTS_FILE"
echo "========================================"
