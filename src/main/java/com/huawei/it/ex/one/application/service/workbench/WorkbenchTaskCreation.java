/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.workbench;

import static com.huawei.it.ex.one.application.service.workbench.WorkbenchTaskJson.args;

import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;

@Service
public class WorkbenchTaskCreation {
    public record Result(boolean created, ObjectNode response) { }
    private final WorkbenchTaskLedger ledger;
    private final WorkbenchTaskAccess access;

    public WorkbenchTaskCreation(WorkbenchTaskLedger ledger, WorkbenchTaskAccess access) {
        this.ledger = ledger;
        this.access = access;
    }
    @Transactional
    public Result create(WorkbenchTaskAccess.Caller caller, ObjectNode body) {
        String request = WorkbenchTaskJson.id(body, "requestId");
        ObjectNode task = build(body);
        caller.agent(WorkbenchTaskModel.agent(task));
        caller.app(WorkbenchTaskModel.app(task));
        ObjectNode context = context(caller, body, task);
        ObjectNode hashed = body.deepCopy();
        hashed.remove("taskCreationContext");
        if (context != null) hashed.put("verifiedSession", context.path("session").asText())
                .put("verifiedRun", context.path("run").asText());
        String hash = ledger.json.hash(hashed);
        var key = args("tenant", caller.tenant(), "caller", caller.id(), "requestId", request);
        var existing = ledger.db.findCreation(key);
        if (existing != null) return new Result(false, ledger.replay(existing, hash, "create_hash", "create_ack_json"));
        if (context != null) {
            lockSource(caller, task, context);
            var replay = ledger.db.findCreation(key);
            if (replay != null) return new Result(false, ledger.replay(replay, hash, "create_hash", "create_ack_json"));
            var link = args("tenant", caller.tenant(), "user", WorkbenchTaskModel.owner(task), "session", context.path("session").asText());
            if (ledger.db.sessionTask(link) != null) throw WorkbenchTaskJson.conflict("SESSION_ALREADY_LINKED");
        }
        ledger.extensions.pin(task, caller.tenant());
        ObjectNode response = ledger.ack(task);
        if (context != null) response.put("referenceToken", access.receipt(task.path("taskId").asText(),
                context.path("run").asText(), context.path("session").asText()));
        var insert = args("taskId", task.path("taskId").asText(), "owner", WorkbenchTaskModel.owner(task), "agent", WorkbenchTaskModel.agent(task),
                "status", task.path("status").asText(), "app", WorkbenchTaskModel.app(task), "title", task.path("title").asText(),
                "l1", task.path("l1").path("id").asText(), "l2", task.path("l2").path("id").textValue(),
                "l1Name", task.path("l1").path("name").textValue(),
                "l3", task.path("l3").path("id").textValue(), "hash", hash, "ack", response.toString(),
                "json", task.toString());
        insert.putAll(key);
        ledger.db.insertTask(insert);
        var stored = ledger.db.findCreation(key);
        if (!task.path("taskId").asText().equals(stored.get("task_id"))) {
            return new Result(false, ledger.replay(stored, hash, "create_hash", "create_ack_json"));
        }
        finish(caller, task, context, hash);
        return new Result(true, response);
    }
    private void lockSource(WorkbenchTaskAccess.Caller caller, ObjectNode task, ObjectNode ctx) {
        var key = args("tenant", caller.tenant(), "user", WorkbenchTaskModel.owner(task), "session", ctx.path("session").asText());
        if (ledger.db.lockSourceSession(key) == null) throw WorkbenchTaskJson.missing();
    }
    private ObjectNode context(WorkbenchTaskAccess.Caller c, ObjectNode body, ObjectNode task) {
        String channel = WorkbenchTaskJson.text(task, "channel", 32);
        if ("CHAT".equals(channel)) {
            return access.verify(WorkbenchTaskJson.text(body, "taskCreationContext", 4096), c, WorkbenchTaskModel.agent(task),
                    WorkbenchTaskModel.owner(task));
        }
        if (!Set.of("EXTERNAL", "SCHEDULED", "AGENT").contains(channel) || body.has("taskCreationContext")) {
            throw WorkbenchTaskJson.bad("channel");
        }
        c.user(WorkbenchTaskModel.owner(task));
        return null;
    }
    private ObjectNode build(ObjectNode body) {
        ObjectNode task = ledger.json.object().put("taskId", WorkbenchTaskJson.newId("task")).put("assignmentVersion", 1L)
                .put("taskVersion", 1L).put("createdAt", Instant.now().toString());
        WorkbenchTaskModel.copy(task, body, "title", "description", "locale", "channel", "owner", "sourceProduct", "l1", "l2",
                "l3", "tags", "participants", "creator", "responsibleAgent", "executingAgents", "collaboratingAgents",
                "recommendedSkills", "agentExecutionStatus", "taskContext", "extensions", "plannedStart", "plannedEnd",
                "businessCode", "progressSummary", "statusDescription");
        for (String key : Set.of("l1", "owner", "sourceProduct")) WorkbenchTaskModel.named(task.path(key));
        WorkbenchTaskJson.text(task.path("owner"), "id", 64);
        for (String key : Set.of("l2", "l3")) if (task.has(key)) WorkbenchTaskModel.named(task.path(key));
        WorkbenchTaskJson.text(task, "creator", 300);
        if (!Set.of("zh", "en").contains(WorkbenchTaskJson.text(task, "locale", 8))) throw WorkbenchTaskJson.bad("locale");
        WorkbenchTaskModel.validate(task);
        WorkbenchTaskModel.transition(task, WorkbenchTaskJson.text(body, "status", 32), false);
        return task;
    }
    private void finish(WorkbenchTaskAccess.Caller c, ObjectNode t, ObjectNode context, String hash) {
        String task = t.path("taskId").asText();
        ledger.members(t, c);
        ledger.extensions.save(t, c.tenant());
        if (context != null) ledger.db.insertSession(args("taskId", task, "user", WorkbenchTaskModel.owner(t),
                "session", context.path("session").asText(), "type", "MAIN"));
        ledger.event(t, c.id(), "create:" + task, hash, ledger.json.array().add(
                ledger.json.object().put("type", "task.created")));
        ledger.audit(task, c.id(), "SERVICE", task, "task.created");
    }
}
