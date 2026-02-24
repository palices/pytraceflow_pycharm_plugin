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
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

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
                .setRequestFocus(false)
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
        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        header.add(refresh);
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

        refresh.addActionListener(e -> refreshFlow(project, targetCallable, status, tree, details));
        refreshFlow(project, targetCallable, status, tree, details);

        root.setPreferredSize(new Dimension(1080, 520));
        return root;
    }

    private static void refreshFlow(
            Project project,
            String targetCallable,
            JLabel status,
            Tree tree,
            JTextArea details
    ) {
        ExecutionFlowData flow = BreakpointService.readExecutionFlow(project);

        DefaultMutableTreeNode rootUi = new DefaultMutableTreeNode(
                flow.sourceFile() == null ? "Flow JSON (no encontrado)" : "Flow JSON: " + flow.sourceFile()
        );
        for (TraceBlock block : flow.roots()) {
            rootUi.add(buildTree(block));
        }

        tree.setModel(new DefaultTreeModel(rootUi));
        expandAll(tree);

        List<TraceBlock> matches = BreakpointService.findByCallable(flow.roots(), targetCallable);
        String callableText = targetCallable == null || targetCallable.isBlank() ? "(sin callable detectado)" : targetCallable;
        status.setText("Callable breakpoint: " + callableText + " | Roots: " + flow.roots().size() + " | Matches: " + matches.size());

        if (!matches.isEmpty()) {
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
            details.setText("No se encontraron bloques de ejecucion en el JSON.");
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

    private static String formatBlockDetails(TraceBlock block) {
        StringBuilder sb = new StringBuilder();
        appendLine(sb, "id", block.id());
        appendLine(sb, "callable", block.callable());
        appendLine(sb, "module", block.module());
        appendLine(sb, "called", block.called());
        appendLine(sb, "caller", block.caller());
        appendLine(sb, "instance_id", block.instanceId());
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
        sb.append("\ninputs_after:\n").append(block.inputsAfter()).append("\n");
        sb.append("\noutput:\n").append(block.output()).append("\n");
        sb.append("\nerror:\n").append(block.error()).append("\n");

        List<String> callLabels = new ArrayList<>();
        for (TraceBlock child : block.calls()) {
            callLabels.add(child.label());
        }
        sb.append("\ncalls_count: ").append(block.calls().size()).append("\n");
        if (!callLabels.isEmpty()) {
            sb.append("calls:\n");
            for (String label : callLabels) {
                sb.append(" - ").append(label).append("\n");
            }
        }
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
