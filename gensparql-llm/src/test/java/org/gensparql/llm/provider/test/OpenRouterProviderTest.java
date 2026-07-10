package org.gensparql.llm.provider.test;

import org.gensparql.core.model.GenerateRequest;
import org.gensparql.core.model.GenerateResponse;
import org.gensparql.core.model.ModelSpec;
import org.gensparql.llm.provider.OpenRouterProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for OpenRouterProvider.
 * 
 * To run the API test, set OPENROUTER_API_KEY environment variable:
 *   export OPENROUTER_API_KEY="your-api-key-here"
 *   mvn test -Dtest=OpenRouterProviderTest -pl gensparql-llm
 */
public class OpenRouterProviderTest {

    private OpenRouterProvider provider;

    @BeforeEach
    void setUp() {
        String apiKey = System.getenv("OPENROUTER_API_KEY");
        if (apiKey != null && !apiKey.isEmpty()) {
            provider = new OpenRouterProvider(apiKey);
        } else {
            provider = new OpenRouterProvider(); // Will use env var if available
        }
    }

    @Test
    void testProviderCreation() {
        assertNotNull(provider);
        assertEquals("openrouter", provider.getName());
    }

    @Test
    void testIsNotAvailableWithoutApiKey() {
        OpenRouterProvider providerWithoutKey = new OpenRouterProvider(null);
        assertFalse(providerWithoutKey.isAvailable(), 
                "OpenRouter should not be available without API key");
    }

    @Test
    void testGenerationFailsWithoutApiKey() {
        OpenRouterProvider providerWithoutKey = new OpenRouterProvider(null);
        
        ModelSpec modelSpec = ModelSpec.builder()
                .provider("openrouter")
                .model("test-model")
                .build();

        GenerateRequest request = GenerateRequest.builder()
                .prompt("Test prompt")
                .modelSpec(modelSpec)
                .outputVariables(List.of("result"))
                .build();

        // generateSync catches exceptions and returns error response
        GenerateResponse response = providerWithoutKey.generateSync(request);
        
        assertNotNull(response, "Response should not be null");
        assertFalse(response.isSuccess(), "Response should not be successful without API key");
        assertNotNull(response.getErrorMessage(), "Response should have error message");
        assertTrue(response.getErrorMessage().contains("API key") || 
                  response.getErrorMessage().contains("not configured"),
                  "Error message should mention API key");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "OPENROUTER_API_KEY", matches = ".+")
    void testOpenRouterAPI() {
        // Skip if no API key
        if (!provider.isAvailable()) {
            System.out.println("Skipping test - OPENROUTER_API_KEY not set");
            return;
        }

        ModelSpec modelSpec = ModelSpec.builder()
                .provider("openrouter")
                .model("allenai/olmo-3.1-32b-think:free")
                .build();

        GenerateRequest request = GenerateRequest.builder()
                .prompt("How many r's are in the word 'strawberry'? Answer with just a number.")
                .modelSpec(modelSpec)
                .outputVariables(List.of("answer"))
                .build();

        GenerateResponse response = provider.generateSync(request);

        assertNotNull(response, "Response should not be null");
        assertTrue(response.isSuccess(), "Response should be successful");
        
        // Debug information if response is empty
        if (response.getRawText() == null || response.getRawText().trim().isEmpty()) {
            System.err.println("\n=== DEBUG: Empty Response ===");
            System.err.println("Response success: " + response.isSuccess());
            System.err.println("Response error: " + response.getErrorMessage());
            System.err.println("Response rawText: '" + response.getRawText() + "'");
            System.err.println("Response rawText length: " + 
                             (response.getRawText() != null ? response.getRawText().length() : 0));
            if (response.getMetadata() != null) {
                System.err.println("Metadata: " + response.getMetadata());
            }
            System.err.println("=============================\n");
        }
        
        assertNotNull(response.getRawText(), "Response should have raw text");
        assertFalse(response.getRawText().trim().isEmpty(), 
                   "Response text should not be empty. Got: '" + response.getRawText() + "'");
        
        System.out.println("\n=== OpenRouter API Test ===");
        System.out.println("Prompt: " + request.getPrompt());
        System.out.println("Response: " + response.getRawText());
        if (response.getMetadata() != null) {
            System.out.println("Tokens used: " + response.getMetadata().getPromptTokens() + 
                             " prompt + " + response.getMetadata().getCompletionTokens() + " completion");
            System.out.println("Latency: " + response.getMetadata().getLatencyMs() + "ms");
        }
        System.out.println("===========================\n");
    }
}

