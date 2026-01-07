package org.example.dto;

/**
 * Simple DTO used by the gateway to share conversational history with the
 * backend.
 */
public class ConversationTurn {

    private String role;
    private String content;

    public ConversationTurn() {
    }

    public ConversationTurn(String role, String content) {
        this.role = role;
        this.content = content;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
