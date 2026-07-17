package org.gensparql.engine.cost;

/**
 * Cost model for a generative operator (GENOP) — component C1 of the cost-aware planner.
 *
 * <p>In stock GenSPARQL a GENOP is invisible to the optimizer ({@code OpGenerate.effectiveOp()}
 * returns a unit table), so it cannot be costed or reordered. This model gives the planner a
 * quantitative estimate of what executing a GENOP over its input will cost, along the
 * dimensions that matter for LLM operators: number of LLM calls, tokens, dollars, latency,
 * and produced rows.
 *
 * <p>The central relation it encodes (the C2/C3 bridge): with cross-binding prompt
 * deduplication, the number of <em>LLM calls</em> is the number of <em>distinct</em> prompts
 * {@code D}, not the number of input bindings {@code N} — while the number of <em>result
 * rows</em> is still {@code N × F} (every binding is expanded against the reused output).
 * {@code D} is the value-cardinality of the join/context variable in the KG, a graph
 * statistic knowable before execution; {@code F} (fan-out) is the C2 estimate.
 *
 * <p>Pure, deterministic arithmetic — no LLM calls. Token counts use the repository's
 * chars/4 heuristic (cf. {@code BatchedPromptBuilder}, {@code TokenCostFunction}); callers
 * that have real tokenizer counts should pass them directly.
 */
public final class GenOpCostModel {

    /** Repository-wide rough token heuristic: ~4 characters per token. */
    public static final double CHARS_PER_TOKEN = 4.0;

    private final double dollarsPerMTokIn;
    private final double dollarsPerMTokOut;
    private final double perCallLatencyMs;
    private final int concurrency;

    private GenOpCostModel(Builder b) {
        this.dollarsPerMTokIn = b.dollarsPerMTokIn;
        this.dollarsPerMTokOut = b.dollarsPerMTokOut;
        this.perCallLatencyMs = b.perCallLatencyMs;
        this.concurrency = Math.max(1, b.concurrency);
    }

    /**
     * Inputs describing a GENOP invocation over its bound input.
     *
     * @param inputBindings    N — number of input bindings the GENOP fires over
     * @param distinctPrompts  D — number of distinct resolved prompts (≤ N); equals N when no
     *                         dedup / all prompts unique, and the KG join-var value-cardinality
     *                         when dedup applies
     * @param fanOut           F — expected result rows produced per successful call
     * @param promptTokens     estimated prompt tokens per call
     * @param completionTokens estimated completion tokens per call
     */
    public record Inputs(long inputBindings, long distinctPrompts, double fanOut,
                         int promptTokens, int completionTokens) {
        public Inputs {
            if (inputBindings < 0 || distinctPrompts < 0) {
                throw new IllegalArgumentException("cardinalities must be non-negative");
            }
            if (distinctPrompts > inputBindings) {
                throw new IllegalArgumentException("distinctPrompts (" + distinctPrompts
                        + ") cannot exceed inputBindings (" + inputBindings + ")");
            }
            if (fanOut < 0) {
                throw new IllegalArgumentException("fanOut must be non-negative");
            }
        }
    }

    /** Estimated cost of executing the GENOP. */
    public record Estimate(long llmCalls, long totalTokens, double dollars,
                           double latencyMs, long outputRows) {
        /** Scalar the planner minimizes; LLM calls dominate GENOP cost. */
        public double dominantCost() {
            return llmCalls;
        }
    }

    /**
     * Estimate cost. With dedup, LLM calls = distinct prompts (D); without, = input bindings (N).
     * Output rows are always N × F (dedup reuses outputs, it does not drop rows).
     */
    public Estimate estimate(Inputs in, boolean dedupEnabled) {
        long llmCalls = dedupEnabled ? in.distinctPrompts() : in.inputBindings();
        long perCallTokens = (long) in.promptTokens() + in.completionTokens();
        long totalTokens = llmCalls * perCallTokens;
        double dollars = llmCalls * (in.promptTokens() * dollarsPerMTokIn
                + in.completionTokens() * dollarsPerMTokOut) / 1_000_000.0;
        double latencyMs = Math.ceil((double) llmCalls / concurrency) * perCallLatencyMs;
        long outputRows = Math.round(in.inputBindings() * in.fanOut());
        return new Estimate(llmCalls, totalTokens, dollars, latencyMs, outputRows);
    }

    /** Estimate token count for a prompt string via the chars/4 heuristic. */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (int) Math.ceil(text.length() / CHARS_PER_TOKEN);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private double dollarsPerMTokIn = 0.0;
        private double dollarsPerMTokOut = 0.0;
        private double perCallLatencyMs = 0.0;
        private int concurrency = 1;

        /** Pricing in dollars per 1M input / output tokens (e.g. deepseek-chat). */
        public Builder pricing(double perMTokIn, double perMTokOut) {
            this.dollarsPerMTokIn = perMTokIn;
            this.dollarsPerMTokOut = perMTokOut;
            return this;
        }

        public Builder perCallLatencyMs(double ms) {
            this.perCallLatencyMs = ms;
            return this;
        }

        /** Concurrent in-flight calls; latency is divided by this. */
        public Builder concurrency(int c) {
            this.concurrency = c;
            return this;
        }

        public GenOpCostModel build() {
            return new GenOpCostModel(this);
        }
    }
}
