#!/bin/bash
# Run a single GenSPARQL query
# Usage: ./run_single_query.sh <data_path> <query_path>

DATA_PATH="$1"
QUERY_PATH="$2"

if [ -z "$DATA_PATH" ] || [ -z "$QUERY_PATH" ]; then
    echo "Usage: $0 <data_path> <query_path>"
    exit 1
fi

# Find project root
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# Build if needed
if [ ! -d "$PROJECT_ROOT/gensparql-engine/target/classes" ]; then
    echo "Building project..."
    (cd "$PROJECT_ROOT" && mvn compile -q)
fi

# Build classpath
CLASSPATH=""
for module in gensparql-engine gensparql-parser gensparql-llm gensparql-core; do
    if [ -d "$PROJECT_ROOT/$module/target/classes" ]; then
        CLASSPATH="$CLASSPATH:$PROJECT_ROOT/$module/target/classes"
    fi
done

# Add Maven dependencies
M2_REPO="$HOME/.m2/repository"
CLASSPATH="$CLASSPATH:$M2_REPO/org/apache/jena/jena-arq/5.2.0/jena-arq-5.2.0.jar"
CLASSPATH="$CLASSPATH:$M2_REPO/org/apache/jena/jena-core/5.2.0/jena-core-5.2.0.jar"
CLASSPATH="$CLASSPATH:$M2_REPO/org/apache/jena/jena-base/5.2.0/jena-base-5.2.0.jar"
CLASSPATH="$CLASSPATH:$M2_REPO/org/apache/jena/jena-shacl/5.2.0/jena-shacl-5.2.0.jar"
CLASSPATH="$CLASSPATH:$M2_REPO/commons-codec/commons-codec/1.17.1/commons-codec-1.17.1.jar"
CLASSPATH="$CLASSPATH:$M2_REPO/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar"
CLASSPATH="$CLASSPATH:$M2_REPO/org/slf4j/slf4j-api/2.0.9/slf4j-api-2.0.9.jar"
CLASSPATH="$CLASSPATH:$M2_REPO/org/slf4j/slf4j-nop/2.0.9/slf4j-nop-2.0.9.jar"

# Remove leading colon
CLASSPATH="${CLASSPATH#:}"

# Run query
java -cp "$CLASSPATH" org.gensparql.engine.boot.GenSPARQL \
    --data "$DATA_PATH" \
    --query "$QUERY_PATH" \
    2>/dev/null
