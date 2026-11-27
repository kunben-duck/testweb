/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.workbench;

/** Stable transport-independent rejection for a workbench request. */
public final class WorkbenchTaskFault extends RuntimeException {
    private final int status;
    private final Long expected;

    public WorkbenchTaskFault(int status, String code) { this(status, code, null); }
    public WorkbenchTaskFault(int status, String code, Long expected) {
        super(code);
        this.status = status;
        this.expected = expected;
    }
    public int status() { return status; }
    public Long expected() { return expected; }
}
