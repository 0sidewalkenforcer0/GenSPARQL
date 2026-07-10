#!/bin/bash
# Sample Evaluation Script - Quick test with 1-2 patterns per query type, 2 queries each
# Estimated time: ~1 hour

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

# Configuration
DATASETS=${DATASETS:-"NELL995+H FB15k-237+H"}
MAX_QUERIES=${MAX_QUERIES:-2}
GROUNDING=${GROUNDING:-"true"}
MODEL=${MODEL:-"google/gemma-3-12b-it:free"}
GROUNDING_THRESHOLD=${GROUNDING_THRESHOLD:-0.4}

# Set OpenRouter API Key
if [ -z "$OPENROUTER_API_KEY" ]; then
    export OPENROUTER_API_KEY=YOUR_OPENROUTER_API_KEY
fi

# Sample patterns for each query type (first pattern with mostly BGP, first with mostly GENOP)
# Format: "query_type:pattern1,pattern2"
SAMPLES=(
    "2p:01,10"
    "2i:01,10"
    "3p:001,100"
    "3i:001,100"
    "4p:0001,1000"
    "pi:001,100"
    "ip:001,100"
    "up:001,100"
)

# Results directory
RESULTS_DIR="$SCRIPT_DIR/results"
mkdir -p "$RESULTS_DIR"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
RESULTS_FILE="$RESULTS_DIR/sample_results_${TIMESTAMP}.txt"
SUMMARY_CSV="$RESULTS_DIR/sample_summary_${TIMESTAMP}.csv"

# Build project
echo "============================================"
echo "Building project..."
echo "============================================"
cd "$PROJECT_ROOT"
mvn compile -q -DskipTests 2>/dev/null || mvn compile -DskipTests

# Get classpath
DEP_CP=$(mvn -pl gensparql-example -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout 2>/dev/null)
DEP_CP=$(echo "$DEP_CP" | tr ':' '\n' | grep -v "gensparql" | tr '\n' ':')
CP="$PROJECT_ROOT/gensparql-core/target/classes"
CP="$CP:$PROJECT_ROOT/gensparql-llm/target/classes"
CP="$CP:$PROJECT_ROOT/gensparql-parser/target/classes"
CP="$CP:$PROJECT_ROOT/gensparql-engine/target/classes"
CP="$CP:$PROJECT_ROOT/gensparql-example/target/classes"
CP="$CP:$DEP_CP"

# CSV header
echo "dataset,query_type,pattern,num_queries,hits1,hits10,mrr" > "$SUMMARY_CSV"

echo "" | tee "$RESULTS_FILE"
echo "============================================" | tee -a "$RESULTS_FILE"
echo "GenSPARQL Sample Evaluation" | tee -a "$RESULTS_FILE"
echo "============================================" | tee -a "$RESULTS_FILE"
echo "Datasets:     $DATASETS" | tee -a "$RESULTS_FILE"
echo "Max queries:  $MAX_QUERIES per pattern" | tee -a "$RESULTS_FILE"
echo "Grounding:    $GROUNDING (threshold=$GROUNDING_THRESHOLD)" | tee -a "$RESULTS_FILE"
echo "Model:        $MODEL" | tee -a "$RESULTS_FILE"
echo "Started at:   $(date)" | tee -a "$RESULTS_FILE"
echo "============================================" | tee -a "$RESULTS_FILE"

TOTAL=0
for DATASET in $DATASETS; do
    DATA_DIR="$PROJECT_ROOT/gensparql-benchmark/GENSPARQL_Data/$DATASET"
    DATA_FILE="$DATA_DIR/train.nt"

    if [ ! -f "$DATA_FILE" ]; then
        echo "WARNING: Data file not found: $DATA_FILE, skipping $DATASET" | tee -a "$RESULTS_FILE"
        continue
    fi

    echo "" | tee -a "$RESULTS_FILE"
    echo "########################################" | tee -a "$RESULTS_FILE"
    echo "# Dataset: $DATASET" | tee -a "$RESULTS_FILE"
    echo "########################################" | tee -a "$RESULTS_FILE"

    for sample in "${SAMPLES[@]}"; do
        IFS=':' read -r qt patterns <<< "$sample"
        IFS=',' read -ra pattern_list <<< "$patterns"

        for pattern in "${pattern_list[@]}"; do
            PATTERN_DIR="$DATA_DIR/$qt/pattern_$pattern"
            QUERIES_JSON="$PATTERN_DIR/queries_readable.json"

            if [ ! -f "$QUERIES_JSON" ]; then
                echo "  Skipping $qt/pattern_$pattern - not found" | tee -a "$RESULTS_FILE"
                continue
            fi

            TOTAL=$((TOTAL + 1))
            echo "" | tee -a "$RESULTS_FILE"
            echo "[$TOTAL] Running $DATASET/$qt/pattern_$pattern ($MAX_QUERIES queries)..." | tee -a "$RESULTS_FILE"

            START_TIME=$(date +%s)

            OUTPUT=$(java -cp "$CP" \
                -Dgensparql.grounding.enabled=$GROUNDING \
                -Dgensparql.grounding.threshold=$GROUNDING_THRESHOLD \
                -Dgensparql.model="$MODEL" \
                org.gensparql.example.QuantitativeEvaluator \
                "$DATA_FILE" \
                "$QUERIES_JSON" \
                "$MAX_QUERIES" \
                "$GROUNDING" 2>&1) || true

            END_TIME=$(date +%s)
            ELAPSED=$((END_TIME - START_TIME))

            # Show only key lines (not all debug output)
            echo "$OUTPUT" | grep -E "(Hits@|MRR|Queries evaluated|Results:|GROUNDING.*SUCCESS|GROUNDING.*FAILED)" | tee -a "$RESULTS_FILE"
            echo "  Time: ${ELAPSED}s" | tee -a "$RESULTS_FILE"

            # Extract metrics
            hits1=$(echo "$OUTPUT" | grep "Hits@1:" | tail -1 | grep -oP '[\d.]+(?=\s*\()' || echo "0")
            hits10=$(echo "$OUTPUT" | grep "Hits@10:" | tail -1 | grep -oP '[\d.]+(?=\s*\()' || echo "0")
            mrr=$(echo "$OUTPUT" | grep "MRR:" | tail -1 | awk '{print $NF}' || echo "0")
            num_q=$(echo "$OUTPUT" | grep "Queries evaluated:" | tail -1 | awk '{print $NF}' || echo "0")

            echo "$DATASET,$qt,$pattern,$num_q,$hits1,$hits10,$mrr" >> "$SUMMARY_CSV"
        done
    done
done

echo "" | tee -a "$RESULTS_FILE"
echo "============================================" | tee -a "$RESULTS_FILE"
echo "Evaluation completed at: $(date)" | tee -a "$RESULTS_FILE"
echo "Total runs: $TOTAL" | tee -a "$RESULTS_FILE"
echo "============================================" | tee -a "$RESULTS_FILE"

# Summary table
echo "" | tee -a "$RESULTS_FILE"
echo "SUMMARY TABLE" | tee -a "$RESULTS_FILE"
echo "============================================" | tee -a "$RESULTS_FILE"
printf "%-12s %-6s %-8s %-4s %-8s %-8s %-8s\n" "Dataset" "Type" "Pattern" "N" "Hits@1" "Hits@10" "MRR" | tee -a "$RESULTS_FILE"
echo "------------------------------------------------------------" | tee -a "$RESULTS_FILE"
tail -n +2 "$SUMMARY_CSV" | while IFS=',' read -r ds qt pat nq h1 h10 m; do
    printf "%-12s %-6s %-8s %-4s %-8s %-8s %-8s\n" "$ds" "$qt" "$pat" "$nq" "$h1" "$h10" "$m" | tee -a "$RESULTS_FILE"
done
echo "============================================" | tee -a "$RESULTS_FILE"

echo ""
echo "Results: $RESULTS_FILE"
echo "Summary: $SUMMARY_CSV"
