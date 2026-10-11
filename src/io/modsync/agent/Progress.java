package io.modsync.agent;

import java.io.PrintStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Progress reporting with a console renderer. The Swing variant lives in {@link GuiProgress} and is
 * created reflectively-through-try/catch so that this class stays loadable on JREs without
 * {@code java.desktop} (dedicated server runtimes).
 */
public class Progress implements AutoCloseable {

    protected final PrintStream out;
    protected final boolean interactive;

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile String phase = "";
    private volatile String detail = "";
    private volatile long totalBytes;
    private volatile int totalFiles;
    private long doneBytes;
    private int doneFiles;
    private boolean lineDirty;
    private long lastRenderNanos;

    protected Progress(PrintStream out) {
        this.out = out;
        this.interactive = System.console() != null;
    }

    /** Returns a GUI reporter when possible, otherwise the console reporter. */
    public static Progress create(boolean wantGui, PrintStream out, String title) {
        if (wantGui) {
            try {
                return new GuiProgress(out, title);
            } catch (Throwable t) {
                out.println("[modsync] 图形界面不可用，改用控制台进度: " + t);
            }
        }
        return new Progress(out);
    }

    public final boolean isCancelled() {
        return cancelled.get();
    }

    public final void cancel() {
        cancelled.set(true);
    }

    public final String phase() {
        return phase;
    }

    public final String detail() {
        return detail;
    }

    public final int percent() {
        if (totalBytes > 0) {
            return (int) Math.min(100, doneBytes * 100 / totalBytes);
        }
        if (totalFiles > 0) {
            return Math.min(100, doneFiles * 100 / totalFiles);
        }
        return 0;
    }

    public final long doneBytes() {
        return doneBytes;
    }

    public final int doneFiles() {
        return doneFiles;
    }

    public final int totalFiles() {
        return totalFiles;
    }

    public final long totalBytes() {
        return totalBytes;
    }

    // ------------------------------------------------------------ lifecycle

    public void phase(String name) {
        synchronized (this) {
            this.phase = name == null ? "" : name;
            this.detail = "";
            if (interactive) {
                renderLine(true);
            } else {
                out.println("[modsync] " + phase + " ...");
                out.flush();
            }
        }
        uiUpdate();
    }

    public void setTotal(long bytes, int files) {
        synchronized (this) {
            this.totalBytes = Math.max(0, bytes);
            this.totalFiles = Math.max(0, files);
            this.doneBytes = 0;
            this.doneFiles = 0;
        }
        uiUpdate();
    }

    public void fileStart(String name, long size) {
        synchronized (this) {
            this.detail = name + (size > 0 ? " (" + Syncer.humanBytes(size) + ")" : "");
        }
        uiUpdate();
    }

    public void addBytes(long n) {
        boolean shouldRender;
        synchronized (this) {
            doneBytes += n;
            long now = System.nanoTime();
            shouldRender = now - lastRenderNanos > 60_000_000L;
            if (shouldRender) {
                lastRenderNanos = now;
            }
        }
        if (shouldRender) {
            uiUpdate();
        }
    }

    public void fileDone(String name) {
        synchronized (this) {
            doneFiles++;
            this.detail = name;
        }
        uiUpdate();
    }

    public void info(String message) {
        synchronized (this) {
            finishLine();
            out.println("[modsync] " + message);
            out.flush();
        }
        uiUpdate();
    }

    public void warn(String message) {
        info("警告: " + message);
    }

    /** Prints an unmissable multi-line banner (used for version mismatches and similar). */
    public void banner(String text) {
        String line = "=".repeat(68);
        synchronized (this) {
            finishLine();
            out.println("[modsync] " + line);
            for (String part : text.split("\n", -1)) {
                out.println("[modsync] " + part);
            }
            out.println("[modsync] " + line);
            out.flush();
        }
        uiUpdate();
    }

    /**
     * Shows a reminder the user has to notice before the game starts (GUI only; no-op otherwise).
     *
     * @param seconds how long the reminder stays if the user does not dismiss it
     */
    public void holdWarning(String title, String body, int seconds) {
        // console-only reporter: banner() already printed it
    }

    // ------------------------------------------------------------ rendering

    protected void uiUpdate() {
        synchronized (this) {
            renderLine(false);
        }
    }

    private void renderLine(boolean force) {
        if (!interactive) {
            return;
        }
        StringBuilder sb = new StringBuilder(112);
        sb.append('\r').append("[modsync] [");
        int width = 28;
        int pct = percent();
        int filled = width * pct / 100;
        for (int i = 0; i < width; i++) {
            sb.append(i < filled ? '#' : '.');
        }
        sb.append("] ");
        sb.append(String.format("%3d%%", pct));
        if (totalBytes > 0) {
            sb.append(' ').append(Syncer.humanBytes(doneBytes)).append('/').append(Syncer.humanBytes(totalBytes));
        }
        if (totalFiles > 0) {
            sb.append("  ").append(doneFiles).append('/').append(totalFiles);
        }
        String d = detail;
        if (d != null && !d.isEmpty()) {
            if (d.length() > 32) {
                d = "…" + d.substring(d.length() - 31);
            }
            sb.append("  ").append(d);
        }
        out.print(sb);
        out.flush();
        lineDirty = true;
    }

    protected final void finishLine() {
        if (lineDirty) {
            out.println();
            out.flush();
            lineDirty = false;
        }
    }

    protected void closeUi() {
        // nothing by default
    }

    @Override
    public final void close() {
        synchronized (this) {
            finishLine();
        }
        try {
            closeUi();
        } catch (Throwable ignored) {
            // closing the UI must never fail a sync
        }
    }
}
