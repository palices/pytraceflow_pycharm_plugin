package org.example.pytraceflow;

import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.util.ExecUtil;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.util.execution.ParametersListUtil;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.UIUtil;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.xdebugger.XDebuggerManager;
import com.intellij.xdebugger.breakpoints.XBreakpoint;
import com.intellij.xdebugger.breakpoints.XLineBreakpoint;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

public class PytraceflowBreakpointLineMarkerProvider extends LineMarkerProviderDescriptor {
    private static final Icon GUTTER_ICON = IconLoader.getIcon("/icons/pytraceflowGutter.svg", PytraceflowBreakpointLineMarkerProvider.class);

    @Override
    public @Nullable String getName() {
        return "Pytraceflow Breakpoint Marker";
    }

    @Override
    public @Nullable Icon getIcon() {
        return GUTTER_ICON;
    }

    @Override
    public @Nullable LineMarkerInfo<?> getLineMarkerInfo(@NotNull PsiElement element) {
        if (element.getFirstChild() != null) {
            return null;
        }

        PsiFile file = element.getContainingFile();
        if (file == null) {
            return null;
        }

        VirtualFile virtualFile = file.getVirtualFile();
        if (virtualFile == null || virtualFile.isDirectory() || !virtualFile.getName().endsWith(".py")) {
            return null;
        }

        Project project = element.getProject();
        Document document = FileDocumentManager.getInstance().getDocument(virtualFile);
        if (document == null) {
            return null;
        }

        int offset = element.getTextRange().getStartOffset();
        int lineZeroBased = document.getLineNumber(offset);
        if (!isFirstNonWhitespaceElementOnLine(document, offset, lineZeroBased)) {
            return null;
        }

        if (!hasBreakpoint(project, virtualFile.getUrl(), lineZeroBased)) {
            return null;
        }

        int lineOneBased = lineZeroBased + 1;
        String callableAtLine = BreakpointService.detectCallableAtLine(virtualFile, lineOneBased);

        TextRange range = new TextRange(offset, Math.min(offset + 1, element.getTextRange().getEndOffset()));
        return new LineMarkerInfo<>(
                element,
                range,
                getIcon(),
                e -> "Abrir Pytraceflow popup",
                (mouseEvent, psiElement) -> showPopup(project, mouseEvent, callableAtLine),
                GutterIconRenderer.Alignment.LEFT,
                () -> "Pytraceflow"
        );
    }

    private static void showPopup(Project project, java.awt.event.MouseEvent mouseEvent, String targetCallable) {
        JPanel panel = buildPopupPanel(project, targetCallable);
        com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
                .createComponentPopupBuilder(panel, null)
                .setRequestFocus(true)
                .setResizable(true)
                .setMovable(true)
                .setTitle("Pytraceflow")
                .createPopup()
                .show(new RelativePoint(mouseEvent));
    }

    private static JPanel buildPopupPanel(Project project, String targetCallable) {
        JPanel root = new JPanel(new BorderLayout(0, 8));
        applyBaseColors(root);

        JLabel status = new JLabel();

        // Row 1: generate button + command preview
        JButton generateBtn = new JButton("Generate Pytraceflow json");
        JTextField commandPreview = new JTextField(buildCommandPreview(project), 48);
        commandPreview.setEditable(true);
        JPanel actionRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actionRow.add(generateBtn);
        actionRow.add(new JLabel("Command:"));
        actionRow.add(commandPreview);
        applyAccentRowColors(actionRow, commandPreview);
        styleButton(generateBtn);

        JButton refresh = new JButton("Refresh");
        JTextField searchField = new JTextField(24);
        JButton searchButton = new JButton("Search");
        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        header.add(refresh);
        header.add(new JLabel("Filter callable/called:"));
        header.add(searchField);
        header.add(searchButton);
        header.add(status);
        applyHeaderColors(header, status);
        styleButton(refresh);
        styleButton(searchButton);

        JPanel north = new JPanel();
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        north.add(actionRow);
        north.add(header);
        root.add(north, BorderLayout.NORTH);

        DefaultMutableTreeNode placeholder = new DefaultMutableTreeNode("No data");
        Tree tree = new Tree(new DefaultTreeModel(placeholder));
        JEditorPane details = new JEditorPane();
        details.setEditable(false);
        details.setEditorKit(new javax.swing.text.html.HTMLEditorKit());
        details.setContentType("text/html");
        details.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        styleTextAreas(tree, details);

        JSplitPane splitPane = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT,
                ScrollPaneFactory.createScrollPane(tree),
                ScrollPaneFactory.createScrollPane(details)
        );
        splitPane.setResizeWeight(0.30); // 30% tree, 70% details
        root.add(splitPane, BorderLayout.CENTER);

        tree.addTreeSelectionListener(e -> {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
            if (node == null) {
                details.setText("");
                return;
            }
            Object user = node.getUserObject();
            if (user instanceof TraceBlock block) {
                details.setContentType("text/html");
                details.setText(formatBlockDetails(block));
                details.setCaretPosition(0);
            } else {
                details.setContentType("text/plain");
                details.setText(String.valueOf(user));
            }
        });

        refresh.addActionListener(e -> refreshFlow(project, targetCallable, searchField.getText(), status, tree, details));
        searchButton.addActionListener(e -> refreshFlow(project, targetCallable, searchField.getText(), status, tree, details));
        searchField.addActionListener(e -> refreshFlow(project, targetCallable, searchField.getText(), status, tree, details));
        generateBtn.addActionListener(e -> runGeneration(project, generateBtn, commandPreview, status, tree, details, targetCallable, searchField));

        updateCommandPreview(project, commandPreview);
        refreshFlow(project, targetCallable, "", status, tree, details);

        root.setPreferredSize(new Dimension(920, 480)); // compact window
        return root;
    }

    private static void refreshFlow(
            Project project,
            String targetCallable,
            String filterText,
            JLabel status,
            Tree tree,
            JEditorPane details
    ) {
        ExecutionFlowData flow = BreakpointService.readExecutionFlow(project);

        DefaultMutableTreeNode rootUi = new DefaultMutableTreeNode(
                flow.sourceFile() == null ? "Flow JSON (not found)" : "Flow JSON: " + flow.sourceFile()
        );
        List<TraceBlock> filteredRoots = filterByText(flow.roots(), filterText);

        List<TraceBlock> matches = BreakpointService.findByCallable(flow.roots(), targetCallable);
        String callableText = targetCallable == null || targetCallable.isBlank() ? "(no callable detected)" : targetCallable;
        String filterLabel = (filterText == null || filterText.isBlank()) ? "no filter" : "\"" + filterText + "\"";
        status.setText("Callable breakpoint: " + callableText
                + " | Roots: " + flow.roots().size()
                + " | Matches: " + matches.size()
                + " | Filter: " + filterLabel);

        boolean filterApplied = filterText != null && !filterText.isBlank();

        // Without filter and without matches: show nothing
        if (!filterApplied && matches.isEmpty()) {
            rootUi.removeAllChildren();
            tree.setModel(new DefaultTreeModel(rootUi));
            details.setText("No matches found for detected callable.");
            tree.clearSelection();
            return;
        }

        // Build tree contents
        rootUi.removeAllChildren();
        List<TraceBlock> blocksToShow = filterApplied ? filteredRoots : flow.roots();
        for (TraceBlock block : blocksToShow) {
            rootUi.add(buildTree(block));
        }
        tree.setModel(new DefaultTreeModel(rootUi));
        expandAll(tree);

        // Select match if available and no filter
        if (!filterApplied && !matches.isEmpty()) {
            TreePath matchPath = findPathForBlock(rootUi, matches.get(0));
            if (matchPath != null) {
                tree.setSelectionPath(matchPath);
                tree.scrollPathToVisible(matchPath);
                return;
            }
        }

        if (rootUi.getChildCount() > 0) {
            DefaultMutableTreeNode firstChild = (DefaultMutableTreeNode) rootUi.getChildAt(0);
            TreePath firstPath = new TreePath(firstChild.getPath());
            tree.setSelectionPath(firstPath);
            tree.scrollPathToVisible(firstPath);
        } else {
            details.setText("No execution blocks found in the JSON.");
            tree.clearSelection();
        }
    }

    private static DefaultMutableTreeNode buildTree(TraceBlock block) {
        DefaultMutableTreeNode node = new DefaultMutableTreeNode(block);
        for (TraceBlock child : block.calls()) {
            node.add(buildTree(child));
        }
        return node;
    }

    private static TreePath findPathForBlock(DefaultMutableTreeNode root, TraceBlock target) {
        Enumeration<?> enumeration = root.depthFirstEnumeration();
        while (enumeration.hasMoreElements()) {
            Object next = enumeration.nextElement();
            if (!(next instanceof DefaultMutableTreeNode node)) {
                continue;
            }
            if (node.getUserObject() == target) {
                return new TreePath(node.getPath());
            }
        }
        return null;
    }

    private static void expandAll(Tree tree) {
        TreeNode rootNode = (TreeNode) tree.getModel().getRoot();
        if (rootNode == null) {
            return;
        }
        Enumeration<?> enumeration = ((DefaultMutableTreeNode) rootNode).breadthFirstEnumeration();
        while (enumeration.hasMoreElements()) {
            Object next = enumeration.nextElement();
            if (next instanceof DefaultMutableTreeNode node) {
                tree.expandPath(new TreePath(node.getPath()));
            }
        }
    }

    private static List<TraceBlock> filterByText(List<TraceBlock> roots, String filter) {
        if (roots == null || roots.isEmpty()) {
            return List.of();
        }
        if (filter == null || filter.isBlank()) {
            return roots;
        }
        String needle = filter.toLowerCase(Locale.ROOT);
        return roots.stream()
                .map(block -> filterBlock(block, needle))
                .filter(block -> block != null)
                .toList();
    }

    private static TraceBlock filterBlock(TraceBlock block, String needle) {
        if (block == null) {
            return null;
        }
        List<TraceBlock> filteredChildren = block.calls().stream()
                .map(child -> filterBlock(child, needle))
                .filter(child -> child != null)
                .toList();

        boolean selfMatches = containsIgnoreCase(block.callable(), needle) || containsIgnoreCase(block.called(), needle);
        if (!selfMatches && filteredChildren.isEmpty()) {
            return null;
        }

        return new TraceBlock(
                block.id(),
                block.callable(),
                block.module(),
                block.called(),
                block.caller(),
                block.instanceId(),
                block.durationMs(),
                block.memoryBefore(),
                block.memoryAfter(),
                block.inputs(),
                block.inputsAfter(),
                block.output(),
                block.error(),
                filteredChildren
        );
    }

    private static boolean containsIgnoreCase(String value, String needle) {
        if (value == null || needle == null) {
            return false;
        }
        return value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static void updateCommandPreview(Project project, JTextField field) {
        String value = buildCommandPreview(project);
        field.setText(value);
        field.setCaretPosition(0);
    }

    private static String buildCommandPreview(Project project) {
        try {
            Object runProfile = tryGetActiveRunProfile(project);
            if (runProfile == null) {
                return "(no run configuration selected)";
            }
            runProfile = unwrapRunProfile(runProfile);
            if (!isPythonRunConfiguration(runProfile)) {
                return "(la configuración activa no es Python)";
            }
            return buildCmdFromPyConfig(runProfile);
        } catch (Throwable t) {
            return "(could not obtain command)";
        }
    }

    private static Object tryGetActiveRunProfile(Project project) {
        try {
            var session = XDebuggerManager.getInstance(project).getCurrentSession();
            if (session != null) {
                Object profile = invokeObject(session, "getRunProfile");
                if (profile != null) {
                    return profile;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            RunManager runManager = RunManager.getInstance(project);
            RunnerAndConfigurationSettings settings = runManager.getSelectedConfiguration();
            if (settings != null) {
                return settings.getConfiguration();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Object unwrapRunProfile(Object profile) {
        if (profile == null) {
            return null;
        }
        // Some profiles expose the configuration via getConfiguration()
        Object config = invokeObject(profile, "getConfiguration");
        if (config instanceof RunConfiguration) {
            return config;
        }
        return profile;
    }

    private static boolean isPythonRunConfiguration(Object config) {
        if (config == null) {
            return false;
        }
        String name = config.getClass().getName();
        return name.equals("com.jetbrains.python.run.PythonRunConfiguration")
                || name.endsWith(".PythonRunConfiguration")
                || name.endsWith(".AbstractPythonRunConfiguration");
    }

    private static String buildCmdFromPyConfig(Object config) {
        String script = invokeString(config, "getScriptName");
        if (script == null || script.isBlank()) {
            // Some variants use getScriptPath
            script = invokeString(config, "getScriptPath");
        }
        String params = invokeString(config, "getScriptParameters");
        if (params == null || params.isBlank()) {
            // Legacy field name
            params = invokeString(config, "getParameters");
        }

        if (script == null || script.isBlank()) {
            return "(no script in Python configuration)";
        }
        StringBuilder cmd = new StringBuilder();
        cmd.append("python pytraceflow.py -s \"").append(script).append("\"");
        if (params != null && !params.isBlank()) {
            cmd.append(" ").append(params.trim());
        }
        return cmd.toString();
    }

    private static void runGeneration(
            Project project,
            JButton button,
            JTextField cmdField,
            JLabel status,
            Tree tree,
            JEditorPane details,
            String targetCallable,
            JTextField searchField
    ) {
        String commandText = cmdField.getText();
        List<String> parts = ParametersListUtil.parse(commandText);
        if (parts.isEmpty()) {
            status.setText("Empty command; not executed.");
            return;
        }

        if (Boolean.TRUE.equals(button.getClientProperty("busy"))) {
            return;
        }

        String detectedJsonPath = extractJsonPath(parts);
        final String jsonPathArg = detectedJsonPath == null || detectedJsonPath.isBlank() ? null : detectedJsonPath;

        Color originalColor = button.getBackground();
        String originalText = button.getText();
        Color originalFg = button.getForeground();
        button.setText("Generating...");
        button.setBackground(Color.LIGHT_GRAY);
        button.setForeground(Color.WHITE);
        button.putClientProperty("busy", true);

        var executor = com.intellij.openapi.application.ApplicationManager.getApplication();
        executor.executeOnPooledThread(() -> {
            try {
                GeneralCommandLine cmd = new GeneralCommandLine(parts);
                if (project.getBasePath() != null) {
                    cmd.setWorkDirectory(project.getBasePath());
                }
                var output = ExecUtil.execAndGetOutput(cmd);
                if (output.getExitCode() != 0) {
                    String msg = "Generation failed (" + output.getExitCode() + "): " + output.getStderr();
                    SwingUtilities.invokeLater(() -> status.setText(msg));
                } else {
                    String resolved = chooseExistingJson(project, jsonPathArg);
                    if (resolved != null) {
                        BreakpointService.setOverrideJsonPath(resolved);
                        SwingUtilities.invokeLater(() -> {
                            status.setText("Generated OK -> " + resolved);
                            refreshFlow(project, targetCallable, searchField.getText(), status, tree, details);
                        });
                    } else {
                        SwingUtilities.invokeLater(() -> status.setText("No generated JSON found (checked command, ptf.json, pytraceflow.json)"));
                    }
                }
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> status.setText("Execution error: " + ex.getMessage()));
            } finally {
                SwingUtilities.invokeLater(() -> {
                    button.setText(originalText);
                    button.setBackground(originalColor);
                    button.setForeground(originalFg);
                    button.putClientProperty("busy", null);
                });
            }
        });
    }

    private static String extractJsonPath(List<String> parts) {
        for (int i = parts.size() - 1; i >= 0; i--) {
            String token = parts.get(i);
            if (token.toLowerCase(Locale.ROOT).endsWith(".json")) {
                return token;
            }
        }
        return null;
    }

    private static String resolveJsonPath(Project project, String jsonPath) {
        if (jsonPath == null || jsonPath.isBlank()) {
            return null;
        }
        Path path = Paths.get(jsonPath);
        if (!path.isAbsolute() && project.getBasePath() != null) {
            path = Paths.get(project.getBasePath()).resolve(path);
        }
        return path.normalize().toString();
    }

    private static String chooseExistingJson(Project project, String jsonFromCommand) {
        String[] candidates = new String[]{
                jsonFromCommand,
                "ptf.json",
                "pytraceflow.json"
        };
        for (String candidate : candidates) {
            String resolved = resolveJsonPath(project, candidate);
            if (resolved != null && Files.exists(Paths.get(resolved))) {
                return resolved;
            }
        }
        return null;
    }

    private static void applyBaseColors(JPanel root) {
        Color bg = new JBColor(new Color(245, 247, 250), new Color(43, 49, 58));
        root.setBackground(bg);
    }

    private static void applyAccentRowColors(JPanel row, JTextField commandField) {
        // Entre el título (más oscuro) y la franja del buscador (más clara)
        Color rowBg = new JBColor(new Color(228, 234, 242), new Color(46, 54, 65));
        Color border = new JBColor(new Color(190, 198, 210), new Color(70, 80, 95));
        row.setBackground(rowBg);
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, border),
                BorderFactory.createEmptyBorder(4, 6, 4, 6)
        ));
        commandField.setBackground(new JBColor(new Color(250, 252, 255), new Color(62, 70, 82)));
        commandField.setForeground(new JBColor(new Color(18, 26, 33), new Color(225, 235, 245)));
        commandField.setBorder(BorderFactory.createLineBorder(border));
    }

    private static void applyHeaderColors(JPanel header, JLabel status) {
        Color bg = new JBColor(new Color(236, 242, 248), new Color(50, 58, 70));
        header.setBackground(bg);
        status.setForeground(new JBColor(new Color(20, 92, 64), new Color(150, 230, 200)));
    }

    private static void styleTextAreas(Tree tree, JEditorPane details) {
        Color paneBg = new JBColor(new Color(250, 251, 253), new Color(38, 44, 52));
        Color text = new JBColor(new Color(20, 24, 30), new Color(245, 248, 255));
        tree.setBackground(paneBg);
        details.setBackground(paneBg);
        details.setForeground(text);
        details.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        tree.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        details.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
    }

    private static void styleButton(AbstractButton button) {
        Color normal = new JBColor(new Color(242, 244, 250), new Color(108, 116, 132));     // claro
        Color hover = new JBColor(new Color(222, 226, 238), new Color(94, 102, 120));       // medio
        Color pressed = new JBColor(new Color(196, 202, 216), new Color(78, 86, 104));      // oscuro al pulsar
        Color text = new JBColor(new Color(28, 30, 36), Color.WHITE);                       // oscuro en claro, blanco en oscuro
        button.setFocusPainted(false);
        button.setOpaque(true);
        button.setContentAreaFilled(true);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setBackground(normal);
        button.setForeground(text);
        button.putClientProperty("JButton.disabledText", text);
        Color shadow = new JBColor(new Color(182, 186, 196), new Color(82, 88, 102));
        Color highlight = new JBColor(new Color(252, 253, 255), new Color(142, 150, 168));
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1,1,2,2, shadow),
                BorderFactory.createMatteBorder(0,0,1,1, highlight)
        ));
        button.addChangeListener(e -> {
            ButtonModel model = button.getModel();
            if (model.isPressed()) {
                button.setBackground(pressed);
                button.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(2,2,1,1, shadow),
                        BorderFactory.createMatteBorder(0,0,1,1, highlight)
                ));
            } else if (model.isRollover()) {
                button.setBackground(hover);
                button.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(1,1,2,2, shadow),
                        BorderFactory.createMatteBorder(0,0,1,1, highlight)
                ));
            } else {
                button.setBackground(normal);
                button.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(1,1,2,2, shadow),
                        BorderFactory.createMatteBorder(0,0,1,1, highlight)
                ));
            }
        });
    }

    private static String invokeString(Object target, String methodName) {
        Object obj = invokeObject(target, methodName);
        return obj == null ? null : obj.toString();
    }

    private static Object invokeObject(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        try {
            var method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String formatBlockDetails(TraceBlock block) {
        Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();
        StringBuilder sb = new StringBuilder();
        String textHex = toHex(new JBColor(new Color(20, 24, 30), new Color(245, 248, 255)));
        String bgHex = toHex(new JBColor(new Color(250, 251, 253), new Color(38, 44, 52)));
        String sectionHex = toHex(new JBColor(new Color(242, 245, 249), new Color(48, 56, 68)));
        String headerHex = toHex(new JBColor(new Color(224, 232, 245), new Color(66, 78, 96)));
        String borderHex = toHex(new JBColor(new Color(210, 214, 222), new Color(70, 78, 92)));
        sb.append("<html><body style='font-family:Segoe UI, sans-serif; font-size:10px; margin:0; padding:0; color:")
                .append(textHex).append("; background:").append(bgHex).append(";'>");

        sb.append("<div style='background:").append(headerHex).append("; padding:6px 10px; font-weight:700;'>Summary</div>");
        sb.append("<div style='background:").append(sectionHex).append("; padding:8px 10px;'>");
        sb.append("<table style='border-collapse:separate; border-spacing:0 4px; width:100%; border:0;' cellspacing='0' cellpadding='4' width='100%'>");
        row(sb, "Callable", block.displayCallable());
        row(sb, "Module", block.module());
        row(sb, "Called label", block.called());
        row(sb, "Caller", block.caller());
        row(sb, "Duration", block.durationMs() == null ? "n/a" : block.durationMs() + " ms");
        sb.append("</table></div>");

        sb.append("<div style='background:").append(headerHex).append("; padding:6px 10px; font-weight:700; margin-top:6px;'>Memory</div>");
        sb.append("<div style='background:").append(sectionHex).append("; padding:8px 10px;'>");
        sb.append("<table style='border-collapse:separate; border-spacing:0 4px; width:100%; border:0;' cellspacing='0' cellpadding='4' width='100%'>");
        if (block.memoryBefore() != null) {
            row(sb, "Before", "curr=" + toMb(block.memoryBefore().current()) + ", peak=" + toMb(block.memoryBefore().peak()));
        } else {
            row(sb, "Before", "n/a");
        }
        if (block.memoryAfter() != null) {
            row(sb, "After", "curr=" + toMb(block.memoryAfter().current()) + ", peak=" + toMb(block.memoryAfter().peak()));
        } else {
            row(sb, "After", "n/a");
        }
        sb.append("</table></div>");

        sb.append("<div style='background:").append(headerHex).append("; padding:6px 10px; font-weight:700; margin-top:6px;'>Inputs</div>");
        sb.append("<div style='background:").append(sectionHex).append("; padding:8px 10px;'>");
        jsonBlock(sb, block.inputs(), PRETTY_GSON, borderHex);
        sb.append("</div>");

        sb.append("<div style='background:").append(headerHex).append("; padding:6px 10px; font-weight:700; margin-top:6px;'>Outputs</div>");
        sb.append("<div style='background:").append(sectionHex).append("; padding:8px 10px;'>");
        jsonBlock(sb, block.output(), PRETTY_GSON, borderHex);
        sb.append("</div>");

        sb.append("<div style='background:").append(headerHex).append("; padding:6px 10px; font-weight:700; margin-top:6px;'>Error</div>");
        sb.append("<div style='background:").append(sectionHex).append("; padding:8px 10px;'>");
        jsonBlock(sb, block.error(), PRETTY_GSON, borderHex);
        sb.append("</div>");

        sb.append("<div style='background:").append(headerHex).append("; padding:6px 10px; font-weight:700; margin-top:6px;'>Calls</div>");
        sb.append("<div style='background:").append(sectionHex).append("; padding:8px 10px;'>");
        if (block.calls().isEmpty()) {
            sb.append("(none)");
        } else {
            sb.append("<ul style='margin:4px 0 0 12px; padding:0; list-style:disc;'>");
            for (TraceBlock child : block.calls()) {
                sb.append("<li style='margin-bottom:4px; list-style-position:outside;'>")
                        .append(escape(child.displayCallable()))
                        .append(" (")
                        .append(child.durationMs() == null ? "n/a" : child.durationMs() + " ms")
                        .append(")")
                        .append("</li>");
            }
            sb.append("</ul>");
        }
        sb.append("</div>");

        sb.append("</body></html>");
        return sb.toString();
    }

    private static void row(StringBuilder sb, String label, Object value) {
        sb.append("<tr><td style='padding-right:8px;'><b>").append(escape(label)).append("</b></td><td>")
                .append(escape(value))
                .append("</td></tr>");
    }

    private static void jsonBlock(StringBuilder sb, String value, Gson prettyGson, String borderHex) {
        String v = prettyOrDash(value);
        if (v.startsWith("{") || v.startsWith("[")) {
            try {
                JsonElement el = JsonParser.parseString(v);
                sb.append(renderJson(el, prettyGson, borderHex));
                return;
            } catch (Exception ignored) {
                // fall through
            }
        }
        sb.append("<pre style='margin:0; white-space:pre-wrap; word-break:break-word;'>")
                .append(escape(v))
                .append("</pre>");
    }

    private static String renderJson(JsonElement el, Gson prettyGson, String borderHex) {
        if (el == null || el.isJsonNull()) {
            return "-";
        }
        if (el.isJsonPrimitive()) {
            return escape(el.getAsJsonPrimitive().toString());
        }
        if (el.isJsonObject()) {
            StringBuilder sb = new StringBuilder();
            sb.append("<table style='border-collapse:collapse; width:100%; margin-top:4px;' cellspacing='0' cellpadding='4'>");
            JsonObject obj = el.getAsJsonObject();
            for (String key : obj.keySet()) {
                sb.append("<tr><td><b>").append(escape(key)).append("</b></td><td>");
                sb.append(renderJson(obj.get(key), prettyGson, borderHex));
                sb.append("</td></tr>");
            }
            sb.append("</table>");
            return sb.toString();
        }
        if (el.isJsonArray()) {
            JsonArray arr = el.getAsJsonArray();
            StringBuilder sb = new StringBuilder();
            sb.append("<ul style='margin:4px 0 0 12px; padding:0; list-style:disc;'>");
            int idx = 0;
            for (JsonElement e : arr) {
                sb.append("<li style='margin-bottom:4px; list-style-position:outside;'>");
                if (e.isJsonPrimitive()) {
                    sb.append(renderJson(e, prettyGson, borderHex));
                } else {
                    sb.append(renderJson(e, prettyGson, borderHex));
                }
                sb.append("</li>");
                idx++;
            }
            sb.append("</ul>");
            return sb.toString();
        }
        // fallback pretty string
        return "<pre style='margin:0; white-space:pre-wrap; word-break:break-word;'>"
                + escape(prettyGson.toJson(el))
                + "</pre>";
    }

    private static String prettyOrDash(String value) {
        if (value == null) {
            return "-";
        }
        String trimmed = value.trim();
        if (trimmed.equalsIgnoreCase("null") || trimmed.isBlank()) {
            return "-";
        }
        return trimmed;
    }

    private static String toMb(Long bytes) {
        if (bytes == null) {
            return "n/a";
        }
        double mb = bytes / (1024.0 * 1024.0);
        return String.format("%.1f MB", mb);
    }

    private static String toHex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    private static String escape(Object value) {
        if (value == null) return "-";
        String s = value.toString();
        return s
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static boolean isFirstNonWhitespaceElementOnLine(Document document, int offset, int line) {
        int lineStart = document.getLineStartOffset(line);
        CharSequence text = document.getCharsSequence();
        for (int i = lineStart; i < offset; i++) {
            if (!Character.isWhitespace(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasBreakpoint(Project project, String fileUrl, int line) {
        XBreakpoint<?>[] all = XDebuggerManager.getInstance(project).getBreakpointManager().getAllBreakpoints();
        for (XBreakpoint<?> breakpoint : all) {
            if (breakpoint instanceof XLineBreakpoint<?> lineBreakpoint
                    && fileUrl.equals(lineBreakpoint.getFileUrl())
                    && lineBreakpoint.getLine() == line) {
                return true;
            }
        }
        return false;
    }
}
