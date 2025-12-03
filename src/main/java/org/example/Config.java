package org.example;

public class Config {
    // Qdrant
    public static final String QDRANT_URL = System.getProperty("qdrant.url", "http://localhost:6333");
    public static final String QDRANT_COLLECTION = System.getProperty("qdrant.collection", "learning_chunks");

    // ONNX / models
    public static final String EMBED_MODEL_PATH = System.getProperty("model.path", "C:\\Users\\sniki\\rag_learning_project\\rag-learning\\models\\all-mpnet-base-v2-onnx");
    public static final String CROSS_ENCODER_ONNX_DIR = System.getProperty("cross.encoder.path", "C:\\Users\\sniki\\rag_learning_project\\rag-learning\\models\\cross-encoder-ms-marco-miniLM-L-6-v2");

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
    public static final String LUCENE_INDEX_DIR = "lucene_index";
}
