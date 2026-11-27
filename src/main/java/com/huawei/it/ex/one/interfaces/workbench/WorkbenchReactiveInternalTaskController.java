/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.interfaces.workbench;

import com.huawei.it.ex.one.application.service.workbench.WorkbenchTaskAccess;
import com.huawei.it.ex.one.application.service.workbench.WorkbenchTaskCreation;
import com.huawei.it.ex.one.application.service.workbench.WorkbenchTaskFault;
import com.huawei.it.ex.one.application.service.workbench.WorkbenchTaskIngress;

import com.fasterxml.jackson.databind.node.ObjectNode;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/internal/tasks")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnProperty(prefix = "financeex.workbench", name = "enabled", havingValue = "true")
public class WorkbenchReactiveInternalTaskController extends WorkbenchTaskHttpErrors {
    private final WorkbenchInternalTaskController.Input input;
    private final WorkbenchTaskAccess access;
    private final WorkbenchTaskCreation creation;
    private final WorkbenchTaskIngress ingress;
    public WorkbenchReactiveInternalTaskController(WorkbenchInternalTaskController.Input input, WorkbenchTaskAccess access, WorkbenchTaskCreation creation,
                                          WorkbenchTaskIngress ingress) {
        this.input = input;
        this.access = access;
        this.creation = creation;
        this.ingress = ingress;
    }
    @PostMapping(consumes = "application/json")
    public Mono<ResponseEntity<ObjectNode>> create(ServerHttpRequest request) {
        var caller = caller(request);
        return body(request).map(body -> {
            var result = creation.create(caller, body);
            return ResponseEntity.status(result.created() ? 201 : 200).body(result.response());
        });
    }
    @PostMapping(path = "/{taskId}/sync", consumes = "application/json")
    public Mono<ObjectNode> sync(@PathVariable String taskId, ServerHttpRequest request) {
        var caller = caller(request);
        return body(request).map(body -> ingress.sync(caller, taskId, body));
    }
    private WorkbenchTaskAccess.Caller caller(ServerHttpRequest request) {
        return access.authenticate(request.getHeaders().getFirst("X-Task-Client"), request.getHeaders().getFirst("Authorization"));
    }
    private Mono<ObjectNode> body(ServerHttpRequest request) {
        return DataBufferUtils.join(request.getBody(), input.limit()).map(buffer -> {
            try {
                byte[] data = new byte[buffer.readableByteCount()];
                buffer.read(data);
                return data;
            } finally { DataBufferUtils.release(buffer); }
        }).switchIfEmpty(Mono.error(new WorkbenchTaskFault(400, "INVALID_JSON")))
                .onErrorMap(DataBufferLimitException.class, ex -> new WorkbenchTaskFault(413, "BATCH_TOO_LARGE"))
                .publishOn(Schedulers.boundedElastic()).mapNotNull(bytes -> {
                    try { return input.parse(bytes); }
                    catch (java.io.IOException ex) { throw new WorkbenchTaskFault(400, "INVALID_JSON"); }
                });
    }
}
