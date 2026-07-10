#!/bin/bash
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_ROOT"

# Set OpenRouter API Key
export OPENROUTER_API_KEY=YOUR_OPENROUTER_API_KEY

# Ensure project is compiled (including gensparql-example)
mvn compile -DskipTests -q > /dev/null 2>&1

# Build classpath
mvn dependency:build-classpath -Dmdep.outputFile=/tmp/classpath.txt -q -pl gensparql-core,gensparql-llm,gensparql-parser,gensparql-engine,gensparql-example,gensparql-functions
CLASSPATH=""
if [ -f /tmp/classpath.txt ]; then
    CLASSPATH="$(cat /tmp/classpath.txt)"
fi

CLASSPATH="$CLASSPATH:$PROJECT_ROOT/gensparql-core/target/classes"
CLASSPATH="$CLASSPATH:$PROJECT_ROOT/gensparql-llm/target/classes"
CLASSPATH="$CLASSPATH:$PROJECT_ROOT/gensparql-parser/target/classes"
CLASSPATH="$CLASSPATH:$PROJECT_ROOT/gensparql-engine/target/classes"
CLASSPATH="$CLASSPATH:$PROJECT_ROOT/gensparql-functions/target/classes"
CLASSPATH="$CLASSPATH:$PROJECT_ROOT/gensparql-example/target/classes"

# Run (preserve environment variables, especially OPENROUTER_API_KEY)
cd "$PROJECT_ROOT/gensparql-example"
java -cp "$CLASSPATH" org.gensparql.example.Query3_1Example
