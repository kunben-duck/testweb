/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.workbench;

import com.huawei.it.ex.one.application.config.WorkbenchProperties;
import com.huawei.it.ex.one.application.config.WorkbenchProperties.Client;
import com.huawei.it.ex.one.application.integration.agent.DomainAgentRequest;

import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@Component
public class WorkbenchTaskAccess {
    public record Caller(String id, Client grant) {
        public String tenant() { return grant.tenant(); }
        public void agent(String id) {
            if (!grant.agents().contains(id)) throw WorkbenchTaskJson.denied();
        }
        public void app(String id) {
            if (!grant.apps().contains(id)) throw WorkbenchTaskJson.denied();
        }
        public void user(String id) {
            if (!grant.delegatedUsers().contains(id)) throw WorkbenchTaskJson.denied();
        }
    }

    private final WorkbenchProperties properties;
    private final WorkbenchTaskJson json;
    public WorkbenchTaskAccess(WorkbenchProperties properties, WorkbenchTaskJson json) {
        this.properties = properties;
        this.json = json;
    }
    public Caller authenticate(String client, String authorization) {
        if (!properties.isEnabled()) throw WorkbenchTaskJson.missing();
        Client grant = client == null ? null : properties.getClients().get(client);
        if (grant == null || grant.tenant() == null || grant.tenant().isBlank() || grant.tokenSha256() == null) {
            throw new WorkbenchTaskFault(401, "SERVICE_IDENTITY_REQUIRED");
        }
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.length() <= 7) {
            throw new WorkbenchTaskFault(401, "SERVICE_IDENTITY_REQUIRED");
        }
        String actual = WorkbenchTaskJson.digest(authorization.substring(7).getBytes(StandardCharsets.UTF_8));
        if (!equal(actual, grant.tokenSha256())) throw new WorkbenchTaskFault(401, "INVALID_SERVICE_CREDENTIAL");
        return new Caller(client, grant);
    }

    public String issue(DomainAgentRequest request) {
        if (!properties.isEnabled() || !canSign() || request.user() == null) return null;
        boolean eligible = properties.getClients().values().stream().anyMatch(g ->
                request.user().tenantId().equals(g.tenant()) && g.agents().contains(request.domainAgentId()));
        if (!eligible || request.runId() == null || request.sessionId() == null) return null;
        ObjectNode claims = json.object().put("tenant", request.user().tenantId())
                .put("user", request.user().ownerUserId()).put("session", request.sessionId())
                .put("run", request.runId()).put("agent", request.domainAgentId())
                .put("expires", Instant.now().plusSeconds(300).getEpochSecond());
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                claims.toString().getBytes(StandardCharsets.UTF_8));
        return payload + "." + sign(payload);
    }
    public ObjectNode verify(String token, Caller caller, String agent, String user) {
        if (!canSign() || token == null || token.length() > 4096) throw WorkbenchTaskJson.denied();
        String[] pieces = token.split("\\.", -1);
        if (pieces.length != 2 || !equal(sign(pieces[0]), pieces[1])) throw WorkbenchTaskJson.denied();
        ObjectNode c = json.read(new String(Base64.getUrlDecoder().decode(pieces[0]), StandardCharsets.UTF_8));
        if (c.path("expires").asLong() <= Instant.now().getEpochSecond()) throw WorkbenchTaskJson.denied();
        if (!caller.tenant().equals(c.path("tenant").asText()) || !user.equals(c.path("user").asText())
                || !agent.equals(c.path("agent").asText())) throw WorkbenchTaskJson.denied();
        return c;
    }
    private boolean canSign() {
        return properties.getSigningKey() != null && properties.getSigningKey().length() >= 32;
    }
    public boolean enabled() { return properties.isEnabled(); }

    /** Presentation receipt: bound to one Run and not valid as a creation credential. */
    String receipt(String task, String run, String session) {
        return sign("reference:" + task + ":" + run + ":" + session);
    }
    String actionProof(String task, String run, String session, long version) {
        return canSign() ? sign("action:" + task + ":" + run + ":" + session + ":" + version) : null;
    }
    public boolean validAction(String task, String run, String session, long version, String proof) {
        if (task == null || run == null || session == null || proof == null || !canSign()) return false;
        return equal(actionProof(task, run, session, version), proof);
    }
    public boolean validReference(String task, String run, String session, String proof) {
        if (run == null || session == null || proof == null) return false;
        return canSign() && task != null && task.matches("task_[0-9a-f]{32}") && equal(receipt(task, run, session), proof);
    }
    private String sign(String text) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.getSigningKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Cannot sign task creation context", ex);
        }
    }
    private static boolean equal(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
