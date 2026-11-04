package com.minty.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

@Service
public class WorkspaceService {
    private final Path root;
    private final ObjectMapper json;
    WorkspaceService(AgentProperties config, ObjectMapper json) throws IOException {
        this.root = Path.of(config.workspaces()).toAbsolutePath().normalize();
        this.json = json;
        Files.createDirectories(root);
    }
    public Path workspace(String sessionId) throws IOException {
        UUID.fromString(sessionId);
        Path path = root.resolve(sessionId).normalize();
        Files.createDirectories(path);
        if (Files.isSymbolicLink(path) || !path.toRealPath().startsWith(root.toRealPath())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "工作目录不可用");
        }
        return path.toRealPath();
    }
    public List<Map<String, Object>> files(String sessionId) throws IOException {
        Path workspace = workspace(sessionId);
        try (Stream<Path> paths = Files.walk(workspace, 12)) {
            return paths.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .filter(p -> visible(workspace.relativize(p)))
                    .filter(p -> safeRealPath(p, workspace))
                    .sorted().limit(500).map(p -> {
                        try { return Map.<String, Object>of("path", workspace.relativize(p).toString().replace('\\', '/'), "size", Files.size(p)); }
                        catch (IOException e) { return Map.<String, Object>of("path", workspace.relativize(p).toString(), "size", 0L); }
                    }).toList();
        }
    }
    private boolean visible(Path relative) {
        for (Path part : relative) {
            String name = part.toString();
            if (name.startsWith(".") || name.equals("node_modules") || name.equals("__pycache__") || name.endsWith(".pyc")) return false;
        }
        return true;
    }
    private boolean safeRealPath(Path path, Path workspace) {
        try { return path.toRealPath().startsWith(workspace); } catch (IOException e) { return false; }
    }
    public Map<String, String> content(String sessionId, String requested) throws IOException {
        Path workspace = workspace(sessionId);
        Path relative;
        try { relative = Path.of(requested); }
        catch (InvalidPathException e) { throw new ApiException(HttpStatus.BAD_REQUEST, "无效文件路径"); }
        Path path = workspace.resolve(relative).normalize();
        if (relative.isAbsolute() || !path.startsWith(workspace) || !visible(relative) || Files.isSymbolicLink(path)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "只能查看当前会话的工作文件");
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new ApiException(HttpStatus.NOT_FOUND, "文件不存在");
        if (!safeRealPath(path, workspace)) throw new ApiException(HttpStatus.FORBIDDEN, "文件超出工作目录");
        if (Files.size(path) > 262144) throw new ApiException(HttpStatus.BAD_REQUEST, "预览仅支持 256 KB 以内的文本文件");
        byte[] bytes = Files.readAllBytes(path);
        for (byte b : bytes) if (b == 0) throw new ApiException(HttpStatus.BAD_REQUEST, "该文件不是可预览的文本文件");
        return Map.of("path", workspace.relativize(path).toString().replace('\\', '/'), "content", new String(bytes, StandardCharsets.UTF_8));
    }
    public Map<String, Object> upload(String sessionId, String requested, byte[] bytes) throws IOException {
        if (bytes.length > 1048576) throw new ApiException(HttpStatus.BAD_REQUEST, "单个文件不能超过 1 MB");
        if (requested == null || requested.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "请选择文件");
        Path workspace = workspace(sessionId);
        Path relative;
        try { relative = Path.of(requested.replace('\\', '/')); }
        catch (InvalidPathException e) { throw new ApiException(HttpStatus.BAD_REQUEST, "无效文件路径"); }
        Path target = workspace.resolve(relative).normalize();
        boolean traversal = false;
        for (Path part : relative) if (part.toString().equals("..")) traversal = true;
        if (relative.isAbsolute() || traversal || !visible(relative) || !target.startsWith(workspace) || target.equals(workspace)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "只能上传到当前会话的非隐藏目录");
        }
        Path cursor = workspace;
        for (Path part : relative) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) throw new ApiException(HttpStatus.FORBIDDEN, "上传路径不能包含符号链接");
        }
        for (byte b : bytes) if (b == 0) throw new ApiException(HttpStatus.BAD_REQUEST, "只支持 UTF-8 文本或代码文件");
        try { StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)); }
        catch (java.nio.charset.CharacterCodingException e) { throw new ApiException(HttpStatus.BAD_REQUEST, "文件必须使用 UTF-8 编码"); }
        Files.createDirectories(target.getParent());
        if (!target.getParent().toRealPath().startsWith(workspace)) throw new ApiException(HttpStatus.FORBIDDEN, "上传路径超出工作目录");
        try (OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) { output.write(bytes); }
        return Map.of("path", workspace.relativize(target).toString().replace('\\', '/'), "size", bytes.length);
    }
    public List<Map<String, Object>> tasks(String sessionId) throws IOException {
        Path workspace = workspace(sessionId);
        Path tasks = workspace.resolve(".tasks");
        if (!Files.isDirectory(tasks, LinkOption.NOFOLLOW_LINKS)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        try (Stream<Path> stream = Files.list(tasks)) {
            for (Path path : stream.filter(p -> p.getFileName().toString().matches("task_\\d+\\.json"))
                    .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).sorted().limit(200).toList()) {
                if (!safeRealPath(path, workspace) || Files.size(path) > 262144) continue;
                try { result.add(json.readValue(Files.readString(path), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {})); }
                catch (IOException ignored) { /* A task may be in the middle of an atomic write. Poll again. */ }
            }
        }
        return result;
    }
}
