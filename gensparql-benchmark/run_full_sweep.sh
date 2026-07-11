#!/bin/bash
# Full post-fix sweep: every dataset x every pattern x N query instances, scored
# with GenSPARQLExample (Precision/Recall/F1). Relies on the similarity join for
# GEN->KG matching (grounding off by default; set GROUNDING=true to enable).
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BENCH="$ROOT/gensparql-benchmark"

DATASETS=${DATASETS:-"FB15k-237+H NELL995+H"}
TYPES=${TYPES:-"2i 2p 3i 3p 4p ip pi up"}
INSTANCES=${INSTANCES:-"1 2 3"}
GROUNDING=${GROUNDING:-false}
GROUNDING_THRESHOLD=${GROUNDING_THRESHOLD:-0.5}
OUTDIR=${OUTDIR:-"$BENCH/sweep_results"}

if [ -z "${OPENROUTER_API_KEY:-}" ]; then
    export OPENROUTER_API_KEY=YOUR_OPENROUTER_API_KEY
fi

mkdir -p "$OUTDIR/logs"
CSV="$OUTDIR/results.csv"
SUMMARY="$OUTDIR/summary.txt"

# Build classpath once
cd "$ROOT"
mvn -q -pl gensparql-example -am compile 2>/dev/null
DEP=$(mvn -pl gensparql-example -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout 2>/dev/null | tr ':' '\n' | grep -v gensparql | tr '\n' ':')
CP="$ROOT/gensparql-core/target/classes:$ROOT/gensparql-llm/target/classes:$ROOT/gensparql-parser/target/classes:$ROOT/gensparql-engine/target/classes:$ROOT/gensparql-functions/target/classes:$ROOT/gensparql-example/target/classes:$DEP"

GFLAGS=""
if [ "$GROUNDING" = "true" ]; then
    GFLAGS="-Dgensparql.grounding.enabled=true -Dgensparql.grounding.threshold=$GROUNDING_THRESHOLD"
fi

echo "dataset,query_type,pattern,instance,expected,actual,tp,fp,fn,precision,recall,f1,time_ms" > "$CSV"
echo "Full sweep | datasets=[$DATASETS] types=[$TYPES] grounding=$GROUNDING | $(date)" > "$SUMMARY"

N=0
for ds in $DATASETS; do
    DATA="$BENCH/GENSPARQL_Data/$ds/train.nt"
    [ -f "$DATA" ] || { echo "skip $ds (no train.nt)"; continue; }
    for t in $TYPES; do
        for pdir in "$BENCH/Queries-$ds/$t"/pattern_*/; do
            [ -d "$pdir" ] || continue
            pat=$(basename "$pdir")
            for i in $INSTANCES; do
                q="$pdir/query$i.sparql"; e="$pdir/expected$i.json"
                [ -f "$q" ] && [ -f "$e" ] || continue
                N=$((N+1))
                log="$OUTDIR/logs/${ds}_${t}_${pat}_q${i}.log"
                echo "[$N] $ds/$t/$pat q$i"
                java -cp "$CP" $GFLAGS \
                    org.gensparql.example.GenSPARQLExample "$DATA" "$q" "$e" > "$log" 2>&1 || true
                exp=$(grep "Expected answers:" "$log" | awk '{print $3}' | head -1); exp=${exp:-0}
                act=$(grep "Actual answers:"   "$log" | awk '{print $3}' | head -1); act=${act:-0}
                tp=$(grep  "True Positives:"   "$log" | awk '{print $3}' | head -1); tp=${tp:-0}
                fp=$(grep  "False Positives:"  "$log" | awk '{print $3}' | head -1); fp=${fp:-0}
                fn=$(grep  "False Negatives:"  "$log" | awk '{print $3}' | head -1); fn=${fn:-0}
                p=$(grep   "Precision:"        "$log" | awk '{print $2}' | head -1); p=${p:-0.0000}
                r=$(grep   "Recall:"           "$log" | awk '{print $2}' | head -1); r=${r:-0.0000}
                f1=$(grep  "F1 Score:"         "$log" | awk '{print $3}' | head -1); f1=${f1:-0.0000}
                tms=$(grep "Execution time:"   "$log" | awk '{print $3}' | head -1); tms=${tms:-0}
                echo "$ds,$t,$pat,$i,$exp,$act,$tp,$fp,$fn,$p,$r,$f1,$tms" >> "$CSV"
            done
        done
    done
done

echo "" >> "$SUMMARY"
echo "=== Per dataset x query_type averages (P / R / F1, n) ===" >> "$SUMMARY"
awk -F',' 'NR>1 {k=$1" "$2; sp[k]+=$10; sr[k]+=$11; sf[k]+=$12; c[k]++}
END { for (k in c) printf "%-22s P=%.4f R=%.4f F1=%.4f  (n=%d)\n", k, sp[k]/c[k], sr[k]/c[k], sf[k]/c[k], c[k] }' "$CSV" | sort >> "$SUMMARY"
echo "" >> "$SUMMARY"
echo "Total queries scored: $N" >> "$SUMMARY"
cat "$SUMMARY"
