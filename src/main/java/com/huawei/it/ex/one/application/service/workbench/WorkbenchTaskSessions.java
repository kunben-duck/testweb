/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.workbench;

import static com.huawei.it.ex.one.application.service.workbench.WorkbenchTaskJson.args;

import com.huawei.it.ex.one.application.facade.ChatSessionFacade;
import com.huawei.it.ex.one.application.integration.conversation.SessionRepository;
import com.huawei.it.ex.one.application.integration.identity.WorkbenchIdentityTrust;
import com.huawei.it.ex.one.domain.auth.UserContext;

import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "financeex.workbench", name = "enabled", havingValue = "true")
public class WorkbenchTaskSessions {
    private final WorkbenchTaskLedger ledger;
    private final ChatSessionFacade sessions;
    private final SessionRepository repository;
    private final WorkbenchIdentityTrust identity;

    public WorkbenchTaskSessions(WorkbenchTaskLedger ledger, @org.springframework.context.annotation.Lazy ChatSessionFacade sessions,
                        SessionRepository repository, WorkbenchIdentityTrust identity) {
        this.ledger = ledger;
        this.sessions = sessions;
        this.repository = repository;
        this.identity = identity;
    }
    public void requireVerified() {
        if (!identity.verified()) throw new WorkbenchTaskFault(403, "VERIFIED_USER_IDENTITY_REQUIRED");
    }
    @Transactional
    public String open(UserContext user, String taskId) {
        requireVerified();
        var task = ledger.load(user.tenantId(), taskId, true);
        ledger.requireMember(task, user.ownerUserId());
        var key = args("taskId", taskId, "user", user.ownerUserId());
        String id = ledger.db.session(key);
        if (id != null && repository.findByTenantIdAndUserIdAndId(user.tenantId(), user.ownerUserId(), id)
                .filter(s -> !"DELETED".equals(s.status())).isPresent()) return id;
        if (id != null) ledger.db.dropSession(key);
        String created = sessions.createSession(user, "任务对话", null).id();
        key.putAll(args("session", created, "type", "PERSONAL"));
        ledger.db.insertSession(key);
        return created;
    }
    public void unlinkDeleted(UserContext user, String session) {
        ledger.db.unlinkDeleted(args("tenant", user.tenantId(), "user", user.ownerUserId(), "session", session));
    }
    @Transactional
    public ObjectNode linked(UserContext user, String session) {
        String id = ledger.db.sessionTask(args("session", session, "tenant", user.tenantId(), "user", user.ownerUserId()));
        if (id == null) return null;
        requireVerified();
        var task = ledger.load(user.tenantId(), id, false);
        ledger.requireMember(task, user.ownerUserId());
        return task;
    }
}
