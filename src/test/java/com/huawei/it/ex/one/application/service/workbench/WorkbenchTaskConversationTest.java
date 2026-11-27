/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.workbench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.config.WorkbenchProperties;
import com.huawei.it.ex.one.application.facade.ChatSessionFacade;
import com.huawei.it.ex.one.application.integration.agent.DomainAgentRequest;
import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.application.integration.conversation.SessionRepository;
import com.huawei.it.ex.one.application.integration.identity.VerifiedWorkbenchIdentity;
import com.huawei.it.ex.one.application.integration.identity.WorkbenchIdentityTrust;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.infrastructure.persistence.WorkbenchMapper;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

class WorkbenchTaskConversationTest {
    @Test
    void userHasOwnSessionAndNextTurnUsesNewAgentWithoutTrustingClientTaskFields() {
        var user = new UserContext("tenant", "member", "User");
        var db = mock(WorkbenchMapper.class);
        var json = new WorkbenchTaskJson(new ObjectMapper());
        var ledger = new WorkbenchTaskLedger(db, json, mock(WorkbenchTaskSignals.class), new WorkbenchTaskExtensions(db, json));
        var repo = mock(SessionRepository.class);
        var chats = mock(ChatSessionFacade.class);
        var trust = new WorkbenchIdentityTrust((VerifiedWorkbenchIdentity) () -> user);
        var links = new WorkbenchTaskSessions(ledger, chats, repo, trust);
        var svc = new WorkbenchTaskChatBinding(links, new WorkbenchTaskAccess(new WorkbenchProperties(), json), trust);
        var task = json.read("""
                {"taskId":"T","taskVersion":3,"assignmentVersion":1,"title":"Task","status":"RUNNING",
                 "owner":{"id":"member"},"responsibleAgent":{"id":"A"}}
                """);
        when(db.lockTask(anyMap())).thenReturn(task.toString());
        when(db.shareTask(anyMap())).thenAnswer(i -> task.toString());
        when(db.session(anyMap())).thenReturn(null, "private");
        var session = mock(ChatSession.class);
        when(session.id()).thenReturn("private");
        when(session.status()).thenReturn("ACTIVE");
        when(repo.findByTenantIdAndUserIdAndId("tenant", "member", "private")).thenReturn(Optional.of(session));
        when(chats.createSession(user, "任务对话", null)).thenReturn(session);
        assertThat(links.open(user, "T")).isEqualTo("private");
        assertThat(links.open(user, "T")).isEqualTo("private");
        verify(db, times(1)).insertSession(anyMap());
        when(db.sessionTask(anyMap())).thenReturn("T");
        assertThat(svc.target(user, "private")).isEqualTo("A");
        task.putObject("responsibleAgent").put("id", "B");
        task.put("assignmentVersion", 2);
        var wire = new HashMap<String,Object>(Map.of("taskId", "untrusted", "taskCreationContext", "fake"));
        assertThatThrownBy(() -> svc.decorate(request(user, "A"), wire)).hasMessage("STALE_TASK_ROUTE");
        assertThat(svc.target(user, "private")).isEqualTo("B");
        svc.decorate(request(user, "B"), wire);
        assertThat(wire).containsEntry("taskId", "T").containsEntry("assignmentVersion", 2L)
                .doesNotContainKey("taskCreationContext");
    }

    private DomainAgentRequest request(UserContext user, String agent) {
        return new DomainAgentRequest(user, "private", "run", agent, "remote-session", "question",
                List.of(), Map.of(), RuntimeForwardHeaders.empty());
    }
}
