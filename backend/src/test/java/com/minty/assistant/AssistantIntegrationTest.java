package com.minty.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:minty-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "agent.api-key=test-key", "agent.model=fixture-model", "agent.timeout-seconds=10"
})
@AutoConfigureMockMvc
class AssistantIntegrationTest {
    static final Path root;
    static {
        try {
            root = Files.createTempDirectory("minty-backend-test-");
            Files.writeString(root.resolve("worker.py"), """
                    import json, sys, time
                    from pathlib import Path
                    data = json.load(sys.stdin)
                    if data.get('mode') == 'chat':
                        assert '--workspace' not in sys.argv
                        assert 'checkpointDirectory' not in data and 'projectResources' not in data
                        print(json.dumps({'type':'text','content':'Plain reply'}), flush=True)
                        print(json.dumps({'type':'done','content':''}), flush=True)
                        sys.exit(0)
                    if data['prompt'] == 'inspect project resources':
                        assert data['mode'] == 'project'
                        titles = [item['title'] for item in data['projectResources']]
                        print(json.dumps({'type':'text','content':','.join(titles)}), flush=True)
                        print(json.dumps({'type':'done','content':''}), flush=True)
                        sys.exit(0)
                    if data['prompt'] == 'complete both files':
                        workspace = Path(sys.argv[sys.argv.index('--workspace') + 1])
                        checkpoint = Path(data['checkpointDirectory'])
                        checkpoint.mkdir(exist_ok=True)
                        saved = checkpoint / 'state.json'
                        if data['resume']:
                            assert data['history'] == [{'role': 'user', 'content': 'Keep the original context'}]
                            assert json.loads(saved.read_text())['rounds'] == 1
                            assert (workspace / 'first.txt').read_text() == 'done once'
                            (workspace / 'second.txt').write_text('done after resume')
                            rounds = 2
                        else:
                            (workspace / 'first.txt').write_text('done once')
                            rounds = 1
                        progress = {'rounds': rounds, 'inputTokens': rounds * 12, 'outputTokens': rounds * 7,
                                    'plan': [{'step': 'first', 'status': 'completed'}]}
                        saved.write_text(json.dumps(progress))
                        print(json.dumps({'type':'checkpoint','content':json.dumps(progress)}), flush=True)
                        if not data['resume']:
                            time.sleep(30)
                        print(json.dumps({'type':'recovery','content':json.dumps({'changedCount':0})}), flush=True)
                        print(json.dumps({'type':'done','content':''}), flush=True)
                        sys.exit(0)
                    if data['prompt'] == 'slow':
                        time.sleep(30)
                    if data['prompt'] == 'error':
                        print(json.dumps({'type':'error','content':'controlled failure'}), flush=True)
                        print(json.dumps({'type':'error','content':'controlled failure'}), flush=True)
                        sys.exit(1)
                    for event in [
                      {'type':'phase','content':json.dumps({'phase':'prepare','phaseId':'prepare','label':'Preparing','status':'completed'})},
                      {'type':'model_start','content':json.dumps({'round':1,'model':data['model']})},
                      {'type':'model_end','content':json.dumps({'round':1,'model':data['model'],'inputTokens':12,'outputTokens':7})},
                      {'type':'usage','inputTokens':12,'outputTokens':7}]:
                        print(json.dumps(event), flush=True)
                    if data['prompt'] == 'observe-live':
                        time.sleep(2)
                    workspace = Path(sys.argv[sys.argv.index('--workspace') + 1])
                    (workspace / 'hello.txt').write_text('hello from agent', encoding='utf-8')
                    state = Path(data['metadataDirectory']) if 'metadataDirectory' in data else workspace
                    (state / '.tasks').mkdir(exist_ok=True)
                    (state / '.tasks' / 'task_1.json').write_text(json.dumps({'id':1,'subject':'Write greeting','status':'completed'}))
                    for event in [
                      {'type':'tool_start','content':json.dumps({'name':'write_file','input':{'path':'hello.txt'},'toolUseId':'fixture-tool-1','round':1})},
                      {'type':'tool_result','content':json.dumps({'name':'write_file','output':'OK','toolUseId':'fixture-tool-1','round':1,'status':'completed'})},
                      {'type':'hook','content':json.dumps({'hook':'stop','status':'completed'})},
                      {'type':'changes','content':json.dumps({'totalFiles':1,'files':[{'path':'hello.txt','kind':'created','patch':'+hello from agent'}]})},
                      {'type':'verification','content':json.dumps({'outcome':'unverified','reason':'No tests executed','changedFiles':1,'tests':None})},
                      {'type':'text','content':'Finished!'},
                      {'type':'done','content':''}]:
                        print(json.dumps(event), flush=True)
                    """);
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("agent.worker", () -> root.resolve("worker.py").toString());
        registry.add("agent.workspaces", () -> root.resolve("workspaces").toString());
        registry.add("agent.python", () -> System.getenv().getOrDefault("AGENT_TEST_PYTHON", "python"));
    }
    @Autowired AgentService agent;
    @Autowired WorkspaceService workspaces;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired RunRepository runRepository;
    @Autowired MessageRepository messageRepository;
    @Autowired EventRepository eventRepository;
    @Autowired ProjectService projects;

    @Test void projectSidebarCreatesConversationsInOneDefaultWorkspaceAndKeepsListsLight() throws Exception {
        var project = projects.create("Sidebar project");
        var memory = projects.saveResource(project.id(), null, "memory", "Release decision", "Meeting", "Full body", false);
        String payload = json.writeValueAsString(Map.of("mode", "workspace", "projectId", project.id()));
        String firstBody = mvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var first = json.readTree(firstBody);
        String workspace = first.path("workspaceId").asText();
        String sessionId = first.path("id").asText();
        assertThat(workspace).isNotBlank();
        mvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk()).andExpect(jsonPath("$.workspaceId").value(workspace));
        assertThat(projects.workspaces(project.id())).hasSize(1);
        mvc.perform(get("/api/projects/" + project.id() + "/resource-list"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(memory.id()))
                .andExpect(jsonPath("$[0].content").doesNotExist());
        mvc.perform(get("/api/projects/" + project.id() + "/resources/" + memory.id()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").value("Full body"));
        AgentRun run = new AgentRun(sessionId, "read policy", "fixture-model");
        run.status = "completed";
        runRepository.save(run);
        eventRepository.save(new RunEvent(run.id, "tool_result", "full tool result"));
        eventRepository.save(new RunEvent(run.id, "knowledge", "retained citation"));
        mvc.perform(get("/api/sessions/" + sessionId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.latestRun.id").value(run.id));
        mvc.perform(get("/api/runs/" + run.id + "/events").param("summary", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].type").value("knowledge"));
        mvc.perform(get("/api/runs/" + run.id + "/events"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
    }

    @Test void workspacesShareOnlyTheirOwnProjectResourcesAndKeepFilesSeparate() throws Exception {
        var project = projects.create("Release project");
        var unrelated = projects.create("Unrelated");
        var a = projects.createWorkspace(project.id(), "Source");
        var b = projects.createWorkspace(project.id(), "Experiments");
        var doc = projects.saveResource(project.id(), null, "document", "Release policy", "release.md", "Release evidence.", false);
        var memo = projects.saveResource(project.id(), null, "memory", "Release memory", "", "Use two approvals.", false);
        projects.saveResource(unrelated.id(), null, "memory", "Other project secret", "", "Independent context.", false);
        assertThatThrownBy(() -> agent.createSession("Wrong workspace", "workspace", unrelated.id(), a.id()))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> projects.resource(unrelated.id(), doc.id())).isInstanceOf(ApiException.class);
        ChatSession first = agent.createSession("First", "workspace", project.id(), a.id());
        ChatSession same = agent.createSession("Same files", "workspace", project.id(), a.id());
        ChatSession other = agent.createSession("Other files", "workspace", project.id(), b.id());
        agent.upload(first.id, "unique.txt", new byte[]{65});
        mvc.perform(get("/api/sessions/" + same.id + "/files/content").param("path", "unique.txt"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").value("A"));
        mvc.perform(get("/api/sessions/" + other.id + "/files/content").param("path", "unique.txt"))
                .andExpect(status().isNotFound());
        AgentRun running = agent.start(first.id, "slow");
        try {
            assertThatThrownBy(() -> agent.start(same.id, "must wait")).isInstanceOf(ApiException.class);
            assertThatThrownBy(() -> agent.upload(same.id, "late.txt", new byte[]{66})).isInstanceOf(ApiException.class);
        } finally {
            agent.stop(running.id);
            awaitRun(running.id);
        }
        AgentRun run = agent.start(other.id, "inspect project resources");
        assertThat(awaitRun(run.id).status).isEqualTo("completed");
        assertThat(agent.events(run.id, 0).stream().filter(e -> e.type.equals("text")).findFirst().orElseThrow().content)
                .contains("Release policy", "Release memory").doesNotContain("Other project secret");
        mvc.perform(put("/api/projects/" + project.id() + "/resources/" + memo.id())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"kind":"memory","title":"New decision","content":"Approved text","source":"Meeting"}
                                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("New decision"));
        mvc.perform(delete("/api/projects/" + project.id() + "/resources/" + memo.id())).andExpect(status().isNoContent());
        mvc.perform(get("/api/projects/" + project.id() + "/resources/" + memo.id())).andExpect(status().isNotFound());
    }

    @Test void linkedDirectoryIsUsedByFileEndpointsAndAgentButRetainedOnDelete() throws Exception {
        Path directory = Files.createTempDirectory(root, "local-source-").toRealPath();
        Files.writeString(directory.resolve("existing.txt"), "original source");
        Files.writeString(directory.resolve(".env"), "LOCAL_TEST_FIXTURE=hidden");
        mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", "invalid directory", "localDirectory", "relative/path"))))
                .andExpect(status().isBadRequest());
        String projectId = json.readTree(mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", "local project", "localDirectory", "\"" + directory + "\""))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.localDirectory").value(directory.toString()))
                .andReturn().getResponse().getContentAsString()).get("id").asText();
        ChatSession first = agent.createSession("first local chat", "workspace", projectId, null);
        ChatSession second = agent.createSession("second local chat", "workspace", projectId, null);
        ChatSession ordinary = agent.createSession("separate chat", "chat", null, null);
        String projectApi = "/api/projects/" + projectId;
        String firstApi = "/api/sessions/" + first.id;
        String secondApi = "/api/sessions/" + second.id;
        String directoryJson = json.writeValueAsString(Map.of("localDirectory", directory.toString()));
        var resource = projects.saveResource(projectId, null, "memory", "decision", "", "local", false);
        mvc.perform(get(projectApi + "/files/content").param("path", "existing.txt"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").value("original source"));
        mvc.perform(get(firstApi + "/files")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].path").value("existing.txt"));
        mvc.perform(get(firstApi + "/files/content").param("path", "existing.txt"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").value("original source"));
        mvc.perform(get(firstApi + "/files/content").param("path", ".env")).andExpect(status().isForbidden());
        AgentRun running = agent.start(first.id, "observe-live");
        try {
            mvc.perform(delete(firstApi)).andExpect(status().isConflict());
            mvc.perform(delete(projectApi)).andExpect(status().isConflict());
            mvc.perform(put(projectApi + "/directory").contentType(MediaType.APPLICATION_JSON).content(directoryJson))
                    .andExpect(status().isConflict());
        } finally {
            assertThat(awaitRun(running.id).status).isEqualTo("completed");
        }
        assertThat(Files.readString(directory.resolve("hello.txt"))).isEqualTo("hello from agent");
        assertThat(directory.resolve(".tasks")).doesNotExist();
        mvc.perform(get(firstApi + "/tasks")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].subject").value("Write greeting"));
        mvc.perform(delete(firstApi)).andExpect(status().isNoContent());
        mvc.perform(get(firstApi)).andExpect(status().isNotFound());
        mvc.perform(get("/api/runs/" + running.id)).andExpect(status().isNotFound());
        assertThat(agent.recentRuns(null)).extracting(r -> r.id).doesNotContain(running.id);
        mvc.perform(put(projectApi + "/directory").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.localDirectory").doesNotExist());
        mvc.perform(get(secondApi + "/files")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(put(projectApi + "/directory").contentType(MediaType.APPLICATION_JSON).content(directoryJson))
                .andExpect(status().isOk());
        mvc.perform(delete(projectApi)).andExpect(status().isNoContent());
        mvc.perform(get(secondApi)).andExpect(status().isNotFound());
        mvc.perform(get(projectApi + "/resources/" + resource.id())).andExpect(status().isNotFound());
        assertThat(projects.list()).extracting(ProjectService.ProjectInfo::id).doesNotContain(projectId);
        assertThat(awaitRun(agent.start(ordinary.id, "Hello").id).status).isEqualTo("completed");
        assertThat(agent.sessions()).extracting(s -> s.id).contains(ordinary.id).doesNotContain(first.id, second.id);
        assertThat(Files.readString(directory.resolve("existing.txt"))).isEqualTo("original source");
        assertThat(directory.resolve("hello.txt")).exists();
    }

    @Test void sidebarListsOnlyChatsWithAUserMessageEvenAfterAnUpload() throws Exception {
        String projectId = projects.create("draft project").id();
        ChatSession draft = agent.createSession(null, "workspace", projectId, null);
        ChatSession plainDraft = agent.createSession(null, "chat", null, null);
        agent.upload(draft.id, "first.txt", new byte[]{65});
        assertThat(agent.sessions()).extracting(s -> s.id).doesNotContain(draft.id, plainDraft.id);
        AgentRun run = agent.start(draft.id, "First message");
        assertThat(agent.sessions()).extracting(s -> s.id).contains(draft.id).doesNotContain(plainDraft.id);
        assertThat(agent.requireSession(draft.id).title).isEqualTo("First message");
        awaitRun(run.id);
    }

    @Test void folderPickerListsNavigableDirectoriesWithoutFilesOrManagedState() throws Exception {
        Path location = Files.createTempDirectory(root, "folder-picker-").toRealPath();
        Path child = Files.createDirectory(location.resolve("Example Folder"));
        Files.createDirectory(location.resolve(".hidden"));
        Files.writeString(location.resolve("readme.txt"), "fixture");
        mvc.perform(get("/api/projects/directories"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.selectable").value(false));
        mvc.perform(get("/api/projects/directories").param("path", location.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.selectable").value(true))
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].path").value(child.toString()));
        mvc.perform(get("/api/projects/directories").param("path", child.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.parent").value(location.toString()));
        mvc.perform(get("/api/projects/directories").param("path", root.resolve("workspaces").toString()))
                .andExpect(status().isForbidden());
    }

    @Test void ordinaryChatUsesNoFileScopeOrProjectWorkerContext() throws Exception {
        String body = mvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Plain\",\"mode\":\"chat\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("chat"))
                .andReturn().getResponse().getContentAsString();
        String id = json.readTree(body).path("id").asText();
        mvc.perform(get("/api/sessions/" + id + "/files")).andExpect(status().isConflict());
        assertThatThrownBy(() -> agent.upload(id, "test.txt", new byte[]{65})).isInstanceOf(ApiException.class);
        AgentRun run = agent.start(id, "Hello");
        assertThat(awaitRun(run.id).status).isEqualTo("completed");
        assertThat(Files.exists(root.resolve("workspaces").resolve(id))).isFalse();
    }

    @Test void completeRunPersistsMessagesEventsFilesTasksAndUsage() throws Exception {
        ChatSession session = agent.createSession(null);
        AgentRun run = agent.start(session.id, "write a greeting");
        AgentRun finished = awaitRun(run.id);
        assertThat(finished.status).isEqualTo("completed");
        assertThat(finished.inputTokens).isEqualTo(12);
        assertThat(finished.outputTokens).isEqualTo(7);
        assertThat(finished.prompt).isEqualTo("write a greeting");
        assertThat(finished.model).isEqualTo("fixture-model");
        assertThat(json.readTree(finished.verification).path("outcome").asText()).isEqualTo("unverified");
        assertThat(json.readTree(finished.changes).path("totalFiles").asInt()).isEqualTo(1);
        assertThat(agent.requireSession(session.id).title).isEqualTo("write a greeting");
        List<?> messages = (List<?>) agent.session(session.id).get("messages");
        assertThat(messages).hasSize(2);
        assertThat(agent.events(run.id, 0)).extracting(e -> e.type).contains("phase", "model_start", "model_end", "tool_start", "tool_result", "text", "status");
        assertThat(workspaces.content(session.id, "hello.txt").get("content")).isEqualTo("hello from agent");
        assertThat(workspaces.tasks(session.id)).hasSize(1);
        assertThat(workspaces.files(session.id)).hasSize(1);
        assertThat((Long) agent.stats().get("totalTokens")).isGreaterThanOrEqualTo(19);
    }
    @Test void concurrentSessionRunIsRejectedAndStopIsTerminal() throws Exception {
        ChatSession session = agent.createSession(null);
        AgentRun run = agent.start(session.id, "slow");
        try {
            assertThatThrownBy(() -> agent.start(session.id, "second task"))
                    .isInstanceOf(ApiException.class).hasMessageContaining("正在运行");
        } finally { agent.stop(run.id); }
        assertThat(awaitRun(run.id).status).isEqualTo("stopped");
        Thread.sleep(150);
        assertThat(agent.requireRun(run.id).status).isEqualTo("stopped");
    }
    @Test void workerErrorBecomesFailedRun() throws Exception {
        ChatSession session = agent.createSession(null);
        AgentRun run = agent.start(session.id, "error");
        AgentRun finished = awaitRun(run.id);
        assertThat(finished.status).isEqualTo("failed");
        assertThat(finished.error).contains("controlled failure");
        assertThat(agent.events(run.id, 0)).filteredOn(e -> e.type.equals("error")).hasSize(1);
    }
    @Test void pauseAndResumeKeepOneRunOneUserMessageAndTheOriginalBudget() throws Exception {
        ChatSession session = agent.createSession(null);
        messageRepository.save(new ChatMessage(session.id, "user", "Keep the original context"));
        AgentRun run = agent.start(session.id, "complete both files");
        Instant deadline = Instant.now().plusSeconds(6);
        try {
            while (agent.requireRun(run.id).roundsUsed == 0 && Instant.now().isBefore(deadline)) Thread.sleep(25);
            assertThat(agent.requireRun(run.id).roundsUsed).isEqualTo(1);
        } finally {
            mvc.perform(post("/api/runs/" + run.id + "/pause")).andExpect(status().isOk());
        }
        AgentRun paused = awaitRun(run.id);
        assertThat(paused.status).isEqualTo("interrupted");
        assertThat(paused.resumable).isTrue();
        assertThat(paused.elapsedMillis).isPositive();
        mvc.perform(post("/api/runs/" + run.id + "/resume")).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(run.id));
        AgentRun finished = awaitRun(run.id);
        assertThat(finished.status).isEqualTo("completed");
        assertThat(finished.roundsUsed).isEqualTo(2);
        assertThat(finished.inputTokens + finished.outputTokens).isEqualTo(38);
        assertThat(finished.elapsedMillis).isGreaterThanOrEqualTo(paused.elapsedMillis);
        assertThat(workspaces.content(session.id, "first.txt").get("content")).isEqualTo("done once");
        assertThat(workspaces.content(session.id, "second.txt").get("content")).isEqualTo("done after resume");
        assertThat(messageRepository.findBySessionIdOrderByCreatedAtAsc(session.id))
                .extracting(message -> message.content).containsExactly("Keep the original context", "complete both files");
        assertThat(agent.events(run.id, 0)).extracting(e -> e.type).contains("recovery");
    }
    @Test void startupRecoversSavedWorkAndRejectsALegacyRunWithoutCheckpoint() throws Exception {
        ChatSession session = agent.createSession(null);
        AgentRun legacy = new AgentRun(session.id, "legacy", "fixture-model");
        AgentRun run = new AgentRun(session.id, "complete both files", "fixture-model");
        run.maxRounds = 24;
        run.roundsUsed = 0;
        run.maxTokens = 400000;
        run.timeLimitSeconds = 10;
        run.elapsedMillis = 0L;
        run.attemptStartedAt = Instant.now().minusMillis(200);
        runRepository.saveAll(List.of(legacy, run));
        Path folder = Files.createDirectory(workspaces.checkpoint(run.id));
        Files.writeString(folder.resolve("state.json"), "{\"rounds\":1,\"inputTokens\":12,\"outputTokens\":7}");
        agent.recoverInterruptedRuns();
        AgentRun recovered = agent.requireRun(run.id);
        assertThat(recovered.status).isEqualTo("interrupted");
        assertThat(recovered.resumable).isTrue();
        assertThat(recovered.roundsUsed).isEqualTo(1);
        assertThat(recovered.elapsedMillis).isGreaterThanOrEqualTo(200);
        assertThat(agent.requireRun(legacy.id).status).isEqualTo("failed");
    }
    @Test void recentRunApiIncludesPromptModelAndFiltersBySession() throws Exception {
        ChatSession first = agent.createSession("first session");
        AgentRun firstRun = agent.start(first.id, "first task");
        awaitRun(firstRun.id);
        ChatSession second = agent.createSession("second session");
        AgentRun secondRun = agent.start(second.id, "second task");
        awaitRun(secondRun.id);
        mvc.perform(get("/api/runs").param("sessionId", first.id))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(firstRun.id))
                .andExpect(jsonPath("$[0].prompt").value("first task"))
                .andExpect(jsonPath("$[0].model").value("fixture-model"));
        assertThat(agent.recentRuns(null)).extracting(r -> r.id).contains(firstRun.id, secondRun.id);
        assertThat(agent.recentRuns(first.id)).extracting(r -> r.id).containsExactly(firstRun.id);
        mvc.perform(get("/api/runs").param("sessionId", UUID.randomUUID().toString())).andExpect(status().isNotFound());
    }
    @Test void runHistoryIsBoundedAndSupportsLegacyNullMetadata() throws Exception {
        ChatSession session = agent.createSession("bounded history");
        List<AgentRun> seeded = new ArrayList<>();
        for (int i = 0; i < 55; i++) {
            AgentRun run = new AgentRun(session.id, i == 54 ? null : "task " + i, null);
            run.status = "completed";
            run.createdAt = Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i);
            run.finishedAt = run.createdAt;
            seeded.add(run);
        }
        runRepository.saveAll(seeded);
        List<AgentRun> recent = agent.recentRuns(session.id);
        assertThat(recent).hasSize(50);
        assertThat(recent.get(0).id).isEqualTo(seeded.get(54).id);
        assertThat(recent.get(0).prompt).isNull();
        assertThat(recent.get(0).model).isNull();
        assertThat(recent.get(49).id).isEqualTo(seeded.get(5).id);
        assertThat(agent.recentRuns(null)).hasSize(50);
    }
    @Test void lifecycleEventsAndTokenCountsAreVisibleWhileWorkerIsStillRunning() throws Exception {
        ChatSession session = agent.createSession(null);
        AgentRun run = agent.start(session.id, "observe-live");
        try {
            Instant deadline = Instant.now().plusSeconds(6);
            AgentRun observed;
            do {
                observed = agent.requireRun(run.id);
                if (observed.inputTokens > 0) break;
                Thread.sleep(30);
            } while (Instant.now().isBefore(deadline));
            assertThat(observed.status).isEqualTo("running");
            assertThat(observed.inputTokens).isEqualTo(12);
            assertThat(observed.outputTokens).isEqualTo(7);
            mvc.perform(get("/api/runs/" + run.id)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("running"))
                    .andExpect(jsonPath("$.inputTokens").value(12))
                    .andExpect(jsonPath("$.outputTokens").value(7));
            List<RunEvent> observedEvents = agent.events(run.id, 0);
            assertThat(observedEvents).extracting(e -> e.type).contains("phase", "model_start", "model_end");
            RunEvent phase = observedEvents.stream().filter(e -> e.type.equals("phase")).findFirst().orElseThrow();
            assertThat(json.readTree(phase.content).path("phaseId").asText()).isEqualTo("prepare");
            RunEvent modelStart = observedEvents.stream().filter(e -> e.type.equals("model_start")).findFirst().orElseThrow();
            assertThat(json.readTree(modelStart.content).path("round").asInt()).isEqualTo(1);
        } finally { agent.stop(run.id); }
    }
    @Test void filePreviewCannotEscapeSessionOrReadHiddenFiles() throws Exception {
        ChatSession session = agent.createSession(null);
        Path workspace = workspaces.workspace(session.id);
        Files.writeString(workspace.resolve(".env"), "SECRET=keep-private");
        assertThatThrownBy(() -> workspaces.content(session.id, "../outside.txt")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> workspaces.content(session.id, ".env")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> workspaces.content(session.id, root.toAbsolutePath().toString())).isInstanceOf(ApiException.class);
        assertThat(workspaces.files(session.id)).isEmpty();
    }
    @Test void apiRejectsBlankPromptsAndUntrustedBrowserOrigins() throws Exception {
        ChatSession session = agent.createSession(null);
        mvc.perform(post("/api/sessions/" + session.id + "/runs").contentType(MediaType.APPLICATION_JSON).content("{\"prompt\":\"  \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/sessions").header("Origin", "https://untrusted.example").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/status")).andExpect(status().isOk()).andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.databaseReady").value(true)).andExpect(jsonPath("$.ready").value(true))
                .andExpect(jsonPath("$.apiKey").doesNotExist());
    }
    @Test void uploadAcceptsUtf8CodeAndRejectsEscapeBinaryAndActiveRunWrites() throws Exception {
        ChatSession session = agent.createSession(null);
        mvc.perform(multipart("/api/sessions/" + session.id + "/files")
                        .file(new MockMultipartFile("file", "hello.py", "text/plain", "print('你好')".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        .param("path", "src/hello.py"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.path").value("src/hello.py"));
        assertThat(workspaces.content(session.id, "src/hello.py").get("content")).contains("你好");
        assertThatThrownBy(() -> agent.upload(session.id, "../outside.py", new byte[]{65})).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> agent.upload(session.id, ".env", new byte[]{65})).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> agent.upload(session.id, "binary.png", new byte[]{0, 1})).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> agent.upload(session.id, "invalid.txt", new byte[]{(byte) 0xff})).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> agent.upload(session.id, "large.txt", new byte[1048577])).isInstanceOf(ApiException.class);
        AgentRun run = agent.start(session.id, "slow");
        try { assertThatThrownBy(() -> agent.upload(session.id, "late.txt", new byte[]{65})).isInstanceOf(ApiException.class).hasMessageContaining("停止"); }
        finally { agent.stop(run.id); }
    }
    @Test void evaluationApiValidatesBudgetAndDoesNotExposeOtherFiles() throws Exception {
        mvc.perform(get("/api/evaluations")).andExpect(status().isOk());
        mvc.perform(get("/api/evaluations/not-found")).andExpect(status().isNotFound());
        mvc.perform(post("/api/evaluations").contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"invalid", "limit":301, "maxSteps":24, "timeoutSeconds":1200,
                 "testTimeoutSeconds":900, "instanceIds":["../../secret"]}
                """)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/evaluations").header("Origin", "https://untrusted.example")
                .contentType(MediaType.APPLICATION_JSON).content("{}" )).andExpect(status().isForbidden());
    }
    private AgentRun awaitRun(String id) throws Exception {
        Instant deadline = Instant.now().plusSeconds(8);
        AgentRun run;
        do { run = agent.requireRun(id); if (!run.status.equals("running")) return run; Thread.sleep(40); }
        while (Instant.now().isBefore(deadline));
        throw new AssertionError("Run did not finish: " + id);
    }
}
