package org.example.llm;

import org.example.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for creating LLM providers based on configuration.
 *
 * Customers select LLM via LLM_PROVIDER environment variable: - "llama"
 * (default) → LlamaCppProvider (local llama.cpp server) - "openai" →
 * OpenAIProvider (OpenAI API) - "custom_http" → CustomHttpProvider (customer's
 * own LLM API)
 */
public class LLMFactory {

    private static final Logger LOG = LoggerFactory.getLogger(LLMFactory.class);

    /**
     * Create an LLM provider based on LLM_PROVIDER config.
     */
    public static LLMProvider createProvider() {
        String provider = (Config.LLM_PROVIDER == null) ? "llama" : Config.LLM_PROVIDER.trim().toLowerCase();

        LOG.info("Creating LLM provider: {}", provider);

        switch (provider) {
            case "llama":
            case "llama_cpp":
            case "llamacpp":
                LOG.info("Using LlamaCpp provider at {}", Config.LLM_URL);
                return new LlamaCppProvider();

            case "openai":
            case "gpt":
                if (Config.LLM_API_KEY == null || Config.LLM_API_KEY.isEmpty()) {
                    throw new IllegalStateException("LLM_API_KEY required for OpenAI provider");
                }
                LOG.info("Using OpenAI provider with model {}", Config.LLM_MODEL);
                return new OpenAIProvider();

            case "custom_http":
            case "custom":
            case "http":
                LOG.info("Using CustomHttp provider at {}", Config.LLM_URL);
                return new CustomHttpProvider();

            default:
                LOG.warn("Unknown LLM_PROVIDER '{}', falling back to llama", provider);
                return new LlamaCppProvider();
        }
    }
}
