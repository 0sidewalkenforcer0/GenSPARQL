#!/bin/bash
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_ROOT"

# Set OpenRouter API Key
export OPENROUTER_API_KEY=YOUR_OPENROUTER_API_KEY

# Ensure project is compiled
echo "Compiling project..."
mvn compile -DskipTests -q

# Build classpath - start with Maven dependencies
mvn dependency:build-classpath -Dmdep.outputFile=/tmp/classpath.txt -q -pl gensparql-core,gensparql-llm,gensparql-parser,gensparql-engine
CLASSPATH=""
if [ -f /tmp/classpath.txt ]; then
    CLASSPATH="$(cat /tmp/classpath.txt)"
fi

# Add compiled classes (use absolute paths to avoid issues)
CLASSPATH="$CLASSPATH:$PROJECT_ROOT/gensparql-core/target/classes"
CLASSPATH="$CLASSPATH:$PROJECT_ROOT/gensparql-llm/target/classes"
CLASSPATH="$CLASSPATH:$PROJECT_ROOT/gensparql-parser/target/classes"
CLASSPATH="$CLASSPATH:$PROJECT_ROOT/gensparql-engine/target/classes"

# Add JARs
for jar in "$PROJECT_ROOT"/gensparql-*/target/*.jar; do
    if [ -f "$jar" ]; then
        CLASSPATH="$CLASSPATH:$jar"
    fi
done

# Compile example (compile to project root to match package structure)
echo "Compiling ScientistAwardExample.java..."
javac -cp "$CLASSPATH" -d "$PROJECT_ROOT" "$PROJECT_ROOT/gensparql-example/ScientistAwardExample.java"

if [ $? -ne 0 ]; then
    echo "Compilation failed!"
    exit 1
fi

# Add project root for compiled example
CLASSPATH="$CLASSPATH:$PROJECT_ROOT"

# Run
echo "Running queries..."
cd "$PROJECT_ROOT/gensparql-example"
java -cp "$CLASSPATH" org.gensparql.example.ScientistAwardExample

