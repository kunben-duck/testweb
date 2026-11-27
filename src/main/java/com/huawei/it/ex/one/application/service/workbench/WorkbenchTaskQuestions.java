/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.workbench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashMap;
import java.util.Map;

final class WorkbenchTaskQuestions {
    private WorkbenchTaskQuestions() { }

    static Map<String, Boolean> definitions(ObjectNode request) {
        Map<String, Boolean> modes = new HashMap<>();
        for (JsonNode q : WorkbenchTaskJson.items(request, "questions", 64)) {
            String title = WorkbenchTaskJson.text(q, "question", 512);
            if (q.has("multi_select") && !q.get("multi_select").isBoolean()) throw WorkbenchTaskJson.bad("multi_select");
            if (modes.putIfAbsent(title, q.path("multi_select").asBoolean()) != null) throw WorkbenchTaskJson.bad("questions");
        }
        if (modes.isEmpty()) throw WorkbenchTaskJson.bad("questions");
        return modes;
    }
    static void validate(ObjectNode request, ObjectNode answer) {
        var modes = definitions(request);
        ObjectNode labels = WorkbenchTaskJson.object(answer.path("label"));
        if (answer.size() != 1 || labels.isEmpty()) throw WorkbenchTaskJson.bad("answer");
        labels.fields().forEachRemaining(entry -> {
            Boolean multi = modes.get(entry.getKey());
            if (multi == null) throw WorkbenchTaskJson.bad("UNKNOWN_QUESTION");
            JsonNode value = entry.getValue();
            if (multi) {
                if (!value.isArray() || value.isEmpty() || value.size() > 128) throw WorkbenchTaskJson.bad("answer");
                value.forEach(WorkbenchTaskQuestions::selection);
            } else selection(value);
        });
    }
    private static void selection(JsonNode value) {
        if (!value.isTextual() || value.textValue().isBlank() || value.textValue().length() > 4096) {
            throw WorkbenchTaskJson.bad("answer");
        }
    }
}
