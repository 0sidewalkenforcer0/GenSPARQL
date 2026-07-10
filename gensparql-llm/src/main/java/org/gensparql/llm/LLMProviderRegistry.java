package org.gensparql.llm;

import org.gensparql.core.exception.GenSPARQLException;
import org.gensparql.core.model.ModelSpec;
import org.gensparql.llm.provider.MockLLMProvider;
import org.gensparql.llm.provider.OpenAIProvider;
import org.gensparql.llm.provider.AnthropicProvider;
import org.gensparql.llm.provider.OpenRouterProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Registry for LLM providers.
 *
 * Manages provider registration, discovery, and instantiation.
 */
public class LLMProviderRegistry {
    private static final Logger LOG = LoggerFactory.getLogger(LLMProviderRegistry.class);

    private static final Map<String, Supplier<LLMProvider>> factories = new ConcurrentHashMap<>();
    private static final Map<String, LLMProvider> instances = new ConcurrentHashMap<>();

    private static volatile LLMProvider defaultProvider = null;

    static {
        // Register built-in providers
        registerFactory("openai", OpenAIProvider::new);
        registerFactory("anthropic", AnthropicProvider::new);
        registerFactory("openrouter", OpenRouterProvider::new);
        registerFactory("mock", MockLLMProvider::new);
    }

    /**
     * Register a provider factory.
     *
     * @param name the provider name
     * @param factory the factory to create provider instances
     */
    public static void registerFactory(String name, Supplier<LLMProvider> factory) {
        factories.put(name.toLowerCase(), factory);
        LOG.debug("Registered LLM provider factory: {}", name);
    }

    /**
     * Get a provider by name.
     *
     * @param name the provider name
     * @return the provider instance
     * @throws GenSPARQLException if provider not found
     */
    public static LLMProvider get(String name) {
        String key = name.toLowerCase();

        // If default provider matches the requested name, return it
        // This allows tests to inject mock providers via setDefault
        if (defaultProvider != null && defaultProvider.getName().equalsIgnoreCase(name)) {
            return defaultProvider;
        }

        return instances.computeIfAbsent(key, k -> {
            Supplier<LLMProvider> factory = factories.get(k);
            if (factory == null) {
                throw new GenSPARQLException("Unknown LLM provider: " + name +
                        ". Available: " + getAvailableProviders());
            }
            LLMProvider provider = factory.get();
            LOG.info("Created LLM provider instance: {}", name);
            return provider;
        });
    }

    /**
     * Register a specific provider instance.
     * Useful for testing with mock providers.
     *
     * @param provider the provider instance to register
     */
    public static void registerInstance(LLMProvider provider) {
        instances.put(provider.getName().toLowerCase(), provider);
        LOG.debug("Registered LLM provider instance: {}", provider.getName());
    }

    /**
     * Get or create a provider from a ModelSpec.
     *
     * @param spec the model specification
     * @return the provider instance
     */
    public static LLMProvider getFromSpec(ModelSpec spec) {
        return get(spec.getProvider());
    }

    /**
     * Get the default provider.
     *
     * @return the default provider
     * @throws GenSPARQLException if no default provider is set
     */
    public static LLMProvider getDefault() {
        if (defaultProvider == null) {
            // Try to auto-detect based on environment
            defaultProvider = autoDetectProvider();
        }
        if (defaultProvider == null) {
            throw new GenSPARQLException("No default LLM provider configured. " +
                    "Set OPENAI_API_KEY, ANTHROPIC_API_KEY, or OPENROUTER_API_KEY environment variable.");
        }
        return defaultProvider;
    }

    /**
     * Set the default provider.
     *
     * @param provider the default provider
     */
    public static void setDefault(LLMProvider provider) {
        defaultProvider = provider;
        LOG.info("Set default LLM provider: {}", provider.getName());
    }

    /**
     * Set the default provider by name.
     *
     * @param name the provider name
     */
    public static void setDefault(String name) {
        setDefault(get(name));
    }

    /**
     * Check if a provider is registered.
     *
     * @param name the provider name
     * @return true if registered
     */
    public static boolean isRegistered(String name) {
        return factories.containsKey(name.toLowerCase());
    }

    /**
     * Get names of all registered providers.
     */
    public static Set<String> getAvailableProviders() {
        return factories.keySet();
    }

    /**
     * Clear cached provider instances.
     */
    public static void clearInstances() {
        for (LLMProvider provider : instances.values()) {
            try {
                provider.close();
            } catch (Exception e) {
                LOG.warn("Error closing provider: {}", provider.getName(), e);
            }
        }
        instances.clear();
        defaultProvider = null;
    }

    /**
     * Auto-detect provider based on environment variables.
     */
    private static LLMProvider autoDetectProvider() {
        // Check for API keys in order of preference
        if (System.getenv("OPENAI_API_KEY") != null) {
            LOG.info("Auto-detected OpenAI API key");
            return get("openai");
        }
        if (System.getenv("ANTHROPIC_API_KEY") != null) {
            LOG.info("Auto-detected Anthropic API key");
            return get("anthropic");
        }
        if (System.getenv("OPENROUTER_API_KEY") != null) {
            LOG.info("Auto-detected OpenRouter API key");
            return get("openrouter");
        }
        return null;
    }

    // Prevent instantiation
    private LLMProviderRegistry() {}
}
