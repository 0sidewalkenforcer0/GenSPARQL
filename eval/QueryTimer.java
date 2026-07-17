import org.apache.jena.query.*;
import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.RDFDataMgr;
import org.gensparql.engine.boot.GenSPARQL;
import org.gensparql.parser.GenSPARQLQueryFactory;
import java.util.*;

/**
 * E4: end-to-end query latency + cache effect through the REAL engine.
 * Runs base-mode (Q3, grounding) and context-mode (Q1, enrichment) queries,
 * cold then warm, and reports wall-clock + result counts.
 * Doubles as validation that the engine grounds physics -> ex:quantum_mechanics.
 */
public class QueryTimer {

    static final String Q3_BASE =
        "PREFIX ex: <http://example.org/>\n" +
        "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>\n" +
        "SELECT ?field WHERE {\n" +
        "  ?field rdf:type ex:ResearchField .\n" +
        "  GENOP(\"List the major branches of physics. Return ONLY a JSON array of short names like ['Quantum Mechanics','Thermodynamics'].\",\n" +
        "        (?field), <model:openrouter:deepseek/deepseek-chat>, 0.85)\n" +
        "}";

    static final String Q1_CTX =
        "PREFIX ex: <http://example.org/>\n" +
        "PREFIX foaf: <http://xmlns.com/foaf/0.1/>\n" +
        "SELECT ?name ?summary WHERE {\n" +
        "  ?s a foaf:Person ; foaf:name ?name .\n" +
        "  GENOP(\"In one sentence, what is {?name} best known for scientifically?\",\n" +
        "        (?summary), <model:openrouter:deepseek/deepseek-chat>)\n" +
        "}";

    public static void main(String[] args) throws Exception {
        GenSPARQL.init();
        Model model = ModelFactory.createDefaultModel();
        RDFDataMgr.read(model, args[0]);
        Dataset ds = DatasetFactory.create(model);
        System.out.println("LOADED triples=" + model.size());

        // Q3 base-mode: cold + warm
        timeRun("Q3_base_cold", Q3_BASE, ds, "field");
        timeRun("Q3_base_warm", Q3_BASE, ds, "field");

        // Q1 context-mode (26 persons -> 26 generations): cold + warm
        timeRun("Q1_ctx_cold", Q1_CTX, ds, "name");
        timeRun("Q1_ctx_warm", Q1_CTX, ds, "name");

        System.out.println("DONE");
    }

    static void timeRun(String tag, String sparql, Dataset ds, String var) {
        long t0 = System.currentTimeMillis();
        int rows = 0; List<String> sample = new ArrayList<>();
        try {
            Query q = GenSPARQLQueryFactory.create(sparql);
            try (QueryExecution qe = QueryExecutionFactory.create(q, ds)) {
                ResultSet rs = qe.execSelect();
                while (rs.hasNext()) {
                    QuerySolution s = rs.next();
                    rows++;
                    if (sample.size() < 3 && s.contains(var))
                        sample.add(s.get(var).toString());
                }
            }
        } catch (Exception e) {
            System.out.println("TIMER " + tag + " ERROR=" + e.getMessage());
            return;
        }
        long dt = System.currentTimeMillis() - t0;
        System.out.println("TIMER " + tag + " rows=" + rows + " wallMs=" + dt + " sample=" + sample);
    }
}
