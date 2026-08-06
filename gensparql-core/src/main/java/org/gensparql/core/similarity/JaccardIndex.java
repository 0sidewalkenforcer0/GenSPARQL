package org.gensparql.core.similarity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An index that narrows Jaccard similarity search to the entries that could reach a threshold.
 *
 * <p>Comparing a value against every indexed entry costs the same whatever the threshold, since
 * the threshold is only consulted after each score exists. Two standard filters make the cost
 * fall as the threshold rises, and both are exact: an entry they exclude could not have reached
 * the threshold, so the surviving matches are the same ones a full comparison would find.
 *
 * <p><b>Length.</b> {@code J(A,B) = |A∩B| / |A∪B| <= min(|A|,|B|) / max(|A|,|B|)}, so
 * {@code J(A,B) >= t} requires {@code t*|A| <= |B| <= |A|/t}. An entry whose token count falls
 * outside that band is out regardless of which tokens it holds.
 *
 * <p><b>Prefix.</b> Order tokens the same way everywhere, here by how many entries contain them,
 * rarest first. Take the first {@code |A| - ceil(t*|A|) + 1} tokens of the query. If none of them
 * occurs in an entry, then that entry shares at most {@code ceil(t*|A|) - 1 < t*|A|} tokens with
 * the query, and since {@code J(A,B) <= |A∩B| / max(|A|,|B|) <= |A∩B| / |A|}, its similarity is
 * below the threshold. Rarest-first ordering is what makes the prefix cheap to probe: the tokens
 * a query must match on are the ones fewest entries have.
 *
 * <p>Both filters tighten as the threshold rises: the length band narrows and the prefix gets
 * shorter. At a threshold of 1 the prefix is a single token and the band is a single length.
 *
 * <p>Tokenisation matches {@link JaccardSimText}, and the token Jaccard this index reasons about
 * is never below the score that class reports, so a candidate it excludes could not have scored
 * at or above the threshold there either.
 *
 * <p>Not thread-safe while being built. Reads are safe once building has finished.
 *
 * @param <T> what an entry carries, returned to the caller as a candidate
 */
public final class JaccardIndex<T> {

    /** One indexed entry: its tokens, and what the caller wants back. */
    private static final class Entry<T> {
        final Set<String> tokens;
        final T payload;

        Entry(Set<String> tokens, T payload) {
            this.tokens = tokens;
            this.payload = payload;
        }
    }

    private final List<Entry<T>> entries = new ArrayList<>();
    private final Map<String, Integer> documentFrequency = new HashMap<>();

    /** token -> entries holding it. Built on first query, since it needs the frequencies. */
    private Map<String, List<Entry<T>>> postings;

    /**
     * Tokens of a string, as {@link JaccardSimText} sees them.
     */
    public static Set<String> tokens(String text) {
        if (text == null) {
            return Collections.emptySet();
        }
        String trimmed = text.toLowerCase().trim();
        if (trimmed.isEmpty()) {
            return Collections.emptySet();
        }
        return new LinkedHashSet<>(List.of(trimmed.split("\\s+")));
    }

    /** Add an entry. Adding after a query invalidates the postings, which are rebuilt. */
    public void add(String text, T payload) {
        Set<String> tokenSet = tokens(text);
        if (tokenSet.isEmpty()) {
            return;
        }
        entries.add(new Entry<>(tokenSet, payload));
        for (String token : tokenSet) {
            documentFrequency.merge(token, 1, Integer::sum);
        }
        postings = null;
    }

    public int size() {
        return entries.size();
    }

    /**
     * Entries that could reach {@code threshold} against {@code queryText}.
     *
     * <p>A superset of the true matches, usually a much smaller one. Callers still score what
     * comes back; what they are spared is scoring everything else.
     *
     * <p>A threshold of zero excludes nothing, so everything is returned: at that threshold any
     * entry is a match, including one sharing no tokens.
     */
    public List<T> candidates(String queryText, double threshold) {
        Set<String> queryTokens = tokens(queryText);
        if (queryTokens.isEmpty() || entries.isEmpty()) {
            return Collections.emptyList();
        }
        if (threshold <= 0) {
            List<T> all = new ArrayList<>(entries.size());
            for (Entry<T> e : entries) {
                all.add(e.payload);
            }
            return all;
        }

        ensurePostings();

        int querySize = queryTokens.size();
        int minTokens = (int) Math.ceil(threshold * querySize);
        int maxTokens = (int) Math.floor(querySize / threshold);
        int prefixLength = Math.max(1, Math.min(querySize, querySize - minTokens + 1));

        List<String> ordered = rarestFirst(queryTokens);
        // Identity, not equals: two entries may carry equal payloads and both are wanted.
        Set<Entry<T>> seen = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        List<T> candidates = new ArrayList<>();

        for (int i = 0; i < prefixLength; i++) {
            List<Entry<T>> holders = postings.get(ordered.get(i));
            if (holders == null) {
                continue;
            }
            for (Entry<T> entry : holders) {
                int size = entry.tokens.size();
                if (size < minTokens || size > maxTokens) {
                    continue;
                }
                if (seen.add(entry)) {
                    candidates.add(entry.payload);
                }
            }
        }
        return candidates;
    }

    /** Query tokens ordered by how many entries hold them, rarest first, ties by token. */
    private List<String> rarestFirst(Set<String> queryTokens) {
        List<String> ordered = new ArrayList<>(queryTokens);
        ordered.sort((a, b) -> {
            int fa = documentFrequency.getOrDefault(a, 0);
            int fb = documentFrequency.getOrDefault(b, 0);
            return fa != fb ? Integer.compare(fa, fb) : a.compareTo(b);
        });
        return ordered;
    }

    private void ensurePostings() {
        if (postings != null) {
            return;
        }
        Map<String, List<Entry<T>>> built = new HashMap<>();
        for (Entry<T> entry : entries) {
            for (String token : entry.tokens) {
                built.computeIfAbsent(token, t -> new ArrayList<>()).add(entry);
            }
        }
        postings = built;
    }
}
