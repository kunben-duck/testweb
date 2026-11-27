/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.workbench;

import static com.huawei.it.ex.one.application.service.workbench.WorkbenchTaskJson.args;

import com.huawei.it.ex.one.infrastructure.persistence.WorkbenchMapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class WorkbenchTaskLedger {
    final WorkbenchMapper db;
    final WorkbenchTaskJson json;
    final WorkbenchTaskSignals signals;
    final WorkbenchTaskExtensions extensions;

    public WorkbenchTaskLedger(WorkbenchMapper db, WorkbenchTaskJson json, WorkbenchTaskSignals signals, WorkbenchTaskExtensions extensions) {
        this.db = db;
        this.json = json;
        this.signals = signals;
        this.extensions = extensions;
    }
    ObjectNode load(String tenant, String id, boolean write) {
        var key = args("taskId", id, "tenant", tenant);
        String value = write ? db.lockTask(key) : db.shareTask(key);
        if (value == null) throw WorkbenchTaskJson.missing();
        return json.read(value);
    }
    void requireMember(ObjectNode task, String user) {
        if (!WorkbenchTaskModel.owner(task).equals(user) && db.member(args("taskId", task.path("taskId").asText(),
                "user", user)) == 0) throw WorkbenchTaskJson.missing();
    }
    void members(ObjectNode task, WorkbenchTaskAccess.Caller c) {
        String taskId = task.path("taskId").asText();
        db.clearMembers(args("taskId", taskId));
        for (JsonNode member : task.path("participants")) {
            String user = WorkbenchTaskJson.text(member, "id", 64);
            c.user(user);
            db.insertMember(args("taskId", taskId, "user", user, "name", member.path("name").textValue()));
        }
    }
    void audit(String taskId, String actor, String type, String object, String action) {
        db.audit(args("id", WorkbenchTaskJson.newId("audit"), "taskId", taskId, "actor", actor,
                "type", type, "object", object, "action", action, "outcome", "ACCEPTED"));
    }
    ObjectNode ack(ObjectNode task) {
        ObjectNode out = json.object();
        WorkbenchTaskModel.copy(out, task, "taskId", "status", "taskVersion", "assignmentVersion");
        return out;
    }
    void event(ObjectNode task, String caller, String id, String hash, JsonNode changes) {
        db.insertEvent(args("taskId", task.path("taskId").asText(), "caller", caller, "eventId", id, "hash", hash,
                "version", task.path("taskVersion").longValue(), "changes", changes.toString(), "ack", ack(task).toString()));
        signals.changed(task.path("taskId").asText(), null);
    }
    void persist(ObjectNode task) {
        long prev = task.path("taskVersion").longValue();
        task.put("taskVersion", Math.incrementExact(prev));
        int updated = db.updateTask(args("taskId", task.path("taskId").asText(), "version", prev + 1, "previous", prev,
                "agent", WorkbenchTaskModel.agent(task), "assignment", task.path("assignmentVersion").longValue(),
                "status", task.path("status").asText(), "title", task.path("title").asText(), "json", task.toString()));
        if (updated != 1) throw WorkbenchTaskJson.conflict("TASK_VERSION_CHANGED");
    }
    static void assignment(ObjectNode task, JsonNode body) {
        if (WorkbenchTaskJson.positive(body, "assignmentVersion") != task.path("assignmentVersion").longValue()) {
            throw WorkbenchTaskJson.conflict("STALE_ASSIGNMENT");
        }
    }
    ObjectNode replay(Map<String, Object> prior, String hash, String hashKey, String ackKey) {
        if (!hash.equals(prior.get(hashKey))) throw WorkbenchTaskJson.conflict("IDEMPOTENCY_CONFLICT");
        return json.read((String) prior.get(ackKey));
    }
}
