package com.minty.assistant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.*;

interface SessionRepository extends JpaRepository<ChatSession, String> {
    @Query("""
            select s from ChatSession s where s.deletedAt is null and exists (
                select 1 from ChatMessage m where m.sessionId = s.id and m.role = 'user'
            ) order by s.updatedAt desc
            """)
    List<ChatSession> startedSessions();
    Optional<ChatSession> findByIdAndDeletedAtIsNull(String id);
    @Modifying
    @Query("update ChatSession s set s.deletedAt = :when where s.projectId = :id and s.deletedAt is null")
    void deleteProjectSessions(@Param("id") String projectId, @Param("when") Instant when);
}
interface MessageRepository extends JpaRepository<ChatMessage, String> {
    List<ChatMessage> findBySessionIdOrderByCreatedAtAsc(String sessionId);
}
interface RunRepository extends JpaRepository<AgentRun, String> {
    @Query("""
            select r from AgentRun r where exists (
                select 1 from ChatSession s where s.id = r.sessionId and s.deletedAt is null
            ) order by r.createdAt desc
            """)
    List<AgentRun> recentVisible(Pageable page);
    List<AgentRun> findTop50BySessionIdOrderByCreatedAtDesc(String sessionId);
    Optional<AgentRun> findFirstBySessionIdOrderByCreatedAtDesc(String sessionId);
    List<AgentRun> findByStatus(String status);
    List<AgentRun> findByCleanupPendingTrue();
    List<AgentRun> findAllByOrderByCreatedAtAsc();
}
interface EventRepository extends JpaRepository<RunEvent, Long> {
    List<RunEvent> findFirst200ByRunIdAndIdGreaterThanOrderByIdAsc(String runId, Long after);
    List<RunEvent> findFirst200ByRunIdAndTypeInOrderByIdAsc(String runId, List<String> types);
}
