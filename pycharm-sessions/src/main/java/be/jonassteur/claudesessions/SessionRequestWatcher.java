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
import com.intellij.terminal.ui.TerminalWidget;
import com.intellij.util.concurrency.AppExecutorUtil;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jetbrains.plugins.terminal.TerminalToolWindowManager;

public final class SessionRequestWatcher {
    private static final Logger LOG = Logger.getInstance(SessionRequestWatcher.class);
    private static final Path REQUEST_DIRECTORY = Path.of(System.getProperty("user.home"), ".claude", "pycharm-session-requests");
    private static final String REQUEST_SUFFIX = ".json";
    private static final String TAKEN_SUFFIX = ".taken";
    private static final String RESULT_SUFFIX = ".result";
    private static final long POLL_INTERVAL_MS = 500;
    private static final AtomicBoolean isStarted = new AtomicBoolean(false);

    private record SessionRequest(String title, @SerializedName("prompt_file") String promptFile, String directory) {
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
                writeResult(result, openSessionTab(sessionRequest));
            } catch (RuntimeException exception) {
                LOG.warn("Could not open a terminal tab for a Claude session", exception);
                writeResult(result, "error: could not open a terminal tab: " + exception.getMessage());
            }
        });
    }

    private static String openSessionTab(SessionRequest request) {
        if (request == null || request.title() == null || request.promptFile() == null || request.directory() == null) {
            return "error: the request needs a title, prompt_file and directory";
        }
        if (!Files.isRegularFile(Path.of(request.promptFile()))) {
            return "error: no prompt file at " + request.promptFile();
        }
        if (!Path.of(request.directory()).isAbsolute() || !Files.isDirectory(Path.of(request.directory()))) {
            return "error: no directory at " + request.directory();
        }
        Project project = findProject(Path.of(request.directory()).normalize());
        if (project == null) {
            return "error: PyCharm has no open project";
        }

        TerminalToolWindowManager terminalManager = TerminalToolWindowManager.getInstance(project);
        TerminalWidget terminal = terminalManager.createShellWidget(request.directory(), request.title(), true, true);
        terminal.sendCommandToExecute(
            "claude -n " + quoteForShell(request.title()) + " "
                + quoteForShell("Read " + request.promptFile() + " and follow it.")
        );
        terminalManager.getToolWindow().activate(null);
        return "ok: opened in the PyCharm project " + project.getName();
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
