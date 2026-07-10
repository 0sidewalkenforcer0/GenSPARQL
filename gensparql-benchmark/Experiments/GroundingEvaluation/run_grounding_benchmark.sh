#!/bin/bash
# Grounding benchmark for FB15k-237+H and NELL995+H
# Tests embedding-based entity grounding with specific relations

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

# Set OpenRouter API Key
if [ -z "$OPENROUTER_API_KEY" ]; then
    export OPENROUTER_API_KEY=YOUR_OPENROUTER_API_KEY
fi

# Configuration
DATASET=${DATASET:-"FB15k-237+H"}  # or "NELL995+H"
THRESHOLD=${THRESHOLD:-0.4}

# Paths based on dataset
if [ "$DATASET" = "FB15k-237+H" ]; then
    DATA_FILE="$PROJECT_ROOT/gensparql-benchmark/GENSPARQL_Data/FB15k-237+H/train.nt"
elif [ "$DATASET" = "NELL995+H" ]; then
    DATA_FILE="$PROJECT_ROOT/gensparql-benchmark/GENSPARQL_Data/NELL995+H/train.nt"
else
    echo "Unknown dataset: $DATASET"
    exit 1
fi

echo "============================================"
echo "Grounding Benchmark"
echo "Dataset: $DATASET"
echo "Threshold: $THRESHOLD"
echo "============================================"
echo ""

# Build project
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

TEMP_DIR=$(mktemp -d)
trap "rm -rf $TEMP_DIR" EXIT

# Results tracking
declare -a TEST_NAMES
declare -a BASELINE_RESULTS
declare -a GROUNDED_RESULTS
declare -a GROUNDED_URIS

run_test() {
    local name="$1"
    local query="$2"
    local relation="$3"

    echo "=== $name ==="

    # Create query file with grounding
    cat > "$TEMP_DIR/query.sparql" << EOF
SELECT ?answer WHERE {
  GENOP("$query",
        (?answer),
        <model:openrouter:deepseek/deepseek-r1-0528:free>,
        grounding_relation: "$relation")
}
EOF

    # Run baseline
    echo -n "  Baseline: "
    local baseline_result=$(java -cp "$CP" \
        -Dgensparql.grounding.enabled=false \
        org.gensparql.example.GenSPARQLExample \
        "$DATA_FILE" \
        "$TEMP_DIR/query.sparql" 2>&1 | grep "^\[1\]" | sed 's/\[1\] //')
    echo "$baseline_result"

    # Run with grounding
    echo -n "  Grounded: "
    local grounded_result=$(java -cp "$CP" \
        -Dgensparql.grounding.enabled=true \
        -Dgensparql.grounding.threshold=$THRESHOLD \
        org.gensparql.example.GenSPARQLExample \
        "$DATA_FILE" \
        "$TEMP_DIR/query.sparql" 2>&1 | grep "^\[1\]" | sed 's/\[1\] //')
    echo "$grounded_result"

    # Check if grounded (has URI)
    if echo "$grounded_result" | grep -q "http://"; then
        echo "  Status: ✅ GROUNDED"
    else
        echo "  Status: ❌ NOT GROUNDED"
    fi
    echo ""

    # Store results
    TEST_NAMES+=("$name")
    BASELINE_RESULTS+=("$baseline_result")
    GROUNDED_RESULTS+=("$grounded_result")
}

if [ "$DATASET" = "FB15k-237+H" ]; then
    echo "============================================"
    echo "FB15k-237+H Tests"
    echo "============================================"
    echo ""

    run_test "Country Government" \
        "What is the form of government of France? Answer with one or two words only." \
        "http://freebase.com/relation/forward_location_country_form_of_government"

    run_test "Country Capital" \
        "What is the capital city of Japan? Answer with the city name only." \
        "http://freebase.com/relation/forward_location_country_capital"

    run_test "Film Director" \
        "Who directed the movie Inception? Answer with the director name only." \
        "http://freebase.com/relation/forward_film_film_directed_by"

    run_test "Music Artist" \
        "Who sang the song 'Bohemian Rhapsody'? Answer with the artist name only." \
        "http://freebase.com/relation/forward_music_recording_artist"

elif [ "$DATASET" = "NELL995+H" ]; then
    echo "============================================"
    echo "NELL995+H Tests"
    echo "============================================"
    echo ""

    run_test "City River" \
        "What river does the city of Cohoes lie on? Answer with one or two words only." \
        "http://nell.cs.cmu.edu/relation/concept_cityliesonriver"

    run_test "Team Sport" \
        "What sport do the Los Angeles Lakers play? Answer with one word only." \
        "http://nell.cs.cmu.edu/relation/concept_teamplaysinleague"

    run_test "Company Industry" \
        "What industry is Apple Inc in? Answer with one or two words only." \
        "http://nell.cs.cmu.edu/relation/concept_companyeconomicsector"

    run_test "Country Capital" \
        "What is the capital of Germany? Answer with the city name only." \
        "http://nell.cs.cmu.edu/relation/concept_countrycapital"
fi

echo "============================================"
echo "Summary"
echo "============================================"
echo ""
printf "%-25s | %-25s | %-50s\n" "Test" "Baseline" "Grounded"
printf "%-25s-+-%-25s-+-%-50s\n" "-------------------------" "-------------------------" "--------------------------------------------------"
for i in "${!TEST_NAMES[@]}"; do
    baseline=$(echo "${BASELINE_RESULTS[$i]}" | cut -c1-25)
    grounded=$(echo "${GROUNDED_RESULTS[$i]}" | cut -c1-50)
    printf "%-25s | %-25s | %-50s\n" "${TEST_NAMES[$i]}" "$baseline" "$grounded"
done

echo ""
echo "Test complete!"
