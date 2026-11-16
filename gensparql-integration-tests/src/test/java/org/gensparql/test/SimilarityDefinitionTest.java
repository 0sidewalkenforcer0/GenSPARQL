package org.gensparql.test;

import org.gensparql.core.model.ModelSpec;
import org.gensparql.core.similarity.JaccardSimText;
import org.gensparql.engine.similarity.EmbeddingSimText;
import org.gensparql.llm.provider.MockLLMProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two similarities as Section 2.2 of the paper defines them: the Jaccard similarity of the
 * lower-case word sets, and the cosine of the two embeddings. Neither has a rule of its own for
 * strings that differ only in case, which scored 0.95 before.
 */
class SimilarityDefinitionTest {

    @Test
    void jaccardComparesTheLowerCaseWordSets() {
        JaccardSimText jaccard = new JaccardSimText();
        assertEquals(1.0, jaccard.similarity("Brazil", "brazil"), 1e-9);
        assertEquals(1.0, jaccard.similarity("Korea Republic", "republic KOREA"), 1e-9);
        assertEquals(0.5, jaccard.similarity("South Korea", "korea"), 1e-9);
    }

    @Test
    void theEmbeddingSimilarityIsTheCosineOfTheEmbeddings() {
        // The mock embeds a name and its case variants alike, so their cosine is 1, and it embeds
        // other strings at random, so their cosine stays far below any threshold.
        EmbeddingSimText embedding = new EmbeddingSimText(new JaccardSimText(), new MockLLMProvider(),
                ModelSpec.fromURI("model:mock:test"));
        assertEquals(1.0, embedding.similarity("Brazil", "brazil"), 1e-6);
        assertEquals(1.0, embedding.similarity("Brazil", "Brazil"), 1e-9);
        assertTrue(embedding.similarity("Brazil", "Argentina") < 0.5);
    }
}
