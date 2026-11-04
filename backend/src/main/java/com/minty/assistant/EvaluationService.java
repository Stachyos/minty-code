package com.minty.assistant;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** One evaluation at a time, independent of chat sessions and their data. */
@Service
public class EvaluationService {
    private final AgentProperties config;
    private final ObjectMapper json;
    private final Path root, script;
    private final String python;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Map<String, Map<String, Object>> records = new LinkedHashMap<>();
    private Process process;
    private String activeId;
    private static final Set<String> TERMINAL = Set.of("resolved", "unresolved", "error", "stopped");

    EvaluationService(AgentProperties config, ObjectMapper json,
                      @Value("${EVALUATION_PYTHON:${agent.python}}") String evaluationPython) throws IOException {
        this.config = config; this.json = json;
        this.python = evaluationPython;
        this.script = Path.of(config.worker()).toAbsolutePath().normalize().resolveSibling("evaluation_worker.py");
        this.root = Path.of(config.workspaces()).toAbsolutePath().normalize().resolveSibling("evaluations");
        Files.createDirectories(root);
        try (var dirs = Files.list(root)) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                if (!Files.isRegularFile(dir.resolve("run.json"))) continue;
                Map<String, Object> record = json.readValue(dir.resolve("run.json").toFile(), new TypeReference<>() {});
                String id = record.get("id").toString();
                if (!id.equals(dir.getFileName().toString())) continue;
                if (Set.of("running", "preparing", "stopping").contains(record.get("status"))) {
                    record.put("status", "interrupted"); record.put("error", "服务重启中断；已有补丁和报告已保留，请新建评测");
                    record.put("finishedAt", Instant.now().toString());
                    for (var task : tasks(record)) if (!TERMINAL.contains(task.get("status"))) task.put("status", "stopped");
                    save(record);
                }
                records.put(id, record);
            }
        }
    }

    private ProcessBuilder builder(String... args) {
        List<String> command = new ArrayList<>(List.of(python, script.toString(), "--data-root", root.toString()));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).directory(script.getParent().toFile());
        builder.environment().put("PYTHONIOENCODING", "utf-8");
        builder.environment().put("PYTHONUNBUFFERED", "1");
        // Configuration goes only to the worker's environment, never arguments or API responses.
        builder.environment().put("ANTHROPIC_API_KEY", Objects.toString(config.apiKey(), ""));
        builder.environment().put("MODEL_ID", Objects.toString(config.model(), ""));
        if (config.baseUrl() != null) builder.environment().put("ANTHROPIC_BASE_URL", config.baseUrl());
        return builder;
    }

    private Object query(String flag, int timeout) throws IOException, InterruptedException {
        Process child = builder(flag).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        // Read concurrently so a catalog larger than the pipe buffer cannot deadlock.
        var read = CompletableFuture.supplyAsync(() -> {
            try { return child.getInputStream().readAllBytes(); } catch (IOException e) { throw new UncheckedIOException(e); }
        });
        try {
            if (!child.waitFor(timeout, TimeUnit.SECONDS)) throw new IOException("评测环境检查超时，请稍后重试");
            if (child.exitValue() != 0) throw new IOException("无法获取评测数据，请检查网络和 Python 环境");
            return json.readValue(read.join(), Object.class);
        } finally { if (child.isAlive()) child.destroyForcibly(); }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> environment() {
        try {
            var state = (Map<String, Object>) query("--environment", 25);
            state.put("configured", config.configured()); state.put("model", config.model());
            synchronized (this) { state.put("busy", activeId != null); }
            return state;
        } catch (Exception e) {
            return Map.of("dockerReady", false, "imageReady", false, "configured", config.configured(),
                    "model", config.model(), "message", redact(e.getMessage()));
        }
    }

    public Object catalog() throws IOException, InterruptedException { return query("--catalog", 150); }

    public synchronized List<Map<String, Object>> list() {
        return records.values().stream().sorted(Comparator.comparing(r -> r.get("createdAt").toString(), Comparator.reverseOrder()))
                .map(this::summary).toList();
    }
    private Map<String, Object> summary(Map<String, Object> record) {
        var result = new LinkedHashMap<>(record); result.remove("tasks"); result.remove("manifest"); return result;
    }
    public synchronized Map<String, Object> get(String id) {
        var record = require(id);
        return json.convertValue(record, new TypeReference<>() {});
    }
    private Map<String, Object> require(String id) {
        var record = records.get(id);
        if (record == null) throw new ApiException(HttpStatus.NOT_FOUND, "评测记录不存在");
        return record;
    }
    private void save(Map<String, Object> record) throws IOException {
        Path dir = root.resolve(record.get("id").toString()); Files.createDirectories(dir);
        Path temporary = dir.resolve("run.json.tmp");
        json.writeValue(temporary.toFile(), record);
        Files.move(temporary, dir.resolve("run.json"), StandardCopyOption.REPLACE_EXISTING);
    }
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> tasks(Map<String, Object> record) {
        return (List<Map<String, Object>>) record.get("tasks");
    }

    public synchronized Map<String, Object> start(EvaluationController.Request input, boolean setup) throws IOException {
        if (activeId != null) throw new ApiException(HttpStatus.CONFLICT, "已有评测或环境准备任务正在运行");
        if (!setup && !config.configured()) throw new ApiException(HttpStatus.CONFLICT, "请先配置真实模型");
        String id = UUID.randomUUID().toString();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("action", setup ? "setup" : "evaluate"); request.put("model", config.model());
        if (!setup) {
            request.put("limit", input.limit()); request.put("instanceIds", input.instanceIds() == null ? List.of() : input.instanceIds());
            request.put("maxSteps", input.maxSteps()); request.put("timeoutSeconds", input.timeoutSeconds());
            request.put("testTimeoutSeconds", input.testTimeoutSeconds());
        }
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", id); record.put("name", setup ? "准备评分环境" : input.name().strip());
        record.put("action", setup ? "setup" : "evaluate"); record.put("status", setup ? "preparing" : "running");
        record.put("createdAt", Instant.now().toString()); record.put("model", config.model()); record.put("config", request);
        record.put("tasks", new ArrayList<>()); record.put("inputTokens", 0L); record.put("outputTokens", 0L);
        record.put("total", 0); record.put("resolved", 0); record.put("completed", 0); record.put("errors", 0);
        record.put("lastEventId", 0L); save(record); records.put(id, record); activeId = id;
        executor.submit(() -> execute(id, request));
        return get(id);
    }

    private void execute(String id, Map<String, Object> request) {
        Process child = null;
        try {
            synchronized (this) {
                if (!id.equals(activeId) || require(id).get("status").equals("stopping")) return;
                child = builder("--run-dir", root.resolve(id).toString()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                process = child;
            }
            try (Writer writer = new OutputStreamWriter(child.getOutputStream(), StandardCharsets.UTF_8)) { json.writeValue(writer, request); }
            try (var reader = child.inputReader(StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    Map<String, Object> event = json.readValue(redact(line), new TypeReference<>() {});
                    accept(id, event);
                }
            }
            int code = child.waitFor();
            synchronized (this) {
                var record = require(id);
                if (record.get("status").equals("stopping")) finish(record, "stopped", null);
                else if (!Set.of("completed", "failed").contains(record.get("status"))) finish(record, "failed", "评测进程退出（" + code + "），未返回完成记录");
            }
        } catch (Exception e) {
            synchronized (this) {
                try { finish(require(id), require(id).get("status").equals("stopping") ? "stopped" : "failed", redact(e.getMessage())); }
                catch (IOException ignored) { }
            }
        } finally {
            if (child != null && child.isAlive()) { child.descendants().forEach(p -> p.destroyForcibly()); child.destroyForcibly(); }
            cleanup(id);
            synchronized (this) {
                try {
                    if (require(id).get("status").equals("stopping")) finish(require(id), "stopped", null);
                } catch (IOException ignored) { }
                if (id.equals(activeId)) { activeId = null; process = null; }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private synchronized void accept(String id, Map<String, Object> event) throws IOException {
        var record = require(id);
        if (Set.of("stopping", "stopped").contains(record.get("status"))) return;
        long cursor = ((Number) record.get("lastEventId")).longValue() + 1;
        event.put("id", cursor); event.put("createdAt", Instant.now().toString());
        Files.writeString(root.resolve(id).resolve("events.jsonl"), json.writeValueAsString(event) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        record.put("lastEventId", cursor);
        String type = Objects.toString(event.get("type"), "");
        if (type.equals("selected")) {
            record.put("tasks", event.get("tasks")); record.put("manifest", event.get("manifest"));
            record.put("total", tasks(record).size());
        } else if (type.equals("task")) {
            for (var task : tasks(record)) if (task.get("instanceId").equals(event.get("instanceId"))) {
                event.forEach((key, value) -> { if (!Set.of("type", "id", "createdAt").contains(key)) task.put(key, value); });
            }
        } else if (type.equals("agent") && event.get("eventType").equals("usage")) {
            for (String key : List.of("inputTokens", "outputTokens")) {
                long amount = ((Number) event.getOrDefault(key, 0)).longValue();
                record.put(key, ((Number) record.get(key)).longValue() + amount);
                for (var task : tasks(record)) if (task.get("instanceId").equals(event.get("instanceId")))
                    task.put(key, ((Number) task.getOrDefault(key, 0)).longValue() + amount);
            }
        } else if (type.equals("fatal")) { finish(record, "failed", Objects.toString(event.get("content"))); }
        else if (type.equals("finished")) { finish(record, "completed", null); }
        record.put("resolved", tasks(record).stream().filter(t -> t.get("status").equals("resolved")).count());
        record.put("completed", tasks(record).stream().filter(t -> TERMINAL.contains(t.get("status"))).count());
        record.put("errors", tasks(record).stream().filter(t -> t.get("status").equals("error")).count());
        save(record);
    }

    private void finish(Map<String, Object> record, String state, String error) throws IOException {
        record.put("status", state); record.put("finishedAt", Instant.now().toString());
        if (error != null) record.put("error", error);
        if (!state.equals("completed")) for (var task : tasks(record)) if (!TERMINAL.contains(task.get("status"))) task.put("status", "stopped");
        save(record);
    }

    public synchronized Map<String, Object> stop(String id) throws IOException {
        var record = require(id);
        if (id.equals(activeId)) {
            record.put("status", "stopping"); save(record);
            if (process != null) { process.descendants().forEach(p -> p.destroyForcibly()); process.destroyForcibly(); }
        }
        return get(id);
    }

    private void cleanup(String id) {
        try {
            Process child = builder("--cleanup", id).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!child.waitFor(75, TimeUnit.SECONDS)) child.destroyForcibly();
        } catch (Exception ignored) { }
    }

    public synchronized List<Map<String, Object>> events(String id, long after) throws IOException {
        require(id); Path path = root.resolve(id).resolve("events.jsonl");
        if (!Files.exists(path)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        try (var lines = Files.lines(path)) {
            var iterator = lines.iterator();
            while (iterator.hasNext() && result.size() < 200) {
                Map<String, Object> event = json.readValue(iterator.next(), new TypeReference<>() {});
                if (((Number) event.get("id")).longValue() > after) result.add(event);
            }
        }
        return result;
    }

    public synchronized String artifact(String id, String instance, String kind) throws IOException {
        var record = require(id);
        if (tasks(record).stream().noneMatch(task -> task.get("instanceId").equals(instance))) throw new ApiException(HttpStatus.NOT_FOUND, "题目不存在");
        String file = Map.of("patch", "patch.diff", "report", "report.json", "tests", "test_output.txt").get(kind);
        if (file == null) throw new ApiException(HttpStatus.BAD_REQUEST, "不支持的文件类型");
        Path path = root.resolve(id).resolve(instance).resolve(file).normalize();
        if (!path.startsWith(root.resolve(id)) || !Files.isRegularFile(path)) throw new ApiException(HttpStatus.NOT_FOUND, "文件尚未生成");
        try (var reader = Files.newBufferedReader(path)) {
            StringBuilder content = new StringBuilder(); char[] buffer = new char[8192]; int count;
            while (content.length() < 500_000 && (count = reader.read(buffer, 0, Math.min(buffer.length, 500_000 - content.length()))) != -1)
                content.append(buffer, 0, count);
            if (reader.read() != -1) content.append("\n[页面预览已截断；完整文件保存在本地评测目录]");
            return redact(content.toString());
        }
    }

    private String redact(String content) {
        String value = Objects.toString(content, "未知错误");
        return config.apiKey() == null || config.apiKey().isBlank() ? value : value.replace(config.apiKey(), "[redacted]");
    }
    @PreDestroy synchronized void shutdown() {
        if (process != null) { process.descendants().forEach(p -> p.destroyForcibly()); process.destroyForcibly(); }
        executor.shutdownNow();
    }
}
