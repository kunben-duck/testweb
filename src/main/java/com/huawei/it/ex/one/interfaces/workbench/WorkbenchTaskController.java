/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.interfaces.workbench;

import com.huawei.it.ex.one.application.integration.identity.AuthContextProvider;
import com.huawei.it.ex.one.application.service.workbench.WorkbenchTaskActions;
import com.huawei.it.ex.one.application.service.workbench.WorkbenchTaskJson;
import com.huawei.it.ex.one.application.service.workbench.WorkbenchTaskQueries;
import com.huawei.it.ex.one.application.service.workbench.WorkbenchTaskSessions;
import com.huawei.it.ex.one.domain.auth.UserContext;

import com.fasterxml.jackson.databind.node.ObjectNode;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

@RestController
@RequestMapping("/v1/chat/tasks")
@ConditionalOnProperty(prefix = "financeex.workbench", name = "enabled", havingValue = "true")
public class WorkbenchTaskController extends WorkbenchTaskHttpErrors {
    private final WorkbenchTaskQueries queries;
    private final WorkbenchTaskActions actions;
    private final WorkbenchTaskSessions sessions;
    private final AuthContextProvider identity;
    public WorkbenchTaskController(WorkbenchTaskQueries queries, WorkbenchTaskActions actions, WorkbenchTaskSessions sessions,
                          AuthContextProvider identity) {
        this.queries = queries;
        this.actions = actions;
        this.sessions = sessions;
        this.identity = identity;
    }
    private UserContext user() {
        sessions.requireVerified();
        var user = identity.resolve();
        if (user == null || user.tenantId() == null || user.ownerUserId() == null) throw WorkbenchTaskJson.denied();
        return user;
    }
    private <T> Mono<T> read(Function<UserContext, T> work) {
        UserContext captured = user();
        return Mono.fromCallable(() -> work.apply(captured)).subscribeOn(Schedulers.boundedElastic());
    }
    @GetMapping
    public Mono<List<ObjectNode>> list(@RequestParam Map<String, String> filters, @RequestParam(defaultValue = "0") int offset,
                                 @RequestParam(defaultValue = "50") int limit) {
        return read(u -> queries.list(u, filters, offset, limit));
    }
    @GetMapping("/{taskId}")
    public Mono<ObjectNode> detail(@PathVariable String taskId) {
        return read(u -> queries.detail(u, taskId));
    }
    @GetMapping("/{taskId}/events")
    public Mono<Object> events(@PathVariable String taskId, @RequestParam(defaultValue = "0") long afterVersion,
                         @RequestParam(defaultValue = "50") int limit) {
        return read(u -> queries.events(u, taskId, afterVersion, limit));
    }
    @GetMapping("/{taskId}/process")
    public Mono<ObjectNode> process(@PathVariable String taskId, @RequestParam(defaultValue = "0") int offset,
                                    @RequestParam(defaultValue = "2") int limit) {
        return read(u -> queries.process(u, taskId, "task", offset, limit));
    }
    @GetMapping("/{taskId}/steps/{stepId}/process")
    public Mono<ObjectNode> process(@PathVariable String taskId, @PathVariable String stepId,
                                    @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "2") int limit) {
        return read(u -> queries.process(u, taskId, "s:" + stepId, offset, limit));
    }
    @PostMapping("/{taskId}/actions")
    public Mono<ResponseEntity<ObjectNode>> action(@PathVariable String taskId, @RequestBody ObjectNode body) {
        return read(u -> ResponseEntity.accepted().body(actions.submit(u, taskId, body)));
    }
    @PostMapping("/{taskId}/session")
    public Mono<Map<String, String>> session(@PathVariable String taskId) {
        return read(u -> Map.of("sessionId", sessions.open(u, taskId)));
    }
    @DeleteMapping("/{taskId}")
    public Mono<ResponseEntity<Void>> delete(@PathVariable String taskId) {
        return read(u -> {
            actions.delete(u, taskId);
            return ResponseEntity.noContent().build();
        });
    }
    @GetMapping("/{taskId}/logs")
    public Mono<Object> logs(@PathVariable String taskId, @RequestParam(defaultValue = "0") int offset,
                       @RequestParam(defaultValue = "50") int limit) {
        return read(u -> queries.logs(u, taskId, offset, limit));
    }
}
