package org.example.llm;

import java.io.IOException;

/**
 * Interface for LLM providers.
 * Customers can implement this to use their own LLM.
 */
public interface LLMProvider {
    
    /**
     * Generate a response from the LLM.
     * 
     * @param prompt The prompt to send to the LLM
     * @param maxTokens Maximum tokens to generate
     * @return The generated text
     * @throws IOException If communication with the LLM fails
     */
    String generate(String prompt, int maxTokens) throws IOException;
    
    /**
     * Get the provider name for logging.
     */
    default String getProviderName() {
        return this.getClass().getSimpleName();
    }
}
