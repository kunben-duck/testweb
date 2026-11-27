/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.workbench;

import static com.huawei.it.ex.one.application.service.workbench.WorkbenchTaskJson.args;

import com.huawei.it.ex.one.application.config.WorkbenchProperties;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import com.huawei.it.ex.one.infrastructure.persistence.WorkbenchMapper;

import jakarta.annotation.PreDestroy;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(prefix = "financeex.workbench", name = "enabled", havingValue = "true")
public class WorkbenchTaskCommandDelivery {
    private static final AppLogger LOG = AppLoggerFactory.getLogger(WorkbenchTaskCommandDelivery.class);
    private final WorkbenchMapper db;
    private final WorkbenchProperties settings;
    private final WebClient http;
    private final AtomicBoolean active = new AtomicBoolean();
    private volatile Disposable subscription;

    public WorkbenchTaskCommandDelivery(WorkbenchMapper db, WorkbenchProperties settings, WebClient.Builder builder) {
        this.db = db;
        this.settings = settings;
        this.http = builder.clone().build();
    }
    @Scheduled(fixedDelayString = "${financeex.workbench.dispatch-interval:3000}")
    void poll() {
        if (!active.compareAndSet(false, true)) return;
        subscription = Mono.fromCallable(() -> {
            db.pruneFrames(Map.of());
            return db.dueCommands(Map.of());
        }).subscribeOn(Schedulers.boundedElastic()).flatMapMany(Flux::fromIterable)
                .concatMap(id -> deliver(id).onErrorResume(ex -> {
                    LOG.warn("Task command delivery deferred: {}", ex.getClass().getSimpleName());
                    return Mono.empty();
                }), 1).doFinally(signal -> active.set(false))
                .subscribe(ignored -> { }, ex -> LOG.warn("Task command scan unavailable: {}", ex.getClass().getSimpleName()));
    }
    @PreDestroy
    void close() {
        Disposable work = subscription;
        if (work != null) work.dispose();
    }
    private Mono<Void> deliver(String id) {
        String lease = WorkbenchTaskJson.newId("lease");
        return Mono.fromCallable(() -> {
            if (db.claimCommand(args("id", id, "lease", lease, "until", Instant.now().plusSeconds(30))) == 0) return null;
            return db.command(args("id", id));
        }).subscribeOn(Schedulers.boundedElastic()).flatMap(row -> send(row)
                .onErrorReturn("PENDING").flatMap(status -> Mono.fromRunnable(() -> {
                    long count = ((Number)row.get("attempt_count")).longValue();
                    String result = count >= 12 && "PENDING".equals(status) ? "FAILED" : status;
                    db.releaseCommand(args("id", id, "lease", lease, "status", result,
                            "next", Instant.now().plusSeconds(Math.min(300L, 1L << Math.min(count, 9)))));
                }).subscribeOn(Schedulers.boundedElastic()))).then();
    }
    private Mono<String> send(Map<String, Object> row) {
        if (!row.get("assignment_version").equals(row.get("current_assignment")) || row.get("deleted_at") != null
                || WorkbenchTaskModel.terminalStatus((String)row.get("task_status"))) return Mono.just("STALE");
        String agent = (String)row.get("agent_id");
        var endpoints = settings.getClients().entrySet().stream().filter(e -> e.getValue().tenant().equals(row.get("tenant_id")))
                .filter(e -> e.getValue().apps().contains(row.get("source_app_id")) && e.getValue().agents().contains(agent))
                .filter(e -> e.getValue().commandEndpoints().containsKey(agent)).toList();
        if (endpoints.size() != 1) return Mono.just("PENDING");
        var registered = endpoints.getFirst();
        URI endpoint = registered.getValue().commandEndpoints().get(agent);
        String credential = settings.getDeliveryTokens().get(registered.getKey());
        if (!"https".equals(endpoint.getScheme()) || endpoint.getUserInfo() != null || credential == null
                || credential.isBlank()) return Mono.just("PENDING");
        return http.post().uri(endpoint).headers(h -> h.setBearerAuth(credential))
                .contentType(MediaType.APPLICATION_JSON).bodyValue((String)row.get("payload_json"))
                .exchangeToMono(response -> response.releaseBody().thenReturn(response.statusCode().is2xxSuccessful()
                        ? "DELIVERED" : "PENDING")).timeout(Duration.ofSeconds(5));
    }
}
