#!/bin/bash
# Batch test script for 2i and 3i queries on FB15k-237+H dataset

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

cd "$PROJECT_ROOT"

# Set OpenRouter API Key
if [ -z "$OPENROUTER_API_KEY" ]; then
    export OPENROUTER_API_KEY=YOUR_OPENROUTER_API_KEY
fi

# Create results directory
RESULTS_DIR="$SCRIPT_DIR/benchmark_results_2i_3i"
mkdir -p "$RESULTS_DIR"

# Compile project
echo "=========================================="
echo "Compiling project..."
echo "=========================================="
mvn compile -DskipTests -q

# Build classpath
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

# Data file
DATA_FILE="$PROJECT_ROOT/gensparql-benchmark/GENSPARQL_Data/FB15k-237+H/train.nt"

# Summary file
SUMMARY_FILE="$RESULTS_DIR/summary.txt"
echo "2i and 3i Benchmark Results" > "$SUMMARY_FILE"
echo "Date: $(date)" >> "$SUMMARY_FILE"
echo "Dataset: FB15k-237+H" >> "$SUMMARY_FILE"
echo "==========================================" >> "$SUMMARY_FILE"
echo "" >> "$SUMMARY_FILE"

# Function to run a single query and extract metrics
run_query() {
    local query_type=$1
    local pattern=$2
    local query_file="$SCRIPT_DIR/Queries-FB15k-237+H/${query_type}/${pattern}/query1.sparql"
    local output_file="$RESULTS_DIR/${query_type}_${pattern}.log"

    echo "Running: $query_type / $pattern"
    echo "  Query file: $query_file"

    if [ ! -f "$query_file" ]; then
        echo "  SKIPPED: Query file not found"
        return
    fi

    # Run query and capture output
    java -cp "$CLASSPATH" org.gensparql.example.GenSPARQLExample "$DATA_FILE" "$query_file" 2>&1 | tee "$output_file"

    # Extract metrics from output
    local precision=$(grep "Precision:" "$output_file" | awk '{print $2}')
    local recall=$(grep "Recall:" "$output_file" | awk '{print $2}')
    local f1=$(grep "F1 Score:" "$output_file" | awk '{print $3}')
    local tp=$(grep "True Positives:" "$output_file" | awk '{print $3}')
    local fp=$(grep "False Positives:" "$output_file" | awk '{print $3}')
    local fn=$(grep "False Negatives:" "$output_file" | awk '{print $3}')
    local expected=$(grep "Expected answers:" "$output_file" | awk '{print $3}')
    local actual=$(grep "Actual answers:" "$output_file" | awk '{print $3}')

    # Append to summary
    echo "$query_type,$pattern,$expected,$actual,$tp,$fp,$fn,$precision,$recall,$f1" >> "$RESULTS_DIR/metrics.csv"

    echo "  Precision: $precision, Recall: $recall, F1: $f1"
    echo ""
}

# CSV Header
echo "query_type,pattern,expected,actual,tp,fp,fn,precision,recall,f1" > "$RESULTS_DIR/metrics.csv"

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
}' "$RESULTS_DIR/metrics.csv" | tee -a "$SUMMARY_FILE"

echo ""
echo "Results saved to: $RESULTS_DIR"
echo "  - Individual logs: ${query_type}_${pattern}.log"
echo "  - Metrics CSV: metrics.csv"
echo "  - Summary: summary.txt"
