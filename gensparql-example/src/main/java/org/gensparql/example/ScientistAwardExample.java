package org.gensparql.example;

import org.apache.jena.query.*;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.util.FileManager;
import org.gensparql.engine.boot.GenSPARQL;
import org.gensparql.parser.GenSPARQLQueryFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Example demonstrating GenSPARQL queries on scientists and awards dataset.
 * 
 * This example loads data and queries from files instead of hardcoding them.
 * 
 * Data includes:
 * - Albert Einstein - Nobel Prize in Physics
 * - Alan Turing - Order of the British Empire
 * - Marie Curie - Nobel Prize in Chemistry
 */
public class ScientistAwardExample {
    
    public static void main(String[] args) {
        // Initialize GenSPARQL
        GenSPARQL.init();
        
        // Optionally set LLM provider
        // GenSPARQL.setDefaultProvider("openrouter");
        
        // Load the TTL data file
        // Try multiple possible paths
        String dataFile = "gensparql-example/data/scientists_awards.ttl";
        if (!java.nio.file.Files.exists(java.nio.file.Paths.get(dataFile))) {
            dataFile = "data/scientists_awards.ttl";
        }
        Model model = loadData(dataFile);
        
        // Example 1: Basic query - list all scientists and awards
        System.out.println("=== Example 1: List Scientists and Awards ===");
        executeQueryFromFile("gensparql-example/queries/query1_list_scientists_awards.sparql", model, false);
        
        // Example 2: Use GENOP to generate descriptions
        System.out.println("\n=== Example 2: Generate Descriptions with GENOP ===");
        executeQueryFromFile("gensparql-example/queries/query2_generate_descriptions.sparql", model, true);
        
        // Example 3: Base Mode GENOP - List Nobel Prize winners
        System.out.println("\n=== Example 3: Base Mode GENOP - List Nobel Prize Winners ===");
        executeQueryFromFile("gensparql-example/queries/query3_list_nobel_laureates.sparql", model, true);
        
        // Example 4: Semantic Filter - Scientists born in France
        System.out.println("\n=== Example 4: Semantic Filter - Scientists Born in France ===");
        executeQueryFromFile("gensparql-example/queries/query4_filter_french_scientists.sparql", model, true);
    }
    
    /**
     * Load RDF data from a TTL file.
     */
    private static Model loadData(String filename) {
        Model model = ModelFactory.createDefaultModel();
        InputStream in = FileManager.get().open(filename);
        if (in == null) {
            throw new IllegalArgumentException("File not found: " + filename);
        }
        model.read(in, null, "TTL");
        return model;
    }
    
    /**
     * Read a SPARQL query from a file.
     */
    private static String readQueryFromFile(String filename) {
        try {
            // Try to read from file system first (relative to current directory)
            java.nio.file.Path filePath = Paths.get(filename);
            if (!Files.exists(filePath)) {
                // Try alternative path without gensparql-example prefix
                String altPath = filename.replace("gensparql-example/", "");
                filePath = Paths.get(altPath);
            }
            if (Files.exists(filePath)) {
                return Files.readString(filePath, StandardCharsets.UTF_8);
            }
            
            // Try with FileManager (Jena's file resolution)
            InputStream in1 = FileManager.get().open(filename);
            if (in1 != null) {
                try (in1) {
                    return new String(in1.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            
            // Fallback to classpath resource
            InputStream in2 = ScientistAwardExample.class.getClassLoader()
                    .getResourceAsStream(filename);
            if (in2 != null) {
                try (in2) {
                    return new String(in2.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            
            throw new IllegalArgumentException("Query file not found: " + filename);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read query file: " + filename, e);
        }
    }
    
    /**
     * Execute a query from a file.
     * 
     * @param filename the query file path
     * @param model the RDF model to query
     * @param useGenSPARQL whether to use GenSPARQL parser (for GENOP queries)
     */
    private static void executeQueryFromFile(String filename, Model model, boolean useGenSPARQL) {
        try {
            String queryString = readQueryFromFile(filename);
            
            Query query;
            if (useGenSPARQL) {
                query = GenSPARQLQueryFactory.create(queryString);
            } else {
                query = QueryFactory.create(queryString);
            }
            
            try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
                ResultSet results = qexec.execSelect();
                ResultSetFormatter.out(System.out, results, query);
            }
        } catch (Exception e) {
            System.err.println("Error executing query from " + filename + ": " + e.getMessage());
            e.printStackTrace();
        }
    }
}

