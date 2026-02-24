package org.example.pytraceflow;

import java.util.List;

public record ExecutionFlowData(String sourceFile, List<TraceBlock> roots) {
}
