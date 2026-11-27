/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.workbench;

import static com.huawei.it.ex.one.application.service.workbench.WorkbenchTaskJson.args;

import com.huawei.it.ex.one.infrastructure.persistence.WorkbenchMapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.Map;

@Component
public class WorkbenchTaskExtensions {
    private final WorkbenchMapper db;
    private final WorkbenchTaskJson json;
    public WorkbenchTaskExtensions(WorkbenchMapper db, WorkbenchTaskJson json) {
        this.db = db;
        this.json = json;
    }
    void save(ObjectNode t, String tenant) {
        if (!t.has("extensions")) return;
        var values = WorkbenchTaskJson.object(t.get("extensions"));
        if (values.size() > 64) throw WorkbenchTaskJson.bad("extensions");
        String category = t.path("l2").path("id").asText(t.path("l1").path("id").asText());
        var key = args("tenant", tenant, "app", WorkbenchTaskModel.app(t), "category", category,
                "version", t.path("schemaVersion").textValue());
        var row = db.schema(key);
        if (row == null) throw WorkbenchTaskJson.bad("SCHEMA_REQUIRED");
        ObjectNode fields = json.read((String)row.get("definition_json"));
        t.put("schemaVersion", (String)row.get("schema_version"));
        db.clearValues(args("taskId", t.path("taskId").asText()));
        values.fields().forEachRemaining(e -> {
            var definition = fields.path(e.getKey());
            if (!definition.isObject()) throw WorkbenchTaskJson.bad("UNREGISTERED_FIELD");
            var projection = typed(e.getValue(), definition);
            if (projection != null) {
                projection.putAll(args("taskId", t.path("taskId").asText(), "key", e.getKey(),
                        "version", t.path("schemaVersion").asText()));
                db.indexValue(projection);
            }
        });
    }
    void pin(ObjectNode t, String tenant) {
        if (!t.has("extensions")) return;
        String category = t.path("l2").path("id").asText(t.path("l1").path("id").asText());
        var row = db.schema(args("tenant", tenant, "app", WorkbenchTaskModel.app(t), "category", category));
        if (row == null) throw WorkbenchTaskJson.bad("SCHEMA_REQUIRED");
        t.put("schemaVersion", (String)row.get("schema_version"));
    }
    void filters(Map<String, String> f, Map<String, Object> sql) {
        String key = f.get("field");
        if (key == null) return;
        String app = f.get("app");
        String category = f.getOrDefault("l2", f.get("l1"));
        if (app == null || category == null) throw WorkbenchTaskJson.bad("FILTER_SCOPE");
        var schema = db.schema(args("tenant", sql.get("tenant"), "app", app, "category", category));
        if (schema == null) throw WorkbenchTaskJson.bad("SCHEMA_REQUIRED");
        JsonNode def = json.read((String)schema.get("definition_json")).path(key);
        if (!def.isObject()) throw WorkbenchTaskJson.bad("UNREGISTERED_FIELD");
        String op = f.getOrDefault("op", "filter");
        String value = f.get("value");
        String type = def.path("type").asText();
        switch (op) {
            case "filter" -> {
                if (value == null || !def.path("filter").asBoolean()) throw WorkbenchTaskJson.bad("filter");
                if (!"TEXT".equals(type) && value.length() > 128) throw WorkbenchTaskJson.bad("value");
                JsonNode input = switch (type) {
                    case "TEXT", "TIME" -> json.tree(value);
                    default -> {
                        try { yield json.parse(value); } catch (IllegalStateException ex) { throw WorkbenchTaskJson.bad("value"); }
                    }
                };
                sql.putAll(typed(input, def));
            }
            case "search" -> {
                if (!def.path("search").asBoolean() || !"TEXT".equals(type) || value == null) throw WorkbenchTaskJson.bad("search");
                if (value.isBlank() || value.length() > 300) throw WorkbenchTaskJson.bad("search");
                sql.put("searchText", "%" + value.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
            }
            case "sort" -> {
                if (!def.path("sort").asBoolean()) throw WorkbenchTaskJson.bad("sort");
                sql.put("sortType", type);
                sql.put("descending", "desc".equals(f.get("direction")));
            }
            default -> throw WorkbenchTaskJson.bad("op");
        }
        sql.putAll(args("field", key, "app", app, "schemaVersion", schema.get("schema_version")));
    }
    private Map<String, Object> typed(JsonNode value, JsonNode def) {
        if (value == null || value.isNull()) throw WorkbenchTaskJson.bad("extensionValue");
        return switch (def.path("type").asText()) {
            case "TEXT" -> {
                if (!value.isTextual() || value.textValue().codePointCount(0, value.textValue().length()) > 300) {
                    throw WorkbenchTaskJson.bad("extensionText");
                }
                yield args("text", value.textValue());
            }
            case "NUMBER" -> {
                if (!value.isNumber()) throw WorkbenchTaskJson.bad("extensionNumber");
                yield args("number", value.decimalValue());
            }
            case "BOOLEAN" -> {
                if (!value.isBoolean()) throw WorkbenchTaskJson.bad("extensionBoolean");
                yield args("bool", value.booleanValue());
            }
            case "TIME" -> {
                try { yield args("time", Instant.parse(value.asText())); }
                catch (DateTimeException ex) { throw WorkbenchTaskJson.bad("extensionTime"); }
            }
            default -> throw WorkbenchTaskJson.bad("extensionType");
        };
    }
}
