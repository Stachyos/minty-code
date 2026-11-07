package com.minty.assistant;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "agent_runs", indexes = @Index(name = "run_session_idx", columnList = "sessionId,createdAt"))
public class AgentRun {
    @Id public String id;
    @Column(nullable = false) public String sessionId;
    @Column(nullable = false, length = 16) public String status;
    @Column(columnDefinition = "text") public String prompt;
    public String model;
    @Column(columnDefinition = "text") public String error;
    public long inputTokens;
    public long outputTokens;
    @Column(columnDefinition = "text") public String verification;
    @Column(columnDefinition = "text") public String changes;
    @Column(columnDefinition = "text") public String progress;
    public Integer maxRounds;
    public Integer roundsUsed;
    public Integer maxTokens;
    public Integer timeLimitSeconds;
    public Long elapsedMillis;
    public Instant attemptStartedAt;
    public Boolean resumable;
    public Boolean cleanupPending;
    @Column(columnDefinition = "text") String workspacePath;
    @Column(nullable = false) public Instant createdAt;
    public Instant finishedAt;
    protected AgentRun() {}
    AgentRun(String sessionId, String prompt, String model) {
        this.id = UUID.randomUUID().toString(); this.sessionId = sessionId;
        this.prompt = prompt; this.model = model;
        this.status = "running"; this.createdAt = Instant.now();
    }
}
