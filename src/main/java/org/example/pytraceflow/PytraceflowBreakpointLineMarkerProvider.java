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
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
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

        JLabel status = new JLabel();
        JButton refresh = new JButton("Refresh");
        JTextField searchField = new JTextField(24);
        JButton searchButton = new JButton("Buscar");
        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        header.add(refresh);
        header.add(new JLabel("Filtro callable/called:"));
        header.add(searchField);
        header.add(searchButton);
        header.add(status);
        root.add(header, BorderLayout.NORTH);

        DefaultMutableTreeNode placeholder = new DefaultMutableTreeNode("Sin datos");
        Tree tree = new Tree(new DefaultTreeModel(placeholder));
        JTextArea details = new JTextArea();
        details.setEditable(false);
        details.setLineWrap(true);
        details.setWrapStyleWord(true);

        JSplitPane splitPane = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT,
                ScrollPaneFactory.createScrollPane(tree),
                ScrollPaneFactory.createScrollPane(details)
        );
        splitPane.setResizeWeight(0.38);
        root.add(splitPane, BorderLayout.CENTER);

        tree.addTreeSelectionListener(e -> {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
            if (node == null) {
                details.setText("");
                return;
            }
            Object user = node.getUserObject();
            if (user instanceof TraceBlock block) {
                details.setText(formatBlockDetails(block));
                details.setCaretPosition(0);
            } else {
                details.setText(String.valueOf(user));
            }
        });

        refresh.addActionListener(e -> refreshFlow(project, targetCallable, searchField.getText(), status, tree, details));
        searchButton.addActionListener(e -> refreshFlow(project, targetCallable, searchField.getText(), status, tree, details));
        searchField.addActionListener(e -> refreshFlow(project, targetCallable, searchField.getText(), status, tree, details));
        refreshFlow(project, targetCallable, "", status, tree, details);

        root.setPreferredSize(new Dimension(1080, 520));
        return root;
    }

    private static void refreshFlow(
            Project project,
            String targetCallable,
            String filterText,
            JLabel status,
            Tree tree,
            JTextArea details
    ) {
        ExecutionFlowData flow = BreakpointService.readExecutionFlow(project);

        DefaultMutableTreeNode rootUi = new DefaultMutableTreeNode(
                flow.sourceFile() == null ? "Flow JSON (no encontrado)" : "Flow JSON: " + flow.sourceFile()
        );
        List<TraceBlock> filteredRoots = filterByText(flow.roots(), filterText);

        tree.setModel(new DefaultTreeModel(rootUi));
        expandAll(tree);

        List<TraceBlock> matches = BreakpointService.findByCallable(flow.roots(), targetCallable);
        String callableText = targetCallable == null || targetCallable.isBlank() ? "(sin callable detectado)" : targetCallable;
        String filterLabel = (filterText == null || filterText.isBlank()) ? "sin filtro" : "\"" + filterText + "\"";
        status.setText("Callable breakpoint: " + callableText
                + " | Roots: " + flow.roots().size()
                + " | Matches: " + matches.size()
                + " | Filtro: " + filterLabel);

        boolean filterApplied = filterText != null && !filterText.isBlank();

        // Sin filtro y sin coincidencias: no mostrar nada
        if (!filterApplied && matches.isEmpty()) {
            rootUi.removeAllChildren();
            tree.setModel(new DefaultTreeModel(rootUi));
            details.setText("No se encontraron coincidencias para el callable detectado.");
            tree.clearSelection();
            return;
        }

        if (!filterApplied && !matches.isEmpty()) {
            TreePath matchPath = findPathForBlock(rootUi, matches.get(0));
            if (matchPath != null) {
                // Build tree only with the matched block's subtree
                rootUi.removeAllChildren();
                rootUi.add(buildTree(matches.get(0)));
                tree.setModel(new DefaultTreeModel(rootUi));
                expandAll(tree);

                TreePath refreshedPath = findPathForBlock(rootUi, matches.get(0));
                tree.setSelectionPath(refreshedPath);
                tree.scrollPathToVisible(refreshedPath);
                return;
            }
        }

        // If there are matches via filter, show only filtered roots
        rootUi.removeAllChildren();
        for (TraceBlock block : filteredRoots) {
            rootUi.add(buildTree(block));
        }
        tree.setModel(new DefaultTreeModel(rootUi));
        expandAll(tree);

        if (rootUi.getChildCount() > 0) {
            DefaultMutableTreeNode firstChild = (DefaultMutableTreeNode) rootUi.getChildAt(0);
            TreePath firstPath = new TreePath(firstChild.getPath());
            tree.setSelectionPath(firstPath);
            tree.scrollPathToVisible(firstPath);
        } else {
            details.setText("No se encontraron bloques de ejecucion en el JSON.");
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

    private static String formatBlockDetails(TraceBlock block) {
        StringBuilder sb = new StringBuilder();
        appendLine(sb, "callable", block.displayCallable());
        appendLine(sb, "duration_ms", block.durationMs());

        if (block.memoryBefore() != null) {
            appendLine(sb, "memory_before.current", block.memoryBefore().current());
            appendLine(sb, "memory_before.peak", block.memoryBefore().peak());
        } else {
            appendLine(sb, "memory_before", "null");
        }

        if (block.memoryAfter() != null) {
            appendLine(sb, "memory_after.current", block.memoryAfter().current());
            appendLine(sb, "memory_after.peak", block.memoryAfter().peak());
        } else {
            appendLine(sb, "memory_after", "null");
        }

        sb.append("\ninputs:\n").append(block.inputs()).append("\n");
        sb.append("\noutputs:\n").append(block.output()).append("\n");
        sb.append("\nerror:\n").append(block.error()).append("\n");
        return sb.toString();
    }

    private static void appendLine(StringBuilder sb, String key, Object value) {
        sb.append(key).append(": ").append(value).append("\n");
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
