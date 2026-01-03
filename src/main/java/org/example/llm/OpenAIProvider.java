package org.example.llm;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.*;
import org.example.Config;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * LLM provider for OpenAI API (GPT-3.5, GPT-4, etc.).
 * Set LLM_PROVIDER=openai and LLM_API_KEY to use.
 */
public class OpenAIProvider implements LLMProvider {
    private final OkHttpClient client;
    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final Gson gson = new Gson();

    public OpenAIProvider() {
        this(Config.LLM_URL, Config.LLM_API_KEY, Config.LLM_MODEL);
    }

    public OpenAIProvider(String baseUrl, String apiKey, String model) {
        this.endpoint = baseUrl.replaceAll("/$", "") + "/v1/chat/completions";
        this.apiKey = apiKey;
        this.model = model != null ? model : "gpt-3.5-turbo";
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public String generate(String prompt, int maxTokens) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("max_tokens", maxTokens);
        body.addProperty("temperature", Config.LLM_TEMPERATURE);
        
        JsonArray messages = new JsonArray();
        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.addProperty("content", prompt);
        messages.add(userMessage);
        body.add("messages", messages);

        Request request = new Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer " + apiKey)
            .addHeader("Content-Type", "application/json")
            .post(RequestBody.create(gson.toJson(body), MediaType.parse("application/json")))
            .build();

        try (Response response = client.newCall(request).execute()) {
            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                throw new IOException("Empty response from OpenAI");
            }
            String json = responseBody.string();
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            
            if (obj.has("error")) {
                throw new IOException("OpenAI error: " + obj.get("error").toString());
            }
            
            return obj.getAsJsonArray("choices")
                    .get(0).getAsJsonObject()
                    .getAsJsonObject("message")
                    .get("content").getAsString();
        }
    }

    @Override
    public String getProviderName() {
        return "OpenAI-" + model;
    }
}
