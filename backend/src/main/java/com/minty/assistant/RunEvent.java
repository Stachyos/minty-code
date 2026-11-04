package com.minty.assistant;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "run_events", indexes = @Index(name = "event_run_idx", columnList = "runId,id"))
public class RunEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(nullable = false) public String runId;
    @Column(nullable = false, length = 24) public String type;
    @Column(nullable = false, columnDefinition = "text") public String content;
    @Column(nullable = false) public Instant createdAt;
    protected RunEvent() {}
    RunEvent(String runId, String type, String content) {
        this.runId = runId; this.type = type; this.content = content; this.createdAt = Instant.now();
    }
}
