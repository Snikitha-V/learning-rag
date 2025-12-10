package org.example.controller;

import org.example.RetrievalService;
import org.example.LLMClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class QueryController {

    private final RetrievalService retrievalService;

    @Autowired
    public QueryController(RetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    // Basic query endpoint: POST { "query": "...", "session_id": "..." }
    @PostMapping("/query")
    public ResponseEntity<?> query(@RequestBody Map<String,String> body) {
        try {
            String q = body.getOrDefault("query", "");
            String sessionId = body.getOrDefault("session_id", null);
            // call your retrieval service; adapt signature if needed
            String answer = retrievalService.ask(q); // or ask(q, sessionId) if you added session handling
            // Optionally include structured fields: sources/confidence. We'll return raw answer for now.
            return ResponseEntity.ok(Map.of("answer", answer, "sources", "N/A"));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/health")
    public ResponseEntity<?> health() {
        return ResponseEntity.ok(Map.of("status", "ok"));
    }
}

