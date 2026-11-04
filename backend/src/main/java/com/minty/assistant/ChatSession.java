package com.minty.assistant;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "chat_sessions")
public class ChatSession {
    @Id public String id;
    @Column(nullable = false, length = 120) public String title;
    @Column(nullable = false) public Instant createdAt;
    @Column(nullable = false) public Instant updatedAt;
    protected ChatSession() {}
    ChatSession(String title) {
        this.id = UUID.randomUUID().toString(); this.title = title;
        this.createdAt = Instant.now(); this.updatedAt = createdAt;
    }
}
