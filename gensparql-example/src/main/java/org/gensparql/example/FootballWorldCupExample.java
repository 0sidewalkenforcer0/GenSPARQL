package org.gensparql.example;

import org.apache.jena.query.*;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.util.FileManager;
import org.gensparql.engine.boot.GenSPARQL;
import org.gensparql.engine.GenSPARQLConfig;
import org.gensparql.parser.GenSPARQLQueryFactory;

import java.io.InputStream;

/**
 * Paper demo query (2): "answering a missing fact, two ways".
 * (a) filter known KG athletes with a per-athlete yes/no GENOP.
 * (b) base-mode generation of World Cup winners, grounded back to KG nodes.
 */
public class FootballWorldCupExample {

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

        String filter =
            "PREFIX ex: <http://example.org/>\n" +
            "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>\n" +
            "SELECT ?name WHERE {\n" +
            "  ?p a ex:Athlete ; rdfs:label ?name .\n" +
            "  GENOP(\"Has {?name} won the FIFA World Cup? Reply with only one word: yes or no.\",\n" +
            "        (?won), <model:openrouter:deepseek/deepseek-chat>)\n" +
            "  FILTER(STRSTARTS(LCASE(STR(?won)), \"yes\"))\n" +
            "}";

        String generate =
            "PREFIX ex: <http://example.org/>\n" +
            "SELECT ?player WHERE {\n" +
            "  ?player a ex:Athlete .\n" +
            "  GENOP(\"List footballers who have won the FIFA World Cup. Return ONLY a JSON array of names.\",\n" +
            "        (?player), <model:openrouter:deepseek/deepseek-chat>, 0.85)\n" +
            "}";

        // (a) filter: no grounding needed (?won is a yes/no literal)
        GenSPARQLConfig.setGroundingEnabled(false);
        run("Query (2a) filter", filter, model);

        // (b) generate + ground: enable SimScore grounding to KG nodes.
        // Requires an embedding backend; set groundingEmbeddingProvider to an
        // OpenAI-compatible provider (e.g. Ollama via OPENAI_BASE_URL) since the
        // generation model (deepseek-chat) does not serve embeddings.
        GenSPARQLConfig.setGroundingEnabled(true);
        GenSPARQLConfig.setGroundingThreshold(0.85);
        GenSPARQLConfig.setGroundingStrategy("embedding");
        GenSPARQLConfig.setGroundingEmbeddingProvider("openai"); // OpenAI-compatible -> Ollama
        run("Query (2b) generate + ground [Ollama nomic-embed-text]", generate, model);
    }

    private static void run(String title, String q, Model model) {
        System.out.println("\n=== " + title + " ===");
        System.out.println(q);
        System.out.println("--- Results ---");
        try {
            Query query = GenSPARQLQueryFactory.create(q);
            try (QueryExecution qexec = GenSPARQL.createQueryExecution(query, model)) {
                ResultSet rs = qexec.execSelect();
                ResultSetFormatter.out(System.out, rs, query);
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
