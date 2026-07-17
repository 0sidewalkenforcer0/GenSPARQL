package org.gensparql.engine.iterator;

import org.apache.jena.atlas.io.IndentedWriter;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.engine.ExecutionContext;
import org.apache.jena.sparql.engine.QueryIterator;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.engine.binding.BindingBuilder;
import org.apache.jena.sparql.engine.binding.BindingFactory;
import org.apache.jena.sparql.engine.iterator.QueryIteratorBase;
import org.apache.jena.sparql.serializer.SerializationContext;
import org.gensparql.core.model.*;
import org.gensparql.core.metrics.LLMCallMetrics;
import org.gensparql.core.util.PromptTemplate;
import org.gensparql.engine.GenSPARQLConfig;
import org.gensparql.engine.candidate.CandidateExtractor;
import org.gensparql.engine.grounding.EntityGrounder;
import org.gensparql.engine.op.OpGenerate;
import org.gensparql.llm.LLMProvider;
import org.gensparql.llm.LLMProviderRegistry;
import org.gensparql.llm.batch.BatchedPromptBuilder;
import org.gensparql.llm.batch.BatchedResponseParser;
import org.gensparql.llm.prompt.PromptBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Query iterator for OpGenerate execution.
 *
 * For each input binding:
 * 1. Resolves the prompt template with bound variables
 * 2. Calls the LLM to generate outputs (with optional batching)
 * 3. Parses the outputs and binds them to output variables
 * 4. Produces new bindings combining input + output
 *
 * Supports batching when GenSPARQLConfig.isBatchingEnabled() is true.
 * Collects timing metrics for performance analysis.
 */
public class QueryIterGenerate extends QueryIteratorBase {
    private static final Logger LOG = LoggerFactory.getLogger(QueryIterGenerate.class);

    private final QueryIterator input;
    private final OpGenerate opGen;
    private final ExecutionContext execCxt;
    private final LLMProvider provider;
    private final PromptTemplate template;

    // Candidate extraction for constrained generation
    private final CandidateExtractor candidateExtractor;
    private final boolean constrainedMode;
    private final String candidatesRelation;
    private Set<String> cachedCandidates;

    // Entity grounding for mapping LLM outputs to KG entities
    private final EntityGrounder entityGrounder;
    private final boolean groundingEnabled;
    private final String groundingRelation;
    private final double groundingThreshold;

    // Metrics collection
    private final List<LLMCallMetrics> llmMetrics = new ArrayList<>();

    // Batching support
    private final boolean batchingEnabled;
    private final int batchSize;
    private final List<Binding> batchInputs = new ArrayList<>();
    private final List<String> batchPrompts = new ArrayList<>();
    private Iterator<Binding> batchResults = null;

    // C3: cross-binding prompt deduplication (per-query memo of parsed outputs).
    private final boolean dedupEnabled;
    private final Map<String, List<Map<String, String>>> promptMemo = new HashMap<>();
    private int dedupSavedCalls = 0;

    // Grounding result cache (raw value -> resolved Node): grounding is deterministic for a
    // fixed relation/threshold, so a value repeated across bindings is grounded only once
    // (D times, not N) — this completes the dedup saving on grounding-enabled runs.
    private final Map<String, Node> groundedNodeCache = new HashMap<>();
    private int groundingCacheHits = 0;

    private Iterator<Binding> currentResults;
    private Binding currentInputBinding;
    private boolean exhausted = false;

    public QueryIterGenerate(QueryIterator input, OpGenerate opGen, ExecutionContext execCxt) {
        this.input = input;
        this.opGen = opGen;
        this.execCxt = execCxt;
        this.template = new PromptTemplate(opGen.getPromptTemplate());

        System.out.println("[DEBUG] QueryIterGenerate constructor called");
        System.out.println("[DEBUG] OpGenerate: " + opGen);
        System.out.println("[DEBUG] Input variables: " + opGen.getInputVariables());
        System.out.println("[DEBUG] Output variables: " + opGen.getOutputVariables());

        // Get LLM provider
        ModelSpec modelSpec = opGen.getModelSpec();
        if (modelSpec != null) {
            this.provider = LLMProviderRegistry.getFromSpec(modelSpec);
        } else {
            this.provider = LLMProviderRegistry.getDefault();
        }

        System.out.println("[DEBUG] LLM Provider: " + provider.getName());
        System.out.println("[DEBUG] Provider available: " + provider.isAvailable());

        // Configure batching
        this.batchingEnabled = GenSPARQLConfig.isBatchingEnabled();
        this.batchSize = GenSPARQLConfig.getBatchSize();
        this.dedupEnabled = GenSPARQLConfig.isBatchDedupEnabled();

        // Configure constrained generation
        // Check if candidates_relation is specified in options
        Object relObj = opGen.getOptions().get("candidates_relation");
        this.candidatesRelation = relObj != null ? relObj.toString() : null;
        this.constrainedMode = GenSPARQLConfig.isConstrainedGenerationEnabled() && candidatesRelation != null;

        if (constrainedMode) {
            this.candidateExtractor = new CandidateExtractor(execCxt.getDataset());
            System.out.println("[DEBUG] Constrained generation enabled with relation: " + candidatesRelation);
        } else {
            this.candidateExtractor = null;
        }

        // Configure embedding-based grounding
        // Check for grounding_relation option (takes precedence) or use candidates_relation
        // If neither is specified, grounding will use the global entity index
        Object groundRelObj = opGen.getOptions().get("grounding_relation");
        this.groundingRelation = groundRelObj != null ? groundRelObj.toString() : candidatesRelation;
        // Enable grounding if configured - null relation means use global index
        this.groundingEnabled = GenSPARQLConfig.isGroundingEnabled();
        System.out.println("[DEBUG] Grounding config check: isGroundingEnabled=" + GenSPARQLConfig.isGroundingEnabled() +
                ", groundingRelation=" + groundingRelation);

        // Get grounding threshold from options or config
        Object thresholdObj = opGen.getOptions().get("grounding_threshold");
        this.groundingThreshold = thresholdObj != null ?
                Double.parseDouble(thresholdObj.toString()) :
                GenSPARQLConfig.getGroundingThreshold();

        if (groundingEnabled) {
            this.entityGrounder = new EntityGrounder(execCxt.getDataset(), this.provider);
            this.entityGrounder.setDefaultThreshold(groundingThreshold);
            this.entityGrounder.setBatchSize(GenSPARQLConfig.getGroundingBatchSize());
            LOG.debug("Embedding-based grounding enabled with relation: {}, threshold: {}",
                    groundingRelation, groundingThreshold);
        } else {
            this.entityGrounder = null;
        }

        LOG.debug("QueryIterGenerate created: {} output vars, {} input vars, batching={}, constrained={}",
                opGen.getOutputVariables().size(), opGen.getInputVariables().size(), batchingEnabled, constrainedMode);
    }

    @Override
    protected boolean hasNextBinding() {
        System.out.println("[DEBUG] hasNextBinding called, exhausted=" + exhausted);
        if (exhausted) {
            return false;
        }

        // If batching is enabled, use batch processing.
        // NOTE: C3 cross-binding dedup (promptMemo) is applied on the standard path below;
        // the batched path coalesces per batch and does not currently also apply dedup.
        if (batchingEnabled && !opGen.isBaseMode()) {
            System.out.println("[DEBUG] Using batched processing");
            return hasNextBindingBatched();
        }

        System.out.println("[DEBUG] Using standard (non-batched) processing");

        // Standard (non-batched) processing
        while (currentResults == null || !currentResults.hasNext()) {
            boolean hasNext = input.hasNext();
            System.out.println("[DEBUG] input.hasNext() = " + hasNext);
            if (!hasNext) {
                exhausted = true;
                System.out.println("[DEBUG] No more input, exhausted=true");
                return false;
            }

            currentInputBinding = input.next();
            System.out.println("[DEBUG] Got input binding: " + currentInputBinding);

            if (opGen.isBaseMode()) {
                System.out.println("[DEBUG] Base mode - generating with template");
                currentResults = generateBindings(currentInputBinding, opGen.getPromptTemplate());
            } else {
                if (!template.allVariablesBound(currentInputBinding)) {
                    System.out.println("[DEBUG] Skipping binding - not all input variables bound");
                    System.out.println("[DEBUG] Unbound variables: " + template.getUnboundVariables(currentInputBinding));
                    LOG.debug("Skipping binding - not all input variables bound: {}",
                            template.getUnboundVariables(currentInputBinding));
                    continue;
                }

                String resolvedPrompt = template.resolve(currentInputBinding);
                System.out.println("[DEBUG] Resolved prompt: " + resolvedPrompt);
                currentResults = generateBindings(currentInputBinding, resolvedPrompt);
            }
        }

        boolean result = currentResults.hasNext();
        System.out.println("[DEBUG] hasNextBinding returning: " + result);
        return result;
    }

    /**
     * Batched version of hasNextBinding.
     * Accumulates bindings until batch size is reached, then processes in one LLM call.
     */
    private boolean hasNextBindingBatched() {
        // Return results from previous batch
        if (batchResults != null && batchResults.hasNext()) {
            return true;
        }

        // Accumulate bindings for a batch
        while (batchInputs.size() < batchSize && input.hasNext()) {
            Binding inputBinding = input.next();

            if (!template.allVariablesBound(inputBinding)) {
                LOG.debug("Skipping binding - not all input variables bound");
                continue;
            }

            String resolvedPrompt = template.resolve(inputBinding);
            batchInputs.add(inputBinding);
            batchPrompts.add(resolvedPrompt);
        }

        // Execute batch if we have accumulated bindings
        if (!batchInputs.isEmpty()) {
            boolean hasInput = input.hasNext();
            if (batchInputs.size() >= batchSize || !hasInput) {
                executeBatch();
                if (batchResults != null && batchResults.hasNext()) {
                    return true;
                }
            }
        }

        // No more input and no pending results
        if (!input.hasNext() && batchInputs.isEmpty() &&
            (batchResults == null || !batchResults.hasNext())) {
            exhausted = true;
            return false;
        }

        return batchResults != null && batchResults.hasNext();
    }

    /**
     * Execute a batch of LLM requests.
     */
    private void executeBatch() {
        LOG.debug("Executing batch of {} prompts", batchPrompts.size());

        List<String> outputVarNames = new ArrayList<>();
        for (Var v : opGen.getOutputVariables()) {
            outputVarNames.add(v.getName());
        }

        try {
            // Build batched prompt
            String batchedPrompt = BatchedPromptBuilder.buildBatchedPrompt(
                batchPrompts, outputVarNames);

            // Create request
            ModelSpec modelSpec = opGen.getModelSpec();
            GenerateRequest request = GenerateRequest.builder()
                    .prompt(batchedPrompt)
                    .modelSpec(modelSpec)
                    .outputVariables(outputVarNames)
                    .build();

            // Call LLM once for entire batch
            long startTime = System.currentTimeMillis();
            GenerateResponse response = provider.generateSync(request);
            long latency = System.currentTimeMillis() - startTime;

            // Record metrics for batch
            recordMetrics(batchedPrompt, latency, response, true, batchPrompts.size());

            if (!response.isSuccess()) {
                LOG.warn("Batched LLM generation failed: {}", response.getErrorMessage());
                batchResults = Collections.emptyIterator();
                return;
            }

            // Parse batched response
            List<Map<String, String>> allResults = BatchedResponseParser.parse(
                response.getRawText(), batchPrompts.size());

            // Create bindings from results
            List<Binding> results = new ArrayList<>();
            for (int i = 0; i < batchInputs.size(); i++) {
                Map<String, String> outputBinding = allResults.get(i);
                Map<String, String> validated = validateOutputBinding(outputBinding);
                if (validated != null) {
                    Binding newBinding = createBinding(batchInputs.get(i), validated);
                    if (newBinding != null) {
                        results.add(newBinding);
                    }
                }
            }

            batchResults = results.iterator();
            LOG.debug("Batch generated {} output bindings from {} prompts",
                results.size(), batchPrompts.size());

        } catch (Exception e) {
            LOG.error("Error during batched LLM generation", e);
            batchResults = Collections.emptyIterator();
        } finally {
            // Clear batch state
            batchInputs.clear();
            batchPrompts.clear();
        }
    }

    @Override
    protected Binding moveToNextBinding() {
        if (batchingEnabled && !opGen.isBaseMode()) {
            return batchResults.next();
        }
        return currentResults.next();
    }

    /**
     * Generate bindings by calling the LLM (non-batched).
     *
     * With C3 deduplication enabled, the parsed output maps for an identical resolved
     * prompt are memoized per query execution: the LLM is called only for the first
     * occurrence of a prompt, and later bindings that resolve to the same prompt reuse
     * those outputs (fanned out against their own input binding). Lossless — the result
     * is identical to calling the LLM every time, minus the redundant calls.
     */
    private Iterator<Binding> generateBindings(Binding inputBinding, String prompt) {
        LOG.debug("Generating with prompt: {}", truncate(prompt, 100));

        List<String> outputVarNames = new ArrayList<>();
        for (Var v : opGen.getOutputVariables()) {
            outputVarNames.add(v.getName());
        }

        // Compute the prompt actually sent to the LLM (may include KG candidates).
        // Guarded: a candidate-extraction / dataset failure must skip this binding, not
        // abort the whole query iteration.
        String finalPrompt = prompt;
        try {
            if (constrainedMode && candidateExtractor != null) {
                Set<String> candidates = getCandidates();
                if (!candidates.isEmpty()) {
                    finalPrompt = PromptBuilder.buildConstrainedPrompt(
                            prompt, candidates, outputVarNames,
                            GenSPARQLConfig.getMaxCandidates());
                }
            }
        } catch (Exception e) {
            LOG.error("Error building constrained prompt; skipping binding", e);
            return Collections.emptyIterator();
        }

        // C3: reuse parsed outputs for a prompt already generated this execution.
        List<Map<String, String>> outputMaps;
        if (dedupEnabled && promptMemo.containsKey(finalPrompt)) {
            outputMaps = promptMemo.get(finalPrompt);
            dedupSavedCalls++;
            LOG.debug("Dedup hit: reusing {} output map(s) for repeated prompt (saved {} call(s))",
                    outputMaps.size(), dedupSavedCalls);
        } else {
            outputMaps = callAndParse(finalPrompt, outputVarNames);
            if (outputMaps == null) {
                // Failed call: produce no rows and do NOT memoize, so an identical later
                // prompt is retried rather than permanently poisoned with an empty result.
                outputMaps = Collections.emptyList();
            } else if (dedupEnabled) {
                promptMemo.put(finalPrompt, outputMaps);
            }
        }

        // Expand the (input-independent) output maps against THIS input binding.
        List<Binding> results = new ArrayList<>();
        for (Map<String, String> outputValues : outputMaps) {
            Binding newBinding = createBinding(inputBinding, outputValues);
            if (newBinding != null) {
                results.add(newBinding);
            }
        }
        LOG.debug("Produced {} output binding(s) from {} output map(s)", results.size(), outputMaps.size());
        return results.iterator();
    }

    /**
     * Call the LLM for a single resolved prompt and parse its response into a list of
     * validated output-variable maps (independent of any input binding). Returns an
     * {@code null} on a failed/errored call (so the caller can avoid memoizing it), or the
     * parsed maps (possibly empty for a successful-but-empty response). This is the unit
     * that C3 deduplication memoizes.
     */
    private List<Map<String, String>> callAndParse(String finalPrompt, List<String> outputVarNames) {
        List<Map<String, String>> outputMaps = new ArrayList<>();
        try {
            GenerateRequest request = GenerateRequest.builder()
                    .prompt(finalPrompt)
                    .modelSpec(opGen.getModelSpec())
                    .outputVariables(outputVarNames)
                    .build();

            LOG.debug("Calling LLM: {}", provider.getName());
            long startTime = System.currentTimeMillis();
            GenerateResponse response = provider.generateSync(request);
            long latency = System.currentTimeMillis() - startTime;

            recordMetrics(finalPrompt, latency, response, false, 1);

            if (!response.isSuccess()) {
                LOG.warn("LLM generation failed: {}", response.getErrorMessage());
                return null;
            }

            if (response.hasBindings()) {
                for (Map<String, String> outputBinding : response.getBindings()) {
                    Map<String, String> validatedBinding = validateOutputBinding(outputBinding);
                    if (validatedBinding != null) {
                        outputMaps.add(validatedBinding);
                    } else {
                        LOG.warn("Filtered out invalid output binding: {}", truncate(outputBinding.toString(), 200));
                    }
                }
            } else if (response.getRawText() != null && opGen.getOutputVariables().size() == 1) {
                // Fallback: use raw text for a single output variable.
                String rawText = response.getRawText().trim();
                if (isValidResponse(rawText)) {
                    outputMaps.add(Map.of(opGen.getOutputVariables().get(0).getName(), rawText));
                } else {
                    LOG.warn("Filtered out invalid raw text response: {}", truncate(rawText, 200));
                }
            }
        } catch (Exception e) {
            LOG.error("Error during LLM generation", e);
            return null;
        }
        return outputMaps;
    }

    /**
     * Record metrics for an LLM call.
     */
    private void recordMetrics(String prompt, long latency, GenerateResponse response,
                               boolean isBatch, int batchSize) {
        if (!GenSPARQLConfig.isTimingEnabled()) {
            return;
        }

        LLMCallMetrics.Builder builder = LLMCallMetrics.builder()
                .latencyMs(latency)
                .promptLength(prompt.length())
                .promptPreview(truncate(prompt, 100))
                .providerName(provider.getName())
                .partOfBatch(isBatch);

        if (isBatch) {
            builder.batchId(llmMetrics.size()).positionInBatch(0);
        }

        // Extract token counts from response metadata if available
        if (response != null && response.getMetadata() != null) {
            GenerateResponse.ResponseMetadata metadata = response.getMetadata();
            builder.promptTokens(metadata.getPromptTokens())
                   .completionTokens(metadata.getCompletionTokens());
        }

        llmMetrics.add(builder.build());
    }

    /**
     * Get collected LLM metrics.
     */
    public List<LLMCallMetrics> getLLMMetrics() {
        return Collections.unmodifiableList(llmMetrics);
    }

    /**
     * Number of LLM calls avoided by C3 cross-binding prompt deduplication in this
     * execution (i.e. repeated-prompt hits). Zero when dedup is disabled.
     */
    public int getDedupSavedCalls() {
        return dedupSavedCalls;
    }

    /**
     * Number of grounding computations avoided by reusing a cached resolved node for a value
     * already grounded in this execution. Zero when grounding is disabled.
     */
    public int getGroundingCacheHits() {
        return groundingCacheHits;
    }

    /**
     * Create a new binding by merging input binding with generated outputs.
     * If grounding is enabled, LLM outputs are mapped to KG entities using embedding similarity.
     */
    private Binding createBinding(Binding inputBinding, Map<String, String> outputValues) {
        BindingBuilder builder = BindingFactory.builder(inputBinding);

        for (Var var : opGen.getOutputVariables()) {
            String value = outputValues.get(var.getName());
            if (value != null) {
                Node node;

                // Apply embedding-based grounding if enabled, memoized per raw value so a
                // value repeated across bindings (common under C3 dedup) is grounded once.
                if (groundingEnabled && entityGrounder != null) {
                    Node cached = groundedNodeCache.get(value);
                    if (cached != null) {
                        groundingCacheHits++;
                        node = cached;
                    } else {
                        GroundingResult groundingResult = entityGrounder.ground(
                                value, groundingRelation, groundingThreshold);
                        if (groundingResult.isGrounded()) {
                            node = groundingResult.toNode();
                            LOG.debug("Grounded '{}' -> '{}' (sim={})",
                                    value, groundingResult.getGroundedLabel(),
                                    String.format("%.4f", groundingResult.getSimilarity()));
                        } else {
                            node = NodeFactory.createLiteralString(value);
                            LOG.debug("Grounding failed for '{}', keeping original", value);
                        }
                        groundedNodeCache.put(value, node);
                    }
                } else {
                    // No grounding, use raw LLM output
                    node = NodeFactory.createLiteralString(value);
                }

                builder.add(var, node);
            }
        }

        return builder.build();
    }

    @Override
    protected void closeIterator() {
        if (input != null) {
            input.close();
        }
    }

    @Override
    protected void requestCancel() {
        // Cancel not supported for LLM calls
    }

    @Override
    public void output(IndentedWriter out, SerializationContext sCxt) {
        out.print("QueryIterGenerate(");
        out.print(opGen.getPromptTemplate());
        out.print(")");
    }

    /**
     * Validate output binding values to filter out invalid responses.
     */
    private Map<String, String> validateOutputBinding(Map<String, String> binding) {
        if (binding == null || binding.isEmpty()) {
            return null;
        }

        Map<String, String> validated = new HashMap<>();
        for (Map.Entry<String, String> entry : binding.entrySet()) {
            String value = entry.getValue();
            if (value == null || value.trim().isEmpty()) {
                // Present-but-empty field: leave this variable unbound and keep the row,
                // rather than dropping the whole (possibly multi-variable) binding.
                continue;
            }
            if (isValidResponse(value)) {
                validated.put(entry.getKey(), value);
            } else {
                // Genuine garbage in a field: reject the whole row.
                return null;
            }
        }
        return validated.isEmpty() ? null : validated;
    }

    /**
     * Check if a response text is valid (not corrupted/garbled).
     */
    private boolean isValidResponse(String text) {
        if (text == null || text.trim().isEmpty()) {
            return false;
        }

        String trimmed = text.trim();

        // Check for excessive special characters or code-like patterns
        if (trimmed.matches(".*[{}()\\[\\];,]+.*[{}()\\[\\];,]+.*[{}()\\[\\];,]+.*")) {
            long bracketCount = trimmed.chars().filter(c -> c == '{' || c == '}' || c == '(' || c == ')' || c == '[' || c == ']').count();
            if (bracketCount > trimmed.length() / 10) {
                LOG.debug("Rejecting response with excessive brackets: {}", truncate(trimmed, 100));
                return false;
            }
        }

        // Check for code-like keywords
        String lowerText = trimmed.toLowerCase();
        String[] codeKeywords = {"lambda", "def ", "print(", "except:", "import ", "return ", "if __name__", "class ", "def ", "->"};
        int codeKeywordCount = 0;
        for (String keyword : codeKeywords) {
            if (lowerText.contains(keyword)) {
                codeKeywordCount++;
            }
        }
        if (codeKeywordCount >= 2) {
            LOG.debug("Rejecting response with code keywords: {}", truncate(trimmed, 100));
            return false;
        }

        // Check for excessive non-alphabetic characters
        long nonAlphaCount = trimmed.chars().filter(c -> !Character.isLetterOrDigit(c) && !Character.isWhitespace(c)
                && c != '.' && c != ',' && c != ';' && c != ':' && c != '!' && c != '?' && c != '-' && c != '\'' && c != '"').count();
        double nonAlphaRatio = (double) nonAlphaCount / trimmed.length();
        if (nonAlphaRatio > 0.3 && trimmed.length() > 50) {
            LOG.debug("Rejecting response with high special character ratio: {}", truncate(trimmed, 100));
            return false;
        }

        // Check for corruption patterns
        if (trimmed.matches(".*,\\d+\\}\\}.*") || trimmed.matches(".*[,\\s]{3,}.*[,\\s]{3,}.*")) {
            LOG.debug("Rejecting response with suspicious corruption patterns: {}", truncate(trimmed, 100));
            return false;
        }

        // Check for excessive commas
        long commaCount = trimmed.chars().filter(c -> c == ',').count();
        if (commaCount > 0 && commaCount > trimmed.length() / 20) {
            if (trimmed.matches(".*,\\s*[=–\\d]+.*") || trimmed.matches(".*,\\s*[a-z]\\s*,.*")) {
                LOG.debug("Rejecting response with excessive comma patterns: {}", truncate(trimmed, 100));
                return false;
            }
        }

        // Check for patterns starting with corruption indicators
        if (trimmed.startsWith(", ") || trimmed.startsWith("=, ") || trimmed.matches("^[,=–\\d\\s]+[a-z].*")) {
            LOG.debug("Rejecting response starting with corruption patterns: {}", truncate(trimmed, 100));
            return false;
        }

        // Check for very low ratio of actual words
        String[] words = trimmed.split("\\s+");
        int validWordCount = 0;
        for (String word : words) {
            long letterCount = word.chars().filter(Character::isLetter).count();
            if (letterCount >= 2) {
                validWordCount++;
            }
        }
        if (words.length > 10 && validWordCount < words.length * 0.3) {
            LOG.debug("Rejecting response with too few valid words: {}/{}", validWordCount, words.length);
            return false;
        }

        return true;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }

    /**
     * Get candidate entities for constrained generation.
     * Extracts candidates from KG based on the candidates_relation option.
     */
    private Set<String> getCandidates() {
        if (cachedCandidates != null) {
            return cachedCandidates;
        }

        if (candidateExtractor == null || candidatesRelation == null) {
            return Collections.emptySet();
        }

        // Check if we should extract subjects or objects
        // Default to objects (most common case: finding tail entities)
        Object extractMode = opGen.getOptions().get("candidates_mode");
        String mode = extractMode != null ? extractMode.toString() : "object";

        Set<String> candidates;
        switch (mode.toLowerCase()) {
            case "subject":
                candidates = candidateExtractor.extractSubjectCandidates(candidatesRelation);
                break;
            case "all":
                candidates = candidateExtractor.extractAllCandidates(candidatesRelation);
                break;
            case "object":
            default:
                candidates = candidateExtractor.extractObjectCandidates(candidatesRelation);
                break;
        }

        LOG.debug("Extracted {} candidates for relation {} (mode={})",
                candidates.size(), candidatesRelation, mode);

        cachedCandidates = candidates;
        return candidates;
    }
}
