/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.workbench;

import com.fasterxml.jackson.databind.node.ObjectNode;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class WorkbenchTaskIngress {
    private final WorkbenchTaskStateChanges changes;
    private final WorkbenchTaskOutputs outputs;
    private final MeterRegistry metrics;
    public WorkbenchTaskIngress(WorkbenchTaskStateChanges changes, WorkbenchTaskOutputs outputs, ObjectProvider<MeterRegistry> registries) {
        this.changes = changes;
        this.outputs = outputs;
        this.metrics = registries.getIfAvailable();
    }
    public ObjectNode sync(WorkbenchTaskAccess.Caller caller, String taskId, ObjectNode body) {
        if (body.has("changes") && body.has("frames")) throw WorkbenchTaskJson.bad("MIXED_BATCH");
        String kind = WorkbenchTaskJson.text(body, "kind", 32);
        if (!"STATE_CHANGE".equals(kind) && !"OUTPUT_FRAMES".equals(kind)) throw WorkbenchTaskJson.bad("kind");
        Timer.Sample sample = metrics == null ? null : Timer.start(metrics);
        try {
            return "STATE_CHANGE".equals(kind) ? changes.sync(caller, taskId, body) : outputs.append(caller, taskId, body);
        } catch (WorkbenchTaskFault ex) {
            if (metrics != null) metrics.counter("workbench.sync.rejections", "status", Integer.toString(ex.status())).increment();
            throw ex;
        } catch (com.huawei.it.ex.one.infrastructure.runtime.domainagent.DomainAgentProtocolException ex) {
            throw WorkbenchTaskJson.bad("OUTPUT_FRAME");
        } finally {
            if (sample != null) sample.stop(metrics.timer("workbench.sync.duration", "kind", kind));
        }
    }
}
