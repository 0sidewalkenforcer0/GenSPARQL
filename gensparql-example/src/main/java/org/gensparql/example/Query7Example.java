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
 * Example 7: Cartesian Product with GENOP
 * 
 * Demonstrates how GENOP handles input variables from multiple BGPs with no shared variables.
 * 
 * This example shows:
 * 1. BGP1: Querying scientists from the database (binds ?scientist)
 * 2. BGP2: Querying awards from the database (binds ?awardLabel)
 *    - These two BGPs have NO shared variables, so they produce a Cartesian product
 * 3. GENOP: Uses both ?scientist and ?awardLabel as input variables
 *    - For each combination of (scientist, award), GENOP generates a description
 * 
 * Key features:
 * - Cartesian product: If BGP1 has 3 scientists and BGP2 has 3 awards,
 *   GENOP will be called 3×3=9 times (one for each combination)
 * - No shared variables between BGPs means natural join becomes Cartesian product
 * - Each GENOP call receives a different combination of input variables
 * 
 * Expected behavior:
 * - BGP1 produces: {?scientist = "Albert Einstein"}, {?scientist = "Alan Turing"}, {?scientist = "Marie Curie"}
 * - BGP2 produces: {?awardLabel = "Nobel Prize in Physics"}, {?awardLabel = "Nobel Prize in Chemistry"}, {?awardLabel = "Order of the British Empire"}
 * - Cartesian product: 3 × 3 = 9 combinations
 * - GENOP called 9 times, once for each combination
 */
public class Query7Example {
    
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
        System.out.println("=== Query 7: Cartesian Product with GENOP ===");
        System.out.println("This query demonstrates:");
        System.out.println("  - BGP1: Query scientists (binds ?scientist)");
        System.out.println("  - BGP2: Query awards (binds ?awardLabel)");
        System.out.println("  - No shared variables → Cartesian product");
        System.out.println("  - GENOP uses both variables as input\n");
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
        String queryString = readQueryFromFile("gensparql-example/queries/query7_cartesian_product.sparql");
        if (queryString == null) {
            queryString = readQueryFromFile("queries/query7_cartesian_product.sparql");
        }
        
        Query query = GenSPARQLQueryFactory.create(queryString);
        
        // Print query string
        System.out.println("--- Query String ---");
        System.out.println(queryString);
        
        // Print algebra tree
        System.out.println("\n--- Algebra Tree (Op Tree) ---");
        System.out.println("Note: Look for OpJoin operations that create Cartesian product");
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
        System.out.println(GenSPARQLFormatterElement.asString(query.getQueryPattern()));
        
        // Execute query
        System.out.println("\n--- Query Results ---");
        System.out.println("Expected: Cartesian product of scientists × awards");
        System.out.println("If there are 3 scientists and 3 awards, expect 9 results.\n");
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

