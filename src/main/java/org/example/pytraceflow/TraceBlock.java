package org.example.pytraceflow;

import java.util.List;

public record TraceBlock(
        Integer id,
        String callable,
        String module,
        String called,
        String caller,
        Long instanceId,
        Double durationMs,
        MemorySnapshot memoryBefore,
        MemorySnapshot memoryAfter,
        String inputs,
        String inputsAfter,
        String output,
        String error,
        List<TraceBlock> calls
) {
    public String label() {
        String callableText = callable == null || callable.isBlank() ? "<callable?>" : callable;
        String idText = id == null ? "?" : id.toString();
        String durationText = durationMs == null ? "n/a" : String.format("%.3f ms", durationMs);
        return "#" + idText + " " + callableText + " (" + durationText + ")";
    }

    @Override
    public String toString() {
        return label();
    }

    public record MemorySnapshot(Long current, Long peak) {
    }
}
