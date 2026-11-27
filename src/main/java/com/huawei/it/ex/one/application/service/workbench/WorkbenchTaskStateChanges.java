/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.workbench;

import static com.huawei.it.ex.one.application.service.workbench.WorkbenchTaskJson.args;

import com.huawei.it.ex.one.application.config.WorkbenchProperties;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class WorkbenchTaskStateChanges {
    private final WorkbenchTaskLedger ledger;
    private final WorkbenchProperties settings;
    public WorkbenchTaskStateChanges(WorkbenchTaskLedger ledger, WorkbenchProperties settings) {
        this.ledger = ledger;
        this.settings = settings;
    }

    @Transactional
    public ObjectNode sync(WorkbenchTaskAccess.Caller c, String taskId, ObjectNode body) {
        ObjectNode t = ledger.load(c.tenant(), taskId, true);
        c.app(WorkbenchTaskModel.app(t));
        String event = WorkbenchTaskJson.id(body, "eventId");
        String hash = ledger.json.hash(body);
        var prior = ledger.db.findEvent(args("taskId", taskId, "caller", c.id(), "eventId", event));
        if (prior != null) return ledger.replay(prior, hash, "payload_hash", "ack_json");
        boolean lead = c.grant().agents().contains(WorkbenchTaskModel.agent(t));
        WorkbenchTaskLedger.assignment(t, body);
        if (body.has("expectedVersion") && WorkbenchTaskJson.positive(body, "expectedVersion") != t.path("taskVersion").longValue()) {
            throw WorkbenchTaskJson.conflict("STALE_TASK_VERSION");
        }
        if (WorkbenchTaskModel.terminal(t)) throw WorkbenchTaskJson.conflict("TERMINAL");
        ArrayNode changes = WorkbenchTaskJson.items(body, "changes", 128);
        if (changes.isEmpty()) throw WorkbenchTaskJson.bad("changes");
        ArrayNode publicChanges = ledger.json.array();
        boolean planChanged = false;
        for (int i = 0; i < changes.size(); i++) {
            JsonNode ch = changes.get(i);
            String type = WorkbenchTaskJson.text(ch, "type", 64);
            ObjectNode data = WorkbenchTaskJson.object(ch.path("data"));
            planChanged |= type.startsWith("step.");
            if (!lead) requireStepExecutor(c, t, type, data);
            if (WorkbenchTaskModel.terminal(t)) throw WorkbenchTaskJson.conflict("TERMINAL");
            if ("task.assigned".equals(type) && i != changes.size() - 1) throw WorkbenchTaskJson.bad("HANDOFF_MUST_BE_LAST");
            ObjectNode notice = apply(t, c, type, data);
            publicChanges.add(notice);
            ledger.audit(taskId, c.id(), "SERVICE", notice.path("objectId").asText(taskId), type);
        }
        if (planChanged) refreshProgress(t);
        ledger.persist(t);
        ledger.event(t, c.id(), event, hash, publicChanges);
        return ledger.ack(t);
    }
    private void requireStepExecutor(WorkbenchTaskAccess.Caller c, ObjectNode task, String type, ObjectNode data) {
        boolean questionnaire = "interaction.requested".equals(type);
        if (!"step.updated".equals(type) && !questionnaire) throw WorkbenchTaskJson.denied();
        var fields = data.fieldNames();
        var permitted = java.util.Set.of("stepId", "status", "summary", "statusDescription");
        if (!questionnaire) {
            while (fields.hasNext()) if (!permitted.contains(fields.next())) throw WorkbenchTaskJson.denied();
        }
        var step = entity(ledger.db.findStep(args("taskId", task.path("taskId").textValue(), "id", WorkbenchTaskJson.id(data, "stepId"))), true);
        if (step.path("executorAssignmentVersion").asLong() != task.path("assignmentVersion").asLong()) throw WorkbenchTaskJson.denied();
        String sender = questionnaire ? WorkbenchTaskJson.id(data, "agentId") : null;
        for (JsonNode agent : step.path("executingAgents")) {
            String executor = agent.path("id").asText();
            if (c.grant().agents().contains(executor) && (sender == null || sender.equals(executor))) return;
        }
        throw WorkbenchTaskJson.denied();
    }
    private void refreshProgress(ObjectNode t) {
        Map<String, Object> counts = ledger.db.stepCounts(args("taskId", t.path("taskId").asText()));
        long total = ((Number)counts.get("total")).longValue();
        if (total == 0) t.remove("progress");
        else t.putObject("progress").put("total", total).put("completed", ((Number)counts.get("completed")).longValue());
    }
    private ObjectNode apply(ObjectNode t, WorkbenchTaskAccess.Caller c, String type, ObjectNode data) {
        String id = t.path("taskId").asText();
        String object = switch (type) {
            case "task.updated" -> update(t, c, data);
            case "task.assigned" -> assign(t, c, data);
            case "group.upsert", "group.removed" -> group(id, data, type.endsWith("removed"));
            case "step.updated", "step.upsert", "step.removed" -> step(t, data, type);
            case "interaction.requested" -> interaction(t, c, data);
            default -> throw WorkbenchTaskJson.bad("changeType");
        };
        ObjectNode notice = ledger.json.object().put("type", type).put("objectId", object);
        // Only copy public deltas: a member may see the state change without seeing the questionnaire.
        ObjectNode delta = notice.putObject("data");
        WorkbenchTaskModel.copy(delta, data, "title", "description", "status", "statusDescription", "progressSummary",
                "responsibleAgent", "executingAgents", "groupId", "stepId", "sortOrder", "summary", "interactionId");
        return notice;
    }
    private String update(ObjectNode t, WorkbenchTaskAccess.Caller c, ObjectNode d) {
        WorkbenchTaskModel.copy(t, d, "title", "description", "progressSummary", "executingAgents", "participants",
                "collaboratingAgents", "recommendedSkills", "statusDescription", "plannedStart", "plannedEnd",
                "agentExecutionStatus", "taskContext", "tags", "extensions");
        WorkbenchTaskModel.validate(t);
        if (d.has("status")) WorkbenchTaskModel.transition(t, WorkbenchTaskJson.text(d, "status", 32), false);
        if (d.has("participants")) ledger.members(t, c);
        if (d.has("extensions")) ledger.extensions.save(t, c.tenant());
        String taskId = t.path("taskId").asText();
        if (WorkbenchTaskModel.terminal(t)) ledger.db.cancelWaits(args("taskId", taskId));
        return taskId;
    }
    private String assign(ObjectNode t, WorkbenchTaskAccess.Caller caller, ObjectNode d) {
        WorkbenchTaskModel.named(d.path("responsibleAgent"));
        String target = WorkbenchTaskModel.agent(d);
        if (settings.getClients().values().stream().noneMatch(grant -> caller.tenant().equals(grant.tenant())
                && grant.apps().contains(WorkbenchTaskModel.app(t)) && grant.agents().contains(target))) throw WorkbenchTaskJson.bad("UNREGISTERED_AGENT");
        boolean changed = !target.equals(WorkbenchTaskModel.agent(t));
        WorkbenchTaskModel.copy(t, d, "responsibleAgent");
        if (!changed) return t.path("taskId").asText();
        t.put("assignmentVersion", Math.incrementExact(t.path("assignmentVersion").longValue()));
        String id = t.path("taskId").asText();
        ledger.db.cancelWaits(args("taskId", id));
        return id;
    }
    private String group(String task, ObjectNode d, boolean remove) {
        String id = WorkbenchTaskJson.id(d, "groupId");
        var key = args("taskId", task, "id", id);
        ObjectNode g = entity(ledger.db.findGroup(key), remove);
        if (remove && ledger.db.groupInUse(key) > 0) throw WorkbenchTaskJson.conflict("GROUP_HAS_STEPS");
        WorkbenchTaskModel.copy(g, d, "groupId", "title", "sortOrder");
        WorkbenchTaskModel.optionalText(g, "title", 100);
        if (!g.has("sortOrder")) g.put("sortOrder", 0);
        g.put("removed", remove);
        ledger.db.saveGroup(args("taskId", task, "id", id, "sort", order(g), "removed", remove, "json", g.toString()));
        return id;
    }
    private String step(ObjectNode t, ObjectNode d, String type) {
        String task = t.path("taskId").asText();
        String id = WorkbenchTaskJson.id(d, "stepId");
        boolean removed = "step.removed".equals(type);
        ObjectNode s = entity(ledger.db.findStep(args("taskId", task, "id", id)), !"step.upsert".equals(type));
        WorkbenchTaskModel.copy(s, d, "stepId", "groupId", "title", "sortOrder", "description", "renderRef", "statusDescription",
                "summary", "executingAgents", "plannedStart", "plannedEnd");
        WorkbenchTaskJson.text(s, "title", 100);
        WorkbenchTaskPlanTime.validate(s);
        if (s.has("executingAgents")) WorkbenchTaskModel.names(s, "executingAgents");
        if (d.has("executingAgents")) s.put("executorAssignmentVersion", t.path("assignmentVersion").longValue());
        String group = s.path("groupId").textValue();
        if (s.hasNonNull("groupId")) entity(ledger.db.findGroup(args("taskId", task, "id", WorkbenchTaskJson.id(s, "groupId"))), true);
        if (d.has("status")) WorkbenchTaskModel.transition(s, WorkbenchTaskJson.text(d, "status", 32), true);
        String status = WorkbenchTaskJson.text(s, "status", 32);
        long version = s.path("stepVersion").asLong() + 1;
        s.put("removed", removed).put("stepVersion", version);
        ledger.db.saveStep(args("taskId", task, "id", id, "sort", order(s), "removed", removed, "group", group,
                "version", version, "status", status, "json", s.toString()));
        return id;
    }
    private int order(ObjectNode o) {
        JsonNode n = o.path("sortOrder");
        if (!n.isIntegralNumber() || !n.canConvertToInt() || n.intValue() < 0) throw WorkbenchTaskJson.bad("sortOrder");
        return n.intValue();
    }
    private ObjectNode entity(String existing, boolean required) {
        if (existing == null && required) throw WorkbenchTaskJson.missing();
        ObjectNode n = existing == null ? ledger.json.object() : ledger.json.read(existing);
        if (n.path("removed").asBoolean()) throw WorkbenchTaskJson.conflict("PLAN_ITEM_REMOVED");
        return n;
    }
    private String interaction(ObjectNode t, WorkbenchTaskAccess.Caller caller, ObjectNode d) {
        String task = t.path("taskId").asText();
        String id = WorkbenchTaskJson.id(d, "interactionId");
        WorkbenchTaskJson.text(d, "interactionId", 64);
        if (d.has("stepId")) entity(ledger.db.findStep(args("taskId", task, "id", WorkbenchTaskJson.id(d, "stepId"))), true);
        WorkbenchTaskQuestions.definitions(d);
        String agent = d.has("agentId") ? WorkbenchTaskJson.id(d, "agentId") : WorkbenchTaskModel.agent(t);
        caller.agent(agent);
        if (!WorkbenchTaskModel.agent(t).equals(agent)) requireStepExecutor(caller, t, "interaction.requested", d);
        if (d.has("operationId")) {
            var op = ledger.db.operation(args("taskId", task, "operation", WorkbenchTaskJson.id(d, "operationId")));
            String scope = d.has("stepId") ? "s:" + d.path("stepId").asText() : "task";
            if (op == null || !scope.equals(op.get("scope_key")) || !agent.equals(op.get("agent_id"))) throw WorkbenchTaskJson.bad("operationId");
        }
        for (JsonNode user : WorkbenchTaskJson.items(d, "answerUserIds", 128)) {
            if (!user.isTextual()) throw WorkbenchTaskJson.bad("answerUserIds");
            ledger.requireMember(t, user.textValue());
        }
        ledger.db.insertInteraction(args("taskId", task, "id", id, "agent", agent,
                "assignment", t.path("assignmentVersion").longValue(), "external", WorkbenchTaskJson.id(d, "requestId"),
                "step", d.path("stepId").textValue(), "operation", d.path("operationId").textValue(), "json", d.toString()));
        return id;
    }
}
