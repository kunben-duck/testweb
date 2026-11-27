/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.workbench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.config.WorkbenchProperties;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.infrastructure.persistence.WorkbenchMapper;
import com.huawei.it.ex.one.infrastructure.runtime.domainagent.DomainAgentResponseNormalizer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

class WorkbenchTaskWorkflowTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final WorkbenchTaskJson j = new WorkbenchTaskJson(mapper);
    private final WorkbenchMapper db = mock(WorkbenchMapper.class);
    private final WorkbenchProperties config = new WorkbenchProperties();
    private final WorkbenchTaskLedger ledger = new WorkbenchTaskLedger(db, j, mock(WorkbenchTaskSignals.class), new WorkbenchTaskExtensions(db, j));
    private final WorkbenchTaskAccess.Caller a = caller("A");
    private final AtomicReference<String> snapshot = new AtomicReference<>();
    private final Map<String, Map<String, Object>> batches = new HashMap<>();
    private final Map<String, String> steps = new HashMap<>();
    private final Map<String, Map<String, Object>> receipts = new HashMap<>();
    private final Map<Integer, String> parts = new HashMap<>();
    private Map<String, Object> created;
    private Map<String, Object> operation;
    private long seq;

    @BeforeEach
    void store() {
        config.setClients(Map.of("A", a.grant(), "B", caller("B").grant()));
        when(db.insertTask(anyMap())).thenAnswer(i -> {
            Map<String, Object> p = i.getArgument(0);
            created = Map.of("task_id", p.get("taskId"), "create_hash", p.get("hash"), "create_ack_json", p.get("ack"));
            snapshot.set((String)p.get("json"));
            return 1;
        });
        when(db.findCreation(anyMap())).thenAnswer(i -> created);
        when(db.findEvent(anyMap())).thenAnswer(i -> receipts.get(i.<Map<String, Object>>getArgument(0).get("eventId")));
        when(db.insertEvent(anyMap())).thenAnswer(i -> {
            Map<String, Object> p = i.getArgument(0);
            receipts.put((String)p.get("eventId"), Map.of("payload_hash", p.get("hash"), "ack_json", p.get("ack")));
            return 1;
        });
        when(db.lockTask(anyMap())).thenAnswer(i -> snapshot.get());
        when(db.shareTask(anyMap())).thenAnswer(i -> snapshot.get());
        when(db.updateTask(anyMap())).thenAnswer(i -> {
            snapshot.set((String)i.<Map<String, Object>>getArgument(0).get("json"));
            return 1;
        });
        when(db.findStep(anyMap())).thenAnswer(i -> steps.get(i.<Map<String, Object>>getArgument(0).get("id")));
        when(db.saveStep(anyMap())).thenAnswer(i -> {
            Map<String, Object> key = i.getArgument(0);
            steps.put((String)key.get("id"), (String)key.get("json"));
            return 1;
        });
        when(db.lockStream(anyMap())).thenAnswer(i -> seq);
        when(db.stepCounts(anyMap())).thenAnswer(i -> Map.of("total", steps.size(), "completed", 0));
        when(db.operation(anyMap())).thenAnswer(i -> operation);
        when(db.reserveOperation(anyMap())).thenAnswer(i -> {
            Map<String, Object> key = i.getArgument(0);
            operation = Map.of("agent_id", key.get("agent"), "assignment_version", key.get("assignment"),
                    "last_frame_no", 0, "status", "RUNNING", "scope_key", key.get("scope"));
            return 1;
        });
        when(db.saveOperation(anyMap())).thenAnswer(i -> {
            Map<String, Object> p = i.getArgument(0);
            operation = Map.of("agent_id", p.get("agent"), "assignment_version", p.get("assignment"),
                    "checkpoint_json", p.get("checkpoint"), "last_frame_no", p.get("last"),
                    "status", p.get("status"), "scope_key", p.get("scope"), "content", p.get("content"));
            return 1;
        });
        when(db.savePart(anyMap())).thenAnswer(i -> {
            Map<String, Object> p = i.getArgument(0);
            parts.put((Integer)p.get("ord"), (String)p.get("json"));
            return 1;
        });
        when(db.frame(anyMap())).thenAnswer(i -> batches.get(String.valueOf(i.<Map<String,Object>>getArgument(0).get("first"))));
        when(db.insertFrame(anyMap())).thenAnswer(i -> {
            Map<String, Object> p = i.getArgument(0);
            seq = (Long)p.get("seq");
            batches.put(String.valueOf(p.get("first")), Map.of("payload_hash", p.get("hash"), "last_frame_no",
                    p.get("last"), "last_output_seq", seq));
            return 1;
        });
    }
    @Test
    void offlineCreationIsIdempotentAndHandoffFencesPreviousAgent() {
        var service = new WorkbenchTaskCreation(ledger, new WorkbenchTaskAccess(config, j));
        var result = service.create(a, body());
        assertThat(result.created()).isTrue();
        String id = result.response().path("taskId").asText();
        assertThat(service.create(a, body()).response().toString()).isEqualTo(result.response().toString());
        assertThatThrownBy(() -> service.create(a, body().put("title", "Other"))).hasMessage("IDEMPOTENCY_CONFLICT");
        var sync = new WorkbenchTaskStateChanges(ledger, config);
        sync.sync(a, id, change("step.upsert", """
                {"stepId":"s1","title":"Check","sortOrder":0,"status":"RUNNING","executingAgents":[{"id":"A","name":"A"}]}
                """, 1));
        sync.sync(a, id, change("step.upsert", "{\"stepId\":\"s2\",\"title\":\"Review\",\"sortOrder\":1,\"status\":\"RUNNING\"}", 1));
        sync.sync(a, id, change("task.assigned", "{\"responsibleAgent\":{\"id\":\"B\",\"name\":\"Other\"}}", 1));
        assertThatThrownBy(() -> sync.sync(a, id, change("task.updated", "{\"status\":\"COMPLETED\"}", 1)))
                .isInstanceOf(WorkbenchTaskFault.class);
        assertThatThrownBy(() -> sync.sync(a, id, change("step.updated", "{\"stepId\":\"s1\",\"status\":\"COMPLETED\"}", 2)))
                .hasMessage("TASK_ACCESS_DENIED");
        assertThat(sync.sync(caller("B"), id, change("task.updated", "{\"status\":\"COMPLETED\"}", 2))
                .path("taskVersion").longValue()).isEqualTo(5);
        assertThat(steps).hasSize(2);
    }
    @Test
    void completeJsonBatchesResumeParserAndAssemblerWithoutChangingTaskStatusOrVersion() {
        String id = new WorkbenchTaskCreation(ledger, new WorkbenchTaskAccess(config, j)).create(a, body()).response().path("taskId").textValue();
        var first = frames(1, "[{\"content\":\"<thi\"}]");
        var second = frames(2, "[{\"content\":\"nk>work</thi\"},{\"type\":\"heartbeat\"}]");
        var third = frames(4, "[{\"content\":\"nk>answer\",\"endFlag\":true}]");
        var baseline = new DomainAgentResponseNormalizer(mapper);
        int firstEvents = baseline.normalizeCallbackFrame(null, null, first.path("frames").get(0),
                baseline.newStreamState()).size();
        assertThat(output().append(a, id, first).path("lastOutputSeq").asLong()).isEqualTo(firstEvents);
        var accepted = output().append(a, id, second);
        assertThat(output().append(a, id, second)).isEqualTo(accepted);
        assertThatThrownBy(() -> output().append(a, id, frames(5, "[{}]"))).hasMessage("FRAME_GAP");
        output().append(a, id, third);
        assertThat(operation).containsEntry("content", "answer").containsEntry("status", "COMPLETED");
        assertThat(parts.values()).anyMatch(p -> p.contains("THINKING") && p.contains("work"));
        assertThat(output().append(a, id, third).path("lastOutputSeq").longValue()).isEqualTo(seq);
        assertThat(j.read(snapshot.get()).path("taskVersion").longValue()).isOne();
        assertThat(j.read(snapshot.get()).path("status").textValue()).isEqualTo("PENDING");
    }
    @Test
    void unregisteredIdentityCannotReadOrControlATask() {
        var t = body().put("taskId", "T");
        snapshot.set(t.toString());
        var outsider = new UserContext("tenant", "stranger", "Other");
        assertThatThrownBy(() -> new WorkbenchTaskQueries(ledger).process(outsider, "T", "task")).hasMessage("TASK_RESOURCE_NOT_FOUND");
        assertThatThrownBy(() -> new WorkbenchTaskActions(ledger).submit(outsider, "T", j.object())).hasMessage("TASK_RESOURCE_NOT_FOUND");
        assertThatThrownBy(() -> new WorkbenchTaskAccess(config, j).authenticate("client", "Bearer bad")).isInstanceOf(WorkbenchTaskFault.class);
    }
    private WorkbenchTaskOutputs output() {
        return new WorkbenchTaskOutputs(ledger, mapper, new DomainAgentResponseNormalizer(mapper), config);
    }

    @Test
    void completedTaskOutputMatchesDocumentedHistoryIncludingHiddenAnswerPart() throws Exception {
        var paths = WorkbenchDocumentationTest.api("tasks").required("paths");
        var batch = paths.path("/v1/internal/tasks/{taskId}/sync").path("post")
                .at("/requestBody/content/application~1json/examples/task/value");
        var history = paths.path("/v1/chat/tasks/{taskId}/process").path("get")
                .at("/responses/200/content/application~1json/examples/completed/value");
        var receipt = new WorkbenchTaskCreation(ledger, new WorkbenchTaskAccess(config, j)).create(a, body());
        var ack = output().append(a, receipt.response().path("taskId").asText(), (ObjectNode)batch);
        assertThat(ack.path("lastOutputSeq").asInt()).isEqualTo(history.required("lastOutputSeq").asInt());
        assertThat(operation).containsEntry("content", history.at("/operations/0/content").asText());
        var expected = (ObjectNode)history.at("/operations/0/parts/0").deepCopy();
        var actual = j.read(parts.get(expected.required("partOrder").asInt()));
        ((ObjectNode)actual.required("payload")).remove("serverTimestampMs");
        ((ObjectNode)expected.required("payload")).remove("serverTimestampMs");
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void documentedCreateAndPlanningBatchMatchReceiptAndPublicChangeProjection() throws Exception {
        var paths = WorkbenchDocumentationTest.api("agent").required("paths");
        var create = paths.required("/v1/internal/tasks").required("post");
        var grant = new WorkbenchProperties.Client("tenant", "", Set.of("finance"), Set.of("ledger-a"),
                Set.of("u1", "u2"), Map.of());
        var sender = new WorkbenchTaskAccess.Caller("product-a", grant);
        var service = new WorkbenchTaskCreation(ledger, new WorkbenchTaskAccess(config, j));
        var request = (ObjectNode) create.at("/requestBody/content/application~1json/examples/EXTERNAL/value");
        var receipt = service.create(sender, request);
        var sample = create.at("/responses/201/content/application~1json/examples/external/value");
        String id = receipt.response().path("taskId").asText();
        assertThat(id).matches("task_[a-f0-9]{32}");
        assertThat(mapper.readTree(receipt.response().deepCopy().put("taskId", sample.path("taskId").asText()).toString()))
                .isEqualTo(sample);
        assertThat(service.create(sender, request).created()).isFalse();
        var sync = paths.required("/v1/internal/tasks/{taskId}/sync").required("post");
        var batch = (ObjectNode) sync.at("/requestBody/content/application~1json/examples/plan/value");
        var group = new AtomicReference<String>();
        when(db.saveGroup(anyMap())).thenAnswer(i -> {
            group.set((String)i.<Map<String, Object>>getArgument(0).get("json"));
            return 1;
        });
        when(db.findGroup(anyMap())).thenAnswer(i -> group.get());
        var ack = new WorkbenchTaskStateChanges(ledger, config).sync(sender, id, batch);
        assertThat(mapper.readTree(ack.put("taskId", sample.path("taskId").asText()).toString()))
                .isEqualTo(sync.at("/responses/200/content/application~1json/examples/state/value"));
        ArgumentCaptor<Map<String, Object>> rows = ArgumentCaptor.captor();
        verify(db, atLeastOnce()).insertEvent(rows.capture());
        var notices = mapper.readTree(((String)rows.getValue().get("changes")).replace(id, sample.path("taskId").asText()));
        assertThat(WorkbenchDocumentationTest.examples("frontend")).anySatisfy(frame ->
                assertThat(frame.at("/details/event/changes")).isEqualTo(notices));
    }
    private ObjectNode frames(long first, String batch) {
        var req = j.object().put("scope", "TASK").put("operationId", "op").put("firstFrameNo", first)
                .put("assignmentVersion", 1L).put("kind", "OUTPUT_FRAMES");
        return req.set("frames", j.parse(batch));
    }
    private ObjectNode change(String type, String data, long assignment) {
        var req = j.object().put("eventId", WorkbenchTaskJson.newId("evt")).put("assignmentVersion", assignment);
        req.putArray("changes").addObject().put("type", type).set("data", j.read(data));
        return req;
    }
    private ObjectNode body() {
        return j.read("""
                {"channel":"EXTERNAL","requestId":"req","locale":"zh","status":"PENDING","title":"月结",
                 "owner":{"id":"u","name":"User"},"sourceProduct":{"id":"finance","name":"Finance"},
                 "l1":{"id":"audit","name":"Review"},"responsibleAgent":{"id":"A","name":"Agent"},"executingAgents":[],
                 "creator":"Finance","agentExecutionStatus":"IDLE"}
                """);
    }
    private WorkbenchTaskAccess.Caller caller(String agent) {
        return new WorkbenchTaskAccess.Caller(agent, new WorkbenchProperties.Client("tenant", "", Set.of("finance"),
                Set.of(agent), Set.of("u"), Map.of()));
    }
}
