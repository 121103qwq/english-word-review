package cn.wordwatch.app;

/** Serial local study work survives Activity recreation; network transfers use their own executor. */
final class StudyWork {
    private static final java.util.concurrent.ExecutorService IO = java.util.concurrent.Executors.newSingleThreadExecutor(r ->
            new Thread(() -> { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run(); }, "wordwatch-study"));
    private StudyWork() {}
    static void execute(Runnable work) { IO.execute(work); }
}
