#!/bin/bash
# Clean test of embedding-based entity grounding on FB15k-237+H
# Minimal debug output, focused on results

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
DATA_DIR="$PROJECT_ROOT/gensparql-benchmark/GENSPARQL_Data/FB15k-237+H"

# Set OpenRouter API Key
if [ -z "$OPENROUTER_API_KEY" ]; then
    export OPENROUTER_API_KEY=YOUR_OPENROUTER_API_KEY
fi

echo "============================================"
echo "FB15k-237+H Grounding Evaluation"
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

# Function to run query and extract results
run_query() {
    local query_file="$1"
    local grounding="$2"
    local label="$3"

    echo "[$label]"
    if [ "$grounding" = "true" ]; then
        java -cp "$CP" \
            -Dgensparql.grounding.enabled=true \
            -Dgensparql.grounding.threshold=0.3 \
            org.gensparql.example.GenSPARQLExample \
            "$DATA_DIR/train.nt" \
            "$query_file" 2>&1 | grep -E "=== RAW LLM|^\[1\]|^\[2\]|^\[3\]|concept_|entity/m_" | head -10
    else
        java -cp "$CP" \
            -Dgensparql.grounding.enabled=false \
            org.gensparql.example.GenSPARQLExample \
            "$DATA_DIR/train.nt" \
            "$query_file" 2>&1 | grep -E "=== RAW LLM|^\[1\]|^\[2\]|^\[3\]" | head -10
    fi
    echo ""
}

# ============================================
# Test 1: Country Form of Government
# ============================================
echo "============================================"
echo "Test 1: Country Form of Government"
echo "Relation: forward_location_country_form_of_government"
echo "============================================"

cat > "$TEMP_DIR/query1.sparql" << 'EOF'
SELECT ?gov WHERE {
  GENOP("What is the form of government of France? Answer with one or two words only.",
        (?gov),
        <model:openrouter:deepseek/deepseek-r1-0528:free>,
        grounding_relation: "http://freebase.com/relation/forward_location_country_form_of_government")
}
EOF

run_query "$TEMP_DIR/query1.sparql" "false" "Baseline (no grounding)"
run_query "$TEMP_DIR/query1.sparql" "true" "With grounding"

# ============================================
# Test 2: Music Genre Sub-genres
# ============================================
echo "============================================"
echo "Test 2: Music Genre Query"
echo "Relation: forward_music_genre_subgenre"
echo "============================================"

cat > "$TEMP_DIR/query2.sparql" << 'EOF'
SELECT ?subgenre WHERE {
  GENOP("What are sub-genres of Rock music? Return one main sub-genre only.",
        (?subgenre),
        <model:openrouter:deepseek/deepseek-r1-0528:free>,
        grounding_relation: "http://freebase.com/relation/forward_music_genre_subgenre")
}
EOF

run_query "$TEMP_DIR/query2.sparql" "false" "Baseline (no grounding)"
run_query "$TEMP_DIR/query2.sparql" "true" "With grounding"

# ============================================
# Test 3: Award Winner Query
# ============================================
echo "============================================"
echo "Test 3: Award Winner Query"
echo "Relation: forward_award_award_winner_awards_won"
echo "============================================"

cat > "$TEMP_DIR/query3.sparql" << 'EOF'
SELECT ?winner WHERE {
  GENOP("Who won the Academy Award for Best Actor in 2020? Answer with the person's name only.",
        (?winner),
        <model:openrouter:deepseek/deepseek-r1-0528:free>,
        grounding_relation: "http://freebase.com/relation/forward_award_award_winner_awards_won._award_award_honor_award_winner")
}
EOF

run_query "$TEMP_DIR/query3.sparql" "false" "Baseline (no grounding)"
run_query "$TEMP_DIR/query3.sparql" "true" "With grounding"

# ============================================
# Test 4: Existing 2p benchmark query
# ============================================
echo "============================================"
echo "Test 4: 2p Benchmark Query (pattern_01)"
echo "============================================"

BENCHMARK_QUERY="$PROJECT_ROOT/gensparql-benchmark/Queries-FB15k-237+H/2p/pattern_01/query1.sparql"
if [ -f "$BENCHMARK_QUERY" ]; then
    echo "Query:"
    head -5 "$BENCHMARK_QUERY"
    echo ""
    run_query "$BENCHMARK_QUERY" "false" "Baseline"
    run_query "$BENCHMARK_QUERY" "true" "With global grounding"
fi

echo "============================================"
echo "Test Complete!"
echo "============================================"
echo ""
echo "Legend:"
echo "- Baseline: LLM returns free-text (e.g., 'Republic')"
echo "- Grounded: LLM answer matched to KG entity (e.g., 'http://freebase.com/entity/m_06cx9_Republic')"
