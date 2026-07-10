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
 * Example 2: Use GENOP to generate descriptions
 * Demonstrates GENOP with context mode (using variables from BGP)
 */
public class Query2Example {
    
    public static void main(String[] args) {
        // Initialize GenSPARQL
        System.out.println("[DEBUG Main] Initializing GenSPARQL...");
        GenSPARQL.init();
        System.out.println("[DEBUG Main] GenSPARQL initialized: " + GenSPARQL.isInitialized());

        // Check QueryEngineRegistry
        System.out.println("[DEBUG Main] Checking QueryEngineRegistry...");

        // Load the TTL data file
        String dataFile = "gensparql-example/data/scientists_awards.ttl";
        if (!java.nio.file.Files.exists(java.nio.file.Paths.get(dataFile))) {
            dataFile = "data/scientists_awards.ttl";
        }
        Model model = loadData(dataFile);

        // Execute query
        System.out.println("=== Query 2: Generate Descriptions with GENOP ===");
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
        String queryString = readQueryFromFile("gensparql-example/queries/query2_generate_descriptions.sparql");
        if (queryString == null) {
            queryString = readQueryFromFile("queries/query2_generate_descriptions.sparql");
        }

        System.out.println("\n--- Query String ---");
        System.out.println(queryString);

        Query query = GenSPARQLQueryFactory.create(queryString);
        System.out.println("[DEBUG] Query parsed successfully");
        System.out.println("[DEBUG] Query pattern type: " + query.getQueryPattern().getClass().getName());

        // Print algebra tree using standard Jena (for display only)
        System.out.println("\n--- Algebra Tree (Op Tree) ---");
        Op op = AlgebraGeneratorGenSPARQL.compile(query.getQueryPattern());
        if (query.isSelectType() && query.getProjectVars() != null) {
            op = new org.apache.jena.sparql.algebra.op.OpProject(op, query.getProjectVars());
        }
        System.out.println(op.toString());

        // Print query pattern
        System.out.println("\n--- Query Pattern (Element Tree) ---");
        System.out.println(GenSPARQLFormatterElement.asString(query.getQueryPattern()));

        // Execute query using GenSPARQL.createQueryExecution (ensures our engine is used)
        System.out.println("\n--- Query Results ---");

        try (QueryExecution qexec = GenSPARQL.createQueryExecution(query, model)) {
            System.out.println("[DEBUG] QueryExecution: " + qexec.getClass().getName());
            ResultSet results = qexec.execSelect();
            System.out.println("[DEBUG] ResultSet hasNext: " + results.hasNext());
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

