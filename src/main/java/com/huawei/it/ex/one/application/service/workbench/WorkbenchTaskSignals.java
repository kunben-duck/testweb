/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.workbench;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;

@Component
public class WorkbenchTaskSignals {
    public record Wake(String topic) { }
    private final ApplicationEventPublisher publisher;
    private final Sinks.Many<String> local = Sinks.many().multicast().directBestEffort();
    public WorkbenchTaskSignals(ApplicationEventPublisher publisher) { this.publisher = publisher; }
    public void changed(String taskId, String scope) {
        String topic = "task:" + taskId;
        if (scope != null) topic += scope.equals("task") ? ":process" : ":step:" + scope.substring(2);
        publisher.publishEvent(new Wake(topic));
    }
    @TransactionalEventListener
    void afterCommit(Wake event) { hint(event.topic()); }
    public void hint(String topic) { local.tryEmitNext(topic); }
    public Flux<Long> ticks(String topic) {
        // A lost local hint or a remote commit is recovered from the durable cursor.
        return Flux.merge(local.asFlux().filter(topic::equals).map(ignored -> 0L),
                Flux.interval(Duration.ZERO, Duration.ofSeconds(3))).onBackpressureLatest();
    }
}
