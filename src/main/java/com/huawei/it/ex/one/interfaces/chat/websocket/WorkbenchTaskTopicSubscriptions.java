/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.interfaces.chat.websocket;

import com.huawei.it.ex.one.application.service.workbench.WorkbenchTaskFault;
import com.huawei.it.ex.one.application.service.workbench.WorkbenchTaskQueries;
import com.huawei.it.ex.one.application.service.workbench.WorkbenchTaskSessions;
import com.huawei.it.ex.one.application.service.workbench.WorkbenchTaskSignals;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.interfaces.chat.dto.ChatWebSocketEnvelopeDto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Component
@ConditionalOnProperty(prefix = "financeex.workbench", name = "enabled", havingValue = "true")
final class WorkbenchTaskTopicSubscriptions {
    private final WorkbenchTaskSignals signals;
    private final WorkbenchTaskQueries queries;
    private final WorkbenchTaskSessions sessions;
    private final LocalWebSocketConnectionRegistry registry;
    WorkbenchTaskTopicSubscriptions(WorkbenchTaskSignals signals, WorkbenchTaskQueries queries, WorkbenchTaskSessions sessions, LocalWebSocketConnectionRegistry registry) {
        this.signals = signals;
        this.queries = queries;
        this.sessions = sessions;
        this.registry = registry;
    }
    record Request(String connection, String command, String topic, long after) { }
    private record Topic(String task, String scope) {
        static Topic parse(String text) {
            String[] p = text.split(":", -1);
            if (p.length >= 2 && p[1].matches("task_[0-9a-f]{32}")) {
                if (p.length == 2) return new Topic(p[1], null);
                if (p.length == 3 && p[2].equals("process")) return new Topic(p[1], "task");
                if (p.length == 4 && p[2].equals("step") && p[3].matches("[a-zA-Z0-9_.-]{1,100}")) {
                    return new Topic(p[1], "s:" + p[3]);
                }
            }
            throw new WorkbenchTaskFault(400, "INVALID_TASK_TOPIC");
        }
    }
    Mono<Void> subscribe(Request r, UserContext user, ChatWebSocketOutbound out) {
        return Mono.fromRunnable(() -> {
            sessions.requireVerified();
            if (r.after < 0) throw new WorkbenchTaskFault(400, "INVALID_CURSOR");
            Topic topic = Topic.parse(r.topic);
            ArrayNode first = read(user, topic, r.after);
            Disposable.Swap holder = Disposables.swap();
            var cursor = new AtomicLong(r.after);
            if (registry.get(r.connection).isEmpty()) return;
            registry.subscribe(r.connection, r.topic, null, r.after, holder);
            out.emit(ChatWebSocketEnvelopeDto.reply(r.command, Map.of("type", "subscribe", "topicId", r.topic)));
            if (holder.isDisposed()) return;
            var pages = signals.ticks(r.topic).concatMap(tick -> Mono.fromCallable(() -> read(user, topic, cursor.get()))
                    .subscribeOn(Schedulers.boundedElastic()), 1).startWith(first);
            holder.update(pages.subscribe(page -> {
                for (JsonNode item : page) {
                    if (holder.isDisposed()) return;
                    long seq = item.path(topic.scope == null ? "taskVersion" : "outputSeq").longValue();
                    cursor.set(seq);
                    out.emit(new ChatWebSocketEnvelopeDto(null, "task-event", r.topic, Long.toString(seq),
                            null, null, null, null, Map.of("event", item)));
                }
            }, failure -> {
                if (!holder.isDisposed()) {
                    out.emit(ChatWebSocketEnvelopeDto.error(r.command, "TASK_REFRESH_REQUIRED", "请重新读取任务详情并检查权限"));
                    registry.unsubscribe(r.connection, r.topic, holder);
                }
            }));
        }).subscribeOn(Schedulers.boundedElastic()).then().onErrorResume(ex -> {
            out.emit(ChatWebSocketEnvelopeDto.error(r.command, "TASK_SUBSCRIBE_REJECTED", "任务不存在、无权限或游标无效"));
            return Mono.empty();
        });
    }
    private ArrayNode read(UserContext u, Topic t, long cursor) {
        return t.scope == null ? queries.events(u, t.task, cursor, 50) : queries.replay(u, t.task, t.scope, cursor);
    }
}
