package org.example.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.example.*;

@Configuration
public class AppConfig {

    @Value("${app.llm.url}")
    private String llmUrl;

    @Bean
    public OnnxEmbedder onnxEmbedder() throws Exception {
        return new OnnxEmbedder(
            Config.EMBED_MODEL_PATH + "/model.onnx",
            Config.EMBED_MODEL_PATH,
            384
        );
    }

    @Bean
    public QdrantClient qdrantClient() {
        return new QdrantClient(Config.QDRANT_URL, Config.QDRANT_COLLECTION);
    }

    @Bean
    public LuceneIndexService luceneIndexService() throws Exception {
        return new LuceneIndexService(Config.LUCENE_INDEX_DIR);
    }

    @Bean
    public CrossEncoderScorer crossEncoderScorer(OnnxEmbedder embedder) {
        return new CrossEncoderScorer(embedder);
    }

    @Bean
    public DataFetcher dataFetcher() throws Exception {
        return new DataFetcher();
    }

    @Bean
    public LLMClient llmClient() {
        return new LLMClient(llmUrl);
    }

    @Bean
    public RetrievalService retrievalService(
            OnnxEmbedder embedder,
            QdrantClient qdrant,
            LuceneIndexService lucene,
            CrossEncoderScorer crossEncoder,
            DataFetcher dbFetcher) throws Exception {
        return new RetrievalService(embedder, qdrant, lucene, crossEncoder, dbFetcher);
    }
}
