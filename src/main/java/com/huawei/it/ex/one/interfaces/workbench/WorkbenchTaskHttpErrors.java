/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.interfaces.workbench;

import com.huawei.it.ex.one.application.service.workbench.WorkbenchTaskFault;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.Map;

abstract class WorkbenchTaskHttpErrors {
    @ExceptionHandler(WorkbenchTaskFault.class)
    public ResponseEntity<Map<String, Object>> taskError(WorkbenchTaskFault fault) {
        var body = fault.expected() == null ? Map.<String, Object>of("code", fault.getMessage())
                : Map.<String, Object>of("code", fault.getMessage(), "expectedFrameNo", fault.expected());
        return ResponseEntity.status(fault.status()).body(body);
    }
}
