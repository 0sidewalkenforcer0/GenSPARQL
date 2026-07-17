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
    static final double[] THETAS = {0.85, 0.90, 0.95};

    static class Exp { String id, prompt, type;
        Exp(String i,String p,String t){id=i;prompt=p;type=t;} }

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
            new Exp("physics_branches","List the major branches of physics. Return ONLY a JSON array of short names, e.g. [\"Quantum Mechanics\",\"Thermodynamics\"].","http://example.org/ResearchField"),
            new Exp("research_fields","List 12 major scientific research fields. Return ONLY a JSON array of short names.","http://example.org/ResearchField"),
            new Exp("awards","List 15 famous scientific awards and prizes. Return ONLY a JSON array of full names.","http://example.org/Award"),
            new Exp("institutions","List 15 world-renowned universities and research institutes. Return ONLY a JSON array of names.","http://example.org/Institution"),
            new Exp("nobel_categories","List the categories of the Nobel Prize. Return ONLY a JSON array.","http://example.org/Award")
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

            report("Jaccard", jac, e, cands, gold, goldNorm);
            if (emb != null) report("Embedding", emb, e, cands, gold, goldNorm);
        }
        System.out.println("DONE");
    }

    static void report(String name, SimText st, Exp e, List<String> cands,
                        Map<String,String> gold, Set<String> goldNorm) {
        for (double th : THETAS) {
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
            System.out.printf(Locale.US, "METHOD id=%s sim=%s theta=%.2f grounded=%d exact=%d fuzzy=%d%n",
                    e.id, name, th, exact.size()+fuzzy.size(), exact.size(), fuzzy.size());
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
