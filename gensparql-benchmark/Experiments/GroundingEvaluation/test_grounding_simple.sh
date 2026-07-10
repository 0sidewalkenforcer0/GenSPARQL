#!/bin/bash
# Simple test of embedding-based entity grounding on FB15k-237+H
# Tests a few specific queries with and without grounding

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
DATA_DIR="$PROJECT_ROOT/gensparql-benchmark/GENSPARQL_Data/FB15k-237+H"

# Set OpenRouter API Key
if [ -z "$OPENROUTER_API_KEY" ]; then
    export OPENROUTER_API_KEY=YOUR_OPENROUTER_API_KEY
fi

echo "============================================"
echo "FB15k-237+H Grounding Evaluation (Simple)"
echo "============================================"
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

TEMP_DIR=$(mktemp -d)
trap "rm -rf $TEMP_DIR" EXIT

# ============================================
# Test 1: Simple 1-hop query (artist genre)
# ============================================
echo ""
echo "============================================"
echo "Test 1: 1-hop Music Artist Genre Query"
echo "============================================"

# Find a relation with enough candidates for grounding
# Using music genre relation
cat > "$TEMP_DIR/query1_baseline.sparql" << 'EOF'
# Query: What music genre is associated with rock music?
SELECT ?genre WHERE {
  GENOP("What are the main sub-genres of rock music? Return ONLY a JSON array like ['genre1', 'genre2']. No explanations.",
        (?genre),
        <model:openrouter:deepseek/deepseek-r1-0528:free>)
}
LIMIT 5
EOF

cat > "$TEMP_DIR/query1_grounded.sparql" << 'EOF'
# Query: What music genre is associated with rock music? (with grounding)
SELECT ?genre WHERE {
  GENOP("What are the main sub-genres of rock music? Return ONLY a JSON array like ['genre1', 'genre2']. No explanations.",
        (?genre),
        <model:openrouter:deepseek/deepseek-r1-0528:free>,
        grounding_relation: "http://freebase.com/relation/forward_music_genre_albums")
}
LIMIT 5
EOF

echo "--- Query (Baseline - no grounding) ---"
cat "$TEMP_DIR/query1_baseline.sparql"
echo ""
echo "--- Running baseline ---"
java -cp "$CP" \
    -Dgensparql.grounding.enabled=false \
    org.gensparql.example.GenSPARQLExample \
    "$DATA_DIR/train.nt" \
    "$TEMP_DIR/query1_baseline.sparql" 2>&1 | grep -E "RAW LLM|^\[" | head -20

echo ""
echo "--- Query (With grounding) ---"
cat "$TEMP_DIR/query1_grounded.sparql"
echo ""
echo "--- Running with grounding ---"
java -cp "$CP" \
    -Dgensparql.grounding.enabled=true \
    -Dgensparql.grounding.threshold=0.3 \
    org.gensparql.example.GenSPARQLExample \
    "$DATA_DIR/train.nt" \
    "$TEMP_DIR/query1_grounded.sparql" 2>&1 | grep -E "RAW LLM|^\[" | head -20

# ============================================
# Test 2: Country-related query
# ============================================
echo ""
echo "============================================"
echo "Test 2: Country Government Query"
echo "============================================"

cat > "$TEMP_DIR/query2_baseline.sparql" << 'EOF'
# Query: What form of government does France have?
SELECT ?gov WHERE {
  GENOP("What is the form of government of France? Answer with one or two words.",
        (?gov),
        <model:openrouter:deepseek/deepseek-r1-0528:free>)
}
EOF

cat > "$TEMP_DIR/query2_grounded.sparql" << 'EOF'
# Query: What form of government does France have? (with grounding)
SELECT ?gov WHERE {
  GENOP("What is the form of government of France? Answer with one or two words.",
        (?gov),
        <model:openrouter:deepseek/deepseek-r1-0528:free>,
        grounding_relation: "http://freebase.com/relation/forward_location_country_form_of_government")
}
EOF

echo "--- Baseline ---"
java -cp "$CP" \
    -Dgensparql.grounding.enabled=false \
    org.gensparql.example.GenSPARQLExample \
    "$DATA_DIR/train.nt" \
    "$TEMP_DIR/query2_baseline.sparql" 2>&1 | grep -E "RAW LLM|^\[" | head -10

echo ""
echo "--- With Grounding ---"
java -cp "$CP" \
    -Dgensparql.grounding.enabled=true \
    -Dgensparql.grounding.threshold=0.3 \
    org.gensparql.example.GenSPARQLExample \
    "$DATA_DIR/train.nt" \
    "$TEMP_DIR/query2_grounded.sparql" 2>&1 | grep -E "RAW LLM|^\[|Grounded" | head -15

# ============================================
# Test 3: 2-hop query from benchmark
# ============================================
echo ""
echo "============================================"
echo "Test 3: 2p Query (from benchmark)"
echo "============================================"

# Check if the benchmark query exists
BENCHMARK_QUERY="$PROJECT_ROOT/gensparql-benchmark/Queries-FB15k-237+H/2p/pattern_01/query1.sparql"
if [ -f "$BENCHMARK_QUERY" ]; then
    echo "--- Original benchmark query ---"
    head -25 "$BENCHMARK_QUERY"

    echo ""
    echo "--- Running baseline ---"
    java -cp "$CP" \
        -Dgensparql.grounding.enabled=false \
        org.gensparql.example.GenSPARQLExample \
        "$DATA_DIR/train.nt" \
        "$BENCHMARK_QUERY" 2>&1 | grep -E "RAW LLM|^\[|COMPARISON" | head -20

    echo ""
    echo "--- Running with global grounding (no specific relation) ---"
    java -cp "$CP" \
        -Dgensparql.grounding.enabled=true \
        -Dgensparql.grounding.threshold=0.3 \
        org.gensparql.example.GenSPARQLExample \
        "$DATA_DIR/train.nt" \
        "$BENCHMARK_QUERY" 2>&1 | grep -E "RAW LLM|^\[|COMPARISON|Grounded" | head -20
else
    echo "Benchmark query not found: $BENCHMARK_QUERY"
fi

echo ""
echo "============================================"
echo "Test Complete!"
echo "============================================"
echo ""
echo "Summary:"
echo "- Baseline: LLM returns free-text answers"
echo "- Grounded: LLM answers are matched to KG entities via embedding similarity"
echo ""
