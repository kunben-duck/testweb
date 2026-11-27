/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatMessagePartDraft;

import java.util.List;
import java.util.Map;

/** Per-operation adapter over the same answer and Parts assembler used by Runs. */
public final class WorkbenchTaskDisplayAssembly {
    public record Cursor(String delta, String snapshot, String fallback, boolean contentSeen,
                         boolean thinkingSinceContent, String active, Map<String, Object> activePayload,
                         int activeOrder, int count) { }
    private final AssistantAssembly assembly = new AssistantAssembly();
    private final int previousOpen;
    private final int base;

    public WorkbenchTaskDisplayAssembly(Cursor cursor) {
        previousOpen = cursor == null ? -1 : cursor.activeOrder();
        base = cursor == null ? 0 : cursor.count();
        if (cursor != null) assembly.restore(cursor);
    }
    public void observe(ChatEvent event) { assembly.observe(event); }
    public List<ChatMessagePartDraft> parts() { return assembly.parts(); }
    public String content() { return assembly.finalContent(); }
    public int order(int index) {
        if (previousOpen < 0) return base + index;
        return index == 0 ? previousOpen : base + index - 1;
    }
    public Cursor checkpoint() {
        int open = assembly.openPart();
        int count = base + parts().size() - (previousOpen < 0 ? 0 : 1);
        return assembly.cursor(open < 0 ? -1 : order(open), count);
    }
}
