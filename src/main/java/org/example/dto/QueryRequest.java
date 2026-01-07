package org.example.dto;

import java.util.List;

/**
 * Canonical request DTO for the /api/v1/query endpoint.
 */
public class QueryRequest {

    private String query;
    private List<ConversationTurn> history;

    public QueryRequest() {
    }

    public QueryRequest(String query) {
        this.query = query;
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public List<ConversationTurn> getHistory() {
        return history;
    }

    public void setHistory(List<ConversationTurn> history) {
        this.history = history;
    }
}
