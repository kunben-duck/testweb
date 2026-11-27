/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.workbench;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.infrastructure.runtime.domainagent.DomainAgentResponseNormalizer;
import com.huawei.it.ex.one.interfaces.chat.dto.ChatWebSocketEnvelopeDto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

class WorkbenchDocumentationTest {
    private static final Path DOCS = Path.of("docs");
    private static final ObjectMapper JSON = new ObjectMapper();

    static JsonNode api(String edition) throws Exception {
        return JSON.readTree(DOCS.resolve("openapi/workbench-" + edition + "-v1.json").toFile());
    }

    static List<JsonNode> examples(String audience) throws Exception {
        String manual = Files.readString(DOCS.resolve("workbench-" + audience + "-integration.md"));
        var blocks = Pattern.compile("(?ms)^```json\\R(.*?)^```").matcher(manual);
        var examples = new ArrayList<JsonNode>();
        while (blocks.find()) examples.add(JSON.readTree(blocks.group(1)));
        return examples;
    }

    @Test
    void editionsContainExactlyTheirCanonicalOperationsAndResolveReferencesLocally() throws Exception {
        JsonNode source = api("tasks");
        Set<String> ids = new HashSet<>();
        for (var audience : List.of("agent", "frontend")) {
            JsonNode spec = api(audience);
            int count = 0;
            for (var path : spec.path("paths").properties()) {
                assertThat(path.getKey().startsWith("/v1/chat/")).isEqualTo("frontend".equals(audience));
                assertThat(path.getValue()).isEqualTo(source.path("paths").path(path.getKey()));
                for (var operation : path.getValue()) {
                    assertThat(ids.add(operation.required("operationId").textValue())).isTrue();
                    count++;
                }
            }
            assertThat(count).isEqualTo("agent".equals(audience) ? 3 : 9);
            for (var reference : spec.findValuesAsText("$ref")) {
                assertThat(reference).startsWith("#/components/");
                assertThat(spec.at(reference.substring(1)).isMissingNode()).as(reference).isFalse();
            }
            assertThat(examples(audience)).isNotEmpty();
        }
        var expected = new ArrayList<String>();
        source.required("paths").forEach(path -> path.forEach(op -> expected.add(op.required("operationId").textValue())));
        assertThat(ids).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void handbooksLinkToLocalFilesAndIncludeEveryNamedRequestExample() throws Exception {
        for (String edition : List.of("agent", "frontend")) {
            var examples = examples(edition);
            for (var path : api(edition).required("paths")) {
                for (var operation : path) {
                    for (var example : operation.at("/requestBody/content/application~1json/examples")) {
                        assertThat(examples).as(operation.required("operationId").asText()).contains(example.required("value"));
                    }
                }
            }
            String manual = Files.readString(DOCS.resolve("workbench-" + edition + "-integration.md"));
            var links = Pattern.compile("\\]\\(([^)]+)\\)").matcher(manual);
            while (links.find()) assertThat(DOCS.resolve(links.group(1))).isRegularFile();
        }
    }

    @Test
    void rawStepExamplesProduceTheDocumentedWebSocketMessagesAndCursors() throws Exception {
        var op = api("agent").path("paths").path("/v1/internal/tasks/{taskId}/sync").path("post");
        var requests = op.at("/requestBody/content/application~1json/examples");
        var responses = op.at("/responses/200/content/application~1json/examples");
        var frontend = examples("frontend");
        var normalizer = new DomainAgentResponseNormalizer(JSON);
        var parser = normalizer.newStreamState();
        String topic = "task:" + api("agent").path("paths").path("/v1/internal/tasks").path("post")
                .at("/responses/201/content/application~1json/examples/external/value/taskId").asText() + ":step:s1";
        int input = 0;
        int seq = 0;
        for (var name : List.of("step", "tail")) {
            var batch = requests.path(name).required("value");
            assertThat(batch.path("firstFrameNo").asInt()).isEqualTo(input + 1);
            for (var frame : batch.path("frames")) {
                input++;
                for (var event : normalizer.normalizeCallbackFrame(null, null, frame, parser)) {
                    var data = Map.of("operationId", "verify", "outputSeq", ++seq, "type", event.type(), "payload", event.payload());
                    var envelope = new ChatWebSocketEnvelopeDto(null, "task-event", topic, Integer.toString(seq),
                            null, null, null, null, Map.of("event", data));
                    assertThat(frontend).as("outputSeq=%s", seq).contains(JSON.<JsonNode>valueToTree(envelope));
                }
            }
            var ack = responses.path("step".equals(name) ? "stepOutput" : "tail").required("value");
            assertThat(ack.path("lastAcceptedFrameNo").asInt()).isEqualTo(input);
            assertThat(ack.path("lastOutputSeq").asInt()).isEqualTo(seq);
        }
    }
}
