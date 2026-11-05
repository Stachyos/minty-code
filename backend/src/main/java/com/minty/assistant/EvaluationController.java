package com.minty.assistant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.*;

@RestController
@RequestMapping("/api/evaluations")
public class EvaluationController {
    private final EvaluationService service;
    EvaluationController(EvaluationService service) { this.service = service; }
    @GetMapping("/environment") public Object environment() { return service.environment(); }
    @GetMapping("/catalog") public Object catalog() throws IOException, InterruptedException { return service.catalog(); }
    @GetMapping("/suites") public Object suites() throws IOException { return service.suites(); }
    @GetMapping public Object list() { return service.list(); }
    @PostMapping("/setup") public Object setup() throws IOException { return service.start(null, true); }
    @PostMapping public Object start(@Valid @RequestBody Request request) throws IOException { return service.start(request, false); }
    @GetMapping("/{id}") public Object get(@PathVariable String id) { return service.get(id); }
    @GetMapping("/{id}/events") public Object events(@PathVariable String id, @RequestParam(defaultValue="0") @Min(0) long after) throws IOException { return service.events(id, after); }
    @PostMapping("/{id}/stop") public Object stop(@PathVariable String id) throws IOException { return service.stop(id); }
    @GetMapping("/{id}/artifacts") public Object artifact(@PathVariable String id, @RequestParam String instance, @RequestParam String kind) throws IOException {
        return Map.of("content", service.artifact(id, instance, kind));
    }
    public record Request(@NotBlank @Size(max=100) String name, @Min(1) @Max(300) int limit,
                          @Size(max=300) List<@Pattern(regexp="[A-Za-z0-9_.-]+__[A-Za-z0-9_.-]+-[0-9]+") String> instanceIds,
                          @Min(1) @Max(40) int maxSteps, @Min(60) @Max(7200) int timeoutSeconds,
                          @Min(60) @Max(7200) int testTimeoutSeconds,
                          @Pattern(regexp="[a-zA-Z0-9-]*") String suiteId) {}
}
