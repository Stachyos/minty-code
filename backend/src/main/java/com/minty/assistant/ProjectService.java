package com.minty.assistant;

import jakarta.persistence.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
class ProjectService {
    private final ProjectRepository projects;
    private final ProjectWorkspaceRepository workspaces;
    private final ProjectResourceRepository resources;
    private final WorkspaceService directories;

    ProjectService(ProjectRepository projects, ProjectWorkspaceRepository workspaces, ProjectResourceRepository resources,
                   WorkspaceService directories) {
        this.projects = projects;
        this.workspaces = workspaces;
        this.resources = resources;
        this.directories = directories;
    }

    record ProjectInfo(String id, String name, Instant createdAt, String localDirectory) {}
    record WorkspaceInfo(String id, String projectId, String name, Instant createdAt) {}
    record ResourceInfo(String id, String kind, String title, String source, String content, boolean verified, Instant updatedAt) {}
    record ResourceSummary(String id, String kind, String title, String source, boolean verified, Instant updatedAt) {}

    List<ProjectInfo> list() {
        return projects.findAllByDeletedAtIsNullOrderByCreatedAtDesc().stream().map(ProjectRecord::info).toList();
    }

    ProjectInfo create(String name) {
        return create(name, null);
    }

    ProjectInfo create(String name, String localDirectory) {
        var project = new ProjectRecord(cleanName(name));
        project.localDirectory = validateDirectory(localDirectory);
        return projects.save(project).info();
    }

    @Transactional
    ProjectInfo setDirectory(String id, String localDirectory) {
        var project = projects.lockById(id).orElseThrow(() -> missing("项目不存在"));
        project.localDirectory = validateDirectory(localDirectory);
        return project.info();
    }

    @Transactional
    void delete(String id, Instant when) {
        var project = projects.lockById(id).orElseThrow(() -> missing("项目不存在"));
        project.deletedAt = when;
    }

    private String validateDirectory(String path) {
        if (path == null || path.isBlank()) return null;
        String cleaned = path.strip();
        if (cleaned.length() >= 2 && cleaned.startsWith("\"") && cleaned.endsWith("\"")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }
        return directories.localDirectory(cleaned).toString();
    }

    ProjectInfo get(String id) {
        return projects.findByIdAndDeletedAtIsNull(id).orElseThrow(() -> missing("项目不存在")).info();
    }

    List<WorkspaceInfo> workspaces(String projectId) {
        get(projectId);
        return workspaces.findByProjectIdOrderByCreatedAtAsc(projectId).stream().map(ProjectWorkspace::info).toList();
    }

    WorkspaceInfo createWorkspace(String projectId, String name) {
        get(projectId);
        return workspaces.save(new ProjectWorkspace(projectId, cleanName(name))).info();
    }

    @Transactional
    WorkspaceInfo defaultWorkspace(String projectId) {
        var project = projects.lockById(projectId).orElseThrow(() -> missing("项目不存在"));
        return workspaces.findFirstByProjectIdOrderByCreatedAtAscIdAsc(projectId)
                .orElseGet(() -> workspaces.save(new ProjectWorkspace(projectId, project.name))).info();
    }

    WorkspaceInfo workspace(String projectId, String id) {
        return workspaces.findByIdAndProjectId(id, projectId).orElseThrow(() -> missing("此项目下没有该工作区")).info();
    }

    List<ResourceInfo> resources(String projectId) {
        get(projectId);
        return resources.findByProjectIdOrderByUpdatedAtDesc(projectId).stream().map(ProjectResource::info).toList();
    }

    List<ResourceSummary> resourceSummaries(String projectId) {
        get(projectId);
        return resources.findSummaries(projectId).stream()
                .map(row -> new ResourceSummary(row.getId(), row.getKind(), row.getTitle(), row.getSource(),
                        row.getVerified(), row.getUpdatedAt())).toList();
    }

    ResourceInfo resource(String projectId, String id) {
        return requireResource(projectId, id).info();
    }

    @Transactional
    ResourceInfo saveResource(String projectId, String id, String kind, String title, String source, String content, boolean verified) {
        projects.lockById(projectId).orElseThrow(() -> missing("项目不存在"));
        if (!Set.of("document", "memory", "skill").contains(kind)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "资料类型无效");
        }
        title = cleanName(title);
        if (kind.equals("skill") && !title.matches("[a-z0-9][a-z0-9-]{0,63}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "技能名称使用小写字母、数字和短横线，最多 64 个字符");
        }
        if (content == null || content.isBlank() || content.length() > 24000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "内容不能为空或超过 24000 字符");
        }
        if (source != null && source.length() > 300) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "来源不能超过 300 字符");
        }
        ProjectResource record;
        if (id == null) {
            if (resources.countByProjectId(projectId) >= 100) {
                throw new ApiException(HttpStatus.CONFLICT, "每个项目最多保存 100 条资料");
            }
            record = new ProjectResource(projectId);
        } else {
            record = requireResource(projectId, id);
        }
        if (kind.equals("skill")) {
            var existing = resources.findFirstByProjectIdAndKindAndTitle(projectId, kind, title);
            if (existing.isPresent() && !existing.get().id.equals(record.id)) {
                throw new ApiException(HttpStatus.CONFLICT, "此项目已有同名技能");
            }
        }
        record.kind = kind;
        record.title = title;
        record.source = source == null ? "" : source.strip();
        record.content = content;
        record.verified = kind.equals("skill") && verified;
        record.updatedAt = Instant.now();
        return resources.save(record).info();
    }

    @Transactional
    void deleteResource(String projectId, String id) {
        resources.delete(requireResource(projectId, id));
    }

    private ProjectResource requireResource(String projectId, String id) {
        get(projectId);
        return resources.findByIdAndProjectId(id, projectId).orElseThrow(() -> missing("此项目下没有该资料"));
    }

    private static ApiException missing(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message);
    }

    private static String cleanName(String name) {
        if (name == null || name.isBlank() || name.strip().length() > 120) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "名称应为 1 至 120 个字符");
        }
        return name.strip();
    }
}

@Entity
@Table(name = "projects")
class ProjectRecord {
    @Id String id;
    @Column(nullable = false, length = 120) String name;
    @Column(nullable = false) Instant createdAt;
    @Column(columnDefinition = "text") String localDirectory;
    Instant deletedAt;

    protected ProjectRecord() {}

    ProjectRecord(String name) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.createdAt = Instant.now();
    }

    ProjectService.ProjectInfo info() {
        return new ProjectService.ProjectInfo(id, name, createdAt, localDirectory);
    }
}

@Entity
@Table(name = "project_workspaces", indexes = @Index(columnList = "projectId"))
class ProjectWorkspace {
    @Id String id;
    @Column(nullable = false) String projectId;
    @Column(nullable = false, length = 120) String name;
    @Column(nullable = false) Instant createdAt;

    protected ProjectWorkspace() {}

    ProjectWorkspace(String projectId, String name) {
        this.id = UUID.randomUUID().toString();
        this.projectId = projectId;
        this.name = name;
        this.createdAt = Instant.now();
    }

    ProjectService.WorkspaceInfo info() {
        return new ProjectService.WorkspaceInfo(id, projectId, name, createdAt);
    }
}

@Entity
@Table(name = "project_resources", indexes = @Index(columnList = "projectId"))
class ProjectResource {
    @Id String id;
    @Column(nullable = false) String projectId;
    @Column(nullable = false, length = 16) String kind;
    @Column(nullable = false, length = 120) String title;
    @Column(nullable = false, length = 300) String source;
    @Column(nullable = false, columnDefinition = "text") String content;
    Boolean verified;
    @Column(nullable = false) Instant updatedAt;

    protected ProjectResource() {}

    ProjectResource(String projectId) {
        this.id = UUID.randomUUID().toString();
        this.projectId = projectId;
    }

    ProjectService.ResourceInfo info() {
        return new ProjectService.ResourceInfo(id, kind, title, source, content, Boolean.TRUE.equals(verified), updatedAt);
    }
}

interface ProjectRepository extends JpaRepository<ProjectRecord, String> {
    List<ProjectRecord> findAllByDeletedAtIsNullOrderByCreatedAtDesc();
    java.util.Optional<ProjectRecord> findByIdAndDeletedAtIsNull(String id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ProjectRecord p where p.id = :id and p.deletedAt is null")
    java.util.Optional<ProjectRecord> lockById(@Param("id") String id);
}

interface ProjectWorkspaceRepository extends JpaRepository<ProjectWorkspace, String> {
    List<ProjectWorkspace> findByProjectIdOrderByCreatedAtAsc(String projectId);
    java.util.Optional<ProjectWorkspace> findFirstByProjectIdOrderByCreatedAtAscIdAsc(String projectId);
    java.util.Optional<ProjectWorkspace> findByIdAndProjectId(String id, String projectId);
}

interface ProjectResourceRepository extends JpaRepository<ProjectResource, String> {
    List<ProjectResource> findByProjectIdOrderByUpdatedAtDesc(String projectId);
    java.util.Optional<ProjectResource> findByIdAndProjectId(String id, String projectId);
    java.util.Optional<ProjectResource> findFirstByProjectIdAndKindAndTitle(String projectId, String kind, String title);
    long countByProjectId(String projectId);
    @Query("""
            select r.id as id, r.kind as kind, r.title as title, r.source as source,
                   coalesce(r.verified, false) as verified, r.updatedAt as updatedAt
            from ProjectResource r where r.projectId = :projectId order by r.updatedAt desc
            """)
    List<ResourceRow> findSummaries(@Param("projectId") String projectId);

    interface ResourceRow {
        String getId();
        String getKind();
        String getTitle();
        String getSource();
        boolean getVerified();
        Instant getUpdatedAt();
    }
}
