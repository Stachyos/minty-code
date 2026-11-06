package com.minty.assistant;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

interface SessionRepository extends JpaRepository<ChatSession, String> {
    List<ChatSession> findAllByOrderByUpdatedAtDesc();
}
interface MessageRepository extends JpaRepository<ChatMessage, String> {
    List<ChatMessage> findBySessionIdOrderByCreatedAtAsc(String sessionId);
}
interface RunRepository extends JpaRepository<AgentRun, String> {
    List<AgentRun> findTop50ByOrderByCreatedAtDesc();
    List<AgentRun> findTop50BySessionIdOrderByCreatedAtDesc(String sessionId);
    Optional<AgentRun> findFirstBySessionIdOrderByCreatedAtDesc(String sessionId);
    List<AgentRun> findByStatus(String status);
    List<AgentRun> findByCleanupPendingTrue();
    List<AgentRun> findAllByOrderByCreatedAtAsc();
}
interface EventRepository extends JpaRepository<RunEvent, Long> {
    List<RunEvent> findFirst200ByRunIdAndIdGreaterThanOrderByIdAsc(String runId, Long after);
}
