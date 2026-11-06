package com.minty.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
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
    @Value("${agent.max-tokens:400000}") private int maxTokens;

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
            JsonNode checkpoint = savedCheckpoint(run.id);
            if (checkpoint != null) {
                readBudget(run, checkpoint);
                run.progress = progressFrom(checkpoint);
                if (checkpoint.path("dockerUsed").asBoolean()) run.cleanupPending = true;
            }
            endAttempt(run, Instant.now());
            run.resumable = checkpoint != null && hasBudget(run);
            run.status = Boolean.TRUE.equals(run.resumable) ? "interrupted" : "failed";
            run.error = checkpoint == null ? "服务已重启，此任务没有可恢复的检查点"
                    : Boolean.TRUE.equals(run.resumable) ? "服务已重启，可从检查点继续" : "服务已重启，任务预算已耗尽";
            runs.save(run);
            event(run.id, "status", run.status);
        }
        for (AgentRun run : runs.findByCleanupPendingTrue()) {
            if (cleanupExecution(run.id)) {
                run.cleanupPending = false;
                runs.save(run);
            }
        }
    }
    public Map<String, Object> status() {
        boolean databaseReady = database.ready();
        return Map.of("configured", config.configured(), "model", config.model(), "running", jobs.size(),
                "databaseReady", databaseReady, "ready", databaseReady && config.configured(),
                "message", databaseReady ? "" : "数据库暂时无法连接，历史记录当前无法读取。请检查本地数据库是否已启动。",
                "shellEnabled", config.allowShell(), "maxSteps", config.maxSteps(), "timeoutSeconds", config.timeoutSeconds(),
                "maxTokens", maxTokens);
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
        Path script = checkCanStart(sessionId);
        Path workspace = workspaces.workspace(sessionId);
        List<Map<String, String>> history = messages.findBySessionIdOrderByCreatedAtAsc(sessionId).stream()
                .map(m -> Map.of("role", m.role, "content", m.content)).toList();
        if (history.isEmpty() && (session.title.equals("新的灵感") || session.title.equals("新会话"))) {
            session.title = prompt.strip().substring(0, Math.min(prompt.strip().length(), 24));
        }
        session.updatedAt = Instant.now(); sessions.save(session);
        messages.save(new ChatMessage(sessionId, "user", prompt));
        AgentRun run = new AgentRun(sessionId, prompt, config.model());
        run.maxRounds = Math.min(40, Math.max(1, config.maxSteps()));
        run.roundsUsed = 0;
        run.maxTokens = Math.max(1, maxTokens);
        run.timeLimitSeconds = Math.max(1, config.timeoutSeconds());
        run.elapsedMillis = 0L;
        run.progress = "{}";
        launch(run, script, workspace, history, false);
        return run;
    }

    private Path checkCanStart(String sessionId) {
        if (!config.configured()) throw new ApiException(HttpStatus.CONFLICT, "请先在服务端配置模型");
        if (activeSessions.containsKey(sessionId)) throw new ApiException(HttpStatus.CONFLICT, "当前会话有任务正在运行或清理，请稍后再试");
        if (jobs.size() >= Math.max(1, config.maxConcurrentRuns())) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "同时运行的任务已达上限，请稍后再试");
        Path script = Path.of(config.worker()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(script)) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "未找到 Agent worker，请检查 AGENT_WORKER 配置");
        return script;
    }

    public synchronized AgentRun resume(String id) throws IOException {
        AgentRun run = requireRun(id);
        if (!Boolean.TRUE.equals(run.resumable) || !run.status.equals("interrupted")
                || !latest(run.sessionId).id.equals(id)) {
            throw new ApiException(HttpStatus.CONFLICT, "只能恢复当前会话最近一次可恢复的任务");
        }
        if (!Objects.equals(run.model, config.model())) {
            throw new ApiException(HttpStatus.CONFLICT, "请切回任务原先使用的模型后再继续");
        }
        Path script = checkCanStart(run.sessionId);
        JsonNode checkpoint = savedCheckpoint(id);
        if (checkpoint == null) throw new ApiException(HttpStatus.CONFLICT, "任务检查点无法读取");
        readBudget(run, checkpoint);
        if (!hasBudget(run)) throw new ApiException(HttpStatus.CONFLICT, "任务预算已用完");
        if (checkpoint.path("dockerUsed").asBoolean() && !cleanupExecution(id)) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "无法核对上一次的执行容器，请确认 Docker Linux 引擎已启动");
        }
        run.cleanupPending = false;
        launch(run, script, workspaces.workspace(run.sessionId), List.of(), true);
        return run;
    }

    private void launch(AgentRun run, Path script, Path workspace, List<Map<String, String>> history, boolean resume) throws IOException {
        Path checkpoint = workspaces.checkpoint(run.id);
        run.status = "running";
        run.attemptStartedAt = Instant.now();
        run.resumable = false;
        run.finishedAt = null;
        run.error = null;
        runs.save(run);
        Job job = new Job(run.id, run.sessionId);
        job.inputTokens = run.inputTokens;
        job.outputTokens = run.outputTokens;
        jobs.put(run.id, job);
        activeSessions.put(run.sessionId, run.id);
        event(run.id, "status", "running");
        workers.submit(() -> execute(job, run, script, workspace, checkpoint, history, resume));
    }

    private void execute(Job job, AgentRun run, Path script, Path workspace, Path checkpoint,
                         List<Map<String, String>> history, boolean resume) {
        long remaining = Math.max(1, run.timeLimitSeconds * 1000L - run.elapsedMillis);
        ScheduledFuture<?> timeout = timer.schedule(() -> {
            synchronized (job) {
                if (job.finished) return;
                terminate(job, "budget_exhausted", "任务用完总执行时间预算，已停止");
            }
        }, remaining, TimeUnit.MILLISECONDS);
        StringBuilder stderr = new StringBuilder();
        Thread errorReader = null;
        String result = "failed";
        String reason = "Agent 未返回完成状态";
        try {
            synchronized (job) {
                if (job.cancelled) return;
                ProcessBuilder builder = new ProcessBuilder(config.python(), "-u", script.toString(), "--workspace", workspace.toString());
                builder.directory(workspace.toFile());
                builder.environment().put("ANTHROPIC_API_KEY", config.apiKey());
                builder.environment().put("MODEL_ID", run.model);
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
                input.write(json.writeValueAsString(Map.of("prompt", run.prompt, "history", history, "model", run.model,
                        "maxSteps", run.maxRounds, "maxTokens", run.maxTokens, "runId", job.id,
                        "checkpointDirectory", checkpoint.toString(), "resume", resume, "supervisorPid", ProcessHandle.current().pid())));
                input.write("\n");
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
                            case "tool_start", "tool_result", "model_start", "model_end", "phase", "hook", "completion_check", "recovery", "background" -> {
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
                            case "checkpoint" -> {
                                AgentRun observed = requireRun(job.id);
                                observed.progress = content;
                                readBudget(observed, json.readTree(content));
                                job.inputTokens = observed.inputTokens;
                                job.outputTokens = observed.outputTokens;
                                runs.save(observed);
                            }
                            case "interrupted", "budget_exhausted" -> {
                                result = type;
                                reason = content;
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
            if (workerError != null) {
                result = "failed";
                reason = workerError;
            } else if (code == 0 && done) {
                result = "completed";
                reason = null;
            } else if (result.equals("failed") && code != 0) {
                if (savedCheckpoint(job.id) != null) {
                    result = "interrupted";
                    reason = "Agent 进程意外退出，可从检查点继续";
                } else {
                    String detail;
                    synchronized (stderr) { detail = stderr.toString().strip(); }
                    reason = "Agent 进程退出（" + code + "）" + (detail.isBlank() ? "" : "：" + redact(detail));
                }
            }
        } catch (Exception e) {
            reason = "无法执行任务：" + redact(e.getMessage());
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        } finally {
            timeout.cancel(false); kill(job.process);
            try {
                if (job.process != null) job.process.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            JsonNode saved = savedCheckpoint(job.id);
            boolean needsCleanup = job.testsUsed || saved != null && saved.path("dockerUsed").asBoolean();
            job.cleanupPending = needsCleanup && !cleanupExecution(job.id);
            synchronized (this) {
                try {
                    synchronized (job) {
                        if (job.terminalStatus != null) {
                            result = job.terminalStatus;
                            reason = job.terminalReason;
                        }
                        finish(job, result, reason, saved);
                    }
                } finally {
                    jobs.remove(job.id);
                    activeSessions.remove(job.sessionId, job.id);
                }
            }
        }
    }
    public AgentRun stop(String id) {
        return interrupt(id, "stopped", null);
    }
    public AgentRun pause(String id) {
        return interrupt(id, "interrupted", "任务已暂停，可从检查点继续");
    }
    private AgentRun interrupt(String id, String status, String reason) {
        requireRun(id);
        Job job = jobs.get(id);
        if (job != null) synchronized (job) {
            if (!job.finished) terminate(job, status, reason);
        }
        return requireRun(id);
    }
    private void terminate(Job job, String status, String reason) {
        if (job.cancelled) return;
        job.cancelled = true;
        job.terminalStatus = status;
        job.terminalReason = reason;
        job.terminationTime = Instant.now();
        kill(job.process);
    }
    private void finish(Job job, String status, String error, JsonNode saved) {
        if (job.finished) return;
        // Final status must not become visible before the final message and events.
        transactions.executeWithoutResult(tx -> {
            AgentRun run = requireRun(job.id);
            run.inputTokens = job.inputTokens;
            run.outputTokens = job.outputTokens;
            if (saved != null) {
                readBudget(run, saved);
                run.progress = progressFrom(saved);
            }
            run.cleanupPending = job.cleanupPending;
            endAttempt(run, job.terminationTime == null ? Instant.now() : job.terminationTime);
            run.resumable = status.equals("interrupted") && saved != null && hasBudget(run);
            run.status = status.equals("interrupted") && !run.resumable ? (saved == null ? "failed" : "budget_exhausted") : status;
            run.error = status.equals("interrupted") && saved == null ? "任务在创建检查点前中断，请重新发送原始目标"
                    : status.equals("interrupted") && !run.resumable ? "任务已用完预算" : error;
            runs.save(run);
            if (!run.resumable && !job.text.isEmpty()) messages.save(new ChatMessage(job.sessionId, "assistant", job.text.toString()));
            ChatSession session = requireSession(job.sessionId); session.updatedAt = Instant.now(); sessions.save(session);
            if (run.status.equals("interrupted")) {
                event(job.id, "hook", "{\"hook\":\"pause\",\"status\":\"completed\"}");
            } else if (run.error != null) reportError(job, run.error);
            event(job.id, "status", run.status);
        });
        job.finished = true;
    }
    private JsonNode savedCheckpoint(String id) {
        try {
            Path file = workspaces.checkpoint(id).resolve("state.json");
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null;
            return json.readTree(file.toFile());
        } catch (IOException e) {
            return null;
        }
    }
    private String progressFrom(JsonNode checkpoint) {
        var progress = json.createObjectNode();
        for (String key : List.of("plan", "constraints", "openIssues", "rounds", "inputTokens", "outputTokens")) {
            if (checkpoint.has(key)) progress.set(key, checkpoint.get(key));
        }
        var steps = progress.putArray("steps");
        JsonNode savedSteps = checkpoint.path("steps");
        for (int i = Math.max(0, savedSteps.size() - 12); i < savedSteps.size(); i++) {
            JsonNode saved = savedSteps.get(i);
            var step = steps.addObject();
            for (String key : List.of("id", "tool", "status", "paths")) step.set(key, saved.path(key));
        }
        return redact(progress.toString());
    }
    private void readBudget(AgentRun run, JsonNode checkpoint) {
        run.roundsUsed = Math.max(run.roundsUsed == null ? 0 : run.roundsUsed, checkpoint.path("rounds").asInt());
        run.inputTokens = Math.max(run.inputTokens, checkpoint.path("inputTokens").asLong());
        run.outputTokens = Math.max(run.outputTokens, checkpoint.path("outputTokens").asLong());
    }
    private boolean hasBudget(AgentRun run) {
        return run.maxRounds != null && run.roundsUsed < run.maxRounds
                && run.inputTokens + run.outputTokens < run.maxTokens
                && run.elapsedMillis < run.timeLimitSeconds * 1000L;
    }
    private void endAttempt(AgentRun run, Instant end) {
        if (run.attemptStartedAt != null) {
            run.elapsedMillis += Math.max(0, Duration.between(run.attemptStartedAt, end).toMillis());
        }
        run.attemptStartedAt = null;
        run.finishedAt = end;
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
    private boolean cleanupContainers(String id) {
        try {
            Process scan = new ProcessBuilder("docker", "ps", "-aq", "--filter", "label=minty.run=" + id).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!scan.waitFor(5, TimeUnit.SECONDS)) { scan.destroyForcibly(); return false; }
            if (scan.exitValue() != 0) return false;
            for (String container : new String(scan.getInputStream().readAllBytes(), StandardCharsets.UTF_8).split("\\s+")) {
                if (!container.matches("[a-f0-9]{12,64}")) continue;
                Process removal = new ProcessBuilder("docker", "rm", "-f", container).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                if (!removal.waitFor(10, TimeUnit.SECONDS)) { removal.destroyForcibly(); return false; }
                if (removal.exitValue() != 0) return false;
            }
            return true;
        } catch (IOException ignored) { return false; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
    }
    private boolean cleanupExecution(String id) {
        if (!cleanupContainers(id)) return false;
        try {
            Path checkpoint = workspaces.checkpoint(id);
            if (!Files.isDirectory(checkpoint, LinkOption.NOFOLLOW_LINKS)) return true;
            try (var children = Files.list(checkpoint)) {
                for (Path path : children.toList()) {
                    if (!path.getFileName().toString().matches("command-[a-f0-9]{32}|minty-(tests|dependencies)-[a-zA-Z0-9_]+")
                            || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                            || !path.toRealPath().getParent().equals(checkpoint.toRealPath())) continue;
                    try (var files = Files.walk(path)) {
                        Path realRoot = path.toRealPath();
                        for (Path item : files.sorted(Comparator.reverseOrder()).toList()) {
                            if (!item.equals(path) && !item.getParent().toRealPath().startsWith(realRoot)) {
                                throw new IOException("执行目录包含越界链接");
                            }
                            Files.deleteIfExists(item);
                        }
                    }
                }
            }
            return true;
        } catch (IOException e) { return false; }
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
            if (!job.finished) terminate(job, "interrupted", "服务已停止，可从检查点继续");
        }
        workers.shutdown();
        try {
            if (!workers.awaitTermination(25, TimeUnit.SECONDS)) workers.shutdownNow();
        } catch (InterruptedException e) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
        timer.shutdownNow();
    }
    private static class Job {
        final String id, sessionId;
        final StringBuilder text = new StringBuilder();
        Process process;
        Instant terminationTime;
        boolean cancelled, finished, testsUsed, cleanupPending;
        String reportedError, terminalStatus, terminalReason;
        long inputTokens, outputTokens;
        Job(String id, String sessionId) { this.id = id; this.sessionId = sessionId; }
    }
}
