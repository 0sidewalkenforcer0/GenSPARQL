#!/bin/bash
# Batch benchmark script for 2i and 3i queries on FB15k-237+H dataset

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

cd "$PROJECT_ROOT"

# Set OpenRouter API Key
if [ -z "$OPENROUTER_API_KEY" ]; then
    export OPENROUTER_API_KEY=YOUR_OPENROUTER_API_KEY
fi

# Create results directory
RESULTS_DIR="$SCRIPT_DIR/benchmark_results_2i_3i_$(date +%Y%m%d_%H%M%S)"
mkdir -p "$RESULTS_DIR"

# Compile project
echo "=========================================="
echo "Compiling project..."
echo "=========================================="
mvn compile -DskipTests -q

# Summary file
SUMMARY_FILE="$RESULTS_DIR/summary.txt"
CSV_FILE="$RESULTS_DIR/metrics.csv"

echo "2i and 3i Benchmark Results" > "$SUMMARY_FILE"
echo "Date: $(date)" >> "$SUMMARY_FILE"
echo "Dataset: FB15k-237+H" >> "$SUMMARY_FILE"
echo "==========================================" >> "$SUMMARY_FILE"
echo "" >> "$SUMMARY_FILE"

# CSV Header
echo "query_type,pattern,expected,actual,tp,fp,fn,precision,recall,f1,time_ms" > "$CSV_FILE"

# Function to run a single query
run_query() {
    local query_type=$1
    local pattern=$2
    local run_script="$SCRIPT_DIR/Queries-FB15k-237+H/${query_type}/${pattern}/run.sh"
    local output_file="$RESULTS_DIR/${query_type}_${pattern}.log"

    echo ""
    echo "Running: $query_type / $pattern"

    if [ ! -f "$run_script" ]; then
        echo "  SKIPPED: run.sh not found"
        echo "$query_type,$pattern,0,0,0,0,0,0,0,0,0" >> "$CSV_FILE"
        return
    fi

    # Run query and capture output
    bash "$run_script" > "$output_file" 2>&1 || true

    # Extract metrics from output
    local expected=$(grep "Expected answers:" "$output_file" | awk '{print $3}' | head -1)
    local actual=$(grep "Actual answers:" "$output_file" | awk '{print $3}' | head -1)
    local tp=$(grep "True Positives:" "$output_file" | awk '{print $3}' | head -1)
    local fp=$(grep "False Positives:" "$output_file" | awk '{print $3}' | head -1)
    local fn=$(grep "False Negatives:" "$output_file" | awk '{print $3}' | head -1)
    local precision=$(grep "Precision:" "$output_file" | awk '{print $2}' | head -1)
    local recall=$(grep "Recall:" "$output_file" | awk '{print $2}' | head -1)
    local f1=$(grep "F1 Score:" "$output_file" | awk '{print $3}' | head -1)
    local time_ms=$(grep "Execution time:" "$output_file" | awk '{print $3}' | head -1)

    # Default values if extraction failed
    expected=${expected:-0}
    actual=${actual:-0}
    tp=${tp:-0}
    fp=${fp:-0}
    fn=${fn:-0}
    precision=${precision:-0.0000}
    recall=${recall:-0.0000}
    f1=${f1:-0.0000}
    time_ms=${time_ms:-0}

    # Append to CSV
    echo "$query_type,$pattern,$expected,$actual,$tp,$fp,$fn,$precision,$recall,$f1,$time_ms" >> "$CSV_FILE"

    echo "  Expected: $expected, Actual: $actual, TP: $tp, Precision: $precision, Recall: $recall, F1: $f1"
}

echo ""
echo "=========================================="
echo "Testing 2i queries..."
echo "=========================================="
echo "" >> "$SUMMARY_FILE"
echo "=== 2i Queries ===" >> "$SUMMARY_FILE"

for pattern in pattern_01 pattern_10 pattern_11; do
    run_query "2i" "$pattern"
done

echo ""
echo "=========================================="
echo "Testing 3i queries..."
echo "=========================================="
echo "" >> "$SUMMARY_FILE"
echo "=== 3i Queries ===" >> "$SUMMARY_FILE"

for pattern in pattern_001 pattern_010 pattern_011 pattern_100 pattern_101 pattern_110 pattern_111; do
    run_query "3i" "$pattern"
done

echo ""
echo "=========================================="
echo "Benchmark Complete!"
echo "=========================================="

# Generate summary statistics
echo "" >> "$SUMMARY_FILE"
echo "=== Overall Statistics ===" >> "$SUMMARY_FILE"
echo "" >> "$SUMMARY_FILE"

# Calculate averages using awk
echo "Calculating average metrics..."
awk -F',' 'NR>1 {
    query_type=$1
    if (query_type == "2i") {
        sum_p_2i += $8
        sum_r_2i += $9
        sum_f1_2i += $10
        count_2i++
    } else if (query_type == "3i") {
        sum_p_3i += $8
        sum_r_3i += $9
        sum_f1_3i += $10
        count_3i++
    }
}
END {
    if (count_2i > 0) {
        printf "2i Average - Precision: %.4f, Recall: %.4f, F1: %.4f (n=%d)\n", sum_p_2i/count_2i, sum_r_2i/count_2i, sum_f1_2i/count_2i, count_2i
    }
    if (count_3i > 0) {
        printf "3i Average - Precision: %.4f, Recall: %.4f, F1: %.4f (n=%d)\n", sum_p_3i/count_3i, sum_r_3i/count_3i, sum_f1_3i/count_3i, count_3i
    }
}' "$CSV_FILE" | tee -a "$SUMMARY_FILE"

echo ""
echo "Results saved to: $RESULTS_DIR"
echo "  - Individual logs: ${query_type}_${pattern}.log"
echo "  - Metrics CSV: metrics.csv"
echo "  - Summary: summary.txt"

# Print CSV contents
echo ""
echo "=== Results Table ==="
column -t -s',' "$CSV_FILE"
