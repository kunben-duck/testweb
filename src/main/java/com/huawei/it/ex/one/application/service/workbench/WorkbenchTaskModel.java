/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.workbench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

final class WorkbenchTaskModel {
    private static final Set<String> STATUSES = Set.of("PENDING", "RUNNING", "PAUSED", "WAITING_HUMAN",
            "TERMINATED", "FAILED", "COMPLETED");
    private WorkbenchTaskModel() { }

    static boolean terminal(JsonNode n) {
        return terminalStatus(n.path("status").asText());
    }
    static boolean terminalStatus(String status) { return "TERMINATED".equals(status) || "COMPLETED".equals(status); }
    static void transition(ObjectNode n, String status, boolean step) {
        if (!STATUSES.contains(status) && !(step && "SKIPPED".equals(status))) throw WorkbenchTaskJson.bad("status");
        if (terminal(n) && !status.equals(n.path("status").asText())) throw WorkbenchTaskJson.conflict("TERMINAL");
        n.put("status", status);
        String now = Instant.now().toString();
        if ("RUNNING".equals(status) && !n.has("startedAt")) n.put("startedAt", now);
        if (terminal(n) && !n.has("finishedAt")) n.put("finishedAt", now);
    }
    static void copy(ObjectNode out, JsonNode in, String... names) {
        for (String name : names) {
            if (in.has(name)) out.set(name, in.get(name));
        }
    }
    static void named(JsonNode n) {
        WorkbenchTaskJson.id(n, "id");
        WorkbenchTaskJson.text(n, "name", 300);
    }
    static void names(JsonNode n, String key) {
        Set<String> ids = new HashSet<>();
        for (JsonNode actor : WorkbenchTaskJson.items(n, key, 128)) {
            named(actor);
            if (!ids.add(actor.path("id").asText())) throw WorkbenchTaskJson.bad(key);
        }
    }
    static void optionalText(JsonNode n, String name, int max) {
        if (n.hasNonNull(name)) WorkbenchTaskJson.text(n, name, max);
    }
    static void validate(ObjectNode n) {
        WorkbenchTaskJson.text(n, "title", 300);
        WorkbenchTaskPlanTime.validate(n);
        optionalText(n, "description", 1000);
        optionalText(n, "progressSummary", 1000);
        WorkbenchTaskJson.text(n, "agentExecutionStatus", 128);
        if (n.has("tags")) {
            Set<String> seen = new java.util.LinkedHashSet<>();
            for (JsonNode tag : WorkbenchTaskJson.items(n, "tags", 128)) {
                if (!tag.isTextual() || tag.asText().isBlank()) throw WorkbenchTaskJson.bad("tags");
                seen.add(tag.textValue());
            }
            var tags = n.putArray("tags");
            seen.forEach(tags::add);
        }
        named(n.path("responsibleAgent"));
        names(n, "executingAgents");
        for (String field : Set.of("collaboratingAgents", "recommendedSkills", "participants")) {
            if (n.has(field)) names(n, field);
        }
    }
    static String agent(JsonNode task) { return task.path("responsibleAgent").path("id").asText(); }
    static String owner(JsonNode task) { return task.path("owner").path("id").asText(); }
    static String app(JsonNode task) { return task.path("sourceProduct").path("id").asText(); }
    static ObjectNode display(ObjectNode n) {
        ObjectNode view = n.deepCopy();
        view.remove(java.util.List.of("taskContext", "agentExecutionStatus", "originRun"));
        return view;
    }
}
