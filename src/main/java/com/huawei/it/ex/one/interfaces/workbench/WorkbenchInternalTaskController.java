/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.interfaces.workbench;

import com.huawei.it.ex.one.application.config.WorkbenchProperties;
import com.huawei.it.ex.one.application.service.workbench.WorkbenchTaskAccess;
import com.huawei.it.ex.one.application.service.workbench.WorkbenchTaskCreation;
import com.huawei.it.ex.one.application.service.workbench.WorkbenchTaskFault;
import com.huawei.it.ex.one.application.service.workbench.WorkbenchTaskIngress;
import com.huawei.it.ex.one.application.service.workbench.WorkbenchTaskJson;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@RequestMapping("/v1/internal/tasks")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "financeex.workbench", name = "enabled", havingValue = "true")
public class WorkbenchInternalTaskController extends WorkbenchTaskHttpErrors {
    private final Input input;
    private final WorkbenchTaskCreation creation;
    private final WorkbenchTaskIngress ingress;
    private final WorkbenchTaskAccess access;
    public WorkbenchInternalTaskController(Input input, WorkbenchTaskCreation creation, WorkbenchTaskIngress ingress, WorkbenchTaskAccess access) {
        this.input = input;
        this.creation = creation;
        this.ingress = ingress;
        this.access = access;
    }
    @PostMapping(consumes = "application/json")
    public ResponseEntity<ObjectNode> create(HttpServletRequest request) throws IOException {
        var caller = caller(request);
        var result = creation.create(caller, input.read(request));
        return ResponseEntity.status(result.created() ? 201 : 200).body(result.response());
    }
    @PostMapping(path = "/{taskId}/sync", consumes = "application/json")
    public ObjectNode sync(@PathVariable String taskId, HttpServletRequest request) throws IOException {
        var caller = caller(request);
        return ingress.sync(caller, taskId, input.read(request));
    }
    private WorkbenchTaskAccess.Caller caller(HttpServletRequest request) {
        return access.authenticate(request.getHeader("X-Task-Client"), request.getHeader("Authorization"));
    }
    @org.springframework.stereotype.Component
    static class Input {
        private final ObjectMapper mapper;
        private final int limit;
        Input(ObjectMapper mapper, WorkbenchProperties config) {
            this.mapper = mapper;
            this.limit = config.getMaxBatchBytes();
        }
        ObjectNode read(HttpServletRequest request) throws IOException {
            if (request.getContentLengthLong() > limit) throw new WorkbenchTaskFault(413, "BATCH_TOO_LARGE");
            byte[] input = request.getInputStream().readNBytes(Math.addExact(limit, 1));
            return parse(input);
        }
        int limit() { return limit; }
        ObjectNode parse(byte[] input) throws IOException {
            if (input.length > limit) throw new WorkbenchTaskFault(413, "BATCH_TOO_LARGE");
            try {
                return WorkbenchTaskJson.object(mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(input));
            } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                throw WorkbenchTaskJson.bad("JSON");
            }
        }
    }
}
