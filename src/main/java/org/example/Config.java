package org.example;

public class Config {

    // Qdrant - use ENV var, fallback to localhost for local dev
    public static final String QDRANT_URL = getEnvOrDefault("QDRANT_URL", "http://localhost:6333");
    public static final String QDRANT_COLLECTION = getEnvOrDefault("QDRANT_COLLECTION", "learning_chunks");

    // ONNX / models - use ENV var, fallback to relative paths for Docker
    public static final String EMBED_MODEL_PATH = getEnvOrDefault("EMBED_MODEL_PATH", "models/all-mpnet-base-v2-onnx");
    public static final String CROSS_ENCODER_ONNX_DIR = getEnvOrDefault("CROSS_ENCODER_PATH", "models/cross-encoder-ms-marco-miniLM-L-6-v2");

    // LLM server
    public static final String LLM_URL = getEnvOrDefault("LLM_URL", "http://localhost:8081");

    // LLM provider plugin system (for customers)
    public static final String LLM_PROVIDER = getEnvOrDefault("LLM_PROVIDER", "llama"); // llama, openai, custom_http
    public static final String LLM_API_KEY = getEnvOrDefault("LLM_API_KEY", "");        // for OpenAI/custom
    public static final String LLM_MODEL = getEnvOrDefault("LLM_MODEL", "gpt-3.5-turbo"); // for OpenAI
    public static final double LLM_TEMPERATURE = Double.parseDouble(getEnvOrDefault("LLM_TEMPERATURE", "0.2"));
    public static final int LLM_MAX_TOKENS = Integer.parseInt(getEnvOrDefault("LLM_MAX_TOKENS", "300"));

    // retrieval params
    public static final int TOPK_DENSE = 100;
    public static final int TOPK_LEX = 50;
    public static final int MMR_FINAL_SIZE = 20;
    public static final double MMR_LAMBDA = 0.7;
    public static final int RERANK_TOP_N = 20;
    public static final int RERANK_FINAL_N = 6;
    public static final int CONTEXT_K = 4;
    public static final int QDRANT_EF = 200;

    // Lucene index dir
    public static final String LUCENE_INDEX_DIR = getEnvOrDefault("LUCENE_INDEX_DIR", "lucene_index");

    // Prompt token budget
    public static final int PROMPT_MAX_TOKENS = 4096;
    public static final int PROMPT_RESERVED_ANSWER = 400;
    public static final int PROMPT_OVERHEAD = 200;

    // Level-7 / routing
    public static final double RAG_SCORE_FALLBACK_THRESHOLD = 0.3;

    // Database - use ENV vars, fallback to localhost for local dev
    public static final String DB_URL = getEnvOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/learning_db");
    public static final String DB_USER = getEnvOrDefault("DB_USER", "postgres");
    public static final String DB_PASS = requireEnv("DB_PASS");

    // API Security
    public static final String API_KEY = System.getenv("API_KEY"); // null means auth disabled

    private static String getEnvOrDefault(String name, String defaultValue) {
        String val = System.getenv(name);
        return (val != null && !val.isBlank()) ? val : defaultValue;
    }

    private static String requireEnv(String name) {
        String val = System.getenv(name);
        if (val == null || val.isBlank()) {
            throw new IllegalStateException("Required environment variable " + name + " is not set");
        }
        return val;
    }
}
