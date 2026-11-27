/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.workbench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.config.WorkbenchProperties;
import com.huawei.it.ex.one.application.integration.agent.DomainAgentRequest;
import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.application.integration.identity.WorkbenchIdentityTrust;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.infrastructure.persistence.WorkbenchMapper;
import com.huawei.it.ex.one.infrastructure.security.ApplicationAuthContextProvider;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

class WorkbenchTaskControlsTest {
    private final UserContext member = new UserContext("tenant", "member", "Member");
    private final WorkbenchTaskJson j = new WorkbenchTaskJson(new ObjectMapper());
    private final WorkbenchProperties config = new WorkbenchProperties();
    private final WorkbenchMapper db = mock(WorkbenchMapper.class);
    private final WorkbenchTaskLedger ledger = new WorkbenchTaskLedger(db, j, mock(WorkbenchTaskSignals.class), new WorkbenchTaskExtensions(db, j));

    @Test
    void serviceCredentialsAndCreationProofBindUserTenantSessionAndAgent() {
        config.setEnabled(true);
        config.setSigningKey("a".repeat(32));
        config.setClients(Map.of("service", new WorkbenchProperties.Client("tenant",
                WorkbenchTaskJson.digest("secret".getBytes(StandardCharsets.UTF_8)), Set.of("app"), Set.of("agent"),
                Set.of("member"), Map.of())));
        WorkbenchTaskAccess access = new WorkbenchTaskAccess(config, j);
        assertThatThrownBy(() -> access.authenticate("service", "Bearer wrong")).hasMessage("INVALID_SERVICE_CREDENTIAL");
        var caller = access.authenticate("service", "Bearer secret");
        var request = new DomainAgentRequest(member, "chat-session", "run", "agent", "agent-session", "Q",
                List.of(), Map.of(), RuntimeForwardHeaders.empty());
        String token = access.issue(request);
        assertThat(access.verify(token, caller, "agent", "member").path("session").asText()).isEqualTo("chat-session");
        assertThatThrownBy(() -> access.verify(token, caller, "different", "member")).isInstanceOf(WorkbenchTaskFault.class);
        assertThatThrownBy(() -> access.verify(token, caller, "agent", "different")).isInstanceOf(WorkbenchTaskFault.class);
        String id = WorkbenchTaskJson.newId("task");
        String proof = access.receipt(id, "run", "chat-session");
        assertThat(access.validReference(id, "run", "chat-session", proof)).isTrue();
        assertThat(access.validReference(id, "another-run", "chat-session", proof)).isFalse();
        assertThat(access.validAction(id, "run", "chat-session", 1, proof)).isFalse();
        assertThat(new WorkbenchIdentityTrust(new ApplicationAuthContextProvider()).verified()).isFalse();
    }

    @Test
    void participantsCanOnlyAnswerAuthorizedCardsAtTheCurrentVersion() {
        var task = j.read("""
                {"taskId":"T","taskVersion":5,"status":"WAITING_HUMAN","owner":{"id":"owner"},
                 "responsibleAgent":{"id":"agent"},"title":"test","assignmentVersion":2}
                """);
        when(db.lockTask(anyMap())).thenReturn(task.toString());
        when(db.member(anyMap())).thenReturn(1);
        when(db.priorCommand(anyMap())).thenReturn(null);
        String request = """
                {"requestId":"R","answerUserIds":["member"],"questions":[{"question":"Approve?"}]}
                """;
        when(db.interaction(anyMap())).thenReturn(Map.of("request_json", request, "agent_id", "agent"));
        when(db.updateTask(anyMap())).thenReturn(1);
        when(db.answer(anyMap())).thenReturn(1);
        WorkbenchTaskActions actions = new WorkbenchTaskActions(ledger);
        var command = j.read("""
                {"action":"ANSWER","expectedVersion":5,"idempotencyKey":"C","interactionId":"I","answer":{"label":{"Approve?":"yes"}}}
                """);
        assertThatThrownBy(() -> actions.submit(member, "T", command.deepCopy().put("action", "PAUSE")))
                .isInstanceOf(WorkbenchTaskFault.class);
        assertThatThrownBy(() -> actions.submit(member, "T", command.deepCopy().put("expectedVersion", 4)))
                .hasMessage("STALE_TASK_VERSION");
        assertThatThrownBy(() -> actions.submit(new UserContext("tenant", "other", ""), "T", command))
                .hasMessage("TASK_ACCESS_DENIED");
        verify(db, never()).insertCommand(anyMap());
        assertThat(actions.submit(member, "T", command).path("commandId").asText()).startsWith("cmd_");
        verify(db).insertCommand(anyMap());
        verify(db).answer(anyMap());
    }

    @Test
    void invalidAnswerAndMidFlightDeleteDoNotConsumeTaskState() {
        var request = j.read("{\"questions\":[{\"question\":\"Which?\",\"multi_select\":true}]}");
        assertThatThrownBy(() -> WorkbenchTaskQuestions.validate(request, j.read("{\"label\":{\"Other\":\"yes\"}}")))
                .hasMessage("INVALID_UNKNOWN_QUESTION");
        assertThatThrownBy(() -> WorkbenchTaskQuestions.validate(request, j.read("{\"label\":{\"Which?\":\"yes\"}}")))
                .hasMessage("INVALID_answer");
        when(db.lockTask(anyMap())).thenReturn("{\"taskId\":\"T\",\"owner\":{\"id\":\"member\"},\"status\":\"RUNNING\"}");
        assertThatThrownBy(() -> new WorkbenchTaskActions(ledger).delete(member, "T")).hasMessage("TERMINATE_BEFORE_DELETE");
        verify(db, never()).audit(anyMap());
    }

    @Test
    void planTimesRetainDeclaredPrecisionAndTimeZone() {
        assertThat(WorkbenchTaskPlanTime.instant(j.read("{\"value\":\"2026-11-28T09\",\"precision\":\"HOUR\",\"zone\":\"Asia/Shanghai\"}")))
                .isEqualTo(java.time.Instant.parse("2026-11-28T01:00:00Z"));
        assertThatThrownBy(() -> WorkbenchTaskPlanTime.instant(j.read("{\"value\":\"2026-02-31\",\"precision\":\"DAY\",\"zone\":\"UTC\"}")))
                .hasMessage("INVALID_PLAN_TIME");
    }
}
