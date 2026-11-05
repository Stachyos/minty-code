package com.minty.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@Service
public class AgentService {
    private final AgentProperties config;
    private final DatabaseHealth database;
    private final SessionRepository sessions;
    private final MessageRepository messages;
    private final RunRepository runs;
    private final EventRepository events;
    private final WorkspaceService workspaces;
    private final ObjectMapper json;
    private final ExecutorService workers;
    private final TransactionTemplate transactions;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final Map<String, String> activeSessions = new ConcurrentHashMap<>();

    AgentService(AgentProperties config, SessionRepository sessions, MessageRepository messages,
                 RunRepository runs, EventRepository events, WorkspaceService workspaces, ObjectMapper json,
                 PlatformTransactionManager transactionManager, DatabaseHealth database) {
        this.config = config; this.sessions = sessions; this.messages = messages;
        this.database = database;
        this.runs = runs; this.events = events; this.workspaces = workspaces; this.json = json;
        this.workers = Executors.newFixedThreadPool(Math.max(1, config.maxConcurrentRuns()));
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @PostConstruct void recoverInterruptedRuns() {
        for (AgentRun run : runs.findByStatus("running")) {
            run.status = "failed"; run.error = "服务已重启，请重新发送任务"; run.finishedAt = Instant.now(); runs.save(run);
            event(run.id, "status", "failed");
        }
    }
    public Map<String, Object> status() {
        boolean databaseReady = database.ready();
        return Map.of("configured", config.configured(), "model", config.model(), "running", jobs.size(),
                "databaseReady", databaseReady, "ready", databaseReady && config.configured(),
                "message", databaseReady ? "" : "数据库暂时无法连接，历史记录当前无法读取。请检查本地数据库是否已启动。",
                "shellEnabled", config.allowShell(), "maxSteps", config.maxSteps(), "timeoutSeconds", config.timeoutSeconds());
    }
    public List<ChatSession> sessions() { return sessions.findAllByOrderByUpdatedAtDesc(); }
    public ChatSession createSession(String title) {
        String clean = title == null || title.isBlank() ? "新的灵感" : title.strip();
        return sessions.save(new ChatSession(clean.substring(0, Math.min(clean.length(), 120))));
    }
    public ChatSession requireSession(String id) {
        return sessions.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "会话不存在"));
    }
    public Map<String, Object> session(String id) {
        ChatSession session = requireSession(id);
        return Map.of("id", session.id, "title", session.title, "createdAt", session.createdAt,
                "updatedAt", session.updatedAt, "messages", messages.findBySessionIdOrderByCreatedAtAsc(id));
    }
    public AgentRun latest(String sessionId) { requireSession(sessionId); return runs.findFirstBySessionIdOrderByCreatedAtDesc(sessionId).orElse(null); }
    public List<AgentRun> recentRuns(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return runs.findTop50ByOrderByCreatedAtDesc();
        requireSession(sessionId);
        return runs.findTop50BySessionIdOrderByCreatedAtDesc(sessionId);
    }
    public AgentRun requireRun(String id) { return runs.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "运行记录不存在")); }
    public List<RunEvent> events(String id, long after) { requireRun(id); return events.findFirst200ByRunIdAndIdGreaterThanOrderByIdAsc(id, Math.max(0, after)); }
    public synchronized Map<String, Object> upload(String sessionId, String path, byte[] bytes) throws IOException {
        requireSession(sessionId);
        if (activeSessions.containsKey(sessionId)) throw new ApiException(HttpStatus.CONFLICT, "请先等待或停止任务，再上传文件");
        return workspaces.upload(sessionId, path, bytes);
    }

    public synchronized AgentRun start(String sessionId, String prompt) throws IOException {
        ChatSession session = requireSession(sessionId);
        if (!config.configured()) throw new ApiException(HttpStatus.CONFLICT, "请先在服务端 .env 中配置有效的 ANTHROPIC_API_KEY");
        if (activeSessions.containsKey(sessionId)) throw new ApiException(HttpStatus.CONFLICT, "当前会话有任务正在运行，请等待或停止任务");
        if (jobs.size() >= Math.max(1, config.maxConcurrentRuns())) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "同时运行的任务已达上限，请稍后再试");
        Path script = Path.of(config.worker()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(script)) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "未找到 Agent worker，请检查 AGENT_WORKER 配置");
        Path workspace = workspaces.workspace(sessionId);
        List<Map<String, String>> history = messages.findBySessionIdOrderByCreatedAtAsc(sessionId).stream()
                .map(m -> Map.of("role", m.role, "content", m.content)).toList();
        if (history.isEmpty() && (session.title.equals("新的灵感") || session.title.equals("新会话"))) {
            session.title = prompt.strip().substring(0, Math.min(prompt.strip().length(), 24));
        }
        session.updatedAt = Instant.now(); sessions.save(session);
        messages.save(new ChatMessage(sessionId, "user", prompt));
        AgentRun run = runs.save(new AgentRun(sessionId, prompt, config.model()));
        Job job = new Job(run.id, sessionId);
        jobs.put(run.id, job); activeSessions.put(sessionId, run.id);
        event(run.id, "status", "running");
        workers.submit(() -> execute(job, script, workspace, prompt, history));
        return run;
    }

    private void execute(Job job, Path script, Path workspace, String prompt, List<Map<String, String>> history) {
        ScheduledFuture<?> timeout = timer.schedule(() -> {
            synchronized (job) {
                if (job.finished) return;
                job.cancelled = true;
                kill(job.process);
                finish(job, "failed", "任务超过时间限制，已停止");
            }
        }, Math.max(1, config.timeoutSeconds()), TimeUnit.SECONDS);
        StringBuilder stderr = new StringBuilder();
        Thread errorReader = null;
        try {
            synchronized (job) {
                if (job.cancelled) return;
                ProcessBuilder builder = new ProcessBuilder(config.python(), "-u", script.toString(), "--workspace", workspace.toString());
                builder.directory(workspace.toFile());
                builder.environment().put("ANTHROPIC_API_KEY", config.apiKey());
                builder.environment().put("MODEL_ID", config.model());
                builder.environment().put("PYTHONIOENCODING", "utf-8");
                builder.environment().put("AGENT_ALLOW_SHELL", Boolean.toString(config.allowShell()));
                if (config.baseUrl() != null && !config.baseUrl().isBlank()) builder.environment().put("ANTHROPIC_BASE_URL", config.baseUrl());
                job.process = builder.start();
            }
            Process process = job.process;
            errorReader = new Thread(() -> {
                try (Reader reader = new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8)) {
                    char[] buffer = new char[1024]; int count;
                    while ((count = reader.read(buffer)) != -1) {
                        synchronized (stderr) { stderr.append(buffer, 0, count); if (stderr.length() > 4000) stderr.delete(0, stderr.length() - 4000); }
                    }
                } catch (IOException ignored) {}
            }, "agent-stderr-" + job.id);
            errorReader.setDaemon(true); errorReader.start();
            try (Writer input = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
                json.writeValue(input, Map.of("prompt", prompt, "history", history, "model", config.model(), "maxSteps", Math.max(1, config.maxSteps()), "runId", job.id));
            }
            boolean done = false;
            String workerError = null;
            try (BufferedReader output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = output.readLine()) != null) {
                    if (line.isBlank()) continue;
                    synchronized (job) {
                        if (job.cancelled || job.finished) break;
                        JsonNode node = json.readTree(line);
                        String type = node.path("type").asText();
                        String content = redact(node.path("content").isTextual() ? node.path("content").asText() : node.path("content").toString());
                        switch (type) {
                            case "text" -> { job.text.append(content); event(job.id, type, content); }
                            case "tool_start", "tool_result", "model_start", "model_end", "phase", "hook", "completion_check" -> {
                                if (type.equals("tool_start") && json.readTree(content).path("name").asText().equals("run_tests")) job.testsUsed = true;
                                event(job.id, type, content);
                            }
                            case "verification", "changes" -> {
                                AgentRun observed = requireRun(job.id);
                                if (type.equals("verification")) observed.verification = content; else observed.changes = content;
                                runs.save(observed); event(job.id, type, content);
                            }
                            case "usage" -> {
                                job.inputTokens += Math.max(0, node.path("inputTokens").asLong());
                                job.outputTokens += Math.max(0, node.path("outputTokens").asLong());
                                persistUsage(job);
                            }
                            case "error" -> { workerError = content; reportError(job, content); }
                            case "done" -> done = true;
                            default -> { /* Ignore extension events without exposing raw process output. */ }
                        }
                    }
                }
            }
            int code = process.waitFor();
            if (errorReader != null) errorReader.join(1000);
            synchronized (job) {
                if (!job.finished) {
                    if (workerError != null) finish(job, "failed", workerError);
                    else if (code != 0) { String detail; synchronized (stderr) { detail = stderr.toString().strip(); }
                        finish(job, "failed", "Agent 进程退出（" + code + "）" + (detail.isBlank() ? "" : "：" + redact(detail))); }
                    else if (!done) finish(job, "failed", "Agent 未返回完成状态，请检查配置后重试");
                    else finish(job, "completed", null);
                }
            }
        } catch (Exception e) {
            synchronized (job) { if (!job.finished) finish(job, "failed", "无法执行任务：" + redact(e.getMessage())); }
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        } finally {
            timeout.cancel(false); kill(job.process);
            if (job.testsUsed) cleanupTests(job.id);
            jobs.remove(job.id); activeSessions.remove(job.sessionId, job.id);
        }
    }
    public AgentRun stop(String id) {
        AgentRun run = requireRun(id);
        Job job = jobs.get(id);
        if (job != null) synchronized (job) {
            if (!job.finished) { job.cancelled = true; kill(job.process); finish(job, "stopped", null); }
        }
        return requireRun(id);
    }
    private void finish(Job job, String status, String error) {
        if (job.finished) return;
        // Final status must not become visible before the final message and events.
        transactions.executeWithoutResult(tx -> {
            AgentRun run = requireRun(job.id);
            run.status = status; run.error = error; run.finishedAt = Instant.now();
            run.inputTokens = job.inputTokens; run.outputTokens = job.outputTokens; runs.save(run);
            if (!job.text.isEmpty()) messages.save(new ChatMessage(job.sessionId, "assistant", job.text.toString()));
            ChatSession session = requireSession(job.sessionId); session.updatedAt = Instant.now(); sessions.save(session);
            if (error != null) reportError(job, error);
            event(job.id, "status", status);
        });
        job.finished = true;
    }
    private void persistUsage(Job job) {
        AgentRun run = requireRun(job.id);
        run.inputTokens = job.inputTokens; run.outputTokens = job.outputTokens;
        runs.save(run);
    }
    private void reportError(Job job, String error) {
        if (!Objects.equals(job.reportedError, error)) {
            event(job.id, "error", error);
            job.reportedError = error;
        }
    }
    private void event(String id, String type, String content) { events.save(new RunEvent(id, type, content == null ? "" : content)); }
    private String redact(String content) {
        if (content == null) return "未知错误";
        if (config.apiKey() != null && !config.apiKey().isBlank()) content = content.replace(config.apiKey(), "[已隐藏密钥]");
        return content;
    }
    private void kill(Process process) {
        if (process == null) return;
        // Snapshot descendants first: destroying the parent can detach children.
        List<ProcessHandle> descendants = process.descendants().toList();
        descendants.forEach(p -> { if (p.isAlive()) p.destroyForcibly(); });
        if (process.isAlive()) process.destroyForcibly();
    }
    private void cleanupTests(String id) {
        try {
            Process scan = new ProcessBuilder("docker", "ps", "-aq", "--filter", "label=minty.run=" + id).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!scan.waitFor(5, TimeUnit.SECONDS)) { scan.destroyForcibly(); return; }
            for (String container : new String(scan.getInputStream().readAllBytes(), StandardCharsets.UTF_8).split("\\s+")) {
                if (!container.matches("[a-f0-9]{12,64}")) continue;
                Process removal = new ProcessBuilder("docker", "rm", "-f", container).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                if (!removal.waitFor(10, TimeUnit.SECONDS)) removal.destroyForcibly();
            }
        } catch (IOException ignored) { /* No Docker is needed for file-only runs. */ }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
    public Map<String, Object> stats() {
        List<AgentRun> all = runs.findAllByOrderByCreatedAtAsc();
        long total = all.stream().mapToLong(r -> r.inputTokens + r.outputTokens).sum();
        List<Map<String, Object>> daily = new ArrayList<>();
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        for (int i = 6; i >= 0; i--) {
            LocalDate date = today.minusDays(i);
            List<AgentRun> day = all.stream().filter(r -> r.createdAt.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().equals(date)).toList();
            daily.add(Map.of("date", date.toString(), "runs", day.size(), "tokens", day.stream().mapToLong(r -> r.inputTokens + r.outputTokens).sum()));
        }
        return Map.of("sessions", sessions.count(), "runs", all.size(), "completedRuns", all.stream().filter(r -> r.status.equals("completed")).count(), "totalTokens", total, "daily", daily);
    }
    @PreDestroy void shutdown() {
        for (Job job : jobs.values()) synchronized (job) {
            job.cancelled = true; kill(job.process);
            if (!job.finished) finish(job, "stopped", "服务正在关闭");
        }
        workers.shutdownNow(); timer.shutdownNow();
    }
    private static class Job {
        final String id, sessionId;
        final StringBuilder text = new StringBuilder();
        Process process;
        boolean cancelled, finished, testsUsed;
        String reportedError;
        long inputTokens, outputTokens;
        Job(String id, String sessionId) { this.id = id; this.sessionId = sessionId; }
    }
}
