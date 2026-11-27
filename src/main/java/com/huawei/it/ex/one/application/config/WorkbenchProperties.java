/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

@Component
@ConfigurationProperties("financeex.workbench")
public class WorkbenchProperties {
    private boolean enabled;
    private String signingKey;
    private Map<String, String> deliveryTokens = Map.of();
    public Map<String, String> getDeliveryTokens() { return deliveryTokens; }
    public void setDeliveryTokens(Map<String, String> value) { deliveryTokens = Map.copyOf(value); }
    private Map<String, Client> clients = Map.of();
    private int maxBatchBytes = 5 * 1024 * 1024;
    private int maxFrames = 128;
    private long maxOperationBytes = 16L * 1024 * 1024;
    public long getMaxOperationBytes() { return maxOperationBytes; }
    public void setMaxOperationBytes(long bytes) {
        if (bytes < 1 || bytes > 256L * 1024 * 1024) throw new IllegalArgumentException("invalid max-operation-bytes");
        maxOperationBytes = bytes;
    }
    private Duration replayRetention = Duration.ofHours(24);

    public record Client(String tenant, String tokenSha256, Set<String> apps, Set<String> agents,
                         Set<String> delegatedUsers, Map<String, URI> commandEndpoints) {
        public Client {
            apps = apps == null ? Set.of() : Set.copyOf(apps);
            agents = agents == null ? Set.of() : Set.copyOf(agents);
            delegatedUsers = delegatedUsers == null ? Set.of() : Set.copyOf(delegatedUsers);
            commandEndpoints = commandEndpoints == null ? Map.of() : Map.copyOf(commandEndpoints);
        }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getSigningKey() { return signingKey; }
    public void setSigningKey(String signingKey) { this.signingKey = signingKey; }
    public Map<String, Client> getClients() { return clients; }
    public void setClients(Map<String, Client> clients) { this.clients = Map.copyOf(clients); }
    public int getMaxBatchBytes() { return maxBatchBytes; }
    public void setMaxBatchBytes(int value) {
        if (value < 1 || value > 32 * 1024 * 1024) throw new IllegalArgumentException("invalid max-batch-bytes");
        maxBatchBytes = value;
    }
    public int getMaxFrames() { return maxFrames; }
    public void setMaxFrames(int value) {
        if (value < 1) throw new IllegalArgumentException("max-frames must be positive");
        maxFrames = value;
    }
    public Duration getReplayRetention() { return replayRetention; }
    public void setReplayRetention(Duration value) {
        if (value == null || value.isNegative() || value.isZero()) throw new IllegalArgumentException("replay-retention");
        replayRetention = value;
    }
}
