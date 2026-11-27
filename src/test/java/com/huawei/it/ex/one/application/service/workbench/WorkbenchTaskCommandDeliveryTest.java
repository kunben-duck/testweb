/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.workbench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.config.WorkbenchProperties;
import com.huawei.it.ex.one.infrastructure.persistence.WorkbenchMapper;

import reactor.core.publisher.Mono;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class WorkbenchTaskCommandDeliveryTest {
    @Test
    void acceptedRetryAndStaleResultsAreDistinctAndNoHttpCallForAnOldAssignment() throws Exception {
        var db = mock(WorkbenchMapper.class);
        when(db.dueCommands(anyMap())).thenReturn(List.of("ok", "fail", "moved"));
        when(db.claimCommand(anyMap())).thenReturn(1);
        when(db.command(anyMap())).thenAnswer(i -> {
            String id = (String)i.<Map<String, Object>>getArgument(0).get("id");
            return Map.of("agent_id", "a", "source_app_id", "app", "assignment_version", 1L,
                    "current_assignment", "moved".equals(id) ? 2L : 1L, "attempt_count", 1,
                    "payload_json", "{\"commandId\":\"" + id + "\"}", "tenant_id", "t", "task_status", "RUNNING");
        });
        var releases = new LinkedBlockingQueue<String>();
        when(db.releaseCommand(anyMap())).thenAnswer(i -> {
            var p = i.<Map<String, Object>>getArgument(0);
            releases.add(p.get("id") + ":" + p.get("status"));
            return 1;
        });
        var config = new WorkbenchProperties();
        config.setClients(Map.of("client", new WorkbenchProperties.Client("t", "", Set.of("app"), Set.of("a"),
                Set.of(), Map.of("a", URI.create("https://agent.invalid/command")))));
        config.setDeliveryTokens(Map.of("client", "outgoing-only"));
        var calls = new AtomicInteger();
        var writer = new WorkbenchTaskCommandDelivery(db, config, WebClient.builder().exchangeFunction(request -> {
            assertThat(request.headers().getFirst("Authorization")).isEqualTo("Bearer outgoing-only");
            var status = calls.incrementAndGet() == 1 ? HttpStatus.ACCEPTED : HttpStatus.SERVICE_UNAVAILABLE;
            return Mono.just(ClientResponse.create(status).build());
        }));
        try {
            writer.poll();
            assertThat(releases.poll(3, TimeUnit.SECONDS)).isEqualTo("ok:DELIVERED");
            assertThat(releases.poll(3, TimeUnit.SECONDS)).isEqualTo("fail:PENDING");
            assertThat(releases.poll(3, TimeUnit.SECONDS)).isEqualTo("moved:STALE");
            assertThat(calls.get()).isEqualTo(2);
        } finally {
            writer.close();
        }
    }
}
