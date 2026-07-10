#!/bin/bash
# Master Evaluation Script - Run all query types x patterns x 10 queries
# for both NELL995+H and FB15k-237+H datasets
#
# Usage:
#   bash run_all_eval.sh
#   DATASETS="NELL995+H" bash run_all_eval.sh        # single dataset
#   MAX_QUERIES=5 bash run_all_eval.sh                # fewer queries per pattern

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

# Configuration
DATASETS=${DATASETS:-"NELL995+H FB15k-237+H"}
MAX_QUERIES=${MAX_QUERIES:-10}
GROUNDING=${GROUNDING:-"true"}
MODEL=${MODEL:-"google/gemma-3-12b-it:free"}
GROUNDING_THRESHOLD=${GROUNDING_THRESHOLD:-0.4}

# Set OpenRouter API Key
if [ -z "$OPENROUTER_API_KEY" ]; then
    export OPENROUTER_API_KEY=YOUR_OPENROUTER_API_KEY
fi

# Results directory
RESULTS_DIR="$SCRIPT_DIR/results"
mkdir -p "$RESULTS_DIR"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
MASTER_RESULTS="$RESULTS_DIR/master_results_${TIMESTAMP}.txt"
SUMMARY_CSV="$RESULTS_DIR/summary_${TIMESTAMP}.csv"

# Build project first
echo "============================================"
echo "Building project..."
echo "============================================"
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

# CSV header
echo "dataset,query_type,pattern,num_queries,hits1,hits10,mrr" > "$SUMMARY_CSV"

# Counters
TOTAL_COMBOS=0
COMPLETED=0
FAILED=0

# Count total combinations first
for DATASET in $DATASETS; do
    DATA_DIR="$PROJECT_ROOT/gensparql-benchmark/GENSPARQL_Data/$DATASET"
    for qt_dir in "$DATA_DIR"/*/; do
        qt=$(basename "$qt_dir")
        for pattern_dir in "$qt_dir"pattern_*/; do
            if [ -f "$pattern_dir/queries_readable.json" ]; then
                TOTAL_COMBOS=$((TOTAL_COMBOS + 1))
            fi
        done
    done
done

echo "" | tee "$MASTER_RESULTS"
echo "============================================" | tee -a "$MASTER_RESULTS"
echo "GenSPARQL Master Evaluation" | tee -a "$MASTER_RESULTS"
echo "============================================" | tee -a "$MASTER_RESULTS"
echo "Datasets:     $DATASETS" | tee -a "$MASTER_RESULTS"
echo "Max queries:  $MAX_QUERIES per pattern" | tee -a "$MASTER_RESULTS"
echo "Grounding:    $GROUNDING" | tee -a "$MASTER_RESULTS"
echo "Model:        $MODEL" | tee -a "$MASTER_RESULTS"
echo "Total combos: $TOTAL_COMBOS" | tee -a "$MASTER_RESULTS"
echo "Started at:   $(date)" | tee -a "$MASTER_RESULTS"
echo "============================================" | tee -a "$MASTER_RESULTS"
echo "" | tee -a "$MASTER_RESULTS"

for DATASET in $DATASETS; do
    DATA_DIR="$PROJECT_ROOT/gensparql-benchmark/GENSPARQL_Data/$DATASET"
    DATA_FILE="$DATA_DIR/train.nt"

    if [ ! -f "$DATA_FILE" ]; then
        echo "WARNING: Data file not found: $DATA_FILE, skipping $DATASET" | tee -a "$MASTER_RESULTS"
        continue
    fi

    echo "" | tee -a "$MASTER_RESULTS"
    echo "########################################" | tee -a "$MASTER_RESULTS"
    echo "# Dataset: $DATASET" | tee -a "$MASTER_RESULTS"
    echo "########################################" | tee -a "$MASTER_RESULTS"

    # Process query types in order
    for qt in 2p 2i 3p 3i 4p pi ip up; do
        QT_DIR="$DATA_DIR/$qt"
        if [ ! -d "$QT_DIR" ]; then
            continue
        fi

        echo "" | tee -a "$MASTER_RESULTS"
        echo "======================================" | tee -a "$MASTER_RESULTS"
        echo "Query Type: $qt ($DATASET)" | tee -a "$MASTER_RESULTS"
        echo "======================================" | tee -a "$MASTER_RESULTS"

        for pattern_dir in "$QT_DIR"/pattern_*; do
            if [ ! -d "$pattern_dir" ]; then
                continue
            fi

            pattern_name=$(basename "$pattern_dir" | sed 's/pattern_//')
            queries_json="$pattern_dir/queries_readable.json"

            if [ ! -f "$queries_json" ]; then
                echo "  Skipping $qt/pattern_$pattern_name - no queries_readable.json" | tee -a "$MASTER_RESULTS"
                continue
            fi

            COMPLETED=$((COMPLETED + 1))
            echo "" | tee -a "$MASTER_RESULTS"
            echo "[$COMPLETED/$TOTAL_COMBOS] Running $DATASET/$qt/pattern_$pattern_name (${MAX_QUERIES} queries)..." | tee -a "$MASTER_RESULTS"

            # Run evaluation and capture output
            OUTPUT=$(java -cp "$CP" \
                -Dgensparql.grounding.enabled=$GROUNDING \
                -Dgensparql.grounding.threshold=$GROUNDING_THRESHOLD \
                -Dgensparql.model="$MODEL" \
                org.gensparql.example.QuantitativeEvaluator \
                "$DATA_FILE" \
                "$queries_json" \
                "$MAX_QUERIES" \
                "$GROUNDING" 2>&1) || true

            echo "$OUTPUT" | tee -a "$MASTER_RESULTS"

            # Extract metrics from output and append to CSV
            hits1=$(echo "$OUTPUT" | grep "Hits@1:" | tail -1 | grep -oP '[\d.]+(?=\s*\()' || echo "0")
            hits10=$(echo "$OUTPUT" | grep "Hits@10:" | tail -1 | grep -oP '[\d.]+(?=\s*\()' || echo "0")
            mrr=$(echo "$OUTPUT" | grep "MRR:" | tail -1 | awk '{print $NF}' || echo "0")
            num_q=$(echo "$OUTPUT" | grep "Queries evaluated:" | tail -1 | awk '{print $NF}' || echo "0")

            echo "$DATASET,$qt,$pattern_name,$num_q,$hits1,$hits10,$mrr" >> "$SUMMARY_CSV"

        done
    done
done

echo "" | tee -a "$MASTER_RESULTS"
echo "============================================" | tee -a "$MASTER_RESULTS"
echo "Evaluation completed at: $(date)" | tee -a "$MASTER_RESULTS"
echo "Total combinations: $TOTAL_COMBOS" | tee -a "$MASTER_RESULTS"
echo "Results: $MASTER_RESULTS" | tee -a "$MASTER_RESULTS"
echo "Summary CSV: $SUMMARY_CSV" | tee -a "$MASTER_RESULTS"
echo "============================================" | tee -a "$MASTER_RESULTS"

# Print summary table
echo "" | tee -a "$MASTER_RESULTS"
echo "============================================" | tee -a "$MASTER_RESULTS"
echo "SUMMARY TABLE" | tee -a "$MASTER_RESULTS"
echo "============================================" | tee -a "$MASTER_RESULTS"
printf "%-12s %-6s %-8s %-6s %-8s %-8s %-8s\n" "Dataset" "Type" "Pattern" "N" "Hits@1" "Hits@10" "MRR" | tee -a "$MASTER_RESULTS"
echo "------------------------------------------------------------" | tee -a "$MASTER_RESULTS"
tail -n +2 "$SUMMARY_CSV" | while IFS=',' read -r ds qt pat nq h1 h10 m; do
    printf "%-12s %-6s %-8s %-6s %-8s %-8s %-8s\n" "$ds" "$qt" "$pat" "$nq" "$h1" "$h10" "$m" | tee -a "$MASTER_RESULTS"
done
echo "============================================" | tee -a "$MASTER_RESULTS"
