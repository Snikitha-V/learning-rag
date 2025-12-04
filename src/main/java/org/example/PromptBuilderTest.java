package org.example;

import java.util.*;

public class PromptBuilderTest {
    public static void main(String[] args) throws Exception {
        PromptBuilder pb = new PromptBuilder(Config.EMBED_MODEL_PATH, 1024, 200, 100);
        DbChunk c = new DbChunk();
        c.setChunkId("TOP-1");
        c.setChunkType("topic_aggregate");
        c.setTitle("Test");
        c.setText("Total classes: 5\nCreated at: 2025-12-01\nLorem ipsum dolor sit amet consectetur. ".repeat(20));
        List<DbChunk> list = Collections.singletonList(c);
        String prompt = pb.buildPrompt(list, "How many classes?", 1);
        System.out.println(prompt.substring(0, Math.min(800, prompt.length())));
        System.out.println("Prompt length chars=" + prompt.length());
        if (!prompt.contains("Total classes: 5")) throw new RuntimeException("Fact line missing");
        System.out.println("PromptBuilderTest passed.");
    }
}
