#!/bin/bash
# Test embedding-based entity grounding with NELL data

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
DATA_DIR="$SCRIPT_DIR/../../GENSPARQL_Data/NELL995+H"

# Set OpenRouter API Key
if [ -z "$OPENROUTER_API_KEY" ]; then
    export OPENROUTER_API_KEY=YOUR_OPENROUTER_API_KEY
fi

echo "============================================"
echo "Testing Embedding-Based Entity Grounding"
echo "============================================"
echo ""

# Build if needed
echo "Building project..."
cd "$PROJECT_ROOT"
mvn compile -q -DskipTests 2>/dev/null || mvn compile -DskipTests

# Get classpath using maven dependency plugin
# Put target/classes FIRST to override any stale JARs in .m2 repository
echo "Getting classpath..."
DEP_CP=$(mvn -pl gensparql-example -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout 2>/dev/null)
# Filter out gensparql JARs from .m2 to use target/classes instead
DEP_CP=$(echo "$DEP_CP" | tr ':' '\n' | grep -v "gensparql" | tr '\n' ':')
CP="$PROJECT_ROOT/gensparql-core/target/classes"
CP="$CP:$PROJECT_ROOT/gensparql-llm/target/classes"
CP="$CP:$PROJECT_ROOT/gensparql-parser/target/classes"
CP="$CP:$PROJECT_ROOT/gensparql-engine/target/classes"
CP="$CP:$PROJECT_ROOT/gensparql-example/target/classes"
CP="$CP:$DEP_CP"

# Create temp query files
TEMP_DIR=$(mktemp -d)
trap "rm -rf $TEMP_DIR" EXIT

# Test 1: Pure LLM (no grounding) - should return free-text like "Hudson River"
echo ""
echo "============================================"
echo "Test 1: Pure LLM (no grounding)"
echo "============================================"

cat > "$TEMP_DIR/query1.sparql" << 'EOF'
SELECT ?river WHERE {
  GENOP("What river does the city of Cohoes lie on? Answer with one or two words only.",
        (?river),
        <model:openrouter:deepseek/deepseek-r1-0528:free>)
}
EOF

echo "Query: What river does Cohoes lie on?"
echo "Expected: Hudson River (free-text)"
echo ""

java -cp "$CP" \
    -Dgensparql.grounding.enabled=false \
    -Dgensparql.verbose=false \
    org.gensparql.example.GenSPARQLExample \
    "$DATA_DIR/train.nt" \
    "$TEMP_DIR/query1.sparql" 2>&1 | grep -v "^\[" | head -30

# Test 2: With grounding enabled - should ground "Hudson River" to "concept_river_hudson"
echo ""
echo "============================================"
echo "Test 2: With Embedding-Based Grounding (Global Index)"
echo "============================================"

# Same query, but with grounding_relation specified in GENOP
# This extracts candidates from the relation and grounds LLM output to them
cat > "$TEMP_DIR/query2.sparql" << 'EOF'
SELECT ?river WHERE {
  GENOP("What river does the city of Cohoes lie on? Answer with one or two words only.",
        (?river),
        <model:openrouter:deepseek/deepseek-r1-0528:free>,
        grounding_relation: "http://nell.cs.cmu.edu/relation/concept_cityliesonriver")
}
EOF

echo "Query: Same query but with grounding_relation option"
echo "Relation: http://nell.cs.cmu.edu/relation/concept_cityliesonriver"
echo "Expected: The LLM output 'Hudson River' should be grounded to concept_river_hudson"
echo ""

# Enable grounding via system property
java -cp "$CP" \
    -Dgensparql.grounding.enabled=true \
    -Dgensparql.grounding.threshold=0.3 \
    -Dgensparql.verbose=true \
    org.gensparql.example.GenSPARQLExample \
    "$DATA_DIR/train.nt" \
    "$TEMP_DIR/query2.sparql" 2>&1 | head -120

echo ""
echo "Test complete!"
