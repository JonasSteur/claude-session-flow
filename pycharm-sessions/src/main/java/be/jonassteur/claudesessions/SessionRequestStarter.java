package be.jonassteur.claudesessions;

import com.intellij.ide.AppLifecycleListener;
import java.util.List;
import org.jetbrains.annotations.NotNull;

public final class SessionRequestStarter implements AppLifecycleListener {
    @Override
    public void appFrameCreated(@NotNull List<String> commandLineArgs) {
        SessionRequestWatcher.start();
    }
}
