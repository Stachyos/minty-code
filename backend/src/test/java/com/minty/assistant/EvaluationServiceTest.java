package com.minty.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class EvaluationServiceTest {
    @TempDir Path root;
    private final ObjectMapper json = new ObjectMapper();

    private EvaluationService service() throws Exception {
        AgentProperties config = new AgentProperties("python", root.resolve("worker.py").toString(),
                root.resolve("workspaces").toString(), "unit-test-secret", null, "test-model", 24, 600, 2, false);
        return new EvaluationService(config, json, "python");
    }
    private Path savedRun(String id, String status) throws Exception {
        Path dir = root.resolve("evaluations").resolve(id); Files.createDirectories(dir);
        json.writeValue(dir.resolve("run.json").toFile(), Map.of("id", id, "status", status,
                "createdAt", "2026-11-04T00:00:00Z", "tasks", List.of(Map.of("instanceId", "psf__requests-2317", "status", "running"))));
        return dir;
    }
    @Test void restartMarksUnfinishedRunsInterruptedAndKeepsArtifacts() throws Exception {
        String id = UUID.randomUUID().toString(); Path dir = savedRun(id, "running");
        Files.createDirectories(dir.resolve("psf__requests-2317"));
        Files.writeString(dir.resolve("psf__requests-2317/patch.diff"), "preserved patch");
        EvaluationService service = service();
        try {
            assertThat(service.get(id).get("status")).isEqualTo("interrupted");
            assertThat(service.artifact(id, "psf__requests-2317", "patch")).isEqualTo("preserved patch");
            assertThat(json.readTree(dir.resolve("run.json").toFile()).path("tasks").get(0).path("status").asText()).isEqualTo("stopped");
        } finally { service.shutdown(); }
    }
    @Test void eventCursorPaginatesAndReturnedRecordsAreIndependent() throws Exception {
        String id = UUID.randomUUID().toString(); Path dir = savedRun(id, "completed");
        StringBuilder lines = new StringBuilder();
        for (int i = 1; i <= 205; i++) lines.append(json.writeValueAsString(Map.of("id", i, "type", "log"))).append('\n');
        Files.writeString(dir.resolve("events.jsonl"), lines);
        EvaluationService service = service();
        try {
            assertThat(service.events(id, 0)).hasSize(200);
            assertThat(service.events(id, 200)).hasSize(5);
            service.get(id).put("status", "tampered");
            assertThat(service.get(id).get("status")).isEqualTo("completed");
        } finally { service.shutdown(); }
    }
    @Test void artifactsAreRestrictedAndSecretsAreRedacted() throws Exception {
        String id = UUID.randomUUID().toString(); Path dir = savedRun(id, "completed");
        Files.createDirectories(dir.resolve("psf__requests-2317"));
        Files.writeString(dir.resolve("psf__requests-2317/test_output.txt"), "unit-test-secret\n" + "x".repeat(16000));
        EvaluationService service = service();
        try {
            assertThat(service.artifact(id, "psf__requests-2317", "tests")).startsWith("[redacted]").hasSize(16011);
            assertThatThrownBy(() -> service.artifact(id, "../outside", "patch")).isInstanceOf(ApiException.class);
            assertThatThrownBy(() -> service.artifact(id, "psf__requests-2317", "../../run.json")).isInstanceOf(ApiException.class);
        } finally { service.shutdown(); }
    }
    @Test void fixedSuiteManifestsAreLoadedWithoutExposingOtherFiles() throws Exception {
        Files.createDirectories(root.resolve("benchmarks"));
        json.writeValue(root.resolve("benchmarks/stage1.json").toFile(), Map.of("id","stage1","instanceIds",List.of("psf__requests-2317")));
        Files.writeString(root.resolve("benchmarks/private.txt"),"not a suite");
        EvaluationService service=service();
        try { assertThat(service.suites()).hasSize(1); assertThat(service.suites().get(0).get("id")).isEqualTo("stage1"); }
        finally { service.shutdown(); }
    }
}
