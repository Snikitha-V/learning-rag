package org.example.llm;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.*;
import org.example.Config;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * LLM provider for customer's own HTTP-based LLM.
 * Set LLM_PROVIDER=custom_http and LLM_URL to customer's endpoint.
 * 
 * Expected customer API contract:
 * 
 * POST {LLM_URL}
 * Headers: Authorization: Bearer {LLM_API_KEY} (optional)
 * Body: {
 *   "prompt": "...",
 *   "max_tokens": 300,
 *   "temperature": 0.2
 * }
 * Response: {
 *   "text": "..." or "content": "..." or "response": "..."
 * }
 */
public class CustomHttpProvider implements LLMProvider {
    private final OkHttpClient client;
    private final String endpoint;
    private final String apiKey;
    private final Gson gson = new Gson();

    public CustomHttpProvider() {
        this(Config.LLM_URL, Config.LLM_API_KEY);
    }

    public CustomHttpProvider(String endpoint, String apiKey) {
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public String generate(String prompt, int maxTokens) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("prompt", prompt);
        body.put("max_tokens", maxTokens);
        body.put("temperature", Config.LLM_TEMPERATURE);

        Request.Builder requestBuilder = new Request.Builder()
            .url(endpoint)
            .post(RequestBody.create(gson.toJson(body), MediaType.parse("application/json")));
        
        // Add API key if provided
        if (apiKey != null && !apiKey.isEmpty()) {
            requestBuilder.addHeader("Authorization", "Bearer " + apiKey);
        }

        try (Response response = client.newCall(requestBuilder.build()).execute()) {
            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                throw new IOException("Empty response from custom LLM");
            }
            String json = responseBody.string();
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            
            // Try common response field names
            if (obj.has("text")) {
                return obj.get("text").getAsString();
            } else if (obj.has("content")) {
                return obj.get("content").getAsString();
            } else if (obj.has("response")) {
                return obj.get("response").getAsString();
            } else if (obj.has("output")) {
                return obj.get("output").getAsString();
            } else if (obj.has("generated_text")) {
                return obj.get("generated_text").getAsString();
            } else {
                throw new IOException("Unknown response format from custom LLM: " + json);
            }
        }
    }

    @Override
    public String getProviderName() {
        return "CustomHttp";
    }
}
