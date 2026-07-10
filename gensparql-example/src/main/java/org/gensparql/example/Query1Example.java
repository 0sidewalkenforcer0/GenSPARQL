package org.gensparql.example;

import org.apache.jena.query.*;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.sparql.algebra.Algebra;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.OpAsQuery;
import org.apache.jena.util.FileManager;
import org.gensparql.engine.boot.GenSPARQL;

import java.io.InputStream;

/**
 * Example 1: Basic SPARQL query - List all scientists and their awards
 */
public class Query1Example {
    
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
        System.out.println("=== Query 1: List Scientists and Awards ===");
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
        String queryString = readQueryFromFile("gensparql-example/queries/query1_list_scientists_awards.sparql");
        if (queryString == null) {
            queryString = readQueryFromFile("queries/query1_list_scientists_awards.sparql");
        }
        
        Query query = QueryFactory.create(queryString);
        
        // Print query string
        System.out.println("\n--- Query String ---");
        System.out.println(queryString);
        
        // Print algebra tree
        System.out.println("\n--- Algebra Tree (Op Tree) ---");
        Op op = Algebra.compile(query);
        System.out.println(op.toString());
        
        // Print query pattern
        System.out.println("\n--- Query Pattern (Element Tree) ---");
        System.out.println(query.getQueryPattern());
        
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

