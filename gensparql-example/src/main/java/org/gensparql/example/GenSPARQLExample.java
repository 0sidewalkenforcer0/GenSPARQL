package org.gensparql.example;

import org.apache.jena.query.*;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.riot.RDFDataMgr;
import org.gensparql.engine.boot.GenSPARQL;
import org.gensparql.parser.GenSPARQLQueryFactory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generic GenSPARQL query executor with Precision/Recall evaluation.
 *
 * Usage:
 *   java GenSPARQLExample <data_file> <query_file>
 *
 * Example:
 *   java GenSPARQLExample data/train.nt queries/query.sparql
 */
public class GenSPARQLExample {

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: GenSPARQLExample <data_file> <query_file> [expected_answers_json]");
            System.err.println("  data_file:  Path to RDF data (TTL, NT, etc.)");
            System.err.println("  query_file: Path to SPARQL query file");
            System.err.println("  expected_answers_json: (optional) expected answers; defaults to expected_answers.json next to the query");
            System.exit(1);
        }

        String dataFile = args[0];
        String queryFile = args[1];
        String expectedFile = args.length >= 3 ? args[2] : null;

        try {
            // Initialize GenSPARQL
            System.out.println("Initializing GenSPARQL...");
            GenSPARQL.init();
            // A command-line run must surface provider/model/response errors instead of
            // silently turning a failed GENOP batch into an empty result set.
            org.apache.jena.query.ARQ.getContext().set(GenSPARQL.FAIL_ON_LLM_ERROR, true);

            // Load data
            System.out.println("Loading data from: " + dataFile);
            Model model = loadData(dataFile);
            System.out.println("Loaded " + model.size() + " triples");

            // Load clean entity labels (entity_labels.tsv next to the data), if present,
            // so CanonicalForm.canon() returns real names instead of garbled URI locals.
            Path labelsPath = Paths.get(dataFile).getParent().resolve("entity_labels.tsv");
            if (Files.exists(labelsPath)) {
                java.util.Map<String, String> labels = new java.util.HashMap<>();
                for (String line : Files.readAllLines(labelsPath)) {
                    int tab = line.indexOf('\t');
                    if (tab > 0) labels.put(line.substring(0, tab), line.substring(tab + 1));
                }
                org.gensparql.core.similarity.CanonicalForm.setLabelLookup(labels::get);
                System.out.println("Loaded " + labels.size() + " clean entity labels");
            }

            // Load query
            System.out.println("Loading query from: " + queryFile);
            String queryString = Files.readString(Paths.get(queryFile));

            // Print query
            System.out.println("\n=== Query ===");
            System.out.println(queryString);

            // Load expected answers if available
            Set<String> expectedAnswers = loadExpectedAnswers(queryFile, expectedFile);

            // Execute query and evaluate
            System.out.println("\n=== Executing Query ===");
            executeQueryWithEvaluation(model, queryString, expectedAnswers);

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static Model loadData(String filename) throws IOException {
        Model model = ModelFactory.createDefaultModel();
        Path path = Paths.get(filename);

        if (!Files.exists(path)) {
            throw new IOException("Data file not found: " + filename);
        }

        RDFDataMgr.read(model, filename);
        return model;
    }

    /**
     * Load expected answers from expected_answers.json in the same directory as the query file.
     */
    private static Set<String> loadExpectedAnswers(String queryFile, String expectedOverride) {
        Set<String> expectedAnswers = new HashSet<>();
        try {
            Path queryPath = Paths.get(queryFile);
            Path expectedFile = (expectedOverride != null && !expectedOverride.isEmpty())
                    ? Paths.get(expectedOverride)
                    : queryPath.getParent().resolve("expected_answers.json");
            
            if (Files.exists(expectedFile)) {
                String json = Files.readString(expectedFile);
                Gson gson = new Gson();
                JsonObject obj = gson.fromJson(json, JsonObject.class);
                JsonArray answers = obj.getAsJsonArray("answers");
                if (answers != null) {
                    for (int i = 0; i < answers.size(); i++) {
                        String answer = answers.get(i).getAsString();
                        expectedAnswers.add(answer);
                    }
                }
                System.out.println("Loaded " + expectedAnswers.size() + " expected answers from: " + expectedFile);
            } else {
                System.out.println("No expected_answers.json found, skipping evaluation.");
            }
        } catch (Exception e) {
            System.err.println("Warning: Could not load expected answers: " + e.getMessage());
        }
        return expectedAnswers;
    }

    /**
     * Extract entity ID from a URI or literal value.
     * Handles both Freebase and NELL formats:
     * 
     * Freebase:
     *   http://freebase.com/entity/m_0214km_Solo -> /m/0214km
     *   /m/0214km -> /m/0214km
     * 
     * NELL:
     *   http://nell.cs.cmu.edu/entity/concept_clothing_baseball_cap -> concept_clothing_baseball_cap
     *   concept_clothing_baseball_cap -> concept_clothing_baseball_cap
     */
    private static String extractEntityId(String value) {
        if (value == null || value.isEmpty()) return null;
        
        // Clean up the value - remove quotes, whitespace, etc.
        value = value.trim();
        if (value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        
        // Handle NELL URIs: http://nell.cs.cmu.edu/entity/concept_xxx
        if (value.contains("nell.cs.cmu.edu/entity/")) {
            int idx = value.indexOf("/entity/");
            return value.substring(idx + 8);
        }
        
        // Handle NELL entities that start with concept_ or other NELL prefixes
        if (value.startsWith("concept_") || value.startsWith("person_") || 
            value.startsWith("location_") || value.startsWith("organization_")) {
            return value;
        }
        
        // Handle Freebase URIs: http://freebase.com/entity/m_0214km_Solo
        if (value.contains("freebase.com/entity/")) {
            int idx = value.indexOf("/entity/");
            String entity = value.substring(idx + 8);
            
            // Remove trailing label (e.g., m_0214km_Solo -> m_0214km)
            // Pattern: m_XXXXX_Label or just m_XXXXX
            Pattern p = Pattern.compile("^(m_[a-z0-9_]+?)(?:_[A-Z].*)?$");
            Matcher m = p.matcher(entity);
            if (m.matches()) {
                String mid = m.group(1);
                // Convert m_0214km to /m/0214km
                return "/" + mid.replaceFirst("_", "/");
            }
            
            // For simpler patterns, just convert underscores
            if (entity.startsWith("m_")) {
                // Find first underscore after m_
                String[] parts = entity.split("_", 3);
                if (parts.length >= 2) {
                    return "/m/" + parts[1];
                }
            }
            
            return entity;
        }
        
        // Generic /entity/ handling
        if (value.contains("/entity/")) {
            int idx = value.indexOf("/entity/");
            return value.substring(idx + 8);
        }
        
        // If already in /m/xxx format (Freebase)
        if (value.startsWith("/m/")) {
            return value;
        }
        
        return value;
    }
    
    /**
     * Parse LLM response which might be a JSON array or JSON string.
     * Extracts entity IDs from various formats.
     */
    private static Set<String> parseAnswerValue(String rawValue) {
        Set<String> results = new HashSet<>();
        if (rawValue == null || rawValue.isEmpty()) {
            return results;
        }
        
        rawValue = rawValue.trim();
        
        // Skip empty arrays
        if (rawValue.equals("[]")) {
            return results;
        }
        
        // Try to parse as JSON array
        if (rawValue.startsWith("[")) {
            try {
                // Remove markdown code block if present
                if (rawValue.startsWith("```json")) {
                    rawValue = rawValue.substring(7);
                    int endIdx = rawValue.indexOf("```");
                    if (endIdx > 0) {
                        rawValue = rawValue.substring(0, endIdx);
                    }
                    rawValue = rawValue.trim();
                }
                if (rawValue.startsWith("```")) {
                    rawValue = rawValue.substring(3);
                    int endIdx = rawValue.indexOf("```");
                    if (endIdx > 0) {
                        rawValue = rawValue.substring(0, endIdx);
                    }
                    rawValue = rawValue.trim();
                }
                
                Gson gson = new Gson();
                JsonArray arr = gson.fromJson(rawValue, JsonArray.class);
                for (int i = 0; i < arr.size(); i++) {
                    var elem = arr.get(i);
                    if (elem.isJsonPrimitive()) {
                        String entityId = extractEntityId(elem.getAsString());
                        if (entityId != null && !entityId.isEmpty()) {
                            results.add(entityId);
                        }
                    } else if (elem.isJsonObject()) {
                        JsonObject obj = elem.getAsJsonObject();
                        // Try common keys: answer, entity, subject, value
                        for (String key : new String[]{"answer", "entity", "subject", "value", "uri", "id"}) {
                            if (obj.has(key)) {
                                String entityId = extractEntityId(obj.get(key).getAsString());
                                if (entityId != null && !entityId.isEmpty()) {
                                    results.add(entityId);
                                }
                                break;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // If JSON parsing fails, try simple extraction
                Pattern uriPattern = Pattern.compile("http://[^\"\\s\\]]+/entity/([^\"\\s\\]]+)");
                Matcher m = uriPattern.matcher(rawValue);
                while (m.find()) {
                    results.add(m.group(1));
                }
            }
        } else if (rawValue.startsWith("{")) {
            // Single JSON object
            try {
                Gson gson = new Gson();
                JsonObject obj = gson.fromJson(rawValue, JsonObject.class);
                // Check for answer array
                if (obj.has("answer")) {
                    var answerElem = obj.get("answer");
                    if (answerElem.isJsonArray()) {
                        JsonArray arr = answerElem.getAsJsonArray();
                        for (int i = 0; i < arr.size(); i++) {
                            String entityId = extractEntityId(arr.get(i).getAsString());
                            if (entityId != null && !entityId.isEmpty()) {
                                results.add(entityId);
                            }
                        }
                    } else if (answerElem.isJsonPrimitive()) {
                        String entityId = extractEntityId(answerElem.getAsString());
                        if (entityId != null && !entityId.isEmpty()) {
                            results.add(entityId);
                        }
                    }
                }
            } catch (Exception e) {
                // Ignore parsing errors
            }
        } else {
            // Simple value
            String entityId = extractEntityId(rawValue);
            if (entityId != null && !entityId.isEmpty()) {
                results.add(entityId);
            }
        }
        
        return results;
    }

    private static void executeQueryWithEvaluation(Model model, String queryString, Set<String> expectedAnswers) {
        long startTime = System.currentTimeMillis();
        Set<String> actualAnswers = new HashSet<>();
        Map<String, String> answerDetails = new LinkedHashMap<>(); // Maintain insertion order
        List<String> rawResponses = new ArrayList<>(); // Store all raw responses for debugging

        try {
            // Use GenSPARQL parser to handle GENOP syntax
            Query query = GenSPARQLQueryFactory.create(queryString);

            try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
                if (query.isSelectType()) {
                    ResultSet results = qexec.execSelect();
                    List<String> resultVars = results.getResultVars();
                    
                    // Find the answer variable (usually ?answer)
                    String answerVar = null;
                    for (String var : resultVars) {
                        if (var.equalsIgnoreCase("answer")) {
                            answerVar = var;
                            break;
                        }
                    }
                    if (answerVar == null && !resultVars.isEmpty()) {
                        answerVar = resultVars.get(0);
                    }

                    int count = 0;
                    while (results.hasNext()) {
                        QuerySolution sol = results.next();
                        count++;
                        
                        if (answerVar != null && sol.contains(answerVar)) {
                            RDFNode node = sol.get(answerVar);
                            String rawValue = node.isResource() ? node.asResource().getURI() : node.toString();
                            rawResponses.add(rawValue);
                            
                            // Parse the answer value - it might be a JSON array or single value
                            Set<String> parsedAnswers = parseAnswerValue(rawValue);
                            
                            if (parsedAnswers.isEmpty()) {
                                // If parsing didn't find anything, try direct extraction
                                String entityId = extractEntityId(rawValue);
                                if (entityId != null && !entityId.isEmpty()) {
                                    actualAnswers.add(entityId);
                                    answerDetails.put(entityId, rawValue);
                                }
                            } else {
                                for (String entityId : parsedAnswers) {
                                    actualAnswers.add(entityId);
                                    answerDetails.put(entityId, rawValue);
                                }
                            }
                        }
                    }

                    long elapsed = System.currentTimeMillis() - startTime;
                    
                    // Print raw responses for debugging
                    System.out.println("\n=== RAW LLM RESPONSES (" + rawResponses.size() + " total) ===");
                    for (int i = 0; i < rawResponses.size(); i++) {
                        String raw = rawResponses.get(i);
                        System.out.println("[" + (i+1) + "] " + (raw.length() > 300 ? raw.substring(0, 300) + "..." : raw));
                    }
                    
                    // Print comparison table
                    printComparisonTable(expectedAnswers, actualAnswers, answerDetails);
                    
                    // Calculate and print metrics
                    printMetrics(expectedAnswers, actualAnswers, elapsed);

                } else if (query.isAskType()) {
                    boolean result = qexec.execAsk();
                    System.out.println("ASK result: " + result);

                } else if (query.isConstructType()) {
                    Model resultModel = qexec.execConstruct();
                    System.out.println("CONSTRUCT result size: " + resultModel.size() + " triples");

                } else if (query.isDescribeType()) {
                    Model resultModel = qexec.execDescribe();
                    System.out.println("DESCRIBE result size: " + resultModel.size() + " triples");
                }
            }

        } catch (Exception e) {
            System.err.println("Query execution error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void printComparisonTable(Set<String> expectedAnswers, Set<String> actualAnswers, Map<String, String> answerDetails) {
        System.out.println("\n" + "=".repeat(120));
        System.out.println("COMPARISON TABLE");
        System.out.println("=".repeat(120));
        
        // Calculate intersection and differences
        Set<String> truePositives = new HashSet<>(actualAnswers);
        truePositives.retainAll(expectedAnswers);
        
        Set<String> falsePositives = new HashSet<>(actualAnswers);
        falsePositives.removeAll(expectedAnswers);
        
        Set<String> falseNegatives = new HashSet<>(expectedAnswers);
        falseNegatives.removeAll(actualAnswers);
        
        // Print header
        System.out.printf("%-50s | %-50s | %-10s%n", "EXPECTED ANSWER", "ACTUAL ANSWER (Parsed ID)", "STATUS");
        System.out.println("-".repeat(120));
        
        // Print true positives (matches)
        List<String> sortedTP = new ArrayList<>(truePositives);
        Collections.sort(sortedTP);
        for (String ans : sortedTP) {
            System.out.printf("%-50s | %-50s | %-10s%n", 
                truncate(ans, 50), 
                truncate(ans, 50), 
                "✓ MATCH");
        }
        
        // Print false negatives (expected but not found)
        List<String> sortedFN = new ArrayList<>(falseNegatives);
        Collections.sort(sortedFN);
        for (String ans : sortedFN) {
            System.out.printf("%-50s | %-50s | %-10s%n", 
                truncate(ans, 50), 
                "(not found)", 
                "✗ MISS");
        }
        
        // Print false positives (found but not expected)
        List<String> sortedFP = new ArrayList<>(falsePositives);
        Collections.sort(sortedFP);
        for (String ans : sortedFP) {
            System.out.printf("%-50s | %-50s | %-10s%n", 
                "(not expected)", 
                truncate(ans, 50), 
                "? EXTRA");
        }
        
        System.out.println("=".repeat(120));
    }

    private static void printMetrics(Set<String> expectedAnswers, Set<String> actualAnswers, long elapsed) {
        // Calculate metrics
        Set<String> truePositives = new HashSet<>(actualAnswers);
        truePositives.retainAll(expectedAnswers);
        
        int tp = truePositives.size();
        int fp = actualAnswers.size() - tp;
        int fn = expectedAnswers.size() - tp;
        
        double precision = actualAnswers.isEmpty() ? 0.0 : (double) tp / actualAnswers.size();
        double recall = expectedAnswers.isEmpty() ? 0.0 : (double) tp / expectedAnswers.size();
        double f1 = (precision + recall) == 0 ? 0.0 : 2 * precision * recall / (precision + recall);
        
        System.out.println("\n=== EVALUATION METRICS ===");
        System.out.println("Expected answers:  " + expectedAnswers.size());
        System.out.println("Actual answers:    " + actualAnswers.size());
        System.out.println("True Positives:    " + tp);
        System.out.println("False Positives:   " + fp);
        System.out.println("False Negatives:   " + fn);
        System.out.println();
        System.out.printf("Precision:         %.4f (%.2f%%)%n", precision, precision * 100);
        System.out.printf("Recall:            %.4f (%.2f%%)%n", recall, recall * 100);
        System.out.printf("F1 Score:          %.4f (%.2f%%)%n", f1, f1 * 100);
        System.out.println();
        System.out.println("Execution time:    " + elapsed + " ms");
        System.out.println("=".repeat(30));
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen - 3) + "...";
    }
}
