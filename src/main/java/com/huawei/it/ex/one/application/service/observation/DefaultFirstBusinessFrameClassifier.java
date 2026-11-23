/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.observation;

import com.huawei.it.ex.one.application.integration.observation.FirstBusinessFrameClassifier;
import com.huawei.it.ex.one.domain.chat.ChatEvent;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 回答 Agent 的思考内容仅触发整轮 Trace 首帧。 */
public final class DefaultFirstBusinessFrameClassifier implements FirstBusinessFrameClassifier {
    private static final Classification TURN_ONLY = new Classification(false, true);

    @Override
    public Classification classify(ChatEvent event) {
        if (event == null || event.payload() == null
                || event.payload().containsKey("candidateSwitchReplay")) {
            return Classification.NONE;
        }
        if ("runtime.thinking".equals(event.type())) {
            return hasThinkingContent(event.payload()) ? TURN_ONLY : Classification.NONE;
        }
        if ("domain-agent".equals(event.payload().get("source"))
                || "domain-agent-content".equals(event.payload().get("sourceType"))) {
            return domainAgent(event);
        }
        return switch (event.type()) {
            case "message.delta", "message.snapshot" ->
                    text(event.payload().get("text")) || text(event.payload().get("content"))
                            || text(event.payload().get("delta")) ? Classification.BUSINESS : Classification.NONE;
            case "runtime.card", "runtime.reference" -> event.payload().isEmpty()
                    ? Classification.NONE : Classification.BUSINESS;
            default -> Classification.NONE;
        };
    }

    private Classification domainAgent(ChatEvent event) {
        Map<String, Object> payload = event.payload();
        return switch (event.type()) {
            case "message.delta" -> "domain-agent-content".equals(payload.get("sourceType"))
                    && text(payload.get("delta")) ? Classification.BUSINESS : Classification.NONE;
            case "runtime.card" -> cardValue(payload.get("diyCardScene")) || cardValue(payload.get("contentAgent"))
                    ? Classification.BUSINESS : Classification.NONE;
            default -> Classification.NONE;
        };
    }

    private boolean hasThinkingContent(Map<String, Object> payload) {
        if (!(payload.get("sourceType") instanceof String sourceType)) {
            return false;
        }
        if ("domain-agent".equals(payload.get("source"))) {
            return switch (sourceType) {
                case "think" -> text(payload.get("thinkTitle")) || text(payload.get("thinkContent"));
                case "content.think" -> text(payload.get("text"));
                default -> false;
            };
        }
        if (!"relay".equals(payload.get("source"))) {
            return false;
        }
        // Relay 在 payload 中保留下游原始拼写。
        String relayType = sourceType.trim().toLowerCase(Locale.ROOT).replace('_', '-').replace('—', '-');
        return switch (relayType) {
            case "agent-reasoning" -> text(payload.get("thought"));
            case "thinking-content-update" -> text(payload.get("content"));
            case "thinking-operation-end", "thinking-operation-finish" -> text(payload.get("reasoning_content"));
            default -> false;
        };
    }

    private boolean cardValue(Object value) {
        return text(value) || value instanceof Map<?, ?> map && !map.isEmpty()
                || value instanceof List<?> list && !list.isEmpty();
    }

    private boolean text(Object value) {
        return value instanceof String string && !string.isBlank();
    }
}
