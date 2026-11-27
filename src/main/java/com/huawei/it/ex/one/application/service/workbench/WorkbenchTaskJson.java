/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.workbench;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

@Component
public final class WorkbenchTaskJson {
    private final ObjectMapper mapper;
    public WorkbenchTaskJson(ObjectMapper mapper) { this.mapper = mapper; }

    public ObjectNode object() { return mapper.createObjectNode(); }
    public ArrayNode array() { return mapper.createArrayNode(); }
    public ObjectNode read(String value) { return object(parse(value)); }
    public JsonNode parse(String value) {
        try { return mapper.readTree(value); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Invalid persisted task JSON", ex); }
    }
    public JsonNode tree(Object value) { return mapper.valueToTree(value); }
    public String hash(JsonNode body) { return digest(canonical(body).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            var entries = new TreeMap<String, JsonNode>();
            node.fields().forEachRemaining(e -> entries.put(e.getKey(), canonical(e.getValue())));
            return object().setAll(entries);
        }
        if (node.isArray()) {
            ArrayNode result = array();
            node.forEach(item -> result.add(canonical(item)));
            return result;
        }
        return node;
    }
    public static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    public static Map<String, Object> args(Object... pairs) {
        var result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
    public static ObjectNode object(JsonNode node) {
        if (!(node instanceof ObjectNode value)) throw bad("OBJECT_REQUIRED");
        return value;
    }
    public static String text(JsonNode node, String name, int max) {
        JsonNode value = node.get(name);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) throw bad(name);
        String s = value.textValue();
        if (s.codePointCount(0, s.length()) > max) throw bad(name);
        return s;
    }
    public static String id(JsonNode node, String name) {
        String s = text(node, name, 100);
        if (!s.matches("[a-zA-Z0-9_.-]+")) throw bad(name);
        return s;
    }
    public static long positive(JsonNode node, String name) {
        JsonNode n = node.path(name);
        if (!n.isIntegralNumber() || !n.canConvertToLong() || n.longValue() < 1) throw bad(name);
        return n.longValue();
    }
    public static ArrayNode items(JsonNode node, String name, int max) {
        if (!(node.get(name) instanceof ArrayNode arr) || arr.size() > max) throw bad(name);
        return arr;
    }
    public static String newId(String prefix) { return prefix + "_" + UUID.randomUUID().toString().replace("-", ""); }
    public static WorkbenchTaskFault bad(String field) { return new WorkbenchTaskFault(400, "INVALID_" + field); }
    public static WorkbenchTaskFault conflict(String code) { return new WorkbenchTaskFault(409, code); }
    public static WorkbenchTaskFault missing() { return new WorkbenchTaskFault(404, "TASK_RESOURCE_NOT_FOUND"); }
    public static WorkbenchTaskFault denied() { return new WorkbenchTaskFault(403, "TASK_ACCESS_DENIED"); }
}
