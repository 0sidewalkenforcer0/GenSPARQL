import com.fasterxml.jackson.databind.*;
import org.gensparql.engine.boot.GenSPARQL;
import org.gensparql.core.similarity.JaccardSimText;
import org.gensparql.core.similarity.SimText;
import org.gensparql.core.model.*;
import org.gensparql.llm.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * E3: label-quality boundary on FB15k-237.
 * Part A (structural, no LLM): grounding recall of a gold entity name to its own
 *   KG entity, under two label regimes -- raw MID vs human label. theta=0.85.
 * Part B (end-to-end, Jaccard grounding, small sample): LLM predicts the tail of
 *   real test triples; ground to human-labeled entities; report Hits.
 */
public class E3Runner {
    static final double THETA = 0.85;

    public static void main(String[] args) throws Exception {
        GenSPARQL.init();
        String dir = args[0];
        SimText st = new JaccardSimText();

        // Load entity2text: mid \t name
        List<String> mids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (String line : Files.readAllLines(Paths.get(dir, "entity2text.txt"))) {
            int t = line.indexOf('\t'); if (t < 0) continue;
            mids.add(line.substring(0, t).trim());
            names.add(line.substring(t + 1).trim());
        }
        int N = names.size();
        System.out.println("ENTITIES " + N);

        // ---- Part A: structural grounding recall (best-case input = gold name) ----
        int K = 300;
        int step = Math.max(1, N / K);
        // groundRecall = a candidate grounds to *some* entity at theta (may be the
        //   wrong one); correctRecall = it grounds to *its own* entity (argmax is
        //   entity i). correctRecall is the meaningful metric: reporting only
        //   "grounds to something" would count wrong-entity matches as success.
        int sampled = 0, midGrounded = 0, midCorrect = 0, textGrounded = 0, textCorrect = 0;
        for (int i = 0; i < N; i += step) {
            String q = names.get(i);
            sampled++;
            // MID regime: argmax over MID labels; correct iff it is entity i's MID.
            double bestMid = -1; int bestMidIdx = -1;
            for (int j = 0; j < mids.size(); j++) {
                double s = st.similarity(q, mids.get(j));
                if (s > bestMid) { bestMid = s; bestMidIdx = j; }
            }
            if (bestMid >= THETA) { midGrounded++; if (bestMidIdx == i) midCorrect++; }
            // Label regime: argmax over name labels; correct iff it is entity i's name.
            double bestText = -1; int bestIdx = -1;
            for (int j = 0; j < N; j++) {
                double s = st.similarity(q, names.get(j));
                if (s > bestText) { bestText = s; bestIdx = j; }
            }
            if (bestText >= THETA) { textGrounded++; if (bestIdx == i || names.get(bestIdx).equalsIgnoreCase(q)) textCorrect++; }
        }
        System.out.printf(Locale.US,
            "PARTA sampled=%d theta=%.2f midGroundRecall=%.3f midCorrectRecall=%.3f textGroundRecall=%.3f textCorrectRecall=%.3f%n",
            sampled, THETA, (double)midGrounded/sampled, (double)midCorrect/sampled,
            (double)textGrounded/sampled, (double)textCorrect/sampled);

        // ---- Part B: small end-to-end with human labels + Jaccard grounding ----
        Set<String> nameSetLc = new HashSet<>();
        for (String n : names) nameSetLc.add(n.toLowerCase());

        LLMProvider prov = LLMProviderRegistry.get("openrouter");
        // temperature 0 for deterministic, reproducible predictions
        ModelSpec spec = ModelSpec.builder().provider("openrouter").model("deepseek/deepseek-chat").temperature(0.0).build();
        ObjectMapper om = new ObjectMapper();

        List<String[]> triples = new ArrayList<>();
        for (String line : Files.readAllLines(Paths.get(dir, "test.txt"))) {
            if (line.startsWith("#") || line.isBlank()) continue;
            String[] p = line.split("\t");
            if (p.length != 3 || !p[1].startsWith("→")) continue; // forward only
            triples.add(p);
            if (triples.size() >= 15) break;
        }

        int hits = 0, done = 0;
        for (String[] tr : triples) {
            String head = tr[0].trim();
            String rel = tr[1].replace("→","").replace("/"," ").replace("."," ").trim().replaceAll("\\s+"," ");
            String goldTail = tr[2].trim();
            String prompt = "In a general knowledge graph, list the entities that fill the blank: '"
                + head + "' -- " + rel + " -- ? . Return ONLY a JSON array of entity names.";
            try {
                GenerateResponse resp = prov.generateSync(GenerateRequest.builder()
                        .prompt(prompt).modelSpec(spec).outputVariables(Collections.singletonList("x")).build());
                List<String> cands = ExperimentRunner.parseArray(om, resp.getRawText());
                // ground candidates to human-labeled entities
                Set<String> grounded = new LinkedHashSet<>();
                for (String c : cands) {
                    if (nameSetLc.contains(c.toLowerCase())) { grounded.add(c); continue; }
                    double best=0; String bl=null;
                    for (String n : names) { double s=st.similarity(c,n); if (s>best){best=s;bl=n;} }
                    if (best>=THETA && bl!=null) grounded.add(bl);
                }
                boolean hit = grounded.stream().anyMatch(g -> g.equalsIgnoreCase(goldTail));
                if (hit) hits++;
                done++;
                System.out.println("PARTB q=[" + head + " | " + rel + "] gold=[" + goldTail
                        + "] cands=" + cands.size() + " grounded=" + grounded.size() + " hit=" + hit);
            } catch (Exception ex) {
                System.out.println("PARTB ERROR " + ex.getMessage());
            }
        }
        System.out.printf(Locale.US, "PARTB_SUMMARY queries=%d hitsAny=%d hitRate=%.3f%n",
            done, hits, done>0?(double)hits/done:0);
        System.out.println("DONE");
    }
}
