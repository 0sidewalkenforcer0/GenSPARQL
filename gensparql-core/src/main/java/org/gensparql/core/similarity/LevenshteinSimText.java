package org.gensparql.core.similarity;

/**
 * Levenshtein (edit distance) based text similarity.
 *
 * Computes similarity as:
 * 1 - (edit_distance / max_length)
 *
 * This provides a normalized similarity score where
 * fewer edits means higher similarity.
 */
public class LevenshteinSimText implements SimText {

    @Override
    public double similarity(String s1, String s2) {
        if (s1 == null || s2 == null) {
            return s1 == s2 ? 1.0 : 0.0;
        }

        // Exact match
        if (s1.equals(s2)) {
            return 1.0;
        }

        // Normalize strings for comparison
        String n1 = s1.toLowerCase().trim();
        String n2 = s2.toLowerCase().trim();

        if (n1.equals(n2)) {
            return 0.98; // Very high but not perfect
        }

        int maxLen = Math.max(n1.length(), n2.length());
        if (maxLen == 0) {
            return 1.0;
        }

        int distance = levenshteinDistance(n1, n2);
        return 1.0 - ((double) distance / maxLen);
    }

    /**
     * Compute the Levenshtein edit distance between two strings.
     */
    private int levenshteinDistance(String s1, String s2) {
        int m = s1.length();
        int n = s2.length();

        // Create distance matrix
        int[][] dp = new int[m + 1][n + 1];

        // Initialize first column
        for (int i = 0; i <= m; i++) {
            dp[i][0] = i;
        }

        // Initialize first row
        for (int j = 0; j <= n; j++) {
            dp[0][j] = j;
        }

        // Fill in the rest of the matrix
        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                int cost = (s1.charAt(i - 1) == s2.charAt(j - 1)) ? 0 : 1;
                dp[i][j] = Math.min(
                        Math.min(
                                dp[i - 1][j] + 1,      // deletion
                                dp[i][j - 1] + 1),     // insertion
                        dp[i - 1][j - 1] + cost        // substitution
                );
            }
        }

        return dp[m][n];
    }

    @Override
    public String getName() {
        return "Levenshtein";
    }
}
