package org.example;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Builds the Level-6 prompt according to the canonical template,
 * while respecting token budget and preserving fact lines.
 */
public class PromptBuilder {
    private final TokenizerUtil tokenizer;
    private final int maxTotalTokens;
    private final int reservedForAnswer;
    private final int overheadTokens;

    public PromptBuilder(String modelPath, int maxTotalTokens, int reservedForAnswer, int overheadTokens) {
        this.tokenizer = new TokenizerUtil(modelPath);
        this.maxTotalTokens = maxTotalTokens;
        this.reservedForAnswer = reservedForAnswer;
        this.overheadTokens = overheadTokens;
    }

    /**
     * Build the prompt using reranked chunks (in order).
     * contextChunks: reranked list (most relevant first).
     */
    public String buildPrompt(List<DbChunk> contextChunks, String userQuestion, int contextK) {
        // estimate available tokens for evidence
        int available = maxTotalTokens - reservedForAnswer - overheadTokens;
        int used = 0;
        StringBuilder evidence = new StringBuilder();
        int included = 0;

        for (DbChunk c : contextChunks) {
            if (included >= contextK) break;
            String header = String.format("[CHUNK id=%s type=%s]\n", c.getChunkId(), safeType(c.getChunkType()));
            String body = c.getText() == null ? "" : c.getText();
            // compute tokens for header + body (approx)
            int headerTok = tokenizer.countTokens(header);
            int bodyTok = tokenizer.countTokens(body);
            if (used + headerTok + bodyTok <= available) {
                // include full chunk
                evidence.append(header).append(body).append("\n[/CHUNK]\n\n");
                used += headerTok + bodyTok;
                included++;
            } else {
                // need to trim body to fit
                // estimate chars budget proportionally: approximate token->char mapping with 4 chars/token
                int remainingTokens = Math.max(0, available - used - headerTok);
                int charBudget = Math.max(80, remainingTokens * 4); // conservative
                String trimmed = tokenizer.truncateHeadTailPreserveFacts(body, charBudget);
                int trimmedTok = tokenizer.countTokens(trimmed);
                if (trimmedTok + headerTok <= (available - used) && trimmed.length() > 0) {
                    evidence.append(header).append(trimmed).append("\n[/CHUNK]\n\n");
                    used += headerTok + trimmedTok;
                    included++;
                } else {
                    // cannot include more chunks
                    break;
                }
            }
        }

        if (included == 0) {
            // No evidence could be included due to extremes; still include first chunk truncated minimally
            if (!contextChunks.isEmpty()) {
                DbChunk c = contextChunks.get(0);
                String header = String.format("[CHUNK id=%s type=%s]\n", c.getChunkId(), safeType(c.getChunkType()));
                String trimmed = tokenizer.truncateHeadTailPreserveFacts(c.getText(), 512); // fall back
                evidence.append(header).append(trimmed).append("\n[/CHUNK]\n\n");
            }
        }

        // Build final prompt (SYSTEM + EVIDENCE + QUESTION + INSTRUCTIONS + OUTPUT FORMAT)
        StringBuilder prompt = new StringBuilder();
        prompt.append("[SYSTEM]\n")
              .append("You are a factual assistant. You may only use the evidence excerpts provided below to answer the user's question. If the evidence does not support the question, say exactly: \"I don't have that information in your database.\"\n\n")
              .append("[EVIDENCE]\n")
              .append(evidence.toString())
              .append("[USER QUESTION]\n")
              .append(userQuestion).append("\n\n")
              .append("[INSTRUCTIONS]\n")
              .append("1. Answer concisely (1–3 sentences).\n")
              .append("2. Base every factual claim only on the evidence above.\n")
              .append("3. If you state a fact present in the evidence, append the source bracket(s) for that fact: [source: <CHUNK_ID>].\n")
              .append("4. Never invent dates, numbers or facts. If a fact is not present, respond: \"I don't have that information in your database.\"\n")
              .append("5. If you compute a numeric aggregation, use only numbers explicitly present in the evidence and show the short calculation in square brackets, e.g., \"[calc: 2+3=5]\".\n")
              .append("6. If the question asks for explanation + fact, put the fact first (with source), then one short explanation sentence that does not include new factual claims.\n\n")
              .append("[OUTPUT FORMAT]\n")
              .append("Answer: <one paragraph (1–3 sentences)>\n")
              .append("Sources: <comma-separated CHUNK_IDs used>\n")
              .append("Optional SQL: <SQL snippet or \"N/A\">\n\n")
              .append("[END]\n");

        return prompt.toString();
    }

    private String safeType(String t) {
        return t == null ? "unknown" : t.replaceAll("\\s+", "_");
    }
}
