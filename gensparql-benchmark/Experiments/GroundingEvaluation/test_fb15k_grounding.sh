#!/bin/bash
# Test embedding-based entity grounding on FB15k-237+H dataset
# Compares results with and without grounding across different query patterns

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
DATA_DIR="$PROJECT_ROOT/gensparql-benchmark/GENSPARQL_Data/FB15k-237+H"
QUERIES_DIR="$PROJECT_ROOT/gensparql-benchmark/Queries-FB15k-237+H"

# Set OpenRouter API Key
if [ -z "$OPENROUTER_API_KEY" ]; then
    export OPENROUTER_API_KEY=YOUR_OPENROUTER_API_KEY
fi

# Configuration
MAX_PATTERNS=${MAX_PATTERNS:-2}  # Number of patterns to test per query type
GROUNDING_THRESHOLD=${GROUNDING_THRESHOLD:-0.5}
RESULTS_DIR="$SCRIPT_DIR/results_$(date +%Y%m%d_%H%M%S)"

echo "============================================"
echo "FB15k-237+H Grounding Evaluation"
echo "============================================"
echo "Data: $DATA_DIR/train.nt"
echo "Queries: $QUERIES_DIR"
echo "Results: $RESULTS_DIR"
echo "Grounding threshold: $GROUNDING_THRESHOLD"
echo ""

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

# Function to run a single query
run_query() {
    local query_file="$1"
    local grounding_enabled="$2"
    local output_file="$3"

    if [ "$grounding_enabled" = "true" ]; then
        java -cp "$CP" \
            -Dgensparql.grounding.enabled=true \
            -Dgensparql.grounding.threshold=$GROUNDING_THRESHOLD \
            org.gensparql.example.GenSPARQLExample \
            "$DATA_DIR/train.nt" \
            "$query_file" 2>&1 | tee "$output_file"
    else
        java -cp "$CP" \
            -Dgensparql.grounding.enabled=false \
            org.gensparql.example.GenSPARQLExample \
            "$DATA_DIR/train.nt" \
            "$query_file" 2>&1 | tee "$output_file"
    fi
}

# Function to extract answers from output
extract_answers() {
    local output_file="$1"
    grep -E "^\[" "$output_file" | sed 's/^\[[0-9]*\] //' | sort -u
}

# Function to count matches
count_matches() {
    local actual_file="$1"
    local expected_file="$2"

    local matches=0
    local total_expected=$(cat "$expected_file" | python3 -c "import json,sys; d=json.load(sys.stdin); print(len(d['answers']))")

    while IFS= read -r answer; do
        # Check if answer matches any expected (by MID or readable name)
        if grep -q "\"$answer\"" "$expected_file" 2>/dev/null; then
            matches=$((matches + 1))
        fi
    done < "$actual_file"

    echo "$matches $total_expected"
}

# Summary arrays
declare -a QUERY_TYPES
declare -a PATTERNS
declare -a BASELINE_HITS
declare -a GROUNDED_HITS
declare -a EXPECTED_COUNTS

echo ""
echo "============================================"
echo "Running Queries"
echo "============================================"

# Test 2p patterns
for pattern_dir in "$QUERIES_DIR/2p"/pattern_*; do
    if [ -d "$pattern_dir" ]; then
        pattern_name=$(basename "$pattern_dir")
        query_file="$pattern_dir/query1.sparql"
        expected_file="$pattern_dir/expected_answers.json"

        if [ ! -f "$query_file" ] || [ ! -f "$expected_file" ]; then
            continue
        fi

        echo ""
        echo "=== 2p / $pattern_name ==="
        cat "$query_file" | head -5

        # Run without grounding (baseline)
        echo ""
        echo "--- Running without grounding ---"
        run_query "$query_file" "false" "$RESULTS_DIR/2p_${pattern_name}_baseline.txt"

        # Run with grounding
        echo ""
        echo "--- Running with grounding ---"
        run_query "$query_file" "true" "$RESULTS_DIR/2p_${pattern_name}_grounded.txt"

        # Extract and compare results
        extract_answers "$RESULTS_DIR/2p_${pattern_name}_baseline.txt" > "$RESULTS_DIR/2p_${pattern_name}_baseline_answers.txt"
        extract_answers "$RESULTS_DIR/2p_${pattern_name}_grounded.txt" > "$RESULTS_DIR/2p_${pattern_name}_grounded_answers.txt"

        # Store results
        QUERY_TYPES+=("2p")
        PATTERNS+=("$pattern_name")

        baseline_result=$(count_matches "$RESULTS_DIR/2p_${pattern_name}_baseline_answers.txt" "$expected_file")
        grounded_result=$(count_matches "$RESULTS_DIR/2p_${pattern_name}_grounded_answers.txt" "$expected_file")

        BASELINE_HITS+=($(echo $baseline_result | cut -d' ' -f1))
        GROUNDED_HITS+=($(echo $grounded_result | cut -d' ' -f1))
        EXPECTED_COUNTS+=($(echo $baseline_result | cut -d' ' -f2))

        # Limit patterns tested
        pattern_count=$((pattern_count + 1))
        if [ "$pattern_count" -ge "$MAX_PATTERNS" ]; then
            break
        fi
    fi
done

pattern_count=0

# Test 2i patterns
for pattern_dir in "$QUERIES_DIR/2i"/pattern_*; do
    if [ -d "$pattern_dir" ]; then
        pattern_name=$(basename "$pattern_dir")
        query_file="$pattern_dir/query1.sparql"
        expected_file="$pattern_dir/expected_answers.json"

        if [ ! -f "$query_file" ] || [ ! -f "$expected_file" ]; then
            continue
        fi

        echo ""
        echo "=== 2i / $pattern_name ==="
        cat "$query_file" | head -5

        # Run without grounding (baseline)
        echo ""
        echo "--- Running without grounding ---"
        run_query "$query_file" "false" "$RESULTS_DIR/2i_${pattern_name}_baseline.txt"

        # Run with grounding
        echo ""
        echo "--- Running with grounding ---"
        run_query "$query_file" "true" "$RESULTS_DIR/2i_${pattern_name}_grounded.txt"

        # Extract and compare results
        extract_answers "$RESULTS_DIR/2i_${pattern_name}_baseline.txt" > "$RESULTS_DIR/2i_${pattern_name}_baseline_answers.txt"
        extract_answers "$RESULTS_DIR/2i_${pattern_name}_grounded.txt" > "$RESULTS_DIR/2i_${pattern_name}_grounded_answers.txt"

        # Store results
        QUERY_TYPES+=("2i")
        PATTERNS+=("$pattern_name")

        baseline_result=$(count_matches "$RESULTS_DIR/2i_${pattern_name}_baseline_answers.txt" "$expected_file")
        grounded_result=$(count_matches "$RESULTS_DIR/2i_${pattern_name}_grounded_answers.txt" "$expected_file")

        BASELINE_HITS+=($(echo $baseline_result | cut -d' ' -f1))
        GROUNDED_HITS+=($(echo $grounded_result | cut -d' ' -f1))
        EXPECTED_COUNTS+=($(echo $baseline_result | cut -d' ' -f2))

        # Limit patterns tested
        pattern_count=$((pattern_count + 1))
        if [ "$pattern_count" -ge "$MAX_PATTERNS" ]; then
            break
        fi
    fi
done

echo ""
echo "============================================"
echo "Results Summary"
echo "============================================"
echo ""
printf "%-10s %-15s %-15s %-15s %-10s\n" "Type" "Pattern" "Baseline" "Grounded" "Expected"
printf "%-10s %-15s %-15s %-15s %-10s\n" "----" "-------" "--------" "--------" "--------"

total_baseline=0
total_grounded=0
total_expected=0

for i in "${!QUERY_TYPES[@]}"; do
    printf "%-10s %-15s %-15s %-15s %-10s\n" \
        "${QUERY_TYPES[$i]}" \
        "${PATTERNS[$i]}" \
        "${BASELINE_HITS[$i]}" \
        "${GROUNDED_HITS[$i]}" \
        "${EXPECTED_COUNTS[$i]}"

    total_baseline=$((total_baseline + BASELINE_HITS[$i]))
    total_grounded=$((total_grounded + GROUNDED_HITS[$i]))
    total_expected=$((total_expected + EXPECTED_COUNTS[$i]))
done

echo ""
printf "%-10s %-15s %-15s %-15s %-10s\n" "TOTAL" "" "$total_baseline" "$total_grounded" "$total_expected"

if [ "$total_expected" -gt 0 ]; then
    baseline_pct=$(echo "scale=2; $total_baseline * 100 / $total_expected" | bc)
    grounded_pct=$(echo "scale=2; $total_grounded * 100 / $total_expected" | bc)
    echo ""
    echo "Baseline Accuracy: $baseline_pct%"
    echo "Grounded Accuracy: $grounded_pct%"
fi

echo ""
echo "Detailed results saved to: $RESULTS_DIR"
echo ""
echo "Test complete!"
