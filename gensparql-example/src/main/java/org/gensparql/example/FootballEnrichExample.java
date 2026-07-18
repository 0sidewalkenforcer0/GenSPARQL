package org.gensparql.example;

import org.apache.jena.query.*;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.util.FileManager;
import org.gensparql.engine.boot.GenSPARQL;
import org.gensparql.parser.GenSPARQLQueryFactory;

import java.io.InputStream;

/**
 * Paper demo query (1): attribute enrichment (context mode).
 * Enriches each athlete in the football KG with a shirt number and the years
 * they played for their national team, then prints ?name ?number ?years.
 * Run with OPENROUTER_API_KEY set.
 */
public class FootballEnrichExample {

    public static void main(String[] args) {
        GenSPARQL.init();

        String dataFile = "gensparql-example/data/footballers.ttl";
        if (!java.nio.file.Files.exists(java.nio.file.Paths.get(dataFile))) {
            dataFile = "data/footballers.ttl";
        }
        Model model = ModelFactory.createDefaultModel();
        try (InputStream in = FileManager.get().open(dataFile)) {
            if (in == null) throw new IllegalArgumentException("File not found: " + dataFile);
            model.read(in, null, "TTL");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        String q =
            "PREFIX ex: <http://example.org/>\n" +
            "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>\n" +
            "SELECT ?name ?number ?years WHERE {\n" +
            "  ?p a ex:Athlete ; rdfs:label ?name .\n" +
            "  GENOP(\"What is {?name}'s usual shirt number, and which years did they " +
            "play for their national team? Return as JSON object with fields 'number' " +
            "(an integer) and 'years' (a span like 2005-2024 or 2017-present).\",\n" +
            "        (?number, ?years),\n" +
            "        <model:openrouter:deepseek/deepseek-chat>)\n" +
            "}";

        System.out.println("=== Football enrichment (paper demo query 1) ===");
        System.out.println(q);
        System.out.println("\n--- Results ---");
        Query query = GenSPARQLQueryFactory.create(q);
        try (QueryExecution qexec = GenSPARQL.createQueryExecution(query, model)) {
            ResultSet rs = qexec.execSelect();
            ResultSetFormatter.out(System.out, rs, query);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
