package be.jonassteur.claudesessions;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.SerializedName;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.openapi.wm.IdeFrame;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.terminal.ui.TerminalWidget;
import com.intellij.ui.content.Content;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.ui.ColorIcon;
import com.jediterm.terminal.ProcessTtyConnector;
import com.jediterm.terminal.TtyConnector;
import java.awt.Color;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.jetbrains.plugins.terminal.ShellTerminalWidget;
import org.jetbrains.plugins.terminal.TerminalToolWindowManager;
import org.jetbrains.plugins.terminal.ui.TerminalContainer;

public final class SessionRequestWatcher {
    private static final Logger LOG = Logger.getInstance(SessionRequestWatcher.class);
    private static final Path REQUEST_DIRECTORY = Path.of(System.getProperty("user.home"), ".claude", "pycharm-session-requests");
    private static final String REQUEST_SUFFIX = ".json";
    private static final String TAKEN_SUFFIX = ".taken";
    private static final String RESULT_SUFFIX = ".result";
    private static final long POLL_INTERVAL_MS = 500;
    private static final Pattern SESSION_ID_PATTERN = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final int ROLE_ICON_SIZE = 16;
    private static final int ROLE_ICON_COLOR_SIZE = 10;
    private static final AtomicBoolean isStarted = new AtomicBoolean(false);

    private record SessionRequest(
        String title,
        String directory,
        @SerializedName("prompt_file") String promptFile,
        @SerializedName("session_id") String sessionId,
        Long pid,
        @SerializedName("tab_color") String tabColor,
        @SerializedName("role_color") String roleColor
    ) {
    }

    private SessionRequestWatcher() {
    }

    public static void start() {
        if (!isStarted.compareAndSet(false, true)) {
            return;
        }
        AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
            SessionRequestWatcher::handleRequests, POLL_INTERVAL_MS, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS
        );
    }

    private static void handleRequests() {
        try (DirectoryStream<Path> requests = Files.newDirectoryStream(REQUEST_DIRECTORY, "*" + REQUEST_SUFFIX)) {
            for (Path request : requests) {
                handleRequest(request);
            }
        } catch (NoSuchFileException ignored) {
        } catch (IOException | RuntimeException exception) {
            LOG.warn("Could not read the Claude session requests", exception);
        }
    }

    private static void handleRequest(Path request) {
        String fileName = request.getFileName().toString();
        String requestId = fileName.substring(0, fileName.length() - REQUEST_SUFFIX.length());
        Path takenRequest = REQUEST_DIRECTORY.resolve(requestId + TAKEN_SUFFIX);
        Path result = REQUEST_DIRECTORY.resolve(requestId + RESULT_SUFFIX);
        try {
            Files.move(request, takenRequest, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            return;
        }

        SessionRequest sessionRequest;
        try {
            sessionRequest = new Gson().fromJson(Files.readString(takenRequest), SessionRequest.class);
            Files.delete(takenRequest);
        } catch (IOException | JsonParseException exception) {
            writeResult(result, "error: could not read the request: " + exception.getMessage());
            return;
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                boolean isColorRequest = sessionRequest != null && sessionRequest.pid() != null;
                writeResult(result, isColorRequest ? colorSessionTab(sessionRequest) : openSessionTab(sessionRequest));
            } catch (RuntimeException exception) {
                LOG.warn("Could not handle a Claude session request", exception);
                writeResult(result, "error: could not handle the request: " + exception.getMessage());
            }
        });
    }

    private static String colorSessionTab(SessionRequest request) {
        Color tabColor;
        Color roleColor;
        try {
            tabColor = decodeColor(request.tabColor());
            roleColor = decodeColor(request.roleColor());
        } catch (NumberFormatException exception) {
            return "error: not a colour: " + exception.getMessage();
        }

        Set<Long> sessionProcessIds = new HashSet<>();
        Optional<ProcessHandle> process = ProcessHandle.of(request.pid());
        while (process.isPresent()) {
            sessionProcessIds.add(process.get().pid());
            process = process.get().parent();
        }

        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            TerminalToolWindowManager terminalManager = TerminalToolWindowManager.getInstance(project);
            for (TerminalWidget terminal : terminalManager.getTerminalWidgets()) {
                TtyConnector connector = terminal.getTtyConnector();
                ProcessTtyConnector processConnector = connector == null ? null : ShellTerminalWidget.getProcessTtyConnector(connector);
                TerminalContainer container = terminalManager.getContainer(terminal);
                if (processConnector == null || container == null || !sessionProcessIds.contains(processConnector.getProcess().pid())) {
                    continue;
                }
                Content tab = container.getContent();
                tab.setTabColor(tabColor);
                tab.putUserData(ToolWindow.SHOW_CONTENT_ICON, roleColor != null);
                tab.setIcon(roleColor == null ? null : new ColorIcon(ROLE_ICON_SIZE, ROLE_ICON_COLOR_SIZE, roleColor, false));
                return "ok: coloured the tab in the PyCharm project " + project.getName();
            }
        }
        return "error: no PyCharm terminal tab runs process " + request.pid();
    }

    private static String openSessionTab(SessionRequest request) {
        if (request == null || request.title() == null || request.directory() == null) {
            return "error: the request needs a title and a directory";
        }
        if (!Path.of(request.directory()).isAbsolute() || !Files.isDirectory(Path.of(request.directory()))) {
            return "error: no directory at " + request.directory();
        }
        String command;
        if (request.sessionId() != null) {
            if (!SESSION_ID_PATTERN.matcher(request.sessionId()).matches()) {
                return "error: not a session id: " + request.sessionId();
            }
            command = "claude --resume " + request.sessionId();
        } else if (request.promptFile() != null && Files.isRegularFile(Path.of(request.promptFile()))) {
            command = "claude -n " + quoteForShell(request.title()) + " "
                + quoteForShell("Read " + request.promptFile() + " and follow it.");
        } else {
            return "error: the request needs a session_id or an existing prompt_file";
        }
        Project project = findProject(Path.of(request.directory()).normalize());
        if (project == null) {
            return "error: PyCharm has no open project";
        }

        TerminalToolWindowManager terminalManager = TerminalToolWindowManager.getInstance(project);
        TerminalWidget terminal = terminalManager.createShellWidget(request.directory(), request.title(), true, true);
        terminal.sendCommandToExecute(command);
        terminalManager.getToolWindow().activate(null);
        return "ok: opened in the PyCharm project " + project.getName();
    }

    private static Color decodeColor(String hex) {
        return hex == null ? null : Color.decode(hex);
    }

    private static Project findProject(Path directory) {
        Project closestProject = null;
        int closestBasePathLength = -1;
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            String basePath = project.getBasePath();
            if (basePath != null && directory.startsWith(Path.of(basePath)) && basePath.length() > closestBasePathLength) {
                closestProject = project;
                closestBasePathLength = basePath.length();
            }
        }
        if (closestProject != null) {
            return closestProject;
        }
        IdeFrame lastFocusedFrame = IdeFocusManager.getGlobalInstance().getLastFocusedFrame();
        return lastFocusedFrame == null ? null : lastFocusedFrame.getProject();
    }

    private static String quoteForShell(String text) {
        return "'" + text.replace("'", "'\\''") + "'";
    }

    private static void writeResult(Path result, String outcome) {
        Path unfinishedResult = result.resolveSibling(result.getFileName() + ".tmp");
        try {
            Files.writeString(unfinishedResult, outcome);
            Files.move(unfinishedResult, result, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            LOG.warn("Could not write the Claude session result " + result, exception);
        }
    }
}
