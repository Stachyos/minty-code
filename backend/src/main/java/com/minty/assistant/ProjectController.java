package com.minty.assistant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/projects")
class ProjectController {
    private final ProjectService projects;
    private final AgentService agent;
    private final WorkspaceService directories;

    ProjectController(ProjectService projects, AgentService agent, WorkspaceService directories) {
        this.projects = projects;
        this.agent = agent;
        this.directories = directories;
    }

    @GetMapping List<ProjectService.ProjectInfo> list() {
        return projects.list();
    }

    @GetMapping("/directories") WorkspaceService.DirectoryListing directories(@RequestParam(required = false) String path) throws IOException {
        return directories.browseDirectories(path);
    }

    @GetMapping("/{projectId}/files") List<Map<String, Object>> files(
            @PathVariable String projectId, @RequestParam(required = false) String workspaceId) throws IOException {
        return directories.files(agent.projectFileWorkspace(projectId, workspaceId));
    }

    @GetMapping("/{projectId}/files/content") Map<String, String> file(
            @PathVariable String projectId, @RequestParam(required = false) String workspaceId, @RequestParam String path) throws IOException {
        return directories.content(agent.projectFileWorkspace(projectId, workspaceId), path);
    }

    @PostMapping ProjectService.ProjectInfo create(@Valid @RequestBody CreateProject request) {
        return projects.create(request.name(), request.localDirectory());
    }

    @PutMapping("/{projectId}/directory")
    ProjectService.ProjectInfo directory(@PathVariable String projectId, @Valid @RequestBody Directory request) {
        return agent.setProjectDirectory(projectId, request.localDirectory());
    }

    @DeleteMapping("/{projectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable String projectId) {
        agent.deleteProject(projectId);
    }

    @GetMapping("/{projectId}/workspaces") List<ProjectService.WorkspaceInfo> workspaces(@PathVariable String projectId) {
        return projects.workspaces(projectId);
    }

    @PostMapping("/{projectId}/workspaces") ProjectService.WorkspaceInfo createWorkspace(
            @PathVariable String projectId, @Valid @RequestBody Name request) {
        return projects.createWorkspace(projectId, request.name());
    }

    @GetMapping("/{projectId}/resources") List<ProjectService.ResourceInfo> resources(@PathVariable String projectId) {
        return projects.resources(projectId);
    }

    @GetMapping("/{projectId}/resource-list") List<ProjectService.ResourceSummary> resourceSummaries(@PathVariable String projectId) {
        return projects.resourceSummaries(projectId);
    }

    @GetMapping("/{projectId}/resources/{id}") ProjectService.ResourceInfo resource(
            @PathVariable String projectId, @PathVariable String id) {
        return projects.resource(projectId, id);
    }

    @PostMapping("/{projectId}/resources") ProjectService.ResourceInfo createResource(
            @PathVariable String projectId, @Valid @RequestBody Resource request) {
        return projects.saveResource(projectId, null, request.kind(), request.title(), request.source(), request.content(), request.verified());
    }

    @PutMapping("/{projectId}/resources/{id}") ProjectService.ResourceInfo editResource(
            @PathVariable String projectId, @PathVariable String id, @Valid @RequestBody Resource request) {
        return projects.saveResource(projectId, id, request.kind(), request.title(), request.source(), request.content(), request.verified());
    }

    @DeleteMapping("/{projectId}/resources/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteResource(@PathVariable String projectId, @PathVariable String id) {
        projects.deleteResource(projectId, id);
    }

    record Name(@NotBlank @Size(max = 120) String name) {}
    record CreateProject(@NotBlank @Size(max = 120) String name, @Size(max = 4096) String localDirectory) {}
    record Directory(@Size(max = 4096) String localDirectory) {}
    record Resource(@NotBlank @Size(max = 16) String kind, @NotBlank @Size(max = 120) String title,
                    @Size(max = 300) String source, @NotBlank @Size(max = 24000) String content, boolean verified) {}
}
