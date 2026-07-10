package org.gensparql.example;

import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.RDFDataMgr;

/**
 * Simple test to verify the large dataset loads correctly.
 */
public class TestLargeDataset {
    public static void main(String[] args) {
        try {
            // Load large dataset
            System.out.println("Loading scientists_awards_large.ttl...");
            Model model = ModelFactory.createDefaultModel();
            RDFDataMgr.read(model, "data/scientists_awards_large.ttl");

            System.out.println("Dataset loaded successfully!");
            System.out.println("Total triples: " + model.size());
            System.out.println();

            // Test query 1: Count scientists
            String query1 = "PREFIX foaf: <http://xmlns.com/foaf/0.1/> " +
                          "SELECT (COUNT(?person) AS ?count) WHERE { " +
                          "  ?person a foaf:Person . " +
                          "}";

            Query q1 = QueryFactory.create(query1);
            try (QueryExecution qexec = QueryExecutionFactory.create(q1, model)) {
                ResultSet results = qexec.execSelect();
                if (results.hasNext()) {
                    System.out.println("Number of scientists: " +
                        results.next().getLiteral("count").getInt());
                }
            }

            // Test query 2: Count awards
            String query2 = "PREFIX ex: <http://example.org/> " +
                          "SELECT (COUNT(?award) AS ?count) WHERE { " +
                          "  ?award a ex:Award . " +
                          "}";

            Query q2 = QueryFactory.create(query2);
            try (QueryExecution qexec = QueryExecutionFactory.create(q2, model)) {
                ResultSet results = qexec.execSelect();
                if (results.hasNext()) {
                    System.out.println("Number of awards: " +
                        results.next().getLiteral("count").getInt());
                }
            }

            // Test query 3: Count institutions
            String query3 = "PREFIX ex: <http://example.org/> " +
                          "SELECT (COUNT(?inst) AS ?count) WHERE { " +
                          "  ?inst a ex:Institution . " +
                          "}";

            Query q3 = QueryFactory.create(query3);
            try (QueryExecution qexec = QueryExecutionFactory.create(q3, model)) {
                ResultSet results = qexec.execSelect();
                if (results.hasNext()) {
                    System.out.println("Number of institutions: " +
                        results.next().getLiteral("count").getInt());
                }
            }

            // Test query 4: List some scientists with awards
            String query4 = "PREFIX foaf: <http://xmlns.com/foaf/0.1/> " +
                          "PREFIX ex: <http://example.org/> " +
                          "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#> " +
                          "SELECT ?scientist ?award WHERE { " +
                          "  ?person foaf:name ?scientist . " +
                          "  ?person ex:receivedAward ?awardUri . " +
                          "  ?awardUri rdfs:label ?award . " +
                          "} LIMIT 5";

            System.out.println("\nSample scientists with awards:");
            Query q4 = QueryFactory.create(query4);
            try (QueryExecution qexec = QueryExecutionFactory.create(q4, model)) {
                ResultSet results = qexec.execSelect();
                while (results.hasNext()) {
                    var solution = results.next();
                    System.out.println("  " + solution.getLiteral("scientist").getString() +
                                     " - " + solution.getLiteral("award").getString());
                }
            }

            System.out.println("\n✓ Large dataset test successful!");

        } catch (Exception e) {
            System.err.println("✗ Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
