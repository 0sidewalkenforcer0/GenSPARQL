#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CLASSPATH_FILE="${TMPDIR:-/tmp}/gensparql-example-classpath.txt"

if [[ -z "${OPENROUTER_API_KEY:-}" ]]; then
  echo "OPENROUTER_API_KEY is not set." >&2
  echo 'Run: export OPENROUTER_API_KEY="your-openrouter-key"' >&2
  exit 1
fi

cd "$PROJECT_ROOT"
mvn -q -pl gensparql-example -am compile -DskipTests
mvn -q -pl gensparql-example dependency:build-classpath \
  -Dmdep.outputFile="$CLASSPATH_FILE"

java \
  -Dorg.slf4j.simpleLogger.defaultLogLevel=warn \
  -Dorg.slf4j.simpleLogger.log.org.gensparql.llm.provider.OpenRouterProvider=debug \
  -Dorg.slf4j.simpleLogger.log.org.gensparql.llm.parser.JsonResponseParser=debug \
  -Dorg.slf4j.simpleLogger.log.org.gensparql.engine.iterator.QueryIterSimJoin=debug \
  -cp "$(<"$CLASSPATH_FILE"):$PROJECT_ROOT/gensparql-example/target/classes" \
  org.gensparql.example.GenSPARQLExample \
  "$PROJECT_ROOT/gensparql-example/data/nobel_prize_kg.ttl" \
  "$PROJECT_ROOT/gensparql-example/queries/nobel_quantum_simjoin.sparql"
