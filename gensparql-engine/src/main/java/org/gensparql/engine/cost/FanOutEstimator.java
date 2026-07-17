package org.gensparql.engine.cost;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Estimates the fan-out F of a GENOP — the number of result rows a single call produces —
 * for the cost model (C1). Component C2, tiers 1 + 3 of the design in
 * docs/RESEARCH_IDEA1_PLAN.md.
 *
 * <ul>
 *   <li><b>Tier 1 (static prompt prior):</b> read the prompt template for an explicit
 *       cardinal ("list five", "top 10"), a list intent ("list/all/name…" ⇒ a list default),
 *       or a single-answer intent ("the capital of", "which…" ⇒ 1).</li>
 *   <li><b>Tier 3 (online feedback):</b> as real calls return, blend observed fan-out into
 *       the estimate with a pseudo-count prior so early bindings are dominated by the prior
 *       and later ones by observation.</li>
 * </ul>
 *
 * <p>Tier 2 (grounding survival) and the KG-structural prior over the grounding relation are
 * left for a later pass; the distinct-prompt count D (the other cost-model input) is a graph
 * statistic supplied by {@link KgStats}.
 */
public final class FanOutEstimator {

    /** Default fan-out for an unmistakable list request with no explicit count. */
    public static final double DEFAULT_LIST_SIZE = 10.0;
    /** Default fan-out when intent is unclear. */
    public static final double DEFAULT_UNKNOWN = 5.0;
    /** Pseudo-count weight of the prior against online observations. */
    private static final double PRIOR_WEIGHT = 3.0;

    private static final Map<String, Integer> NUMBER_WORDS = Map.ofEntries(
            Map.entry("one", 1), Map.entry("two", 2), Map.entry("three", 3),
            Map.entry("four", 4), Map.entry("five", 5), Map.entry("six", 6),
            Map.entry("seven", 7), Map.entry("eight", 8), Map.entry("nine", 9),
            Map.entry("ten", 10), Map.entry("twenty", 20), Map.entry("fifty", 50));
    // A count tied to a list/generation verb — avoids reading an incidental number
    // (e.g. "in 2 sentences") as the result cardinality. Matches digits or number words.
    private static final Pattern LIST_COUNT = Pattern.compile(
            "\\b(?:list|top|name|give|select|provide|generate|return|find)\\s+"
            + "(?:up to\\s+|the\\s+)?(\\d{1,3}|" + String.join("|", NUMBER_WORDS.keySet()) + ")\\b");
    private static final String[] LIST_WORDS = {
            "list", "all", "enumerate", "several", "examples", "kinds", "types", "many"};
    private static final String[] SINGLE_WORDS = {
            "the capital of", "which ", "what is the", "who is the", "the name of"};

    private final double prior;
    private double sumObserved = 0.0;
    private long nObserved = 0;

    private FanOutEstimator(double prior) {
        this.prior = prior;
    }

    /** Build an estimator whose prior comes from the prompt template (tier 1). */
    public static FanOutEstimator fromPrompt(String promptTemplate) {
        return new FanOutEstimator(promptPrior(promptTemplate));
    }

    /** Build an estimator with an explicit prior (e.g. a KG-structural prior). */
    public static FanOutEstimator withPrior(double prior) {
        return new FanOutEstimator(Math.max(0.0, prior));
    }

    /**
     * Tier 1: static fan-out prior from prompt text. Priority: explicit list-count
     * ("list 5", "name three") &gt; single-answer intent ("the capital of", "which") &gt;
     * list intent ("list", "all") &gt; default. Newline-safe (works on multi-line prompts).
     */
    public static double promptPrior(String template) {
        if (template == null || template.isBlank()) {
            return DEFAULT_UNKNOWN;
        }
        String t = template.toLowerCase();

        // 1. Explicit count tied to a list/generation verb ("list 5", "name three").
        Matcher m = LIST_COUNT.matcher(t);
        if (m.find()) {
            Integer n = parseCount(m.group(1));
            if (n != null && n >= 1 && n <= 100) {
                return n;
            }
        }
        // 2. Single-answer intent.
        for (String s : SINGLE_WORDS) {
            if (t.contains(s)) {
                return 1.0;
            }
        }
        // 3. List intent.
        for (String w : LIST_WORDS) {
            if (containsWord(t, w)) {
                return DEFAULT_LIST_SIZE;
            }
        }
        return DEFAULT_UNKNOWN;
    }

    /**
     * Tier 2: expected fraction of generated candidates that ground to a KG entity at the given
     * threshold. Heuristic prior, decreasing in θ, anchored on the repo's E1 grounding rate
     * (~0.32 at θ=0.85; see docs/EVALUATION.md): {@code survival(θ) = 1 − 0.8·θ}, range [0.2, 1].
     * (Runtime online calibration of this rate is future work — the estimator is not yet fed
     * per-execution grounded/generated counts.)
     */
    public static double survivalPrior(double groundingThreshold) {
        double t = Math.max(0.0, Math.min(1.0, groundingThreshold));
        return 1.0 - 0.8 * t;
    }

    /** Effective (post-grounding) fan-out = raw fan-out × {@link #survivalPrior(double)}. */
    public static double effectiveFanOut(double rawFanOut, double groundingThreshold) {
        return rawFanOut * survivalPrior(groundingThreshold);
    }

    private static Integer parseCount(String token) {
        try {
            return Integer.valueOf(token);
        } catch (NumberFormatException e) {
            return NUMBER_WORDS.get(token);
        }
    }

    /** Word-boundary containment that (unlike String.matches with ".*") works across newlines. */
    private static boolean containsWord(String text, String word) {
        return Pattern.compile("\\b" + Pattern.quote(word) + "\\b").matcher(text).find();
    }

    /** Tier 3: record an observed fan-out (rows produced by an actual call). */
    public void observe(long actualRows) {
        if (actualRows < 0) {
            throw new IllegalArgumentException("actualRows must be non-negative");
        }
        sumObserved += actualRows;
        nObserved++;
    }

    /** Current fan-out estimate: prior blended with observations via a pseudo-count. */
    public double estimate() {
        return (PRIOR_WEIGHT * prior + sumObserved) / (PRIOR_WEIGHT + nObserved);
    }

    public double prior() {
        return prior;
    }

    public long observations() {
        return nObserved;
    }
}
