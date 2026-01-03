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
 * LLM provider for llama.cpp server (local LLM).
 * This is the DEFAULT provider used when LLM_PROVIDER=llama or not set.
 */
public class LlamaCppProvider implements LLMProvider {
    private final OkHttpClient client;
    private final String endpoint;
    private final Gson gson = new Gson();

    public LlamaCppProvider() {
        this(Config.LLM_URL);
    }

    public LlamaCppProvider(String baseUrl) {
        this.endpoint = baseUrl.replaceAll("/$", "") + "/completion";
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
        body.put("n_predict", maxTokens);
        body.put("temperature", Config.LLM_TEMPERATURE);

        Request request = new Request.Builder()
            .url(endpoint)
            .post(RequestBody.create(gson.toJson(body), MediaType.parse("application/json")))
            .build();

        try (Response response = client.newCall(request).execute()) {
            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                throw new IOException("Empty response from LLM server");
            }
            String json = responseBody.string();
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            return obj.get("content").getAsString();
        }
    }

    @Override
    public String getProviderName() {
        return "LlamaCpp";
    }
}
