/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.interfaces.chat.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.service.workbench.WorkbenchTaskFault;
import com.huawei.it.ex.one.application.service.workbench.WorkbenchTaskQueries;
import com.huawei.it.ex.one.application.service.workbench.WorkbenchTaskSessions;
import com.huawei.it.ex.one.application.service.workbench.WorkbenchTaskSignals;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.interfaces.chat.dto.ChatWebSocketEnvelopeDto;

import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Sinks;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

class WorkbenchTaskTopicSubscriptionsTest {
    @Test
    void stateAndStepShareConnectionButNotCursorAndRevocationStopsStepDelivery() throws Exception {
        var u = new UserContext("t", "u", "U");
        var registry = new LocalWebSocketConnectionRegistry();
        registry.register("conn", u);
        var queries = mock(WorkbenchTaskQueries.class);
        var signals = mock(WorkbenchTaskSignals.class);
        var subscriptions = new WorkbenchTaskTopicSubscriptions(signals, queries, mock(WorkbenchTaskSessions.class), registry);
        String id = "task_" + "a".repeat(32);
        String state = "task:" + id;
        String step = state + ":step:s1";
        var stateHint = Sinks.many().multicast().<Long>onBackpressureBuffer();
        var stepHint = Sinks.many().multicast().<Long>onBackpressureBuffer();
        when(signals.ticks(state)).thenReturn(stateHint.asFlux());
        when(signals.ticks(step)).thenReturn(stepHint.asFlux());
        var mapper = new ObjectMapper();
        var statePage = mapper.createArrayNode();
        statePage.addObject().put("taskVersion", 4);
        var stepPage = mapper.createArrayNode();
        stepPage.addObject().put("outputSeq", 9).put("operationId", "op").put("type", "message.delta");
        when(queries.events(u, id, 3, 50)).thenReturn(statePage);
        when(queries.replay(u, id, "s:s1", 8)).thenReturn(stepPage);
        when(queries.replay(u, id, "s:s1", 9)).thenThrow(new WorkbenchTaskFault(404, "revoked"));
        var out = new LinkedBlockingQueue<ChatWebSocketEnvelopeDto>();
        subscriptions.subscribe(new WorkbenchTaskTopicSubscriptions.Request("conn", "1", state, 3), u, out::add)
                .block(Duration.ofSeconds(3));
        assertThat(out.remove().type()).isEqualTo("reply");
        assertThat(out.remove().offset()).isEqualTo("4");
        subscriptions.subscribe(new WorkbenchTaskTopicSubscriptions.Request("conn", "2", step, 8), u, out::add)
                .block(Duration.ofSeconds(3));
        assertThat(out.remove().type()).isEqualTo("reply");
        var frame = out.remove();
        assertThat(frame.topicId()).isEqualTo(step);
        assertThat(frame.offset()).isEqualTo("9");
        stepHint.tryEmitNext(1L);
        assertThat(out.poll(3, TimeUnit.SECONDS)).isNotNull().extracting(ChatWebSocketEnvelopeDto::code)
                .isEqualTo("TASK_REFRESH_REQUIRED");
        registry.unregister("conn");
        assertThat(stepHint.currentSubscriberCount()).isZero();
        assertThat(stateHint.currentSubscriberCount()).isZero();
    }
}
