package org.example.pytraceflow;

public record BreakpointEntry(
        String file,
        Integer line,
        String condition,
        String callable,
        Boolean callableMatched
) {
    @Override
    public String toString() {
        String fileText = file == null || file.isBlank() ? "<sin archivo>" : file;
        String lineText = line == null ? "?" : line.toString();
        String conditionText = condition == null || condition.isBlank() ? "" : " [if " + condition + "]";
        String callableText = callable == null || callable.isBlank() ? "" : " callable=" + callable;
        String matchText = callableMatched == null ? "" : (callableMatched ? " [MATCH]" : " [NO_MATCH]");
        return fileText + ":" + lineText + conditionText + callableText + matchText;
    }
}
