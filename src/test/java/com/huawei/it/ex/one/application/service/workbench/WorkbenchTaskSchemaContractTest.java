/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.workbench;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

class WorkbenchTaskSchemaContractTest {
    @Test
    void incrementalAndFreshInstallHaveTheSameTablesAndKeys() throws Exception {
        var root = Path.of("src/main/resources/db");
        String init = Files.readString(root.resolve("init-20260718.sql"));
        String upgrade = Files.readString(root.resolve("incremental-20261128-workbench.sql"));
        var tables = tableDefinitions(upgrade, "fin_ex_");
        assertThat(tables).hasSize(15).isEqualTo(tableDefinitions(init, "fin_ex_wb_task_"));
        assertThat(tables.keySet()).allSatisfy(name -> {
            assertThat(name).startsWith("fin_ex_wb_task_").endsWith("_t");
            assertThat(name.length()).isLessThanOrEqualTo(30);
        });
        String mapper = Files.readString(Path.of("src/main/resources/mapper/persistence/WorkbenchMapper.opengauss.xml"));
        var mapperTables = Pattern.compile("\\bfin_ex_\\w+\\b").matcher(mapper).results()
                .map(match -> match.group()).filter(name -> !"fin_ex_chat_session_t".equals(name)).distinct().toList();
        assertThat(mapperTables).containsExactlyInAnyOrderElementsOf(tables.keySet());
        assertThat(init).doesNotContain("fin_ex_task_");
        assertThat(upgrade).doesNotContain("ALTER TABLE", "DROP TABLE", "UPDATE fin_ex_chat_");
        assertThat(upgrade).contains("UNIQUE (tenant_id, caller_id, create_request_id)",
                "UNIQUE (task_id, caller_id, source_event_id)", "PRIMARY KEY (task_id, operation_id, first_frame_no)");
        var api = new ObjectMapper().readTree(Files.readString(Path.of("docs/openapi/workbench-tasks-v1.json")));
        assertThat(api.path("paths").has("/v1/internal/tasks/{taskId}/sync")).isTrue();
    }

    private Map<String, String> tableDefinitions(String ddl, String prefix) {
        var result = new LinkedHashMap<String, String>();
        var matcher = Pattern.compile("(?s)CREATE TABLE IF NOT EXISTS (" + prefix + "\\w+) \\(.*?\\n\\);").matcher(ddl);
        while (matcher.find()) {
            assertThat(result.putIfAbsent(matcher.group(1), matcher.group())).as(matcher.group(1)).isNull();
        }
        return result;
    }
}
