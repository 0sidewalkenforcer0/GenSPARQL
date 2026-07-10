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
 * Example 4: Semantic Filter - Scientists born in France
 * Demonstrates GENOP for semantic filtering using LLM knowledge
 */
public class Query4Example {
    
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
        System.out.println("=== Query 4: Semantic Filter - Scientists Born in France ===");
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
        String queryString = readQueryFromFile("gensparql-example/queries/query4_filter_french_scientists.sparql");
        if (queryString == null) {
            queryString = readQueryFromFile("queries/query4_filter_french_scientists.sparql");
        }
        
        Query query = GenSPARQLQueryFactory.create(queryString);
        
        // Print query string
        System.out.println("\n--- Query String ---");
        System.out.println(queryString);
        
        // Print algebra tree
        System.out.println("\n--- Algebra Tree (Op Tree) ---");
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

