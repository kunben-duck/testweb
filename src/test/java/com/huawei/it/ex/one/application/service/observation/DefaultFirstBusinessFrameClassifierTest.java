/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.observation;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.application.integration.intent.IntentDecisionStreamFrame;
import com.huawei.it.ex.one.application.integration.observation.FirstBusinessFrameClassifier.Classification;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.MessageDeltaEvent;
import com.huawei.it.ex.one.domain.chat.MessageSnapshotEvent;
import com.huawei.it.ex.one.domain.chat.RuntimeEvent;
import com.huawei.it.ex.one.domain.chat.StoredChatEvent;
import com.huawei.it.ex.one.infrastructure.runtime.domainagent.DomainAgentResponseNormalizer;
import com.huawei.it.ex.one.infrastructure.runtime.relay.RelayRuntimeResponseNormalizer;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

class DefaultFirstBusinessFrameClassifierTest {
    private static final Classification TRACE_ONLY = new Classification(false, true);

    private final DefaultFirstBusinessFrameClassifier classifier = new DefaultFirstBusinessFrameClassifier();
    private final DomainAgentResponseNormalizer normalizer = new DomainAgentResponseNormalizer(new ObjectMapper());
    private final RelayRuntimeResponseNormalizer relayNormalizer =
            new RelayRuntimeResponseNormalizer(new ObjectMapper());

    @ParameterizedTest
    @MethodSource("domainFrames")
    void classifiesActualDomainAgentEventsWithoutChangingPayload(String frame, int observationCount, int traceCount) {
        List<ChatEvent> events = normalizer.normalize("run", "session", frame);
        List<Map<String, Object>> payloads = events.stream().<Map<String, Object>>map(
                event -> new LinkedHashMap<>(event.payload())).toList();
        var classifications = events.stream().map(classifier::classify).toList();
        assertThat(classifications.stream().filter(Classification::observation).count()).isEqualTo(observationCount);
        assertThat(classifications.stream().filter(Classification::turn).count()).isEqualTo(traceCount);
        assertThat(events.stream().map(ChatEvent::payload)).containsExactlyElementsOf(payloads);
    }

    private static Stream<Arguments> domainFrames() {
        return Stream.of(
                Arguments.of("{\"content\":\"answer\"}", 1, 1),
                Arguments.of("{\"content\":\"<think>thinking</think>answer\"}", 1, 2),
                Arguments.of("{\"content\":\"<think>thinking</think>\"}", 0, 1),
                Arguments.of("{\"content\":\" \"}", 0, 0),
                Arguments.of("{\"content\":null}", 0, 0),
                Arguments.of("{\"cardUrl\":\"https://cards.test/card.js\"}", 0, 0),
                Arguments.of("{\"type\":\"recommended_questions\",\"recommendedQuestions\":[\"next\"]}", 0, 0),
                Arguments.of("{\"cardList\":[{\"text\":\"card\"}]}", 0, 0),
                Arguments.of("{\"specificSceneInfo\":[{\"type\":\"atMember\"}]}", 0, 0),
                Arguments.of("{\"openCard\":\"open\"}", 0, 0),
                Arguments.of("{\"searchList\":[{\"title\":\"document\"}]}", 0, 0),
                Arguments.of("{\"type\":\"think\",\"thinkContent\":\"thinking\"}", 0, 1),
                Arguments.of("{\"type\":\"think\",\"thinkTitle\":\"检查报销项目\"}", 0, 1),
                Arguments.of("{\"type\":\"think\",\"thinkState\":\"start\",\"thinkTitle\":\"正在分析\"}", 0, 1),
                Arguments.of("{\"type\":\"think\",\"thinkState\":\"stop\",\"thinkContent\":\"已完成分析\"}", 0, 1),
                Arguments.of("{\"type\":\"think\",\"thinkState\":\"start\"}", 0, 0),
                Arguments.of("{\"type\":\"think\",\"thinkState\":\"stop\",\"thinkTime\":\"3s\"}", 0, 0),
                Arguments.of("{\"type\":\"think\",\"thinkState\":\"start\",\"thinkContent\":\" \",\"thinkTitle\":\" \"}", 0, 0),
                Arguments.of("{\"type\":\"think\",\"thinkState\":\"start\",\"thinkContent\":{\"text\":\"nested\"}}", 0, 0),
                Arguments.of("{\"content\":\"<think>   </think>\"}", 0, 0),
                Arguments.of("{\"state\":\"THINKING\",\"stateDesc\":\"正在分析\"}", 0, 0),
                Arguments.of("{\"type\":\"tool\",\"toolTitle\":\"search\"}", 0, 0),
                Arguments.of("{\"type\":\"heartbeat\",\"content\":\"ignored\"}", 0, 0),
                Arguments.of("{\"sessionId\":\"downstream-session\"}", 0, 0),
                Arguments.of("{\"endFlag\":true}", 0, 0),
                Arguments.of("{\"type\":\"approval-request\",\"operation_type\":\"questionnaire\","
                        + "\"approval_id\":\"q1\",\"questions\":[{\"question\":\"Choose\"}]}", 0, 0),
                Arguments.of("{\"cardUrl\":\"web.js\",\"diyCardScene\":\"scene\",\"contentAgent\":[]}", 1, 1),
                Arguments.of("{\"cardUrl\":\"web.js\",\"diyCardScene\":[],\"contentAgent\":{\"text\":\"answer\"}}", 1, 1),
                Arguments.of("{\"cardUrl\":\"web.js\",\"diyCardScene\":[],\"contentAgent\":{}}", 0, 0),
                // Existing normalization does not expose contentAgent alongside a standalone URL card.
                Arguments.of("{\"cardUrl\":\"web.js\",\"contentAgent\":\"not emitted\"}", 0, 0),
                Arguments.of("{\"content\":\"answer\",\"cardUrl\":\"web.js\"}", 1, 1),
                Arguments.of("{\"diyCardScene\":\"scene\",\"contentAgent\":\"answer\"}", 1, 1),
                Arguments.of("{\"metadata\":{\"diyCardScene\":\"nested\"}}", 0, 0),
                Arguments.of("{\"DiyCardScene\":\"wrong case\",\"ContentAgent\":\"wrong case\"}", 0, 0),
                Arguments.of("{}", 0, 0));
    }

    @ParameterizedTest
    @MethodSource("relayThinkingFrames")
    void classifiesOnlyProtocolSpecificThinkingTextInRelayFrames(String frame, Classification expected) {
        assertThat(relayNormalizer.normalize("run", "session", frame)).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo("runtime.thinking");
            assertThat(classifier.classify(event)).isEqualTo(expected);
        });
    }

    private static Stream<Arguments> relayThinkingFrames() {
        return Stream.of(
                Arguments.of("{\"type\":\"agent-reasoning\",\"thought\":\"analysis\"}", TRACE_ONLY),
                Arguments.of("{\"type\":\"thinking-content-update\",\"content\":\"fragment\"}", TRACE_ONLY),
                Arguments.of("{\"type\":\"THINKING_CONTENT_UPDATE\",\"content\":\"fragment\"}", TRACE_ONLY),
                Arguments.of("{\"type\":\"thinking—content—update\",\"content\":\"fragment\"}", TRACE_ONLY),
                Arguments.of("{\"type\":\"thinking-operation-end\",\"reasoning_content\":\"summary\"}", TRACE_ONLY),
                Arguments.of("{\"type\":\"thinking-operation-finish\",\"reasoning_content\":\"summary\"}", TRACE_ONLY),
                Arguments.of("{\"type\":\"agent-reasoning\",\"is_start\":true,\"content\":\"status\"}", Classification.NONE),
                Arguments.of("{\"type\":\"thinking-operation-start\",\"content\":\"status\"}", Classification.NONE),
                Arguments.of("{\"type\":\"thinkink-operation-start\",\"operation_id\":\"op-1\"}", Classification.NONE),
                Arguments.of("{\"type\":\"thinking-content-update\",\"content\":\" \"}", Classification.NONE),
                Arguments.of("{\"type\":\"thinking-content-update\",\"content\":{\"text\":\"nested\"}}", Classification.NONE),
                Arguments.of("{\"type\":\"thinking-operation-end\",\"content\":\"status\"}", Classification.NONE),
                Arguments.of("{\"type\":\"thinking-operation-finish\",\"output\":\"answer\"}", Classification.NONE));
    }

    @ParameterizedTest
    @MethodSource("cardValues")
    void cardFieldsRequireNonemptySupportedValues(String field, String value, boolean expected) {
        var events = normalizer.normalize("run", "session", "{\"" + field + "\":" + value + "}");
        assertThat(events).hasSize(1);
        assertThat(classifier.classify(events.getFirst()))
                .isEqualTo(expected ? Classification.BUSINESS : Classification.NONE);
    }

    private static Stream<Arguments> cardValues() {
        return Stream.of("diyCardScene", "contentAgent").flatMap(field -> Stream.concat(
                Stream.of("null", "\"\"", "\" \\t\\n\"", "{}", "[]", "false", "true", "0", "1.5",
                                "{\"value\":null}", "[null]")
                        .map(value -> Arguments.of(field, value, false)),
                Stream.of("\"scene\"", "{\"text\":\"answer\"}", "[\"answer\"]")
                        .map(value -> Arguments.of(field, value, true))));
    }

    @Test
    void checksOnlyTopLevelCardContainerWithoutRecursivelyInspectingItsContents() {
        Map<String, Object> container = new LinkedHashMap<>();
        container.put("optional", null);
        for (String field : List.of("diyCardScene", "contentAgent")) {
            assertThat(classifier.classify(RuntimeEvent.card("run", "session",
                    Map.of("source", "domain-agent", field, container)))).isEqualTo(Classification.BUSINESS);
            assertThat(classifier.classify(RuntimeEvent.card("run", "session",
                    Map.of("source", "domain-agent", field, List.of(Map.of()))))).isEqualTo(Classification.BUSINESS);
        }
    }

    @Test
    void splitThinkingAndCardFramesKeepTheirOriginalOrder() {
        var state = normalizer.newStreamState();
        List<ChatEvent> events = new ArrayList<>();
        for (String chunk : List.of("data: {\"content\":\"<thi", "nk>analysis</think>\"}\n\n",
                "data: {\"cardUrl\":\"web.js\"}\n\ndata: {\"diyCardScene\":{\"name\":",
                "\"scene\"}}\n\ndata: {\"content\":\"answer\"}\n\n")) {
            events.addAll(normalizer.normalize("run", "session", chunk, state));
        }
        events.addAll(normalizer.finish("run", "session", state));
        assertThat(events).extracting(ChatEvent::type).containsExactly(
                "runtime.thinking", "runtime.thinking", "runtime.thinking", "runtime.card", "runtime.card", "message.delta");
        assertThat(events.stream().map(classifier::classify)).containsExactly(
                Classification.NONE, TRACE_ONLY, Classification.NONE, Classification.NONE,
                Classification.BUSINESS, Classification.BUSINESS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"runtime.reference", "runtime.thinking", "runtime.tool", "runtime.metadata",
            "runtime.progress", "runtime.event", "message.completed", "run.waiting_user"})
    void otherDomainEventTypesDoNotCountEvenWithMatchingFields(String type) {
        var event = new StoredChatEvent("run", "session", 0, type, Instant.now(), Map.of(
                "source", "domain-agent", "content", "answer", "diyCardScene", "scene", "contentAgent", "answer"));
        assertThat(classifier.classify(event)).isEqualTo(Classification.NONE);
    }

    @Test
    void nonDomainOutputsKeepTheirClassificationAndIntentSamplesAtTheClient() {
        for (String source : List.of("relay", "chatservice", "intent-agent")) {
            assertThat(classifier.classify(RuntimeEvent.card("run", "session",
                    Map.of("source", source, "sourceType", "approval-request"))))
                    .isEqualTo(Classification.BUSINESS);
            assertThat(classifier.classify(RuntimeEvent.reference("run", "session",
                    Map.of("source", source, "reference", "document"))))
                    .isEqualTo(Classification.BUSINESS);
        }
        assertThat(classifier.classify(MessageDeltaEvent.of("run", "session", "delta")))
                .isEqualTo(Classification.BUSINESS);
        assertThat(classifier.classify(MessageSnapshotEvent.of("run", "session", "snapshot")))
                .isEqualTo(Classification.BUSINESS);
        assertThat(classifier.classify(RuntimeEvent.card("run", "session", Map.of("cardType", "clarification"))))
                .isEqualTo(Classification.BUSINESS);
        assertThat(classifier.classify(RuntimeEvent.thinking("run", "session",
                Map.of("source", "intent-agent", "sourceType", "intent-delta", "text", "analysis"))))
                .isEqualTo(Classification.NONE);
        for (String type : List.of("intent-start", "intent-progress")) {
            assertThat(classifier.classify(RuntimeEvent.progress("run", "session",
                    Map.of("source", "intent-agent", "sourceType", type, "stage", "LLM_PROCESSING"))))
                    .isEqualTo(Classification.NONE);
        }
        assertThat(classifier.classifyIntentResult()).isEqualTo(Classification.BUSINESS);
        assertThat(classifier.classify(null)).isEqualTo(Classification.NONE);
    }

    @Test
    void intentProcessFramesRequireAStageOrDeltaText() {
        var invalid = List.of(
                IntentDecisionStreamFrame.progress(Map.of("stageMessage", "description"), 1, 1),
                IntentDecisionStreamFrame.progress(Map.of("stage", " "), 1, 1),
                IntentDecisionStreamFrame.delta(Map.of("index", 1), 1, 1),
                IntentDecisionStreamFrame.delta(Map.of("content", " "), 1, 1),
                IntentDecisionStreamFrame.delta(Map.of("content", 5), 1, 1),
                IntentDecisionStreamFrame.result(null, 1, 1));
        assertThat(invalid).allSatisfy(frame ->
                assertThat(classifier.isIntentProcessTurnFrame(frame)).isFalse());
    }

    @Test
    void candidateReplayIsExcludedBeforeSourceSpecificClassification() {
        List<ChatEvent> candidates = List.of(
                normalizer.normalize("run", "session", "{\"content\":\"answer\"}").getFirst(),
                normalizer.normalize("run", "session", "{\"diyCardScene\":\"scene\"}").getFirst(),
                normalizer.normalize("run", "session", "{\"type\":\"think\",\"thinkTitle\":\"analysis\"}").getFirst(),
                relayNormalizer.normalize("run", "session", "{\"type\":\"agent-reasoning\",\"thought\":\"analysis\"}")
                        .getFirst());
        for (ChatEvent original : candidates) {
            assertThat(classifier.classify(original).turn()).isTrue();
            Map<String, Object> replay = new LinkedHashMap<>(original.payload());
            replay.put("candidateSwitchReplay", true);
            assertThat(classifier.classify(new StoredChatEvent("run", "session", 0, original.type(),
                    Instant.now(), replay))).isEqualTo(Classification.NONE);
        }
    }
}
