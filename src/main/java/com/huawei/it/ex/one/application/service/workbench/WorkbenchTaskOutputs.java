/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.workbench;

import static com.huawei.it.ex.one.application.service.workbench.WorkbenchTaskJson.args;

import com.huawei.it.ex.one.application.config.WorkbenchProperties;
import com.huawei.it.ex.one.application.service.chat.WorkbenchTaskDisplayAssembly;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatMessagePart;
import com.huawei.it.ex.one.domain.chat.ChatMessagePartDraft;
import com.huawei.it.ex.one.infrastructure.runtime.domainagent.DomainAgentResponseNormalizer;
import com.huawei.it.ex.one.infrastructure.runtime.domainagent.DomainAgentResponseNormalizer.DomainAgentStreamState;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

@Service
public class WorkbenchTaskOutputs {
    private record Cursor(DomainAgentStreamState.DisplayCursor parser, WorkbenchTaskDisplayAssembly.Cursor assembly, long inputBytes) { }
    private record Sender(String agent, boolean closed) { }
    private final WorkbenchTaskLedger ledger;
    private final ObjectMapper mapper;
    private final DomainAgentResponseNormalizer normalizer;
    private final WorkbenchProperties limits;

    public WorkbenchTaskOutputs(WorkbenchTaskLedger ledger, ObjectMapper mapper, DomainAgentResponseNormalizer normalizer,
                       WorkbenchProperties limits) {
        this.ledger = ledger;
        this.mapper = mapper;
        this.normalizer = normalizer;
        this.limits = limits;
    }

    @Transactional
    public ObjectNode append(WorkbenchTaskAccess.Caller c, String id, ObjectNode body) {
        ObjectNode task = ledger.load(c.tenant(), id, false);
        c.app(WorkbenchTaskModel.app(task));
        WorkbenchTaskLedger.assignment(task, body);
        String scope = scope(body);
        Sender sender = authorize(c, task, scope, body);
        String op = WorkbenchTaskJson.id(body, "operationId");
        long first = WorkbenchTaskJson.positive(body, "firstFrameNo");
        var key = args("taskId", id, "scope", scope, "operation", op, "first", first, "agent", sender.agent(),
                "assignment", task.path("assignmentVersion").longValue());
        Long start = ledger.db.lockStream(key);
        if (start == null) {
            ledger.db.ensureStream(key);
            start = ledger.db.lockStream(key);
        }
        key.put("startSeq", start + 1);
        var old = ledger.db.operation(key);
        if (old == null) {
            ledger.db.reserveOperation(key);
            old = ledger.db.operation(key);
        }
        if (!sameOperation(old, scope, sender.agent(), task)) throw WorkbenchTaskJson.conflict("OPERATION_MISMATCH");
        var duplicate = ledger.db.frame(key);
        String hash = ledger.json.hash(body);
        if (duplicate != null) {
            if (!hash.equals(duplicate.get("payload_hash"))) throw WorkbenchTaskJson.conflict("FRAME_CONFLICT");
            return ack(number(duplicate, "last_frame_no"), number(duplicate, "last_output_seq"));
        }
        if (WorkbenchTaskModel.terminal(task) || sender.closed() || "COMPLETED".equals(old.get("status"))) {
            throw WorkbenchTaskJson.conflict("TERMINAL");
        }
        long expected = Math.incrementExact(number(old, "last_frame_no"));
        if (first != expected) throw new WorkbenchTaskFault(409, "FRAME_GAP", expected);
        key.put("hash", hash);
        return appendBatch(key, old, WorkbenchTaskJson.items(body, "frames", limits.getMaxFrames()));
    }
    private boolean sameOperation(Map<String, Object> old, String scope, String agent, ObjectNode t) {
        return scope.equals(old.get("scope_key")) && agent.equals(old.get("agent_id"))
                && number(old, "assignment_version") == t.path("assignmentVersion").longValue();
    }
    static String scope(JsonNode b) {
        return switch (WorkbenchTaskJson.text(b, "scope", 8)) {
            case "TASK" -> {
                if (b.has("stepId")) throw WorkbenchTaskJson.bad("stepId");
                yield "task";
            }
            case "STEP" -> "s:" + WorkbenchTaskJson.id(b, "stepId");
            default -> throw WorkbenchTaskJson.bad("scope");
        };
    }
    private Sender authorize(WorkbenchTaskAccess.Caller c, ObjectNode t, String scope, ObjectNode body) {
        String agent = body.has("agentId") ? WorkbenchTaskJson.id(body, "agentId") : WorkbenchTaskModel.agent(t);
        c.agent(agent);
        boolean allowed = agent.equals(WorkbenchTaskModel.agent(t));
        boolean closed = false;
        if (!scope.equals("task")) {
            String text = ledger.db.findStep(args("taskId", t.path("taskId").asText(), "id", scope.substring(2)));
            if (text == null) throw WorkbenchTaskJson.missing();
            ObjectNode step = ledger.json.read(text);
            closed = step.path("removed").asBoolean() || WorkbenchTaskModel.terminal(step) || "SKIPPED".equals(step.path("status").textValue());
            for (JsonNode executor : step.path("executingAgents")) {
                if (agent.equals(executor.path("id").asText())
                        && step.path("executorAssignmentVersion").asLong() == t.path("assignmentVersion").asLong()) allowed = true;
            }
        }
        if (!allowed) throw WorkbenchTaskJson.denied();
        return new Sender(agent, closed);
    }
    private ObjectNode appendBatch(Map<String, Object> key, Map<String, Object> old, ArrayNode frames) {
        if (frames.isEmpty()) throw WorkbenchTaskJson.bad("frames");
        Cursor cursor = number(old, "last_frame_no") == 0 ? null :
                mapper.convertValue(ledger.json.read((String)old.get("checkpoint_json")), Cursor.class);
        long bytes = (cursor == null ? 0 : cursor.inputBytes()) + frames.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (bytes > limits.getMaxOperationBytes()) throw new WorkbenchTaskFault(413, "OPERATION_TOO_LARGE");
        var parser = DomainAgentStreamState.resume(cursor == null ? null : cursor.parser());
        var assembly = new WorkbenchTaskDisplayAssembly(cursor == null ? null : cursor.assembly());
        long start = (Long)key.get("startSeq") - 1;
        ArrayNode events = ledger.json.array();
        boolean finished = false;
        for (JsonNode frame : frames) {
            if (finished) throw WorkbenchTaskJson.bad("FRAMES_AFTER_END");
            for (ChatEvent event : normalizer.normalizeCallbackFrame(null, null, frame, parser)) {
                assembly.observe(event);
                long nextSeq = Math.addExact(start, events.size() + 1L);
                ObjectNode item = events.addObject().put("outputSeq", nextSeq)
                        .put("operationId", (String) key.get("operation")).put("type", event.type());
                item.set("payload", ledger.json.tree(event.payload()));
                finished |= "message.completed".equals(event.type());
            }
        }
        long seq = start + events.size();
        long last = Math.addExact((Long) key.get("first"), frames.size() - 1L);
        key.putAll(args("last", last, "seq", seq, "startSeq", start + 1, "status", finished ? "COMPLETED" : "RUNNING",
                "content", assembly.content(), "checkpoint", ledger.json.tree(new Cursor(parser.displayCursor(),
                assembly.checkpoint(), bytes)).toString(), "json", events.toString(), "expiry", Instant.now().plus(limits.getReplayRetention())));
        ledger.db.saveOperation(key);
        saveParts(assembly, key);
        if (finished) {
            int order = assembly.checkpoint().count() + 1;
            ChatMessagePartDraft answer = new ChatMessagePartDraft("ANSWER", "message.snapshot", assembly.content(),
                    Map.of("content", assembly.content(), "serverTimestampMs", Instant.now().toEpochMilli()));
            ledger.db.savePart(args("taskId", key.get("taskId"), "operation", key.get("operation"),
                    "ord", order, "json", part(answer, order).toString()));
        }
        ledger.db.insertFrame(key);
        ledger.db.advanceStream(key);
        if (!events.isEmpty()) ledger.signals.changed((String)key.get("taskId"), (String)key.get("scope"));
        return ack(last, seq);
    }
    private void saveParts(WorkbenchTaskDisplayAssembly assembly, Map<String, Object> key) {
        var parts = assembly.parts();
        for (int i = 0; i < parts.size(); i++) {
            var row = args("taskId", key.get("taskId"), "operation", key.get("operation"),
                    "ord", assembly.order(i) + 1, "json", part(parts.get(i), assembly.order(i) + 1).toString());
            ledger.db.savePart(row);
        }
    }
    private ObjectNode part(ChatMessagePartDraft p, int order) {
        var normalized = new ChatMessagePart(null, null, null, null, null, null, p.partType(), p.sourceType(),
                p.contentText(), p.title(), p.status(), p.channel(), p.displayHint(), p.visible(), p.payload(), order, null);
        ObjectNode out = ledger.json.object();
        WorkbenchTaskModel.copy(out, ledger.json.tree(normalized), "partType", "sourceType", "contentText", "title",
                "status", "channel", "displayHint", "visible", "payload", "partOrder");
        return out;
    }
    private ObjectNode ack(long last, long seq) {
        return ledger.json.object().put("lastAcceptedFrameNo", last).put("lastOutputSeq", seq);
    }
    private static long number(Map<String, Object> map, String key) { return ((Number) map.get(key)).longValue(); }
}
