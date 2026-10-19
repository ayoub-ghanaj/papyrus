package papyrus;

/** Makes asynchronous desktop failures fail CI instead of leaving a dialog or stalled pulse running. */
final class UiTestSupport {
    private static final java.util.concurrent.ScheduledExecutorService ticks = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "desktop-test-steps"); thread.setDaemon(true); return thread;
    });
    static void next(Runnable step, int delayMillis) {
        // Keep test deadlines independent of JavaFX animation pulses.
        ticks.schedule(() -> javafx.application.Platform.runLater(step), delayMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
    }
    static volatile String step = "startup";
    static void start() {
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            System.err.println("Desktop failure at " + step + " on " + thread.getName());
            error.printStackTrace(); System.exit(1);
        });
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(180_000); } catch (InterruptedException ignored) { return; }
            System.err.println("Desktop check timed out at " + step);
            Thread.getAllStackTraces().forEach((thread, frames) -> {
                System.err.println(thread.getName() + " " + thread.getState());
                for (StackTraceElement frame : frames) System.err.println("  " + frame);
            });
            System.exit(1);
        }, "desktop-test-timeout");
        watchdog.setDaemon(true); watchdog.start();
    }
    static void progress(String value) { step = value; System.out.println("Desktop: " + value); }
}
