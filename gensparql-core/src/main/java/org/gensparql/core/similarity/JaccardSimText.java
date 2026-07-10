package org.gensparql.core.similarity;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Jaccard-based text similarity implementation.
 *
 * Computes similarity as the Jaccard coefficient between
 * the word sets of two strings:
 * J(A,B) = |A ∩ B| / |A ∪ B|
 */
public class JaccardSimText implements SimText {

    @Override
    public double similarity(String s1, String s2) {
        if (s1 == null || s2 == null) {
            return s1 == s2 ? 1.0 : 0.0;
        }

        // Exact match
        if (s1.equals(s2)) {
            return 1.0;
        }

        // Case-insensitive match
        if (s1.equalsIgnoreCase(s2)) {
            return 0.95; // High but not perfect similarity
        }

        // Tokenize into words
        String[] words1 = s1.toLowerCase().trim().split("\\s+");
        String[] words2 = s2.toLowerCase().trim().split("\\s+");

        Set<String> set1 = new HashSet<>(Arrays.asList(words1));
        Set<String> set2 = new HashSet<>(Arrays.asList(words2));

        // Compute intersection
        Set<String> intersection = new HashSet<>(set1);
        intersection.retainAll(set2);

        // Compute union
        Set<String> union = new HashSet<>(set1);
        union.addAll(set2);

        if (union.isEmpty()) {
            return 0.0;
        }

        return (double) intersection.size() / union.size();
    }

    @Override
    public String getName() {
        return "Jaccard";
    }
}
