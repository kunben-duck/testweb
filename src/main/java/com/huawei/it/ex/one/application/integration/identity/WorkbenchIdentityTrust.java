/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.integration.identity;

import org.springframework.stereotype.Component;

/** Checks the selected provider, not merely the presence of another identity bean. */
@Component
public final class WorkbenchIdentityTrust {
    private final boolean verified;
    public WorkbenchIdentityTrust(AuthContextProvider selected) {
        this.verified = selected instanceof VerifiedWorkbenchIdentity;
    }
    public boolean verified() { return verified; }
}
