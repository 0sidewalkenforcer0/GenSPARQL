# Missing Link Completion Experiment (Row Expansion)

## Purpose

Demonstrate that **GenSPARQL can use LLM's Open World Knowledge to repair missing links in Closed World KG**.

This experiment shows the "bridge between open-world generation and closed-world validity."

## Task

**1-hop Link Prediction**: Given a query `(e1, r1, ?target)`, find the missing target entity.

**Selection Criteria**: We select triples that exist in `test.txt` but NOT in `train.txt` - these represent "missing links" in the training KG that we want to predict.

## Approaches Compared

| Approach | Description | Expected Result |
|----------|-------------|-----------------|
| **Standard SPARQL** | Direct query on training TDB | Recall = 0% (link is missing from train) |
| **Pure LLM (Zero-shot)** | Prompt: "Who is the {relation} of {entity}?" | Moderate Recall, Low Grounding Rate |
| **GenSPARQL (Ours)** | GENOP generates names → string matching to KB IDs | High Recall + High Grounding Rate |

## Metrics

1. **Recall (Hits@1)**: Did we find the correct answer?
   - For each query, we check if the expected answer is in the returned results

2. **Grounding Rate**: Are the returned results valid KB entities?
   - Measures: `(valid KB entities returned) / (total entities returned)`
   - Pure LLM may hallucinate entities that don't exist in the KB

## Expected Results

```
Approach        Recall      Grounding Rate   Analysis
-----------     --------    --------------   --------
SPARQL          0%          N/A              Cannot find missing links (baseline)
Pure LLM        ~40-60%     ~20-40%          Finds some answers but hallucinates
GenSPARQL       ~30-50%     ~90-100%         Lower raw recall but all answers valid
```

**Key Insight**: While Pure LLM might have higher raw recall, GenSPARQL provides **trustworthy results** that are guaranteed to be valid KB entities.

## Running the Experiment

### Prerequisites

1. Build the project:
   ```bash
   cd /path/to/jena_llm
   mvn compile -DskipTests
   ```

2. Set OpenRouter API key (for Pure LLM):
   ```bash
   export OPENROUTER_API_KEY=your-api-key
   ```

### Quick Start

```bash
# Generate experiment cases and run all approaches
./run.sh --generate

# Run with fewer cases for quick test
./run.sh --generate --max-cases 10

# Run only specific approaches
./run.sh --approaches sparql gensparql
```

### Step by Step

1. **Generate experiment cases**:
   ```bash
   python3 generate_experiment.py --dataset NELL995+H --num-cases 50
   ```

2. **Run experiment**:
   ```bash
   python3 run_experiment.py --dataset NELL995+H
   ```

3. **View results**:
   ```bash
   cat NELL995+H/results.json
   ```

## Output Structure

```
MissingLinkCompletion/
├── generate_experiment.py    # Generates test cases
├── run_experiment.py         # Runs evaluation
├── run.sh                    # Main orchestration script
├── NELL995+H/                # Experiment for this dataset
│   ├── metadata.json         # Experiment metadata
│   ├── expected_answers.json # Ground truth
│   ├── sparql_queries/       # Standard SPARQL queries
│   ├── pure_llm_queries/     # Pure LLM prompts
│   ├── gensparql_queries/    # GenSPARQL queries
│   └── results.json          # Evaluation results
```

## Story

> **"GenSPARQL provides the bridge between open-world generation and closed-world validity."**

- **SPARQL alone** cannot find missing links (Closed World Assumption)
- **LLM alone** can find answers but may hallucinate invalid entities
- **GenSPARQL** combines the best of both: LLM's knowledge + KB grounding

## Customization

### Using Different Datasets

```bash
./run.sh --generate --dataset FB15K-237
```

### Adjusting Candidates

In `generate_experiment.py`, modify `--max-candidates` to control the grounding candidate pool:
```bash
python3 generate_experiment.py --max-candidates 500
```

### Using Different LLM Models

Edit the GENOP queries in `generate_experiment.py` to use different models:
```sparql
GENOP("...", (?target), <model:openrouter:anthropic/claude-3-sonnet>)
```
