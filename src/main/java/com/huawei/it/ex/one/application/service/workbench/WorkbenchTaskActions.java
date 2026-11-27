/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.workbench;

import static com.huawei.it.ex.one.application.service.workbench.WorkbenchTaskJson.args;

import com.huawei.it.ex.one.domain.auth.UserContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class WorkbenchTaskActions {
    private final WorkbenchTaskLedger ledger;
    public WorkbenchTaskActions(WorkbenchTaskLedger ledger) { this.ledger = ledger; }

    static List<String> allowed(ObjectNode t, String user) {
        if (!WorkbenchTaskModel.owner(t).equals(user)) return List.of();
        return switch (t.path("status").asText()) {
            case "PENDING" -> List.of("START", "TERMINATE");
            case "RUNNING", "WAITING_HUMAN" -> List.of("PAUSE", "TERMINATE");
            case "PAUSED", "FAILED" -> List.of("RESUME", "TERMINATE");
            case "TERMINATED", "COMPLETED" -> List.of("DELETE");
            default -> List.of();
        };
    }
    static boolean canAnswer(ObjectNode task, ObjectNode request, String user) {
        if (WorkbenchTaskModel.owner(task).equals(user)) return true;
        for (JsonNode id : request.path("answerUserIds")) if (user.equals(id.textValue())) return true;
        return false;
    }
    @Transactional
    public ObjectNode submit(UserContext u, String task, ObjectNode body) {
        String user = u.ownerUserId();
        ObjectNode t = ledger.load(u.tenantId(), task, true);
        ledger.requireMember(t, user);
        String key = WorkbenchTaskJson.id(body, "idempotencyKey");
        String hash = ledger.json.hash(body);
        var prior = ledger.db.priorCommand(args("taskId", task, "user", user, "key", key));
        if (prior != null) {
            if (!hash.equals(prior.get("payload_hash"))) throw WorkbenchTaskJson.conflict("COMMAND_CONFLICT");
            return ledger.json.object().put("commandId", (String)prior.get("command_id"));
        }
        if (WorkbenchTaskJson.positive(body, "expectedVersion") != t.path("taskVersion").longValue()) {
            throw WorkbenchTaskJson.conflict("STALE_TASK_VERSION");
        }
        String action = WorkbenchTaskJson.text(body, "action", 32);
        String id = WorkbenchTaskJson.newId("cmd");
        ObjectNode payload = ledger.json.object().put("commandId", id).put("taskId", task).put("action", action)
                .put("actorUserId", user).put("expectedVersion", t.path("taskVersion").longValue())
                .put("assignmentVersion", t.path("assignmentVersion").longValue());
        String recipient = WorkbenchTaskModel.agent(t);
        if ("ANSWER".equals(action)) recipient = answer(t, user, body, payload);
        else if ("DELETE".equals(action) || !allowed(t, user).contains(action)) throw WorkbenchTaskJson.denied();
        ledger.db.insertCommand(args("id", id, "taskId", task, "user", user, "agent", recipient,
                "assignment", t.path("assignmentVersion").longValue(), "key", key, "hash", hash, "type", action,
                "json", payload.toString()));
        ledger.persist(t);
        ledger.event(t, "workbench", id, hash, ledger.json.array().add(
                ledger.json.object().put("type", "command.accepted").put("action", action)));
        ledger.audit(task, user, "USER", task, action);
        return ledger.json.object().put("commandId", id);
    }
    private String answer(ObjectNode t, String user, ObjectNode body, ObjectNode payload) {
        if (WorkbenchTaskModel.terminal(t)) throw WorkbenchTaskJson.conflict("TERMINAL");
        String id = WorkbenchTaskJson.id(body, "interactionId");
        String task = t.path("taskId").asText();
        Map<String, Object> interaction = ledger.db.interaction(args("taskId", task, "id", id));
        if (interaction == null) throw WorkbenchTaskJson.missing();
        var request = ledger.json.read((String)interaction.get("request_json"));
        if (!canAnswer(t, request, user)) throw WorkbenchTaskJson.denied();
        ObjectNode answer = WorkbenchTaskJson.object(body.path("answer"));
        WorkbenchTaskQuestions.validate(request, answer);
        int count = ledger.db.answer(args("taskId", task, "id", id, "assignment", t.path("assignmentVersion").longValue(),
                "user", user, "json", answer.toString()));
        if (count != 1) throw WorkbenchTaskJson.conflict("INTERACTION_CLOSED");
        payload.put("interactionId", id).put("requestId", request.path("requestId").asText()).set("answer", answer);
        WorkbenchTaskModel.copy(payload, request, "stepId", "operationId");
        return (String)interaction.get("agent_id");
    }
    @Transactional
    public void delete(UserContext u, String id) {
        ObjectNode t = ledger.load(u.tenantId(), id, true);
        if (!WorkbenchTaskModel.owner(t).equals(u.ownerUserId())) throw WorkbenchTaskJson.denied();
        if (ledger.db.deleteTask(args("taskId", id)) != 1) throw WorkbenchTaskJson.conflict("TERMINATE_BEFORE_DELETE");
        ledger.audit(id, u.ownerUserId(), "USER", id, "task.deleted");
    }
}
