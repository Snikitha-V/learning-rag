package org.example;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Tokenizer util: attempt to use DJL HuggingFace tokenizer if available.
 * Fallback: whitespace-based token counting (approx).
 */
public class TokenizerUtil {
    private final boolean djlAvailable;
    private final Object djlTokenizer; // actual type if DJL present
    private final Pattern factLinePattern = Pattern.compile("(?i)^(total\\s+classes|due_date|learned at|total assignments|created at|due date)[:\\s].*");

    public TokenizerUtil(String modelPath) {
        Object tok = null;
        boolean ok = false;
        try {
            // try DJL tokenizer dynamically to avoid compile errors if not present
            Class<?> cls = Class.forName("ai.djl.huggingface.tokenizers.HuggingFaceTokenizer");
            tok = cls.getMethod("newInstance", String.class).invoke(null, modelPath);
            ok = true;
        } catch (Exception e) {
            ok = false;
            tok = null;
        }
        this.djlTokenizer = tok;
        this.djlAvailable = ok;
    }

    public int countTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        if (djlAvailable) {
            try {
                Class<?> cls = djlTokenizer.getClass();
                // encode returns Encoding that has getTokens() or getIds()
                Object enc = cls.getMethod("encode", String.class).invoke(djlTokenizer, text);
                // try getTokenCount by calling getTokens().size()
                try {
                    Object tokens = enc.getClass().getMethod("getTokens").invoke(enc);
                    if (tokens instanceof Collection) return ((Collection<?>) tokens).size();
                } catch (NoSuchMethodException ignore) {}
                // fallback to ids
                try {
                    Object ids = enc.getClass().getMethod("getIds").invoke(enc);
                    if (ids instanceof int[]) return ((int[]) ids).length;
                    if (ids instanceof Integer[]) return ((Integer[]) ids).length;
                } catch (NoSuchMethodException ignore) {}
                // final fallback
            } catch (Exception e) {
                // ignore and fallback
            }
        }
        // whitespace approximation
        String[] toks = text.trim().split("\\s+");
        return toks.length;
    }

    /**
     * Trim text preserving explicit fact lines (lines that match factLinePattern).
     * Keeps head and tail halves of characters budget. If any fact lines exist, they are preserved
     * and prepended to the trimmed body so facts remain available.
     */
    public String truncateHeadTailPreserveFacts(String text, int charBudget) {
        if (text == null) return "";
        if (text.length() <= charBudget) return text;
        String[] lines = text.split("\\r?\\n");
        StringBuilder facts = new StringBuilder();
        StringBuilder body = new StringBuilder();
        for (String l : lines) {
            if (factLinePattern.matcher(l.trim()).find()) {
                facts.append(l).append("\n");
            } else {
                body.append(l).append("\n");
            }
        }
        int remaining = charBudget - facts.length();
        if (remaining <= 0) {
            // only facts fit
            return facts.toString().substring(0, Math.min(facts.length(), charBudget));
        }
        String bodyStr = body.toString().trim();
        if (bodyStr.length() <= remaining) {
            return facts.toString() + bodyStr;
        }
        int half = remaining / 2;
        String head = bodyStr.substring(0, Math.min(half, bodyStr.length()));
        String tail = bodyStr.substring(Math.max(0, bodyStr.length() - half));
        return facts.toString() + head + "\n...\n" + tail;
    }
}

