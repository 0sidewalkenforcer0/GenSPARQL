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
 * Fair Jaccard-vs-embedding comparison: generate candidates ONCE per query, then
 * ground the SAME candidates with both similarities at several thresholds.
 * Separates exact matches (trivially correct) from fuzzy matches (need judgment,
 * printed for inspection).
 */
public class ExperimentRunnerCompare {
    // Full sweep for the threshold figure; the backend table reports 0.85/0.90/0.95.
    static final double[] THETAS = {0.50, 0.60, 0.70, 0.80, 0.85, 0.90, 0.95, 1.00};

    static class Exp { String id, prompt, type;
        Exp(String i,String p,String t){id=i;prompt=p;type=t;} }

    // aggregate grounded counts across queries: method -> per-theta total
    static final Map<String,long[]> AGG = new LinkedHashMap<>();
    static long totCand = 0, totExactInKG = 0, totTextGrounded85 = 0;
    static void agg(String method, int ti, int grounded) {
        AGG.computeIfAbsent(method, k -> new long[THETAS.length])[ti] += grounded;
    }

    public static void main(String[] args) throws Exception {
        GenSPARQL.init();
        Model model = ModelFactory.createDefaultModel();
        RDFDataMgr.read(model, args[0]);
        Dataset ds = DatasetFactory.create(model);

        LLMProvider gen = LLMProviderRegistry.get("openrouter");
        ModelSpec spec = ModelSpec.builder().provider("openrouter").model("deepseek/deepseek-chat").build();

        SimText jac = new JaccardSimText();
        SimText emb = null;
        if (System.getenv("OPENAI_BASE_URL") != null) {
            LLMProviderRegistry.setDefault(LLMProviderRegistry.get("openai"));
            emb = new EmbeddingSimText();
            System.out.println("EMBEDDING backend: " + System.getenv("OPENAI_EMBEDDING_MODEL"));
        }

        List<Exp> exps = Arrays.asList(
            new Exp("wc_winners","List 20 footballers who have won the FIFA World Cup. Return ONLY a JSON array of player names, e.g. [\"Lionel Messi\",\"Pele\"].","http://example.org/Athlete"),
            new Exp("national_teams","List 15 national football teams that have won a World Cup or continental title. Return ONLY a JSON array of country names.","http://example.org/Team"),
            new Exp("clubs","List 15 famous football clubs. Return ONLY a JSON array of club names.","http://example.org/Club"),
            new Exp("trophies","List 12 major football trophies and competitions. Return ONLY a JSON array of names.","http://example.org/Trophy"),
            new Exp("birthplaces","List 15 cities that are birthplaces of famous footballers. Return ONLY a JSON array of city names.","http://example.org/City")
        );
        ObjectMapper om = new ObjectMapper();

        for (Exp e : exps) {
            Map<String,String> gold = goldLabels(ds, e.type);
            Set<String> goldNorm = new HashSet<>();
            for (String g : gold.keySet()) goldNorm.add(CanonicalForm.normalize(g));

            GenerateResponse resp = gen.generateSync(GenerateRequest.builder()
                    .prompt(e.prompt).modelSpec(spec).outputVariables(Collections.singletonList("x")).build());
            List<String> cands = parseArray(om, resp.getRawText());
            int exactInKG = 0;
            for (String c : cands) if (goldNorm.contains(CanonicalForm.normalize(c))) exactInKG++;
            System.out.println("RESULT id=" + e.id + " candidates=" + cands.size() + " exactInKG=" + exactInKG);
            totCand += cands.size();
            totExactInKG += exactInKG;

            report("Jaccard", jac, e, cands, gold, goldNorm);
            if (emb != null) report("Embedding", emb, e, cands, gold, goldNorm);
        }
        // aggregate lines: total grounded per (method, theta) across all queries
        for (Map.Entry<String,long[]> me : AGG.entrySet()) {
            StringBuilder sb = new StringBuilder("AGG sim=" + me.getKey() + " ");
            for (int i = 0; i < THETAS.length; i++)
                sb.append(String.format(Locale.US, "%.2f:%d ", THETAS[i], me.getValue()[i]));
            System.out.println(sb.toString().trim());
        }
        // Table 2a headline (text default): raw candidates -> in-KG -> returned(grounded@0.85 text)
        System.out.printf(Locale.US,
            "TABLE2A totalCand=%d totalInKG=%d textGrounded@0.85=%d rawValidity=%.1f%%%n",
            totCand, totExactInKG, totTextGrounded85, 100.0 * totExactInKG / Math.max(1, totCand));
        System.out.println("DONE");
    }

    static void report(String name, SimText st, Exp e, List<String> cands,
                        Map<String,String> gold, Set<String> goldNorm) {
        for (int ti = 0; ti < THETAS.length; ti++) {
            double th = THETAS[ti];
            List<String> exact = new ArrayList<>(), fuzzy = new ArrayList<>();
            for (String c : cands) {
                String bestL = null; double best = -1;
                for (String g : gold.keySet()) { double s = st.similarity(c, g); if (s > best){best=s;bestL=g;} }
                if (best >= th && bestL != null) {
                    boolean isExact = goldNorm.contains(CanonicalForm.normalize(c));
                    String pair = c + "=>" + bestL + "(" + String.format(Locale.US,"%.2f",best) + ")";
                    if (isExact) exact.add(pair); else fuzzy.add(pair);
                }
            }
            int grounded = exact.size() + fuzzy.size();
            agg(name, ti, grounded);
            if ("Jaccard".equals(name) && Math.abs(th - 0.85) < 1e-9) totTextGrounded85 += grounded;
            System.out.printf(Locale.US, "METHOD id=%s sim=%s theta=%.2f grounded=%d exact=%d fuzzy=%d%n",
                    e.id, name, th, grounded, exact.size(), fuzzy.size());
            if (!fuzzy.isEmpty())
                System.out.println("FUZZY id=" + e.id + " sim=" + name + " theta=" + String.format(Locale.US,"%.2f",th) + " " + fuzzy);
        }
    }

    static Map<String,String> goldLabels(Dataset ds, String typeUri) {
        Map<String,String> m = new LinkedHashMap<>();
        String q = "PREFIX rdfs:<http://www.w3.org/2000/01/rdf-schema#> SELECT ?e ?l WHERE { ?e a <" + typeUri + "> ; rdfs:label ?l }";
        try (QueryExecution qe = QueryExecutionFactory.create(QueryFactory.create(q), ds)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) { QuerySolution s = rs.next(); m.put(s.getLiteral("l").getString(), s.getResource("e").getURI()); }
        }
        return m;
    }
    static List<String> parseArray(ObjectMapper om, String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        String s = raw.trim();
        if (s.startsWith("```")) { int nl=s.indexOf('\n'); if(nl>=0) s=s.substring(nl+1); if(s.endsWith("```")) s=s.substring(0,s.length()-3); }
        int lb=s.indexOf('['), rb=s.lastIndexOf(']'); if(lb>=0&&rb>lb) s=s.substring(lb,rb+1);
        try { JsonNode n=om.readTree(s); if(n.isArray()) for(JsonNode x:n) out.add(x.asText().trim()); }
        catch (Exception ex) { for(String ln:raw.split("\n")){ String t=ln.replaceAll("^[-*\\d.\\s\"]+","").replaceAll("[\",]+$","").trim(); if(!t.isEmpty()&&t.length()<60) out.add(t);} }
        return out;
    }
}
