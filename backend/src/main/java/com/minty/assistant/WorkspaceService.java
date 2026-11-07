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
import java.nio.file.attribute.BasicFileAttributes;
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
    Path localDirectory(String requested) {
        try {
            Path path = Path.of(requested);
            if (!path.isAbsolute() || !Files.isDirectory(path)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "请输入这台电脑上已存在的目录的完整路径");
            }
            Path real = path.toRealPath();
            if (real.equals(real.getRoot()) || real.startsWith(protectedDirectory())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "请选择具体的项目目录");
            }
            return real;
        } catch (InvalidPathException | IOException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "无法读取该本地目录，请检查路径与访问权限");
        }
    }
    record DirectoryEntry(String name, String path) {}
    record DirectoryListing(String path, String parent, boolean selectable, List<DirectoryEntry> entries) {}

    DirectoryListing browseDirectories(String requested) throws IOException {
        if (requested == null || requested.isBlank()) {
            List<DirectoryEntry> locations = new ArrayList<>();
            Path home = Path.of(System.getProperty("user.home"));
            if (Files.isDirectory(home)) locations.add(new DirectoryEntry("用户文件夹", home.toString()));
            for (Path drive : FileSystems.getDefault().getRootDirectories()) {
                if (Files.isDirectory(drive)) locations.add(new DirectoryEntry(drive.toString(), drive.toString()));
            }
            return new DirectoryListing(null, null, false, locations);
        }
        Path managed = protectedDirectory();
        try {
            Path input = Path.of(requested);
            if (!input.isAbsolute() || requested.length() > 4096) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "请选择有效的本地文件夹");
            }
            Path folder = input.toRealPath();
            if (!folder.equals(input.normalize()) || folder.startsWith(managed) || !Files.isDirectory(folder)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "这个文件夹不可浏览");
            }
            try (Stream<Path> children = Files.list(folder)) {
                var entries = children.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                        .filter(path -> !path.startsWith(managed) && browsable(path))
                        .limit(500).sorted(Comparator.comparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                        .map(path -> new DirectoryEntry(path.getFileName().toString(), path.toString())).toList();
                Path parent = folder.getParent();
                return new DirectoryListing(folder.toString(), parent == null ? null : parent.toString(), parent != null, entries);
            }
        } catch (InvalidPathException | IOException | java.io.UncheckedIOException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "无法打开这个文件夹，请检查目录与访问权限");
        }
    }
    private boolean browsable(Path path) {
        try {
            return !path.getFileName().toString().startsWith(".") && !Files.isHidden(path) && unchangedPath(path);
        } catch (IOException e) {
            return false;
        }
    }
    Path linkedDirectory(String savedPath) {
        Path path = localDirectory(savedPath);
        if (!path.equals(Path.of(savedPath))) {
            throw new ApiException(HttpStatus.CONFLICT, "项目目录的位置已变化，请重新设置本地目录");
        }
        return path;
    }
    Path protectedDirectory() throws IOException {
        Path actual = root.toRealPath();
        Path parent = actual.getParent();
        return parent != null && parent.endsWith("data") && actual.endsWith("workspaces") ? parent : actual;
    }
    Path checkpoint(String runId) throws IOException {
        UUID.fromString(runId);
        Path directory = root.resolve(".minty-run-state");
        Files.createDirectories(directory);
        if (!directory.toRealPath().equals(root.toRealPath().resolve(".minty-run-state"))) {
            throw new IOException("任务检查点目录不可用");
        }
        Path checkpoint = directory.resolve(runId);
        if (Files.exists(checkpoint, LinkOption.NOFOLLOW_LINKS)
                && !checkpoint.toRealPath().equals(directory.toRealPath().resolve(runId))) {
            throw new IOException("任务检查点目录不可用");
        }
        return checkpoint;
    }
    public List<Map<String, Object>> files(String sessionId) throws IOException {
        return files(workspace(sessionId));
    }
    public List<Map<String, Object>> files(Path workspace) throws IOException {
        List<Map<String, Object>> files = new ArrayList<>();
        Path excluded = excludedDirectory(workspace);
        Files.walkFileTree(workspace, Set.of(), 12, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) {
                return directory.equals(workspace) || (!directory.equals(excluded) && visible(workspace.relativize(directory)) && unchangedPath(directory))
                        ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                Path relative = workspace.relativize(file);
                if (attrs.isRegularFile() && visible(relative) && unchangedPath(file)) {
                    files.add(Map.of("path", relative.toString().replace('\\', '/'), "size", attrs.size()));
                }
                return files.size() == 500 ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file, IOException error) throws IOException {
                if (file.equals(workspace)) throw error;
                return FileVisitResult.CONTINUE;
            }
        });
        files.sort(Comparator.comparing(file -> (String) file.get("path")));
        return files;
    }
    Path excludedDirectory(Path workspace) throws IOException {
        Path managed = protectedDirectory();
        return !managed.equals(workspace) && managed.startsWith(workspace) ? managed : null;
    }
    private static final Set<String> IGNORED = Set.of("node_modules", "__pycache__", "target", "dist", "venv", "coverage");
    private boolean visible(Path relative) {
        for (Path part : relative) {
            String name = part.toString().toLowerCase(Locale.ROOT);
            if (name.startsWith(".") || IGNORED.contains(name) || name.matches(".*\\.(pyc|pem|key|p12|pfx)$")) return false;
        }
        return !relative.toString().contains(":");
    }
    private boolean unchangedPath(Path path) {
        try { return path.equals(path.toRealPath()); } catch (IOException e) { return false; }
    }
    private boolean safeRealPath(Path path, Path workspace) {
        try { return path.toRealPath().startsWith(workspace); } catch (IOException e) { return false; }
    }
    public Map<String, String> content(String sessionId, String requested) throws IOException {
        return content(workspace(sessionId), requested);
    }
    public Map<String, String> content(Path workspace, String requested) throws IOException {
        Path relative;
        try { relative = Path.of(requested); }
        catch (InvalidPathException e) { throw new ApiException(HttpStatus.BAD_REQUEST, "无效文件路径"); }
        Path path = workspace.resolve(relative).normalize();
        if (relative.isAbsolute() || !path.startsWith(workspace) || !visible(relative) || Files.isSymbolicLink(path)
                || isExcluded(workspace, path)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "只能查看当前会话的工作文件");
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new ApiException(HttpStatus.NOT_FOUND, "文件不存在");
        if (!unchangedPath(path)) throw new ApiException(HttpStatus.FORBIDDEN, "不能预览链接文件");
        if (Files.size(path) > 262144) throw new ApiException(HttpStatus.BAD_REQUEST, "预览仅支持 256 KB 以内的文本文件");
        byte[] bytes = Files.readAllBytes(path);
        for (byte b : bytes) if (b == 0) throw new ApiException(HttpStatus.BAD_REQUEST, "该文件不是可预览的文本文件");
        return Map.of("path", workspace.relativize(path).toString().replace('\\', '/'), "content", new String(bytes, StandardCharsets.UTF_8));
    }
    public Map<String, Object> upload(Path workspace, String requested, byte[] bytes) throws IOException {
        if (bytes.length > 1048576) throw new ApiException(HttpStatus.BAD_REQUEST, "单个文件不能超过 1 MB");
        if (requested == null || requested.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "请选择文件");
        Path relative;
        try { relative = Path.of(requested.replace('\\', '/')); }
        catch (InvalidPathException e) { throw new ApiException(HttpStatus.BAD_REQUEST, "无效文件路径"); }
        Path target = workspace.resolve(relative).normalize();
        boolean traversal = false;
        for (Path part : relative) if (part.toString().equals("..")) traversal = true;
        if (relative.isAbsolute() || traversal || !visible(relative) || !target.startsWith(workspace) || target.equals(workspace)
                || isExcluded(workspace, target)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "只能上传到当前会话的非隐藏目录");
        }
        Path cursor = workspace;
        for (Path part : relative) {
            cursor = cursor.resolve(part);
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS) && !unchangedPath(cursor)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "上传路径不能包含链接");
            }
        }
        for (byte b : bytes) if (b == 0) throw new ApiException(HttpStatus.BAD_REQUEST, "只支持 UTF-8 文本或代码文件");
        try { StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)); }
        catch (java.nio.charset.CharacterCodingException e) { throw new ApiException(HttpStatus.BAD_REQUEST, "文件必须使用 UTF-8 编码"); }
        Files.createDirectories(target.getParent());
        if (!unchangedPath(target.getParent())) throw new ApiException(HttpStatus.FORBIDDEN, "上传路径超出工作目录");
        try (OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) { output.write(bytes); }
        return Map.of("path", workspace.relativize(target).toString().replace('\\', '/'), "size", bytes.length);
    }
    private boolean isExcluded(Path workspace, Path candidate) throws IOException {
        Path excluded = excludedDirectory(workspace);
        return excluded != null && candidate.startsWith(excluded);
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
