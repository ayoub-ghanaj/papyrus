package papyrus;

import javafx.application.Platform;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** One queue owns background library work; completion handlers always run on the JavaFX thread. */
final class UiTasks implements AutoCloseable {
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "papyrus-library"); thread.setDaemon(true); return thread;
    });
    <T> void submit(Callable<T> task, Consumer<T> success, Consumer<Exception> failure) {
        worker.submit(() -> {
            try { T result = task.call(); Platform.runLater(() -> success.accept(result)); }
            catch (Exception ex) { Platform.runLater(() -> failure.accept(ex)); }
        });
    }
    @Override public void close() { worker.shutdownNow(); }
}
