#!/bin/bash
# Main script to run the Missing Link Completion experiment
# Usage: ./run.sh [--generate] [--max-cases N] [--approaches sparql pure_llm gensparql]

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
DATASET="NELL995+H"

# Default values
GENERATE=false
MAX_CASES=""
APPROACHES="sparql pure_llm gensparql"
NUM_CASES=50

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --generate)
            GENERATE=true
            shift
            ;;
        --max-cases)
            MAX_CASES="--max-cases $2"
            shift 2
            ;;
        --num-cases)
            NUM_CASES=$2
            shift 2
            ;;
        --approaches)
            shift
            APPROACHES=""
            while [[ $# -gt 0 && ! "$1" =~ ^-- ]]; do
                APPROACHES="$APPROACHES $1"
                shift
            done
            ;;
        --dataset)
            DATASET="$2"
            shift 2
            ;;
        *)
            echo "Unknown option: $1"
            exit 1
            ;;
    esac
done

echo "============================================================"
echo "Missing Link Completion Experiment"
echo "============================================================"
echo "Dataset: $DATASET"
echo "Approaches: $APPROACHES"
echo ""

# Set OpenRouter API Key (if not already set)
if [ -z "$OPENROUTER_API_KEY" ]; then
    export OPENROUTER_API_KEY=YOUR_OPENROUTER_API_KEY
fi

# Step 1: Build project
echo "Step 1: Building project..."
cd "$PROJECT_ROOT"
mvn compile -q -DskipTests 2>/dev/null || {
    echo "Building with verbose output..."
    mvn compile -DskipTests
}
echo "Build complete."
echo ""

# Step 2: Generate experiment (if needed)
EXPERIMENT_DIR="$SCRIPT_DIR/$DATASET"
if [ "$GENERATE" = true ] || [ ! -d "$EXPERIMENT_DIR" ]; then
    echo "Step 2: Generating experiment cases..."
    python3 "$SCRIPT_DIR/generate_experiment.py" \
        --dataset "$DATASET" \
        --num-cases "$NUM_CASES" \
        --max-candidates 300
    echo ""
else
    echo "Step 2: Using existing experiment in $EXPERIMENT_DIR"
    echo ""
fi

# Step 3: Run experiment
echo "Step 3: Running experiment..."
python3 "$SCRIPT_DIR/run_experiment.py" \
    --experiment-dir "$EXPERIMENT_DIR" \
    --dataset "$DATASET" \
    $MAX_CASES \
    --approaches $APPROACHES \
    --output results.json

echo ""
echo "============================================================"
echo "Experiment complete!"
echo "Results saved to: $EXPERIMENT_DIR/results.json"
echo "============================================================"
