package org.gensparql.example;

import org.apache.jena.query.*;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.sparql.algebra.Algebra;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.util.FileManager;
import org.gensparql.engine.boot.GenSPARQL;
import org.gensparql.engine.exec.AlgebraGeneratorGenSPARQL;
import org.gensparql.parser.GenSPARQLQueryFactory;
import org.gensparql.parser.fmt.GenSPARQLFormatterElement;

import java.io.InputStream;

/**
 * Example 9: JOIN with GENOP threshold (BGP + GENOP with similarity threshold)
 * 
 * Demonstrates JOIN semantics between database query and LLM-generated results.
 * 
 * This example shows:
 * 1. BGP: Query scientists and awards from the database (binds ?scientist and ?awardLabel as RDF type)
 * 2. GENOP: Generate Nobel Prize winners independently (binds ?scientist and ?awardLabel as GEN type)
 * 3. JOIN: Natural join between BGP and GENOP results
 * 4. Threshold: Uses 0.8 similarity threshold for matching GEN values
 * 
 * Key features:
 * - Both BGP and GENOP bind the same variables (?scientist, ?awardLabel)
 * - They are joined on these variables (not UNION, but JOIN)
 * - The threshold (0.8) controls how similar values need to be for matching
 * - When SimJoin is enabled, uses semantic similarity matching
 * - Results contain only matched pairs (intersection, not union)
 * 
 * Expected behavior:
 * - BGP executes, producing RDF-typed bindings
 * - GENOP executes independently (base mode), producing GEN-typed bindings
 * - JOIN matches bindings where (?scientist, ?awardLabel) pairs are similar enough
 * - Results show only scientists/awards that exist in both database and LLM output
 * 
 * Note: This demonstrates the difference between JOIN (intersection) and UNION (combination).
 * With JOIN, you only get results that match between both sources.
 */
public class Query9Example {
    
    public static void main(String[] args) {
        // Initialize GenSPARQL
        GenSPARQL.init();

        // Load the TTL data file
        String dataFile = "gensparql-example/data/scientists_awards.ttl";
        if (!java.nio.file.Files.exists(java.nio.file.Paths.get(dataFile))) {
            dataFile = "data/scientists_awards.ttl";
        }
        Model model = loadData(dataFile);

        // Execute query
        System.out.println("=== Query 9: JOIN with GENOP threshold ===");
        System.out.println("This query demonstrates:");
        System.out.println("  - BGP: Query from database (RDF type)");
        System.out.println("  - GENOP: Generate independently (GEN type)");
        System.out.println("  - JOIN: Natural join on (?scientist, ?awardLabel)");
        System.out.println("  - Threshold: 0.8 for similarity matching");
        System.out.println("  - Results: Only matched pairs (intersection)\n");
        executeQuery(model);
    }
    
    private static Model loadData(String filename) {
        Model model = ModelFactory.createDefaultModel();
        InputStream in = FileManager.get().open(filename);
        if (in == null) {
            throw new IllegalArgumentException("File not found: " + filename);
        }
        model.read(in, null, "TTL");
        return model;
    }
    
    private static void executeQuery(Model model) {
        String queryString = readQueryFromFile("gensparql-example/queries/query9_join_with_threshold.sparql");
        if (queryString == null) {
            queryString = readQueryFromFile("queries/query9_join_with_threshold.sparql");
        }
        
        Query query = GenSPARQLQueryFactory.create(queryString);
        
        // Print query string
        System.out.println("--- Query String ---");
        System.out.println(queryString);
        
        // Print algebra tree
        System.out.println("\n--- Algebra Tree (Op Tree) ---");
        System.out.println("Note: Look for OpJoin between OpBGP and OpGenerate");
        Op op;
        try {
            // Try GenSPARQL algebra generator first (for queries with GENOP)
            op = AlgebraGeneratorGenSPARQL.compile(query.getQueryPattern());
            // Apply query modifiers (PROJECT, etc.)
            if (query.isSelectType() && query.getProjectVars() != null) {
                op = new org.apache.jena.sparql.algebra.op.OpProject(op, query.getProjectVars());
            }
        } catch (Exception e) {
            // Fallback to standard algebra
            op = Algebra.compile(query);
        }
        System.out.println(op.toString());
        
        // Print query pattern
        System.out.println("\n--- Query Pattern (Element Tree) ---");
        System.out.println(GenSPARQLFormatterElement.asString(query.getQueryPattern()));
        
        // Execute query
        System.out.println("\n--- Query Results ---");
        System.out.println("Expected: Only scientists/awards that match between database and LLM output");
        System.out.println("(JOIN semantics - intersection, not union)\n");
        try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
            ResultSet results = qexec.execSelect();
            ResultSetFormatter.out(System.out, results, query);
        } catch (Exception e) {
            System.err.println("Error executing query: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static String readQueryFromFile(String filename) {
        try {
            java.nio.file.Path filePath = java.nio.file.Paths.get(filename);
            if (java.nio.file.Files.exists(filePath)) {
                return java.nio.file.Files.readString(filePath, java.nio.charset.StandardCharsets.UTF_8);
            }
            InputStream in = FileManager.get().open(filename);
            if (in != null) {
                try (in) {
                    return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}

