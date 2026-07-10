package org.gensparql.core.metrics;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Locale;

/**
 * Formatter for QueryExecutionMetrics.
 * Supports console, CSV, and JSON output formats.
 */
public class MetricsFormatter {

    /**
     * Format metrics as a human-readable console output.
     */
    public static String formatConsole(QueryExecutionMetrics metrics) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);

        pw.println("=== Query Execution Metrics ===");
        pw.printf("Total Time: %,d ms%n", metrics.getTotalTimeMs());

        if (metrics.getParseTimeMs() > 0) {
            pw.printf("  - Parsing: %,d ms%n", metrics.getParseTimeMs());
        }
        if (metrics.getPlanningTimeMs() > 0) {
            pw.printf("  - Planning: %,d ms%n", metrics.getPlanningTimeMs());
        }
        if (metrics.getExecutionTimeMs() > 0) {
            pw.printf("  - Execution: %,d ms%n", metrics.getExecutionTimeMs());
        }
        pw.println();

        // LLM metrics
        if (metrics.getLlmCallCount() > 0) {
            pw.printf("LLM Calls: %d%n", metrics.getLlmCallCount());
            pw.printf("  - Total LLM Time: %,d ms (%.1f%% of execution)%n",
                metrics.getTotalLlmTimeMs(), metrics.getLlmTimePercentage());
            pw.printf("  - Min: %,d ms%n", metrics.getMinLlmTimeMs());
            pw.printf("  - Max: %,d ms%n", metrics.getMaxLlmTimeMs());
            pw.printf("  - Avg: %.1f ms%n", metrics.getAvgLlmTimeMs());
            pw.println();

            // Token usage
            pw.printf("Tokens: %,d (%,d prompt + %,d completion)%n",
                metrics.getTotalTokens(),
                metrics.getTotalPromptTokens(),
                metrics.getTotalCompletionTokens());
            pw.println();
        }

        // Cache metrics
        int totalCacheChecks = metrics.getCacheHits() + metrics.getCacheMisses();
        if (totalCacheChecks > 0) {
            pw.printf("Cache: %d hits, %d misses (%.1f%% hit rate)%n",
                metrics.getCacheHits(),
                metrics.getCacheMisses(),
                metrics.getCacheHitRate() * 100);
            pw.println();
        }

        // Batch metrics
        if (metrics.getBatchCount() > 0) {
            pw.printf("Batching: %d batches, avg size %.1f%n",
                metrics.getBatchCount(),
                metrics.getAvgBatchSize());
            pw.println();
        }

        // Results
        pw.printf("Results: %d bindings%n", metrics.getResultCount());

        return sw.toString();
    }

    /**
     * Format metrics as a compact single-line summary.
     */
    public static String formatCompact(QueryExecutionMetrics metrics) {
        return String.format(
            "Total: %,dms | LLM: %dx%,dms=%.1fms | Cache: %d/%d (%.0f%%) | Batch: %d | Results: %d",
            metrics.getTotalTimeMs(),
            metrics.getLlmCallCount(),
            metrics.getTotalLlmTimeMs(),
            metrics.getAvgLlmTimeMs(),
            metrics.getCacheHits(),
            metrics.getCacheHits() + metrics.getCacheMisses(),
            metrics.getCacheHitRate() * 100,
            metrics.getBatchCount(),
            metrics.getResultCount()
        );
    }

    /**
     * Format metrics as a CSV header row.
     */
    public static String formatCSVHeader() {
        return "query_name,run_number,optimization,total_time_ms,parse_time_ms,planning_time_ms," +
               "execution_time_ms,llm_call_count,total_llm_time_ms,min_llm_time_ms,max_llm_time_ms," +
               "avg_llm_time_ms,cache_hits,cache_misses,cache_hit_rate,batch_count,avg_batch_size," +
               "prompt_tokens,completion_tokens,total_tokens,result_count";
    }

    /**
     * Format metrics as a CSV data row.
     *
     * @param queryName Name of the query
     * @param runNumber Run number (1, 2, 3, etc.)
     * @param optimization Optimization mode (BASELINE, CACHED, BATCHED, etc.)
     * @param metrics The metrics to format
     */
    public static String formatCSV(String queryName, int runNumber, String optimization,
                                   QueryExecutionMetrics metrics) {
        return String.format(Locale.US,
            "%s,%d,%s,%d,%d,%d,%d,%d,%d,%d,%d,%.2f,%d,%d,%.4f,%d,%.2f,%d,%d,%d,%d",
            queryName,
            runNumber,
            optimization,
            metrics.getTotalTimeMs(),
            metrics.getParseTimeMs(),
            metrics.getPlanningTimeMs(),
            metrics.getExecutionTimeMs(),
            metrics.getLlmCallCount(),
            metrics.getTotalLlmTimeMs(),
            metrics.getMinLlmTimeMs(),
            metrics.getMaxLlmTimeMs(),
            metrics.getAvgLlmTimeMs(),
            metrics.getCacheHits(),
            metrics.getCacheMisses(),
            metrics.getCacheHitRate(),
            metrics.getBatchCount(),
            metrics.getAvgBatchSize(),
            metrics.getTotalPromptTokens(),
            metrics.getTotalCompletionTokens(),
            metrics.getTotalTokens(),
            metrics.getResultCount()
        );
    }

    /**
     * Format metrics as JSON.
     */
    public static String formatJSON(QueryExecutionMetrics metrics) {
        return formatJSON(null, null, null, metrics);
    }

    /**
     * Format metrics as JSON with query context.
     */
    public static String formatJSON(String queryName, Integer runNumber, String optimization,
                                   QueryExecutionMetrics metrics) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");

        // Query context
        if (queryName != null) {
            sb.append(String.format("  \"query_name\": \"%s\",\n", queryName));
        }
        if (runNumber != null) {
            sb.append(String.format("  \"run_number\": %d,\n", runNumber));
        }
        if (optimization != null) {
            sb.append(String.format("  \"optimization\": \"%s\",\n", optimization));
        }

        // Timing
        sb.append(String.format("  \"total_time_ms\": %d,\n", metrics.getTotalTimeMs()));
        sb.append(String.format("  \"parse_time_ms\": %d,\n", metrics.getParseTimeMs()));
        sb.append(String.format("  \"planning_time_ms\": %d,\n", metrics.getPlanningTimeMs()));
        sb.append(String.format("  \"execution_time_ms\": %d,\n", metrics.getExecutionTimeMs()));

        // LLM metrics
        sb.append("  \"llm\": {\n");
        sb.append(String.format("    \"call_count\": %d,\n", metrics.getLlmCallCount()));
        sb.append(String.format("    \"total_time_ms\": %d,\n", metrics.getTotalLlmTimeMs()));
        sb.append(String.format("    \"min_time_ms\": %d,\n", metrics.getMinLlmTimeMs()));
        sb.append(String.format("    \"max_time_ms\": %d,\n", metrics.getMaxLlmTimeMs()));
        sb.append(String.format(Locale.US, "    \"avg_time_ms\": %.2f,\n", metrics.getAvgLlmTimeMs()));
        sb.append(String.format(Locale.US, "    \"time_percentage\": %.2f\n", metrics.getLlmTimePercentage()));
        sb.append("  },\n");

        // Token usage
        sb.append("  \"tokens\": {\n");
        sb.append(String.format("    \"prompt\": %d,\n", metrics.getTotalPromptTokens()));
        sb.append(String.format("    \"completion\": %d,\n", metrics.getTotalCompletionTokens()));
        sb.append(String.format("    \"total\": %d\n", metrics.getTotalTokens()));
        sb.append("  },\n");

        // Cache metrics
        sb.append("  \"cache\": {\n");
        sb.append(String.format("    \"hits\": %d,\n", metrics.getCacheHits()));
        sb.append(String.format("    \"misses\": %d,\n", metrics.getCacheMisses()));
        sb.append(String.format(Locale.US, "    \"hit_rate\": %.4f\n", metrics.getCacheHitRate()));
        sb.append("  },\n");

        // Batch metrics
        sb.append("  \"batch\": {\n");
        sb.append(String.format("    \"count\": %d,\n", metrics.getBatchCount()));
        sb.append(String.format(Locale.US, "    \"avg_size\": %.2f\n", metrics.getAvgBatchSize()));
        sb.append("  },\n");

        // Results
        sb.append(String.format("  \"result_count\": %d,\n", metrics.getResultCount()));

        // Timestamps
        sb.append(String.format("  \"start_time\": \"%s\",\n", metrics.getStartTime()));
        sb.append(String.format("  \"end_time\": \"%s\"\n", metrics.getEndTime()));

        sb.append("}");
        return sb.toString();
    }

    /**
     * Format a comparison table header for console output.
     */
    public static String formatComparisonHeader() {
        return String.format("%-30s | %15s | %15s | %10s | %10s | %10s",
            "Query", "Optimization", "Total Time", "LLM Time", "Cache Hit", "Speedup");
    }

    /**
     * Format a comparison row for console output.
     */
    public static String formatComparisonRow(String queryName, String optimization,
                                            QueryExecutionMetrics metrics,
                                            Double speedup) {
        String speedupStr = speedup != null ? String.format("%.2fx", speedup) : "baseline";
        String cacheStr = (metrics.getCacheHits() + metrics.getCacheMisses()) > 0 ?
            String.format("%.1f%%", metrics.getCacheHitRate() * 100) : "N/A";

        return String.format("%-30s | %15s | %,11d ms | %,9d ms | %10s | %10s",
            queryName, optimization, metrics.getTotalTimeMs(), metrics.getTotalLlmTimeMs(),
            cacheStr, speedupStr);
    }
}
