/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.notification;

import com.huawei.it.ex.one.application.service.workbench.WorkbenchTaskSignals;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.util.retry.Retry;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.util.UUID;

/** Hints carry no content or authority. Every subscriber rechecks the database before delivery. */
@Component
@ConditionalOnProperty(prefix = "financeex.workbench", name = "enabled", havingValue = "true")
public final class WorkbenchTaskTopicHints {
    private final Sinks.Many<String> outgoing = Sinks.many().multicast().onBackpressureBuffer(128, false);
    private final Disposable.Composite handles = Disposables.composite();
    private final String sender = UUID.randomUUID() + "/";
    private final String channel;
    private final ReactiveStringRedisTemplate redis;
    private final WorkbenchTaskSignals signals;
    public WorkbenchTaskTopicHints(ObjectProvider<ReactiveStringRedisTemplate> redis, WorkbenchTaskSignals signals,
                          @Value("${financeex.workbench.hint-channel:financeex:workbench:v1}") String channel) {
        this.redis = redis.getIfAvailable();
        this.signals = signals;
        this.channel = channel;
    }
    @PostConstruct
    void start() {
        if (redis == null) return;
        handles.add(outgoing.asFlux().concatMap(topic -> redis.convertAndSend(channel, sender + topic)
                .timeout(Duration.ofSeconds(2)).onErrorResume(error -> Mono.empty()), 1).subscribe());
        handles.add(redis.listenToChannel(channel).retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1))
                .maxBackoff(Duration.ofSeconds(30))).subscribe(message -> incoming(message.getMessage())));
    }
    private void incoming(String hint) {
        if (hint == null || hint.length() > 256 || hint.startsWith(sender)) return;
        int split = hint.indexOf('/');
        if (split != 36 || !hint.startsWith("task:", split + 1)) return;
        signals.hint(hint.substring(split + 1));
    }
    @TransactionalEventListener
    public void send(WorkbenchTaskSignals.Wake wake) {
        if (redis != null) outgoing.tryEmitNext(wake.topic());
    }
    @PreDestroy
    void close() { handles.dispose(); }
}
