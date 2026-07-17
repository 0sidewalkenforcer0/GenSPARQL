#!/bin/bash
# Reproduce the paper's Preliminary Evaluation (E1-E4).
# Requires: OPENROUTER_API_KEY in the environment (this repo ships no key).
#   export OPENROUTER_API_KEY=sk-or-...
# E1/E2/E4 use the bundled scientists KG. E3 needs the FB15k-237+H data, which is
# NOT shipped in this repo (too large); pass its directory as $FB15K_DIR to run E3.
#
# Grounding uses text similarity (Jaccard) by default. To use embedding-cosine
# grounding via a foundation text embedder, also export an OpenAI-compatible
# endpoint (the E1/E2 driver auto-switches); see docs/EVALUATION.md:
#   export OPENAI_API_KEY=ollama OPENAI_BASE_URL=http://localhost:11434/v1 \
#          OPENAI_EMBEDDING_MODEL=nomic-embed-text
set -e
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
EVAL_DIR="$PROJECT_ROOT/eval"
DATA="${DATA:-$PROJECT_ROOT/gensparql-example/data/scientists_awards_large.ttl}"

if [ -z "$OPENROUTER_API_KEY" ]; then
  echo "ERROR: set OPENROUTER_API_KEY first." >&2; exit 1
fi

echo ">> compiling engine"
(cd "$PROJECT_ROOT" && mvn -q compile -DskipTests >/dev/null)
mvn -q -f "$PROJECT_ROOT/pom.xml" dependency:build-classpath \
    -Dmdep.outputFile=/tmp/gs_eval_cp.txt \
    -pl gensparql-core,gensparql-llm,gensparql-parser,gensparql-engine,gensparql-example,gensparql-functions >/dev/null
CP="$(cat /tmp/gs_eval_cp.txt)"
for m in core llm parser engine functions example; do
  CP="$CP:$PROJECT_ROOT/gensparql-$m/target/classes"
done

echo ">> compiling eval drivers"
javac -cp "$CP" -d "$EVAL_DIR" "$EVAL_DIR"/ExperimentRunner.java "$EVAL_DIR"/QueryTimer.java "$EVAL_DIR"/E3Runner.java

echo ">> E1 + E2 (grounding precision + threshold sweep)"
java -cp "$CP:$EVAL_DIR" ExperimentRunner "$DATA"

echo ">> E4 (end-to-end latency, cold/warm)"
java -cp "$CP:$EVAL_DIR" QueryTimer "$DATA"

if [ -n "$FB15K_DIR" ]; then
  echo ">> E3 (label-quality boundary)"
  java -cp "$CP:$EVAL_DIR" E3Runner "$FB15K_DIR"
else
  echo ">> E3 skipped (set FB15K_DIR=/path/to/FB15k-237+H to run it)"
fi
