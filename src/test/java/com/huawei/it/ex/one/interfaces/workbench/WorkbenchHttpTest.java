/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.interfaces.workbench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.huawei.it.ex.one.application.config.WorkbenchProperties;
import com.huawei.it.ex.one.application.service.workbench.WorkbenchTaskAccess;
import com.huawei.it.ex.one.application.service.workbench.WorkbenchTaskCreation;
import com.huawei.it.ex.one.application.service.workbench.WorkbenchTaskIngress;
import com.huawei.it.ex.one.application.service.workbench.WorkbenchTaskJson;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

class WorkbenchHttpTest {
    @Test
    void authenticateBeforeParsingAndRejectOversizeOrDuplicateJsonKeys() throws Exception {
        var mapper = new ObjectMapper();
        var properties = new WorkbenchProperties();
        properties.setEnabled(true);
        properties.setMaxBatchBytes(32);
        properties.setClients(Map.of("product", new WorkbenchProperties.Client("tenant",
                WorkbenchTaskJson.digest("test-only".getBytes(StandardCharsets.UTF_8)), Set.of("app"), Set.of("A"),
                Set.of("person"), Map.of())));
        var controller = new WorkbenchInternalTaskController(new WorkbenchInternalTaskController.Input(mapper, properties),
                mock(WorkbenchTaskCreation.class), mock(WorkbenchTaskIngress.class), new WorkbenchTaskAccess(properties, new WorkbenchTaskJson(mapper)));
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        var unauthorized = mvc.perform(post("/v1/internal/tasks").contentType(MediaType.APPLICATION_JSON)
                .content("invalid json".repeat(60))).andExpect(status().isUnauthorized()).andReturn();
        assertThat(unauthorized.getResponse().getContentAsString()).contains("SERVICE_IDENTITY_REQUIRED");
        mvc.perform(post("/v1/internal/tasks/T/sync").header("X-Task-Client", "product")
                .header("Authorization", "Bearer test-only").contentType(MediaType.APPLICATION_JSON)
                .content(" ".repeat(33))).andExpect(status().isPayloadTooLarge());
        mvc.perform(post("/v1/internal/tasks").header("X-Task-Client", "product")
                .header("Authorization", "Bearer test-only").contentType(MediaType.APPLICATION_JSON)
                .content("{\"x\":0,\"x\":1}")).andExpect(status().isBadRequest());
    }
}
