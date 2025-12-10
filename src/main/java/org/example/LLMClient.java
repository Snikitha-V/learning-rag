package org.example;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.*;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class LLMClient {
    private final OkHttpClient client;
    private final String endpoint;
    private final Gson gson = new Gson();

    public LLMClient() {
        this("http://localhost:8081");
    }

    public LLMClient(String baseUrl) {
        this.endpoint = baseUrl.replaceAll("/$", "") + "/completion";
        // Set longer timeouts for LLM generation (can take 60+ seconds for complex queries)
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    public String generate(String prompt, int maxTokens) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("prompt", prompt);
        body.put("n_predict", maxTokens);
        body.put("temperature", 0.2);

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
}

