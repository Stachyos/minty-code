package com.minty.assistant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import java.io.IOException;
import java.util.*;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final AgentService agent;
    private final WorkspaceService workspaces;
    ApiController(AgentService agent, WorkspaceService workspaces) { this.agent = agent; this.workspaces = workspaces; }
    @GetMapping("/status") public Map<String, Object> status() { return agent.status(); }
    @GetMapping("/sessions") public List<ChatSession> sessions() { return agent.sessions(); }
    @PostMapping("/sessions") public ChatSession create(@RequestBody(required = false) CreateSession request) {
        return request == null ? agent.createSession(null)
                : agent.createSession(request.title(), request.mode(), request.projectId(), request.workspaceId());
    }
    @GetMapping("/sessions/{id}") public Map<String, Object> session(@PathVariable String id) { return agent.session(id); }
    @DeleteMapping("/sessions/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) { agent.deleteSession(id); }
    @PostMapping("/sessions/{id}/runs") public AgentRun run(@PathVariable String id, @Valid @RequestBody RunRequest request) throws IOException {
        return agent.start(id, request.prompt().strip());
    }
    @GetMapping("/sessions/{id}/runs/latest") public AgentRun latest(@PathVariable String id) { return agent.latest(id); }
    @GetMapping("/runs") public List<AgentRun> runs(@RequestParam(required = false) String sessionId) { return agent.recentRuns(sessionId); }
    @GetMapping("/runs/{id}") public AgentRun run(@PathVariable String id) { return agent.requireRun(id); }
    @PostMapping("/runs/{id}/stop") public AgentRun stop(@PathVariable String id) { return agent.stop(id); }
    @PostMapping("/runs/{id}/pause") public AgentRun pause(@PathVariable String id) { return agent.pause(id); }
    @PostMapping("/runs/{id}/resume") public AgentRun resume(@PathVariable String id) throws IOException { return agent.resume(id); }
    @GetMapping("/runs/{id}/events") public List<RunEvent> events(@PathVariable String id, @RequestParam(defaultValue = "0") long after,
                                                                  @RequestParam(defaultValue = "false") boolean summary) {
        return summary ? agent.eventSummary(id) : agent.events(id, after);
    }
    @GetMapping("/sessions/{id}/files") public List<Map<String, Object>> files(@PathVariable String id) throws IOException {
        return workspaces.files(agent.fileWorkspace(id));
    }
    @GetMapping("/sessions/{id}/files/content") public Map<String, String> file(@PathVariable String id, @RequestParam String path) throws IOException {
        return workspaces.content(agent.fileWorkspace(id), path);
    }
    @PostMapping(value = "/sessions/{id}/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(@PathVariable String id, @RequestParam("file") MultipartFile file,
                                      @RequestParam(required = false) String path) throws IOException {
        if (file.getSize() > 1048576) throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "单个文件不能超过 1 MB");
        return agent.upload(id, path == null || path.isBlank() ? file.getOriginalFilename() : path, file.getBytes());
    }
    @GetMapping("/sessions/{id}/tasks") public List<Map<String, Object>> tasks(@PathVariable String id) throws IOException {
        return workspaces.tasks(agent.fileScope(id));
    }
    @GetMapping("/stats") public Map<String, Object> stats() { return agent.stats(); }
    record CreateSession(String title, String mode, String projectId, String workspaceId) {}
    record RunRequest(@NotBlank(message = "请输入任务内容") @Size(max = 16000, message = "任务内容不能超过 16000 字") String prompt) {}
}
