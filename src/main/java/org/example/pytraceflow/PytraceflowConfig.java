package org.example.pytraceflow;

import java.util.List;

public record PytraceflowConfig(
        String script,
        List<BreakpointEntry> breakpoints,
        List<String> callables
) {
}
