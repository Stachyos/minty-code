package com.minty.assistant;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "chat_messages", indexes = @Index(name = "message_session_idx", columnList = "sessionId,createdAt"))
public class ChatMessage {
    @Id public String id;
    @Column(nullable = false) public String sessionId;
    @Column(nullable = false, length = 16) public String role;
    @Column(nullable = false, columnDefinition = "text") public String content;
    @Column(nullable = false) public Instant createdAt;
    protected ChatMessage() {}
    ChatMessage(String sessionId, String role, String content) {
        this.id = UUID.randomUUID().toString(); this.sessionId = sessionId;
        this.role = role; this.content = content; this.createdAt = Instant.now();
    }
}
