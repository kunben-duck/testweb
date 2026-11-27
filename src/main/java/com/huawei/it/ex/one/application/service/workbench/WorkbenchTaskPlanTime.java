/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.workbench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

final class WorkbenchTaskPlanTime {
    private WorkbenchTaskPlanTime() { }
    static Instant instant(JsonNode value) {
        try {
            ZoneId zone = ZoneId.of(WorkbenchTaskJson.text(value, "zone", 64));
            String text = WorkbenchTaskJson.text(value, "value", 32);
            return switch (WorkbenchTaskJson.text(value, "precision", 8)) {
                case "DAY" -> LocalDate.parse(text).atStartOfDay(zone).toInstant();
                case "HOUR" -> LocalDateTime.parse(text + ":00", DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm")
                        .withResolverStyle(java.time.format.ResolverStyle.STRICT)).atZone(zone).toInstant();
                case "MINUTE" -> LocalDateTime.parse(text, DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm")
                        .withResolverStyle(java.time.format.ResolverStyle.STRICT)).atZone(zone).toInstant();
                default -> throw WorkbenchTaskJson.bad("precision");
            };
        } catch (DateTimeException ex) {
            throw WorkbenchTaskJson.bad("PLAN_TIME");
        }
    }
    static void validate(ObjectNode n) {
        Instant start = n.hasNonNull("plannedStart") ? instant(n.get("plannedStart")) : null;
        Instant end = n.hasNonNull("plannedEnd") ? instant(n.get("plannedEnd")) : null;
        if (start != null && end != null && start.isAfter(end)) throw WorkbenchTaskJson.bad("PLAN_INTERVAL");
    }
}
