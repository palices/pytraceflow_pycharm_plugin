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
        String callableText = displayCallable();
        String idText = id == null ? "?" : id.toString();
        String durationText = durationMs == null ? "n/a" : String.format("%.3f ms", durationMs);
        return "#" + idText + " " + callableText + " (" + durationText + ")";
    }

    /**
     * Returns a friendly name for the callable to display in tree labels.
     * If callable == "__instance__", the "called" field is more descriptive.
     */
    public String displayCallable() {
        String callableText = callable == null ? "" : callable.trim();
        if ("__instance__".equalsIgnoreCase(callableText)) {
            String calledText = called == null ? "" : called.trim();
            return calledText.isBlank() ? "__instance__" : calledText;
        }
        if (callableText.isBlank()) {
            return "<callable?>";
        }
        return callableText;
    }

    @Override
    public String toString() {
        return label();
    }

    public record MemorySnapshot(Long current, Long peak) {
    }
}
