/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatMessagePartDraft;
import com.huawei.it.ex.one.infrastructure.runtime.domainagent.DomainAgentResponseNormalizer;
import com.huawei.it.ex.one.infrastructure.runtime.domainagent.DomainAgentResponseNormalizer.DomainAgentStreamState;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.TreeMap;

class WorkbenchTaskDisplayAssemblyTest {
    @Test
    void persistedCursorProducesSameOrderedEventsAndHistoryAsOneContinuousRun() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        var normalizer = new DomainAgentResponseNormalizer(mapper);
        var runParser = normalizer.newStreamState();
        var run = new AssistantAssembly();
        DomainAgentStreamState.DisplayCursor parser = null;
        WorkbenchTaskDisplayAssembly.Cursor cursor = null;
        var parts = new TreeMap<Integer, ChatMessagePartDraft>();
        String content = "";
        for (String frame : List.of("{\"content\":\"start<thi\"}", "{\"content\":\"nk>calculating</think>end\"}",
                "{\"type\":\"heartbeat\"}", "{\"contentAgent\":\"alpha\"}", "{\"contentAgent\":\"beta\"}",
                "{\"diyCardScene\":{\"scene\":\"review\"},\"cardUrl\":\"uri\"}", "{\"endFlag\":true}")) {
            var runEvents = normalizer.normalize("chat-run", "chat-session", "data: " + frame + "\n\n", runParser);
            var nextParser = DomainAgentStreamState.resume(parser);
            var taskEvents = normalizer.normalizeCallbackFrame(null, null, mapper.readTree(frame), nextParser);
            assertThat(taskEvents).extracting(ChatEvent::type).containsExactlyElementsOf(runEvents.stream().map(ChatEvent::type).toList());
            assertThat(taskEvents).extracting(ChatEvent::payload).containsExactlyElementsOf(runEvents.stream().map(ChatEvent::payload).toList());
            var batch = new WorkbenchTaskDisplayAssembly(cursor);
            // Compare history with the identical timestamp samples, not two successive clock readings.
            for (ChatEvent event : taskEvents) {
                run.observe(event);
                batch.observe(event);
            }
            var page = batch.parts();
            for (int i = 0; i < page.size(); i++) parts.put(batch.order(i), page.get(i));
            content = batch.content();
            cursor = mapper.readValue(mapper.writeValueAsBytes(batch.checkpoint()), WorkbenchTaskDisplayAssembly.Cursor.class);
            parser = mapper.readValue(mapper.writeValueAsBytes(nextParser.displayCursor()), DomainAgentStreamState.DisplayCursor.class);
        }
        assertThat(content).isEqualTo(run.finalContent());
        assertThat(parts.values()).containsExactlyElementsOf(run.parts());
    }
}
