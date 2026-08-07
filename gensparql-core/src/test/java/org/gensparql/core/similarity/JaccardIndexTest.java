package org.gensparql.core.similarity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The index must never hide a match.
 *
 * <p>It exists so that a higher threshold costs less, which is only worth having if the answer
 * does not change. Every test here is the same question in a different shape: is everything
 * {@link JaccardSimText} would score at or above the threshold still among the candidates?
 */
@DisplayName("Jaccard candidate index")
public class JaccardIndexTest {

    private static final JaccardSimText SIM = new JaccardSimText();

    private static final List<String> CORPUS = List.of(
            "Brazil", "Argentina", "France", "Japan", "Mexico", "Turkey", "Czechia",
            "United States", "United States of America", "United Arab Emirates",
            "Republic of Korea", "Democratic Republic of the Congo", "Republic of Ireland",
            "South Korea", "South Africa", "New Zealand", "Ivory Coast", "Cape Verde",
            "Bosnia and Herzegovina", "Trinidad and Tobago", "Antigua and Barbuda",
            "Saudi Arabia", "United Kingdom", "Papua New Guinea", "Sao Tome and Principe");

    private static final List<String> QUERIES = List.of(
            "Brazil", "brazil", "BRAZIL", "United States", "United States of America",
            "United Kingdom", "Republic of Korea", "South Korea", "Korea",
            "Bosnia and Herzegovina", "and", "of the", "Italy", "zzz qqq", "", "   ",
            "New Zealand", "Democratic Republic of the Congo", "Republic");

    private static JaccardIndex<String> indexOf(List<String> corpus) {
        JaccardIndex<String> index = new JaccardIndex<>();
        for (String s : corpus) {
            index.add(s, s);
        }
        return index;
    }

    /** Everything the scorer would accept, found by comparing against every entry. */
    private static Set<String> trueMatches(List<String> corpus, String query, double threshold) {
        Set<String> matches = new HashSet<>();
        for (String entry : corpus) {
            if (SIM.similarity(query, entry) >= threshold) {
                matches.add(entry);
            }
        }
        return matches;
    }

    @Test
    @DisplayName("no match is ever excluded, across the corpus and the threshold range")
    void neverHidesAMatch() {
        JaccardIndex<String> index = indexOf(CORPUS);

        for (int step = 1; step <= 20; step++) {
            double threshold = step / 20.0;
            for (String query : QUERIES) {
                Set<String> expected = trueMatches(CORPUS, query, threshold);
                Set<String> candidates = new HashSet<>(index.candidates(query, threshold));

                assertTrue(candidates.containsAll(expected),
                        "query \"" + query + "\" at " + String.format("%.2f", threshold)
                        + " lost " + minus(expected, candidates));
            }
        }
    }

    @Test
    @DisplayName("no match is ever excluded, on random token soup")
    void neverHidesAMatchOnRandomData() {
        Random random = new Random(20260806L);
        List<String> corpus = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            corpus.add(randomPhrase(random));
        }
        JaccardIndex<String> index = indexOf(corpus);

        for (int q = 0; q < 200; q++) {
            String query = randomPhrase(random);
            for (double threshold : new double[] {0.05, 0.25, 0.5, 0.75, 0.9, 1.0}) {
                Set<String> expected = trueMatches(corpus, query, threshold);
                Set<String> candidates = new HashSet<>(index.candidates(query, threshold));
                assertTrue(candidates.containsAll(expected),
                        "query \"" + query + "\" at " + threshold + " lost " + minus(expected, candidates));
            }
        }
    }

    private static String randomPhrase(Random random) {
        String[] vocabulary = {"alpha", "beta", "gamma", "delta", "epsilon", "zeta", "eta",
                               "theta", "iota", "kappa", "of", "the", "and", "new", "south"};
        int words = 1 + random.nextInt(5);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(vocabulary[random.nextInt(vocabulary.length)]);
        }
        return sb.toString();
    }

    private static Set<String> minus(Set<String> a, Set<String> b) {
        Set<String> out = new HashSet<>(a);
        out.removeAll(b);
        return out;
    }

    @Test
    @DisplayName("a case-only difference is still a candidate, though it scores below 1")
    void caseInsensitiveMatchSurvives() {
        // JaccardSimText reports 0.95 for a case-only difference while the token sets are
        // identical. The index reasons about the tokens, which is the higher of the two, so it
        // cannot exclude such an entry.
        JaccardIndex<String> index = indexOf(CORPUS);

        assertEquals(0.95, SIM.similarity("brazil", "Brazil"), 1e-9);
        assertTrue(index.candidates("brazil", 0.95).contains("Brazil"));
    }

    @Test
    @DisplayName("a phrase sharing no token with anything has no candidates")
    void nothingInCommonMeansNoCandidates() {
        JaccardIndex<String> index = indexOf(CORPUS);

        assertTrue(index.candidates("Italy", 0.5).isEmpty(),
                "no entry shares a word with it, so nothing needs scoring at all");
        assertTrue(trueMatches(CORPUS, "Italy", 0.5).isEmpty(), "and indeed nothing matches");
    }

    @Test
    @DisplayName("a threshold of zero admits everything, since everything matches")
    void zeroThresholdAdmitsEverything() {
        JaccardIndex<String> index = indexOf(CORPUS);
        assertEquals(CORPUS.size(), index.candidates("Italy", 0.0).size());
    }

    @Test
    @DisplayName("fewer candidates as the threshold rises")
    void candidatesFallAsThresholdRises() {
        JaccardIndex<String> index = indexOf(CORPUS);
        String query = "United States of America";

        int atLow = index.candidates(query, 0.2).size();
        int atMid = index.candidates(query, 0.5).size();
        int atHigh = index.candidates(query, 0.9).size();

        assertTrue(atLow >= atMid && atMid >= atHigh,
                "expected the candidate set to shrink: " + atLow + " -> " + atMid + " -> " + atHigh);
        assertTrue(atHigh < CORPUS.size(),
                "a high threshold must cost less than comparing everything");
    }

    @Test
    @DisplayName("an empty or blank query has no candidates")
    void emptyQuery() {
        JaccardIndex<String> index = indexOf(CORPUS);
        assertTrue(index.candidates("", 0.5).isEmpty());
        assertTrue(index.candidates("   ", 0.5).isEmpty());
        assertTrue(index.candidates(null, 0.5).isEmpty());
    }

    @Test
    @DisplayName("entries added after a query are visible to the next one")
    void addingAfterQuerying() {
        JaccardIndex<String> index = indexOf(CORPUS);
        assertFalse(index.candidates("Iceland", 0.5).contains("Iceland"));

        index.add("Iceland", "Iceland");
        assertTrue(index.candidates("Iceland", 0.5).contains("Iceland"));
    }

    @Test
    @DisplayName("two entries with the same text are both returned")
    void duplicateTextsBothReturned() {
        JaccardIndex<Integer> index = new JaccardIndex<>();
        index.add("United States", 1);
        index.add("United States", 2);

        assertEquals(List.of(1, 2), index.candidates("United States", 0.9));
    }
}
