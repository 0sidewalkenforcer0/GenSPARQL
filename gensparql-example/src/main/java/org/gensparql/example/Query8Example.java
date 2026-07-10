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
 * Example 8: GENOP before BGP (input variables from subsequent BGP)
 * 
 * Demonstrates how GENOP can use input variables that are bound by BGP patterns appearing after it.
 * 
 * This example shows:
 * 1. GENOP: Appears first in the query, uses ?scientist as input variable
 * 2. BGP: Appears after GENOP, binds ?scientist from the database
 *    - In SPARQL, pattern order doesn't affect semantics (natural join is commutative)
 *    - The query engine should handle this by joining all patterns together
 * 
 * Key features:
 * - Pattern order independence: Even though GENOP appears before the BGP that binds ?scientist,
 *   the query engine should still be able to use ?scientist from the BGP as input to GENOP
 * - Demonstrates that SPARQL pattern order is semantically irrelevant for natural joins
 * - The query engine needs to collect all variable bindings before executing GENOP
 * 
 * Expected behavior:
 * - BGP executes (or is considered together with GENOP), binding ?scientist and ?awardLabel
 * - GENOP receives bindings from BGP and uses ?scientist in the prompt
 * - GENOP generates ?description for each scientist
 * - Results combine BGP bindings with GENOP outputs
 * 
 * Note: This tests whether the query engine properly handles variable dependencies
 * regardless of pattern order in the WHERE clause.
 */
public class Query8Example {
    
    public static void main(String[] args) {
        // Initialize GenSPARQL
        GenSPARQL.init();
        
        // Optionally configure LLM provider
        // GenSPARQL.setDefaultProvider("openai");
        
        // Load the TTL data file
        String dataFile = "gensparql-example/data/scientists_awards.ttl";
        if (!java.nio.file.Files.exists(java.nio.file.Paths.get(dataFile))) {
            dataFile = "data/scientists_awards.ttl";
        }
        Model model = loadData(dataFile);
        
        // Execute query
        System.out.println("=== Query 8: GENOP before BGP ===");
        System.out.println("This query demonstrates:");
        System.out.println("  - GENOP appears FIRST, uses ?scientist as input");
        System.out.println("  - BGP appears AFTER, binds ?scientist");
        System.out.println("  - Pattern order doesn't matter (natural join is commutative)");
        System.out.println("  - Query engine should handle variable dependencies correctly\n");
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
        String queryString = readQueryFromFile("gensparql-example/queries/query8_genop_before_bgp.sparql");
        if (queryString == null) {
            queryString = readQueryFromFile("queries/query8_genop_before_bgp.sparql");
        }
        
        Query query = GenSPARQLQueryFactory.create(queryString);
        
        // Print query string
        System.out.println("--- Query String ---");
        System.out.println(queryString);
        
        // Print algebra tree
        System.out.println("\n--- Algebra Tree (Op Tree) ---");
        System.out.println("Note: Look for how OpGenerate and OpBGP are joined");
        System.out.println("The order in the algebra tree may differ from the query pattern order");
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
        
        // Print query pattern (using custom formatter for GENOP support)
        System.out.println("\n--- Query Pattern (Element Tree) ---");
        System.out.println("Note: GENOP appears before BGP in the pattern");
        System.out.println(GenSPARQLFormatterElement.asString(query.getQueryPattern()));
        
        // Execute query
        System.out.println("\n--- Query Results ---");
        System.out.println("Expected: One result per scientist with their award and generated description");
        System.out.println("The query engine should handle variable dependencies regardless of pattern order.\n");
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

