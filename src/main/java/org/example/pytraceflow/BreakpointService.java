package org.example.pytraceflow;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BreakpointService {
    private static final String JSON_FILE_NAME = "pytraceflow.json";
    private static final Gson GSON = new Gson();
    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern CLASS_PATTERN = Pattern.compile("^class\\s+([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern DEF_PATTERN = Pattern.compile("^(?:async\\s+def|def)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");

    private BreakpointService() {
    }

    public static ExecutionFlowData readExecutionFlow(Project project) {
        VirtualFile baseDir = project.getBaseDir();
        if (baseDir == null) {
            return new ExecutionFlowData(null, List.of());
        }

        VirtualFile jsonFile = resolveConfigJson(baseDir);
        if (jsonFile == null || jsonFile.isDirectory()) {
            return new ExecutionFlowData(null, List.of());
        }

        try {
            String content = new String(jsonFile.contentsToByteArray(), StandardCharsets.UTF_8);
            JsonElement rootElement = JsonParser.parseString(content);
            List<TraceBlock> roots = new ArrayList<>();

            if (rootElement.isJsonArray()) {
                JsonArray array = rootElement.getAsJsonArray();
                for (JsonElement element : array) {
                    TraceBlock block = parseTraceBlock(element);
                    if (block != null) {
                        roots.add(block);
                    }
                }
            } else if (rootElement.isJsonObject()) {
                TraceBlock block = parseTraceBlock(rootElement);
                if (block != null) {
                    roots.add(block);
                }
            }

            return new ExecutionFlowData(jsonFile.getName(), roots);
        } catch (JsonSyntaxException ignored) {
            return new ExecutionFlowData(jsonFile.getName(), List.of());
        } catch (Exception ignored) {
            return new ExecutionFlowData(jsonFile.getName(), List.of());
        }
    }

    public static List<TraceBlock> findByCallable(List<TraceBlock> roots, String callable) {
        String normalized = normalizeCallable(callable);
        if (normalized == null || roots == null || roots.isEmpty()) {
            return List.of();
        }

        List<TraceBlock> out = new ArrayList<>();
        for (TraceBlock root : roots) {
            collectByCallable(root, normalized, out);
        }
        return out;
    }

    public static String detectCallableAtLine(VirtualFile file, int oneBasedLine) {
        return detectPythonCallable(file, oneBasedLine);
    }

    private static void collectByCallable(TraceBlock block, String normalizedCallable, List<TraceBlock> out) {
        if (block == null) {
            return;
        }

        String blockCallable = normalizeCallable(block.callable());
        if (blockCallable != null && (blockCallable.equals(normalizedCallable)
                || blockCallable.endsWith("." + normalizedCallable)
                || normalizedCallable.endsWith("." + blockCallable))) {
            out.add(block);
        }

        for (TraceBlock child : block.calls()) {
            collectByCallable(child, normalizedCallable, out);
        }
    }

    private static TraceBlock parseTraceBlock(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }

        JsonObject obj = element.getAsJsonObject();
        List<TraceBlock> calls = new ArrayList<>();
        JsonElement callsElement = obj.get("calls");
        if (callsElement != null && callsElement.isJsonArray()) {
            for (JsonElement child : callsElement.getAsJsonArray()) {
                TraceBlock parsed = parseTraceBlock(child);
                if (parsed != null) {
                    calls.add(parsed);
                }
            }
        }

        return new TraceBlock(
                getInt(obj, "id"),
                getString(obj, "callable"),
                getString(obj, "module"),
                getString(obj, "called"),
                getString(obj, "caller"),
                getLong(obj, "instance_id"),
                getDouble(obj, "duration_ms"),
                parseMemory(obj.get("memory_before")),
                parseMemory(obj.get("memory_after")),
                toPrettyJson(obj.get("inputs")),
                toPrettyJson(obj.get("inputs_after")),
                toPrettyJson(obj.get("output")),
                toPrettyJson(obj.get("error")),
                calls
        );
    }

    private static TraceBlock.MemorySnapshot parseMemory(JsonElement memoryElement) {
        if (memoryElement == null || !memoryElement.isJsonObject()) {
            return null;
        }
        JsonObject memory = memoryElement.getAsJsonObject();
        return new TraceBlock.MemorySnapshot(
                getLong(memory, "py_tracemalloc_current"),
                getLong(memory, "py_tracemalloc_peak")
        );
    }

    private static Integer getInt(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) {
            return null;
        }
        try {
            return el.getAsInt();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Long getLong(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) {
            return null;
        }
        try {
            return el.getAsLong();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Double getDouble(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) {
            return null;
        }
        try {
            return el.getAsDouble();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String getString(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) {
            return null;
        }
        try {
            return el.getAsString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String toPrettyJson(JsonElement el) {
        if (el == null || el.isJsonNull()) {
            return "null";
        }
        return PRETTY_GSON.toJson(el);
    }

    private static VirtualFile resolveConfigJson(VirtualFile baseDir) {
        VirtualFile preferred = baseDir.findChild(JSON_FILE_NAME);
        if (preferred != null && !preferred.isDirectory()) {
            return preferred;
        }

        VirtualFile[] children = baseDir.getChildren();
        return Arrays.stream(children)
                .filter(file -> !file.isDirectory())
                .filter(file -> file.getName().toLowerCase(Locale.ROOT).endsWith(".json"))
                .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                .findFirst()
                .orElse(null);
    }

    private static String detectPythonCallable(VirtualFile file, int lineNumber) {
        if (file == null || lineNumber < 1) {
            return null;
        }

        try {
            String content = new String(file.contentsToByteArray(), StandardCharsets.UTF_8);
            String[] lines = content.split("\\r?\\n", -1);
            int end = Math.min(lineNumber, lines.length);
            List<Scope> scopes = new ArrayList<>();

            for (int i = 0; i < end; i++) {
                String raw = lines[i];
                String trimmed = raw.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }

                int indent = indentationLevel(raw);
                while (!scopes.isEmpty() && indent <= scopes.get(scopes.size() - 1).indent) {
                    scopes.remove(scopes.size() - 1);
                }

                Matcher classMatcher = CLASS_PATTERN.matcher(trimmed);
                if (classMatcher.find()) {
                    scopes.add(new Scope(classMatcher.group(1), true, indent));
                    continue;
                }

                Matcher defMatcher = DEF_PATTERN.matcher(trimmed);
                if (defMatcher.find()) {
                    scopes.add(new Scope(defMatcher.group(1), false, indent));
                }
            }

            List<String> path = new ArrayList<>();
            for (Scope scope : scopes) {
                if (scope.isClass || !path.isEmpty() || !scope.name.isBlank()) {
                    path.add(scope.name);
                }
            }

            if (path.isEmpty()) {
                return null;
            }
            return String.join(".", path);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int indentationLevel(String line) {
        int indent = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == ' ') {
                indent += 1;
            } else if (c == '\t') {
                indent += 4;
            } else {
                break;
            }
        }
        return indent;
    }

    private static String normalizeCallable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT).replace(" ", "");
    }

    private static final class Scope {
        private final String name;
        private final boolean isClass;
        private final int indent;

        private Scope(String name, boolean isClass, int indent) {
            this.name = name;
            this.isClass = isClass;
            this.indent = indent;
        }
    }
}
