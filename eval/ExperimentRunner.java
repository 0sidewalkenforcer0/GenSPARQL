import com.fasterxml.jackson.databind.*;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.RDFDataMgr;
import org.gensparql.engine.boot.GenSPARQL;
import org.gensparql.core.similarity.JaccardSimText;
import org.gensparql.core.similarity.SimText;
import org.gensparql.core.similarity.CanonicalForm;
import org.gensparql.engine.similarity.EmbeddingSimText;
import org.gensparql.core.model.*;
import org.gensparql.llm.*;

import java.util.*;

/**
 * E1 (grounding precision) + E2 (threshold sensitivity) driver.
 * Grounding uses JaccardSimText by default (text similarity; deterministic, no
 * embedding backend needed). Set an OpenAI-compatible embedding endpoint
 * (OPENAI_API_KEY + OPENAI_BASE_URL [+ OPENAI_EMBEDDING_MODEL]) to switch to
 * embedding-cosine grounding via a foundation text embedder. Generation always
 * uses deepseek via OpenRouter. Prints RESULT / CANDS / SWEEP / MAP lines.
 */
public class ExperimentRunner {

    static final double[] THETAS = {0.50, 0.60, 0.70, 0.80, 0.85, 0.90, 1.00};

    static class Exp {
        String id, prompt, type;
        Exp(String id, String prompt, String type){this.id=id;this.prompt=prompt;this.type=type;}
    }

    public static void main(String[] args) throws Exception {
        GenSPARQL.init();
        String data = args[0];
        Model model = ModelFactory.createDefaultModel();
        RDFDataMgr.read(model, data);
        Dataset ds = DatasetFactory.create(model);
        System.out.println("LOADED triples=" + model.size());

        LLMProvider prov = LLMProviderRegistry.get("openrouter");
        // temperature 0 for deterministic, reproducible candidate sets
        ModelSpec spec = ModelSpec.builder().provider("openrouter")
                .model("deepseek/deepseek-chat").temperature(0.0).build();
        // Grounding similarity: text (Jaccard) by default, or embedding cosine when
        // an OpenAI-compatible embedding backend is configured.
        SimText st;
        if (System.getenv("OPENAI_API_KEY") != null
                && (System.getenv("OPENAI_BASE_URL") != null
                    || "embedding".equalsIgnoreCase(System.getenv("GS_GROUNDING")))) {
            LLMProviderRegistry.setDefault(LLMProviderRegistry.get("openai"));
            st = new EmbeddingSimText();
            System.out.println("SIMTEXT Embedding (openai-compatible endpoint)");
        } else {
            st = new JaccardSimText();
            System.out.println("SIMTEXT " + st.getName());
        }

        List<Exp> exps = Arrays.asList(
            new Exp("wc_winners",
                "List 20 footballers who have won the FIFA World Cup. Return ONLY a JSON array of player names, e.g. [\"Lionel Messi\",\"Pele\"].",
                "http://example.org/Athlete"),
            new Exp("national_teams",
                "List 15 national football teams that have won a World Cup or continental title. Return ONLY a JSON array of country names, e.g. [\"Brazil\",\"Germany\"].",
                "http://example.org/Team"),
            new Exp("clubs",
                "List 15 famous football clubs. Return ONLY a JSON array of club names, e.g. [\"Real Madrid\",\"Liverpool\"].",
                "http://example.org/Club"),
            new Exp("trophies",
                "List 12 major football trophies and competitions. Return ONLY a JSON array of names, e.g. [\"World Cup\",\"Champions League\"].",
                "http://example.org/Trophy"),
            new Exp("birthplaces",
                "List 15 cities that are birthplaces of famous footballers. Return ONLY a JSON array of city names, e.g. [\"Rosario\",\"Funchal\"].",
                "http://example.org/City")
        );

        ObjectMapper om = new ObjectMapper();

        for (Exp e : exps) {
            // gold labels for this KG type (clean rdfs:label)
            Map<String,String> goldLabelToUri = goldLabels(ds, e.type);
            Set<String> goldNorm = new HashSet<>();
            for (String g : goldLabelToUri.keySet()) goldNorm.add(CanonicalForm.normalize(g));

            long t0 = System.currentTimeMillis();
            GenerateResponse resp = prov.generateSync(GenerateRequest.builder()
                    .prompt(e.prompt).modelSpec(spec)
                    .outputVariables(Collections.singletonList("x")).build());
            long llmMs = System.currentTimeMillis() - t0;
            List<String> cands = parseArray(om, resp.getRawText());

            int rawValid = 0;
            for (String c : cands) if (goldNorm.contains(CanonicalForm.normalize(c))) rawValid++;

            // theta sweep: ground each candidate to argmax gold label; grounded if maxSim >= theta
            long tg = System.currentTimeMillis();
            StringBuilder sweep = new StringBuilder();
            int groundedAt85 = -1;
            List<String> mapAt85 = new ArrayList<>();
            for (double th : THETAS) {
                Set<String> uris = new LinkedHashSet<>();
                List<String> mapping = new ArrayList<>();
                for (String c : cands) {
                    String bestLabel = null; double best = -1;
                    for (String g : goldLabelToUri.keySet()) {
                        double s = st.similarity(c, g);
                        if (s > best) { best = s; bestLabel = g; }
                    }
                    if (best >= th && bestLabel != null) {
                        uris.add(goldLabelToUri.get(bestLabel));
                        mapping.add(c + "=>" + bestLabel + "(" + String.format(Locale.US,"%.2f",best) + ")");
                    }
                }
                sweep.append(String.format(Locale.US, "%.2f:%d ", th, uris.size()));
                if (Math.abs(th-0.85) < 1e-9) { groundedAt85 = uris.size(); mapAt85 = mapping; }
            }
            long groundMs = System.currentTimeMillis() - tg;

            System.out.println("RESULT id=" + e.id
                    + " poolSize=" + goldLabelToUri.size()
                    + " candidates=" + cands.size()
                    + " rawValid=" + rawValid
                    + " groundedAt0.85=" + groundedAt85
                    + " llmMs=" + llmMs + " groundMs=" + groundMs);
            System.out.println("CANDS id=" + e.id + " " + cands);
            System.out.println("SWEEP id=" + e.id + " " + sweep.toString().trim());
            System.out.println("MAP id=" + e.id + " " + mapAt85);
        }
        System.out.println("DONE");
    }

    static Map<String,String> goldLabels(Dataset ds, String typeUri) {
        Map<String,String> m = new LinkedHashMap<>();
        String q = "PREFIX rdfs:<http://www.w3.org/2000/01/rdf-schema#> " +
                   "SELECT ?e ?l WHERE { ?e a <" + typeUri + "> ; rdfs:label ?l }";
        try (QueryExecution qe = QueryExecutionFactory.create(QueryFactory.create(q), ds)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) {
                QuerySolution s = rs.next();
                m.put(s.getLiteral("l").getString(), s.getResource("e").getURI());
            }
        }
        return m;
    }

    static List<String> parseArray(ObjectMapper om, String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        String s = raw.trim();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n'); if (nl>=0) s = s.substring(nl+1);
            if (s.endsWith("```")) s = s.substring(0, s.length()-3);
        }
        int lb = s.indexOf('['), rb = s.lastIndexOf(']');
        if (lb>=0 && rb>lb) s = s.substring(lb, rb+1);
        try {
            JsonNode n = om.readTree(s);
            if (n.isArray()) for (JsonNode x : n) out.add(x.asText().trim());
        } catch (Exception ex) {
            for (String line : raw.split("\n")) {
                String t = line.replaceAll("^[-*\\d.\\s\"]+","").replaceAll("[\",]+$","").trim();
                if (!t.isEmpty() && t.length()<60) out.add(t);
            }
        }
        return out;
    }
}
