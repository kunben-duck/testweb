/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.workbench;

import com.huawei.it.ex.one.application.integration.agent.DomainAgentRequest;
import com.huawei.it.ex.one.application.integration.identity.WorkbenchIdentityTrust;
import com.huawei.it.ex.one.domain.auth.UserContext;

import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Map;

/** This bridge is absent when workbench is disabled; ordinary Runs do not look up a task. */
@Service
@ConditionalOnProperty(prefix = "financeex.workbench", name = "enabled", havingValue = "true")
public class WorkbenchTaskChatBinding {
    private final WorkbenchTaskSessions links;
    private final WorkbenchTaskAccess access;
    private final WorkbenchIdentityTrust identity;

    public WorkbenchTaskChatBinding(WorkbenchTaskSessions links, WorkbenchTaskAccess access, WorkbenchIdentityTrust identity) {
        this.links = links;
        this.access = access;
        this.identity = identity;
    }
    public String target(UserContext user, String sessionId) {
        ObjectNode t = links.linked(user, sessionId);
        return t == null ? null : WorkbenchTaskModel.agent(t);
    }
    public void decorate(DomainAgentRequest request, Map<String, Object> wire) {
        wire.remove("taskCreationContext");
        wire.remove("taskId");
        wire.remove("assignmentVersion");
        wire.remove("taskActionContext");
        wire.remove("taskVersion");
        wire.remove("taskAllowedActions");
        if (!identity.verified() || request.user() == null) return;
        ObjectNode t = links.linked(request.user(), request.sessionId());
        if (t == null) {
            String context = access.issue(request);
            if (context != null) wire.put("taskCreationContext", context);
            return;
        }
        if (!request.domainAgentId().equals(WorkbenchTaskModel.agent(t))) throw WorkbenchTaskJson.conflict("STALE_TASK_ROUTE");
        wire.put("taskId", t.path("taskId").textValue());
        wire.put("assignmentVersion", t.path("assignmentVersion").longValue());
        long version = t.path("taskVersion").longValue();
        wire.put("taskVersion", version);
        wire.put("taskAllowedActions", WorkbenchTaskActions.allowed(t, request.user().ownerUserId()));
        String proof = access.actionProof(t.path("taskId").textValue(), request.runId(), request.sessionId(), version);
        if (proof != null) wire.put("taskActionContext", proof);
    }
}
