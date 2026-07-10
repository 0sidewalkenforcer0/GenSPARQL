package org.gensparql.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.RDFDataMgr;
import org.gensparql.engine.boot.GenSPARQL;
import org.gensparql.parser.GenSPARQLQueryFactory;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * Quantitative evaluator for GenSPARQL benchmark.
 * Supports all 8 query types: 2p, 2i, 3p, 3i, 4p, pi, ip, up
 *
 * Pattern encoding:
 * - 0 = data in train (use BGP)
 * - 1 = data NOT in train (use GENOP/LLM)
 */
public class QuantitativeEvaluator {

    private final Model model;
    private final Dataset dataset;
    private final ObjectMapper objectMapper;
    private final String llmModel;
    private final boolean groundingEnabled;
    private final double groundingThreshold;

    // Dataset-specific URI prefixes
    private String entityPrefix;
    private String relationPrefix;

    public QuantitativeEvaluator(String dataFile, String llmModel,
                                  boolean groundingEnabled, double groundingThreshold) {
        GenSPARQL.init();

        this.model = ModelFactory.createDefaultModel();
        RDFDataMgr.read(model, dataFile);
        this.dataset = DatasetFactory.create(model);

        this.objectMapper = new ObjectMapper();
        this.llmModel = llmModel;
        this.groundingEnabled = groundingEnabled;
        this.groundingThreshold = groundingThreshold;

        if (dataFile.contains("NELL")) {
            this.entityPrefix = "http://nell.cs.cmu.edu/entity/";
            this.relationPrefix = "http://nell.cs.cmu.edu/relation/";
        } else {
            this.entityPrefix = "http://freebase.com/entity/";
            this.relationPrefix = "http://freebase.com/relation/";
        }

        System.out.println("Loaded " + model.size() + " triples from " + dataFile);
    }

    /**
     * Evaluate a pattern from queries_readable.json
     */
    public EvaluationResult evaluatePattern(String queriesJsonPath, int maxQueries) throws Exception {
        JsonNode root = objectMapper.readTree(new File(queriesJsonPath));

        String queryType = root.get("query_type").asText();
        String pattern = root.get("pattern").asText();
        JsonNode queries = root.get("queries");

        System.out.println("\n============================================");
        System.out.println("Evaluating " + queryType + " pattern_" + pattern);
        System.out.println("Total queries available: " + queries.size());
        System.out.println("Max queries to run: " + (maxQueries > 0 ? maxQueries : "all"));
        System.out.println("Grounding: " + groundingEnabled);
        System.out.println("============================================\n");

        List<QueryResult> results = new ArrayList<>();
        int count = 0;

        for (JsonNode queryNode : queries) {
            if (maxQueries > 0 && count >= maxQueries) break;

            try {
                QueryResult result = evaluateSingleQuery(queryNode, queryType, pattern);
                results.add(result);

                System.out.printf("[%d/%d] %s -> Hits@1: %s, Hits@10: %s, Rank: %s\n",
                    count + 1,
                    maxQueries > 0 ? Math.min(maxQueries, queries.size()) : queries.size(),
                    truncate(queryNode.get("query_readable").asText(), 60),
                    result.hit1 ? "Y" : "N",
                    result.hit10 ? "Y" : "N",
                    result.bestRank < Integer.MAX_VALUE ? String.valueOf(result.bestRank) : "-");

            } catch (Exception e) {
                System.err.println("Error evaluating query " + (count + 1) + ": " + e.getMessage());
                results.add(new QueryResult(false, false, Integer.MAX_VALUE, 0));
            }

            count++;
        }

        return calculateMetrics(results, queryType, pattern);
    }

    /**
     * Evaluate a single query
     */
    private QueryResult evaluateSingleQuery(JsonNode queryNode, String queryType, String pattern) {
        String queryReadable = queryNode.get("query_readable").asText();

        Set<String> expectedAnswers = new HashSet<>();
        for (JsonNode ans : queryNode.get("answers")) {
            expectedAnswers.add(ans.asText());
        }

        String sparql = generateSPARQL(queryReadable, queryType, pattern);
        List<String> actualAnswers = executeQuery(sparql);

        boolean hit1 = !actualAnswers.isEmpty() && expectedAnswers.contains(actualAnswers.get(0));
        boolean hit10 = false;
        int bestRank = Integer.MAX_VALUE;

        for (int i = 0; i < Math.min(10, actualAnswers.size()); i++) {
            if (expectedAnswers.contains(actualAnswers.get(i))) {
                hit10 = true;
                if (bestRank == Integer.MAX_VALUE) {
                    bestRank = i + 1;
                }
            }
        }

        return new QueryResult(hit1, hit10, bestRank, actualAnswers.size());
    }

    // ================================================================
    // SPARQL Generation - routes to type-specific methods
    // ================================================================

    private String generateSPARQL(String queryReadable, String queryType, String pattern) {
        StringBuilder sparql = new StringBuilder();
        sparql.append("SELECT ?answer WHERE {\n");

        switch (queryType) {
            case "2p":
                sparql.append(generate2pQuery(queryReadable, pattern));
                break;
            case "2i":
                sparql.append(generate2iQuery(queryReadable, pattern));
                break;
            case "3p":
                sparql.append(generate3pQuery(queryReadable, pattern));
                break;
            case "3i":
                sparql.append(generate3iQuery(queryReadable, pattern));
                break;
            case "4p":
                sparql.append(generate4pQuery(queryReadable, pattern));
                break;
            case "pi":
                sparql.append(generatePiQuery(queryReadable, pattern));
                break;
            case "ip":
                sparql.append(generateIpQuery(queryReadable, pattern));
                break;
            case "up":
                sparql.append(generateUpQuery(queryReadable, pattern));
                break;
            default:
                throw new IllegalArgumentException("Unknown query type: " + queryType);
        }

        sparql.append("}\n");

        if (Boolean.getBoolean("gensparql.verbose")) {
            System.out.println("\n--- Generated SPARQL ---");
            System.out.println(sparql);
            System.out.println("------------------------\n");
        }

        return sparql.toString();
    }

    // ================================================================
    // Path queries: 2p, 3p, 4p
    // Format: entity --[rel1]--> ? --[rel2]--> ? ...
    // ================================================================

    private String generate2pQuery(String queryReadable, String pattern) {
        // Parse: entity --[rel1]--> ? --[rel2]--> ?
        ParsedPathQuery pq = parsePathQuery(queryReadable);
        return generatePathSPARQL(pq.anchor, pq.relations, pattern, "answer");
    }

    private String generate3pQuery(String queryReadable, String pattern) {
        ParsedPathQuery pq = parsePathQuery(queryReadable);
        return generatePathSPARQL(pq.anchor, pq.relations, pattern, "answer");
    }

    private String generate4pQuery(String queryReadable, String pattern) {
        ParsedPathQuery pq = parsePathQuery(queryReadable);
        return generatePathSPARQL(pq.anchor, pq.relations, pattern, "answer");
    }

    /**
     * Generic path query generation for any number of hops.
     */
    private String generatePathSPARQL(String anchor, List<String> relations, String pattern, String finalVar) {
        StringBuilder sb = new StringBuilder();
        String anchorUri = entityPrefix + anchor;
        char[] digits = pattern.toCharArray();

        for (int i = 0; i < digits.length && i < relations.size(); i++) {
            String subjectVar = (i == 0) ? "<" + anchorUri + ">" : "?mid" + i;
            String objectVar = (i == digits.length - 1) ? "?" + finalVar : "?mid" + (i + 1);
            String rel = convertRelationToUri(relations.get(i));

            if (digits[i] == '0') {
                // BGP
                sb.append(String.format("  %s <%s> %s .\n", subjectVar, rel, objectVar));
            } else {
                // GENOP
                String subjectDesc = (i == 0) ? anchor.replace("_", " ") : "{" + subjectVar + "}";
                String prompt = String.format("What is connected to %s via %s? Return entity name only.",
                    subjectDesc, getRelationName(relations.get(i)));
                sb.append(String.format("  GENOP(\"%s\",\n", escapePrompt(prompt)));
                sb.append(String.format("        (%s),\n", objectVar));
                sb.append(String.format("        <model:openrouter:%s>", llmModel));
                if (groundingEnabled) {
                    sb.append(String.format(",\n        grounding_relation: \"%s\"", rel));
                }
                sb.append(")\n");
            }
        }

        return sb.toString();
    }

    // ================================================================
    // Intersection queries: 2i, 3i
    // Format: (entity1 --[rel1]--> ?) ∩ (entity2 --[rel2]--> ?) [∩ ...]
    // ================================================================

    private String generate2iQuery(String queryReadable, String pattern) {
        List<IntersectionBranch> branches = parseIntersectionQuery(queryReadable);
        return generateIntersectionSPARQL(branches, pattern, "answer");
    }

    private String generate3iQuery(String queryReadable, String pattern) {
        List<IntersectionBranch> branches = parseIntersectionQuery(queryReadable);
        return generateIntersectionSPARQL(branches, pattern, "answer");
    }

    /**
     * Generic intersection query generation.
     * Each branch binds to the same variable (intersection semantics).
     */
    private String generateIntersectionSPARQL(List<IntersectionBranch> branches, String pattern, String targetVar) {
        StringBuilder sb = new StringBuilder();
        char[] digits = pattern.toCharArray();

        for (int i = 0; i < digits.length && i < branches.size(); i++) {
            IntersectionBranch branch = branches.get(i);
            String anchorUri = entityPrefix + branch.entity;
            String rel = convertRelationToUri(branch.relation);

            if (digits[i] == '0') {
                // BGP
                sb.append(String.format("  <%s> <%s> ?%s .\n", anchorUri, rel, targetVar));
            } else {
                // GENOP
                String prompt = String.format("What is connected to %s via %s? Return entity name only.",
                    branch.entity.replace("_", " "), getRelationName(branch.relation));
                sb.append(String.format("  GENOP(\"%s\",\n", escapePrompt(prompt)));
                sb.append(String.format("        (?%s),\n", targetVar));
                sb.append(String.format("        <model:openrouter:%s>", llmModel));
                if (groundingEnabled) {
                    sb.append(String.format(",\n        grounding_relation: \"%s\"", rel));
                }
                sb.append(")\n");
            }
        }

        return sb.toString();
    }

    // ================================================================
    // pi: Projection + Intersection
    // Format: (entity1 --[rel1]--> ? --[rel2]--> ?) ∩ (entity2 --[rel3]--> ?)
    // Pattern: 3 digits - [0]=rel1, [1]=rel2, [2]=rel3
    // ================================================================

    private String generatePiQuery(String queryReadable, String pattern) {
        // Parse: (entity1 --[rel1]--> ? --[rel2]--> ?) ∩ (entity2 --[rel3]--> ?)
        // Split on ∩
        String[] parts = queryReadable.split("\\s*∩\\s*");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Cannot parse pi query: " + queryReadable);
        }

        // First part: path branch (entity1 --[rel1]--> ? --[rel2]--> ?)
        String pathPart = parts[0].trim();
        pathPart = stripOuterParens(pathPart);
        ParsedPathQuery pathBranch = parsePathQuery(pathPart);

        // Second part: simple branch (entity2 --[rel3]--> ?)
        String simplePart = parts[1].trim();
        simplePart = stripOuterParens(simplePart);
        ParsedPathQuery simpleBranch = parsePathQuery(simplePart);

        char[] digits = pattern.toCharArray();
        StringBuilder sb = new StringBuilder();

        // Path branch: hop1 (entity1 -> ?mid) and hop2 (?mid -> ?answer)
        String anchorUri1 = entityPrefix + pathBranch.anchor;
        String rel1 = convertRelationToUri(pathBranch.relations.get(0));
        String rel2 = convertRelationToUri(pathBranch.relations.get(1));

        // Hop 1: digits[0]
        if (digits[0] == '0') {
            sb.append(String.format("  <%s> <%s> ?mid .\n", anchorUri1, rel1));
        } else {
            String prompt = String.format("What is connected to %s via %s? Return entity name only.",
                pathBranch.anchor.replace("_", " "), getRelationName(pathBranch.relations.get(0)));
            sb.append(String.format("  GENOP(\"%s\",\n", escapePrompt(prompt)));
            sb.append("        (?mid),\n");
            sb.append(String.format("        <model:openrouter:%s>", llmModel));
            if (groundingEnabled) sb.append(String.format(",\n        grounding_relation: \"%s\"", rel1));
            sb.append(")\n");
        }

        // Hop 2: digits[1]
        if (digits[1] == '0') {
            sb.append(String.format("  ?mid <%s> ?answer .\n", rel2));
        } else {
            String prompt = String.format("Given {?mid}, what is connected via %s? Return entity name only.",
                getRelationName(pathBranch.relations.get(1)));
            sb.append(String.format("  GENOP(\"%s\",\n", escapePrompt(prompt)));
            sb.append("        (?answer),\n");
            sb.append(String.format("        <model:openrouter:%s>", llmModel));
            if (groundingEnabled) sb.append(String.format(",\n        grounding_relation: \"%s\"", rel2));
            sb.append(")\n");
        }

        // Intersection branch: digits[2] -> entity2 --[rel3]--> ?answer
        String anchorUri2 = entityPrefix + simpleBranch.anchor;
        String rel3 = convertRelationToUri(simpleBranch.relations.get(0));

        if (digits[2] == '0') {
            sb.append(String.format("  <%s> <%s> ?answer .\n", anchorUri2, rel3));
        } else {
            String prompt = String.format("What is connected to %s via %s? Return entity name only.",
                simpleBranch.anchor.replace("_", " "), getRelationName(simpleBranch.relations.get(0)));
            sb.append(String.format("  GENOP(\"%s\",\n", escapePrompt(prompt)));
            sb.append("        (?answer),\n");
            sb.append(String.format("        <model:openrouter:%s>", llmModel));
            if (groundingEnabled) sb.append(String.format(",\n        grounding_relation: \"%s\"", rel3));
            sb.append(")\n");
        }

        return sb.toString();
    }

    // ================================================================
    // ip: Intersection + Projection
    // Format: ((entity1 --[rel1]--> ?) ∩ (entity2 --[rel2]--> ?)) --[rel3]--> ?
    // Pattern: 3 digits - [0]=rel1, [1]=rel2, [2]=rel3
    // ================================================================

    private String generateIpQuery(String queryReadable, String pattern) {
        // Parse: ((entity1 --[rel1]--> ?) ∩ (entity2 --[rel2]--> ?)) --[rel3]--> ?
        // Find the outer closing )) then the remaining --[rel]--> ?
        int outerClose = queryReadable.lastIndexOf("))");
        if (outerClose < 0) {
            throw new IllegalArgumentException("Cannot parse ip query: " + queryReadable);
        }

        String innerPart = queryReadable.substring(0, outerClose + 2).trim();
        String outerPart = queryReadable.substring(outerClose + 2).trim();

        // Strip outer parens from inner: ((e1 --[r1]--> ?) ∩ (e2 --[r2]--> ?))
        innerPart = stripOuterParens(innerPart);

        // Parse inner intersection
        List<IntersectionBranch> branches = parseIntersectionQuery(innerPart);

        // Parse outer relation: --[rel3]--> ?
        Pattern relPat = Pattern.compile("--\\[([^\\]]+)\\]-->");
        Matcher relMat = relPat.matcher(outerPart);
        String outerRel = null;
        if (relMat.find()) {
            outerRel = relMat.group(1);
        }

        if (outerRel == null) {
            throw new IllegalArgumentException("Cannot parse outer relation in ip query: " + queryReadable);
        }

        char[] digits = pattern.toCharArray();
        StringBuilder sb = new StringBuilder();

        // Intersection branches bind to ?mid
        for (int i = 0; i < 2 && i < branches.size(); i++) {
            IntersectionBranch branch = branches.get(i);
            String anchorUri = entityPrefix + branch.entity;
            String rel = convertRelationToUri(branch.relation);

            if (digits[i] == '0') {
                sb.append(String.format("  <%s> <%s> ?mid .\n", anchorUri, rel));
            } else {
                String prompt = String.format("What is connected to %s via %s? Return entity name only.",
                    branch.entity.replace("_", " "), getRelationName(branch.relation));
                sb.append(String.format("  GENOP(\"%s\",\n", escapePrompt(prompt)));
                sb.append("        (?mid),\n");
                sb.append(String.format("        <model:openrouter:%s>", llmModel));
                if (groundingEnabled) sb.append(String.format(",\n        grounding_relation: \"%s\"", rel));
                sb.append(")\n");
            }
        }

        // Outer path: ?mid --[rel3]--> ?answer
        String rel3 = convertRelationToUri(outerRel);
        if (digits[2] == '0') {
            sb.append(String.format("  ?mid <%s> ?answer .\n", rel3));
        } else {
            String prompt = String.format("Given {?mid}, what is connected via %s? Return entity name only.",
                getRelationName(outerRel));
            sb.append(String.format("  GENOP(\"%s\",\n", escapePrompt(prompt)));
            sb.append("        (?answer),\n");
            sb.append(String.format("        <model:openrouter:%s>", llmModel));
            if (groundingEnabled) sb.append(String.format(",\n        grounding_relation: \"%s\"", rel3));
            sb.append(")\n");
        }

        return sb.toString();
    }

    // ================================================================
    // up: Union + Projection
    // Format: ((entity1 --[rel1]--> ?) ∪ (entity2 --[rel2]--> ?)) --[rel3]--> ?
    // Pattern: 3 digits - [0]=rel1, [1]=rel2, [2]=rel3
    // ================================================================

    private String generateUpQuery(String queryReadable, String pattern) {
        // Parse: ((entity1 --[rel1]--> ?) ∪ (entity2 --[rel2]--> ?)) --[rel3]--> ?
        int outerClose = queryReadable.lastIndexOf("))");
        if (outerClose < 0) {
            throw new IllegalArgumentException("Cannot parse up query: " + queryReadable);
        }

        String innerPart = queryReadable.substring(0, outerClose + 2).trim();
        String outerPart = queryReadable.substring(outerClose + 2).trim();

        // Strip outer parens
        innerPart = stripOuterParens(innerPart);

        // Parse union branches (split by ∪ instead of ∩)
        String[] unionParts = innerPart.split("\\s*∪\\s*");
        if (unionParts.length != 2) {
            throw new IllegalArgumentException("Cannot parse union branches in up query: " + queryReadable);
        }

        // Parse each union branch
        String part1 = stripOuterParens(unionParts[0].trim());
        String part2 = stripOuterParens(unionParts[1].trim());
        ParsedPathQuery branch1 = parsePathQuery(part1);
        ParsedPathQuery branch2 = parsePathQuery(part2);

        // Parse outer relation
        Pattern relPat = Pattern.compile("--\\[([^\\]]+)\\]-->");
        Matcher relMat = relPat.matcher(outerPart);
        String outerRel = null;
        if (relMat.find()) {
            outerRel = relMat.group(1);
        }

        if (outerRel == null) {
            throw new IllegalArgumentException("Cannot parse outer relation in up query: " + queryReadable);
        }

        char[] digits = pattern.toCharArray();
        StringBuilder sb = new StringBuilder();

        // UNION block for the two branches
        sb.append("  {\n");

        // Branch 1: digits[0]
        String anchorUri1 = entityPrefix + branch1.anchor;
        String rel1 = convertRelationToUri(branch1.relations.get(0));
        if (digits[0] == '0') {
            sb.append(String.format("    <%s> <%s> ?mid .\n", anchorUri1, rel1));
        } else {
            String prompt = String.format("What is connected to %s via %s? Return entity name only.",
                branch1.anchor.replace("_", " "), getRelationName(branch1.relations.get(0)));
            sb.append(String.format("    GENOP(\"%s\",\n", escapePrompt(prompt)));
            sb.append("          (?mid),\n");
            sb.append(String.format("          <model:openrouter:%s>", llmModel));
            if (groundingEnabled) sb.append(String.format(",\n          grounding_relation: \"%s\"", rel1));
            sb.append(")\n");
        }

        sb.append("  } UNION {\n");

        // Branch 2: digits[1]
        String anchorUri2 = entityPrefix + branch2.anchor;
        String rel2 = convertRelationToUri(branch2.relations.get(0));
        if (digits[1] == '0') {
            sb.append(String.format("    <%s> <%s> ?mid .\n", anchorUri2, rel2));
        } else {
            String prompt = String.format("What is connected to %s via %s? Return entity name only.",
                branch2.anchor.replace("_", " "), getRelationName(branch2.relations.get(0)));
            sb.append(String.format("    GENOP(\"%s\",\n", escapePrompt(prompt)));
            sb.append("          (?mid),\n");
            sb.append(String.format("          <model:openrouter:%s>", llmModel));
            if (groundingEnabled) sb.append(String.format(",\n          grounding_relation: \"%s\"", rel2));
            sb.append(")\n");
        }

        sb.append("  }\n");

        // Outer path: ?mid --[rel3]--> ?answer (digits[2])
        String rel3 = convertRelationToUri(outerRel);
        if (digits[2] == '0') {
            sb.append(String.format("  ?mid <%s> ?answer .\n", rel3));
        } else {
            String prompt = String.format("Given {?mid}, what is connected via %s? Return entity name only.",
                getRelationName(outerRel));
            sb.append(String.format("  GENOP(\"%s\",\n", escapePrompt(prompt)));
            sb.append("        (?answer),\n");
            sb.append(String.format("        <model:openrouter:%s>", llmModel));
            if (groundingEnabled) sb.append(String.format(",\n        grounding_relation: \"%s\"", rel3));
            sb.append(")\n");
        }

        return sb.toString();
    }

    // ================================================================
    // Parsing helpers
    // ================================================================

    /**
     * Parse a path query: entity --[rel1]--> ? --[rel2]--> ? ...
     */
    private ParsedPathQuery parsePathQuery(String queryReadable) {
        // Extract anchor entity (first token before --[)
        Pattern anchorPattern = Pattern.compile("^([^\\s]+)\\s+--\\[");
        Matcher anchorMatcher = anchorPattern.matcher(queryReadable.trim());
        String anchor = null;
        if (anchorMatcher.find()) {
            anchor = anchorMatcher.group(1);
        }

        // Extract all relations
        Pattern relPattern = Pattern.compile("--\\[([^\\]]+)\\]-->");
        Matcher relMatcher = relPattern.matcher(queryReadable);
        List<String> relations = new ArrayList<>();
        while (relMatcher.find()) {
            relations.add(relMatcher.group(1));
        }

        if (anchor == null || relations.isEmpty()) {
            throw new IllegalArgumentException("Cannot parse path query: " + queryReadable);
        }

        return new ParsedPathQuery(anchor, relations);
    }

    /**
     * Parse an intersection query: (entity1 --[rel1]--> ?) ∩ (entity2 --[rel2]--> ?) ...
     */
    private List<IntersectionBranch> parseIntersectionQuery(String queryReadable) {
        String[] parts = queryReadable.split("\\s*∩\\s*");
        List<IntersectionBranch> branches = new ArrayList<>();

        for (String part : parts) {
            String cleaned = stripOuterParens(part.trim());

            // Extract entity and relation
            Pattern anchorPattern = Pattern.compile("^([^\\s]+)\\s+--\\[([^\\]]+)\\]-->");
            Matcher matcher = anchorPattern.matcher(cleaned);

            if (matcher.find()) {
                branches.add(new IntersectionBranch(matcher.group(1), matcher.group(2)));
            } else {
                throw new IllegalArgumentException("Cannot parse intersection branch: " + part);
            }
        }

        return branches;
    }

    /**
     * Strip outer parentheses from a string.
     */
    private String stripOuterParens(String s) {
        s = s.trim();
        while (s.startsWith("(") && s.endsWith(")")) {
            // Make sure these parens match (not nested)
            int depth = 0;
            boolean matches = true;
            for (int i = 0; i < s.length() - 1; i++) {
                if (s.charAt(i) == '(') depth++;
                if (s.charAt(i) == ')') depth--;
                if (depth == 0) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                s = s.substring(1, s.length() - 1).trim();
            } else {
                break;
            }
        }
        return s;
    }

    // ================================================================
    // URI conversion helpers
    // ================================================================

    private String convertRelationToUri(String relation) {
        // NELL format: concept:relationname
        if (relation.startsWith("concept:")) {
            return relationPrefix + "concept_" + relation.substring(8);
        }
        // Freebase format: +/path or -/path (direction prefix)
        if (relation.startsWith("+") || relation.startsWith("-")) {
            String path = relation.substring(1);
            // Replace / and . with _ for URI
            String cleanPath = path.replace("/", "_").replace(".", "_");
            if (cleanPath.startsWith("_")) cleanPath = cleanPath.substring(1);
            String direction = relation.startsWith("+") ? "forward" : "reverse";
            return relationPrefix + direction + "_" + cleanPath;
        }
        return relationPrefix + relation;
    }

    private String getRelationName(String relation) {
        if (relation.startsWith("concept:")) {
            return relation.substring(8).replace("_", " ");
        }
        if (relation.startsWith("+") || relation.startsWith("-")) {
            return relation.substring(1).replace("/", " ").replace(".", " ").replace("_", " ");
        }
        return relation.replace("_", " ").replace("/", " ");
    }

    private String escapePrompt(String prompt) {
        return prompt.replace("\"", "\\\"");
    }

    // ================================================================
    // Query execution
    // ================================================================

    private List<String> executeQuery(String sparql) {
        List<String> results = new ArrayList<>();

        try {
            Query query = GenSPARQLQueryFactory.create(sparql);

            try (QueryExecution qe = QueryExecutionFactory.create(query, dataset)) {
                ResultSet rs = qe.execSelect();

                while (rs.hasNext()) {
                    QuerySolution sol = rs.next();
                    if (sol.contains("answer")) {
                        String answer = sol.get("answer").toString();
                        // Extract entity ID from URI
                        if (answer.contains("/entity/")) {
                            answer = answer.substring(answer.lastIndexOf("/") + 1);
                        }
                        results.add(answer);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Query execution error: " + e.getMessage());
            if (Boolean.getBoolean("gensparql.verbose")) {
                e.printStackTrace();
            }
        }

        return results;
    }

    // ================================================================
    // Metrics
    // ================================================================

    private EvaluationResult calculateMetrics(List<QueryResult> results, String queryType, String pattern) {
        int total = results.size();
        int hits1 = 0;
        int hits10 = 0;
        double mrrSum = 0;

        for (QueryResult r : results) {
            if (r.hit1) hits1++;
            if (r.hit10) hits10++;
            if (r.bestRank < Integer.MAX_VALUE) {
                mrrSum += 1.0 / r.bestRank;
            }
        }

        double hits1Rate = total > 0 ? (double) hits1 / total : 0;
        double hits10Rate = total > 0 ? (double) hits10 / total : 0;
        double mrr = total > 0 ? mrrSum / total : 0;

        return new EvaluationResult(queryType, pattern, total, hits1Rate, hits10Rate, mrr);
    }

    private String truncate(String s, int maxLen) {
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    // ================================================================
    // Inner classes
    // ================================================================

    static class ParsedPathQuery {
        String anchor;
        List<String> relations;

        ParsedPathQuery(String anchor, List<String> relations) {
            this.anchor = anchor;
            this.relations = relations;
        }
    }

    static class IntersectionBranch {
        String entity;
        String relation;

        IntersectionBranch(String entity, String relation) {
            this.entity = entity;
            this.relation = relation;
        }
    }

    static class QueryResult {
        boolean hit1;
        boolean hit10;
        int bestRank;
        int totalResults;

        QueryResult(boolean hit1, boolean hit10, int bestRank, int totalResults) {
            this.hit1 = hit1;
            this.hit10 = hit10;
            this.bestRank = bestRank;
            this.totalResults = totalResults;
        }
    }

    static class EvaluationResult {
        String queryType;
        String pattern;
        int totalQueries;
        double hits1;
        double hits10;
        double mrr;

        EvaluationResult(String queryType, String pattern, int totalQueries,
                        double hits1, double hits10, double mrr) {
            this.queryType = queryType;
            this.pattern = pattern;
            this.totalQueries = totalQueries;
            this.hits1 = hits1;
            this.hits10 = hits10;
            this.mrr = mrr;
        }

        @Override
        public String toString() {
            return String.format(
                "\n============================================\n" +
                "Results: %s pattern_%s\n" +
                "============================================\n" +
                "Queries evaluated: %d\n" +
                "Hits@1:  %.4f (%.1f%%)\n" +
                "Hits@10: %.4f (%.1f%%)\n" +
                "MRR:     %.4f\n",
                queryType, pattern, totalQueries,
                hits1, hits1 * 100,
                hits10, hits10 * 100,
                mrr);
        }
    }

    // Main method
    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: QuantitativeEvaluator <data_file> <queries_json> [max_queries] [grounding]");
            return;
        }

        String dataFile = args[0];
        String queriesJson = args[1];
        int maxQueries = args.length > 2 ? Integer.parseInt(args[2]) : -1;
        boolean grounding = args.length > 3 && Boolean.parseBoolean(args[3]);
        String model = System.getProperty("gensparql.model", "deepseek/deepseek-r1-0528:free");

        try {
            QuantitativeEvaluator evaluator = new QuantitativeEvaluator(
                dataFile, model, grounding, 0.4);

            EvaluationResult result = evaluator.evaluatePattern(queriesJson, maxQueries);
            System.out.println(result);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
