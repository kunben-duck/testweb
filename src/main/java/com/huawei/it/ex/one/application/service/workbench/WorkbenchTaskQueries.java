/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.workbench;

import static com.huawei.it.ex.one.application.service.workbench.WorkbenchTaskJson.args;

import com.huawei.it.ex.one.domain.auth.UserContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@Transactional(isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
public class WorkbenchTaskQueries {
    private final WorkbenchTaskLedger ledger;
    public WorkbenchTaskQueries(WorkbenchTaskLedger ledger) { this.ledger = ledger; }

    public List<ObjectNode> list(UserContext user, Map<String, String> filters, int offset, int limit) {
        var params = args("tenant", user.tenantId(), "user", user.ownerUserId(), "offset", offset, "limit", limit);
        page(offset, limit);
        for (String key : List.of("status", "l1", "l2", "l3", "relation")) params.put(key, filters.get(key));
        if (!Set.of("ALL", "OWNER", "PARTICIPANT").contains(filters.getOrDefault("relation", "ALL"))) {
            throw WorkbenchTaskJson.bad("relation");
        }
        ledger.extensions.filters(filters, params);
        return ledger.db.listTasks(params).stream().map(ledger.json::read).map(WorkbenchTaskModel::display).map(this::elapsed).toList();
    }
    static void page(long offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 200) throw WorkbenchTaskJson.bad("pagination");
    }
    ObjectNode view(UserContext user, String id, boolean write) {
        ObjectNode t = ledger.load(user.tenantId(), id, write);
        ledger.requireMember(t, user.ownerUserId());
        return t;
    }
    public ObjectNode detail(UserContext user, String id) {
        ObjectNode task = WorkbenchTaskModel.display(view(user, id, false));
        var key = args("taskId", id);
        ArrayNode groups = task.putArray("groups");
        ArrayNode steps = task.putArray("steps");
        ArrayNode current = task.putArray("currentStepIds");
        var totals = new HashMap<String, int[]>();
        int complete = 0;
        for (String value : ledger.db.listSteps(key)) {
            ObjectNode step = ledger.json.read(value);
            String status = step.path("status").asText();
            if (Set.of("WAITING_HUMAN", "FAILED", "RUNNING").contains(status)) current.add(step.path("stepId").asText());
            int count = "COMPLETED".equals(status) ? 1 : 0;
            complete += count;
            int[] group = totals.computeIfAbsent(step.path("groupId").asText(), ignored -> new int[2]);
            group[0] += count;
            group[1]++;
            steps.add(elapsed(step));
        }
        for (String value : ledger.db.listGroups(key)) {
            ObjectNode group = ledger.json.read(value);
            int[] total = totals.getOrDefault(group.path("groupId").asText(), new int[2]);
            if (total[1] > 0) group.putObject("progress").put("completed", total[0]).put("total", total[1]);
            groups.add(group);
        }
        if (!steps.isEmpty()) task.putObject("progress").put("completed", complete).put("total", steps.size());
        task.set("interactions", interactions(id, user, task));
        task.set("allowedActions", ledger.json.tree(WorkbenchTaskActions.allowed(task, user.ownerUserId())));
        return elapsed(task);
    }
    private ObjectNode elapsed(ObjectNode n) {
        if (n.hasNonNull("plannedEnd")) {
            n.put("remainingSeconds", Duration.between(Instant.now(), WorkbenchTaskPlanTime.instant(n.get("plannedEnd"))).toSeconds());
        }
        if (n.has("startedAt")) {
            Instant end = n.has("finishedAt") ? Instant.parse(n.path("finishedAt").textValue()) : Instant.now();
            n.put("elapsedMs", Math.max(0, Duration.between(Instant.parse(n.path("startedAt").textValue()), end).toMillis()));
        }
        return n;
    }
    private ArrayNode interactions(String id, UserContext u, ObjectNode t) {
        ArrayNode out = ledger.json.array();
        for (var row : ledger.db.interactions(args("taskId", id))) {
            ObjectNode request = ledger.json.read((String) row.get("request_json"));
            if (WorkbenchTaskActions.canAnswer(t, request, u.ownerUserId())) {
                request.put("status", (String)row.get("status"));
                if (row.get("answer_json") instanceof String a) request.set("answer", ledger.json.read(a));
                out.add(request);
            }
        }
        return out;
    }
    public ArrayNode events(UserContext u, String id, long after, int limit) {
        ObjectNode t = view(u, id, false);
        page(after, limit);
        if (after > t.path("taskVersion").longValue()) throw new WorkbenchTaskFault(410, "REFRESH_TASK");
        ArrayNode result = ledger.json.array();
        long expected = after;
        for (var row : ledger.db.events(args("taskId", id, "cursor", after, "limit", limit))) {
            long version = ((Number)row.get("task_version")).longValue();
            if (version != ++expected) throw new WorkbenchTaskFault(410, "REFRESH_TASK");
            ObjectNode item = result.addObject().put("taskVersion", version);
            item.set("changes", ledger.json.parse((String)row.get("changes_json")));
        }
        return result;
    }
    public ObjectNode process(UserContext u, String id, String scope) {
        return process(u, id, scope, 0, 2);
    }
    public ObjectNode process(UserContext u, String id, String scope, int offset, int limit) {
        ObjectNode task = view(u, id, false);
        if (offset < 0 || limit < 1 || limit > 4) throw WorkbenchTaskJson.bad("processPage");
        var key = scopeKey(id, scope);
        key.putAll(args("offset", offset, "limit", limit));
        ObjectNode result = ledger.json.object();
        ArrayNode operations = result.putArray("operations");
        var byId = new HashMap<String, ArrayNode>();
        for (var row : ledger.db.operations(key)) {
            String operation = (String)row.get("operation_id");
            var item = operations.addObject().put("operationId", operation).put("agentId", (String)row.get("agent_id"))
                    .put("status", (String)row.get("status")).put("content", java.util.Objects.toString(row.get("content"), ""));
            byId.put(operation, item.putArray("parts"));
        }
        for (var row : ledger.db.parts(key)) {
            byId.get(row.get("operation_id")).add(ledger.json.read((String)row.get("part_json")));
        }
        ArrayNode answers = result.putArray("interactions");
        for (JsonNode interaction : interactions(id, u, task)) {
            String target = interaction.hasNonNull("stepId") ? "s:" + interaction.path("stepId").asText() : "task";
            if (scope.equals(target)) answers.add(interaction);
        }
        Long seq = ledger.db.streamSeq(key);
        if (operations.size() == limit) result.put("nextOffset", (long)offset + limit);
        return result.put("lastOutputSeq", seq == null ? 0 : seq);
    }
    Map<String, Object> scopeKey(String id, String scope) {
        if (!"task".equals(scope) && ledger.db.findStep(args("taskId", id, "id", scope.substring(2))) == null) {
            throw WorkbenchTaskJson.missing();
        }
        return args("taskId", id, "scope", scope);
    }
    public ArrayNode replay(UserContext user, String id, String scope, long after) {
        view(user, id, false);
        var key = scopeKey(id, scope);
        key.putAll(args("cursor", after, "limit", 4));
        ArrayNode events = ledger.json.array();
        long cursor = after;
        for (String batch : ledger.db.frames(key)) {
            for (JsonNode e : ledger.json.parse(batch)) {
                long seq = e.path("outputSeq").longValue();
                if (seq <= after) continue;
                if (seq != ++cursor) throw new WorkbenchTaskFault(410, "REFRESH_PROCESS");
                events.add(e);
            }
        }
        Long latest = ledger.db.streamSeq(key);
        if (events.isEmpty() && (latest == null ? 0L : latest) != after) throw new WorkbenchTaskFault(410, "REFRESH_PROCESS");
        return events;
    }
    public List<Map<String, Object>> logs(UserContext u, String id, int offset, int limit) {
        view(u, id, false);
        page(offset, limit);
        return ledger.db.logs(args("taskId", id, "offset", offset, "limit", limit));
    }
}
