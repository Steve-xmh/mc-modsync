package io.modsync.agent;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.PrintStream;

/**
 * Swing progress window, shown before the game starts. Instantiated only from
 * {@link Progress#create(PrintStream, String)} inside a try/catch so that headless
 * environments fall back to the console renderer.
 */
final class GuiProgress extends Progress {

    /**
     * Not volatile-by-accident: {@link #build(String)} runs on the AWT thread while the sync thread may
     * call {@link #closeUi()} at any moment (a fast sync can finish before the window even exists).
     */
    private volatile JFrame frame;
    private JProgressBar bar;
    private JLabel phaseLabel;
    private JLabel detailLabel;
    private volatile boolean closed;

    GuiProgress(PrintStream out, String title) {
        super(out);
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException("headless");
        }
        SwingUtilities.invokeLater(() -> build(title));
    }

    private void build(String title) {
        // 本方法在 AWT 线程上执行，而同步线程随时可能调用 closeUi()（同步很快时窗口还没建好就结束了）。
        // 所以先只用局部变量，全部成功后再发布到 frame 字段，避免 closeUi() 把字段置空后这里再 NPE。
        JFrame built = null;
        try {
            built = new JFrame(title == null || title.isBlank() ? "ModSync" : title);
            built.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            built.setAlwaysOnTop(true);

            JPanel root = new JPanel(new BorderLayout(0, 10));
            root.setBorder(BorderFactory.createEmptyBorder(16, 18, 14, 18));

            phaseLabel = new JLabel("准备中 ...");
            phaseLabel.setFont(phaseLabel.getFont().deriveFont(Font.BOLD, 14f));
            detailLabel = new JLabel(" ");
            detailLabel.setFont(detailLabel.getFont().deriveFont(Font.PLAIN, 12f));

            JPanel labels = new JPanel();
            labels.setLayout(new BoxLayout(labels, BoxLayout.Y_AXIS));
            labels.add(phaseLabel);
            labels.add(Box.createVerticalStrut(4));
            labels.add(detailLabel);

            JProgressBar progressBar = new JProgressBar(0, 100);
            progressBar.setStringPainted(true);
            progressBar.setPreferredSize(new Dimension(420, 22));
            bar = progressBar;

            JButton cancel = new JButton("跳过同步");
            cancel.addActionListener(e -> {
                cancel();
                if (phaseLabel != null) {
                    phaseLabel.setText("正在取消 ...");
                }
            });

            JPanel bottom = new JPanel(new BorderLayout());
            bottom.add(progressBar, BorderLayout.CENTER);
            JPanel south = new JPanel();
            south.add(cancel);
            bottom.add(south, BorderLayout.EAST);

            root.add(labels, BorderLayout.NORTH);
            root.add(bottom, BorderLayout.CENTER);

            built.setContentPane(root);
            built.pack();
            built.setLocationRelativeTo(null);
            built.setVisible(true);
            if (closed) {
                // 同步在窗口建好之前就结束了：立刻收掉，不留下一个没人管的窗口。
                built.setVisible(false);
                built.dispose();
                return;
            }
            frame = built;
            uiUpdate();
        } catch (Throwable t) {
            out.println("[modsync] 无法创建进度窗口: " + t);
            if (built != null) {
                built.dispose();
            }
        }
    }

    @Override
    protected void uiUpdate() {
        if (frame == null) {
            return;
        }
        final String phase = phase();
        final String detail = detail();
        final int pct = percent();
        final String text = totalBytes() > 0
                ? pct + "%  " + Syncer.humanBytes(doneBytes()) + " / " + Syncer.humanBytes(totalBytes())
                        + "  (" + doneFiles() + "/" + totalFiles() + ")"
                : (totalFiles() > 0 ? pct + "%  (" + doneFiles() + "/" + totalFiles() + ")" : "");
        SwingUtilities.invokeLater(() -> {
            if (closed) {
                return;
            }
            if (phaseLabel != null && !phase.isEmpty()) {
                phaseLabel.setText(phase);
            }
            if (detailLabel != null) {
                detailLabel.setText(detail == null || detail.isEmpty() ? " " : detail);
            }
            if (bar != null) {
                bar.setValue(pct);
                bar.setString(text);
            }
        });
    }

    @Override
    protected void closeUi() {
        closed = true;
        final JFrame f = frame;
        frame = null;
        if (f != null) {
            SwingUtilities.invokeLater(() -> {
                f.setVisible(false);
                f.dispose();
            });
        }
    }

    /**
     * Reminder window for version mismatches: stays on top for a few seconds (or until dismissed)
     * so the player sees it before the game window opens.
     */
    @Override
    public void holdWarning(String title, String body, int seconds) {
        if (seconds <= 0) {
            return;
        }
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            try {
                JFrame warn = new JFrame(title == null || title.isBlank() ? "ModSync 提示" : title);
                warn.setAlwaysOnTop(true);
                warn.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

                javax.swing.JTextArea area = new javax.swing.JTextArea(body);
                area.setEditable(false);
                area.setLineWrap(true);
                area.setWrapStyleWord(true);
                area.setColumns(52);
                area.setRows(9);
                area.setBackground(warn.getBackground());
                area.setBorder(BorderFactory.createEmptyBorder(10, 12, 6, 12));

                final int[] left = {seconds};
                JButton ok = new JButton("继续启动（" + seconds + " 秒后自动关闭）");
                javax.swing.Timer timer = new javax.swing.Timer(1000, null);
                timer.addActionListener(e -> {
                    left[0]--;
                    if (left[0] <= 0) {
                        timer.stop();
                        warn.dispose();
                        done.countDown();
                    } else {
                        ok.setText("继续启动（" + left[0] + " 秒后自动关闭）");
                    }
                });
                ok.addActionListener(e -> {
                    timer.stop();
                    warn.dispose();
                    done.countDown();
                });

                JPanel south = new JPanel();
                south.add(ok);
                warn.add(new javax.swing.JScrollPane(area), BorderLayout.CENTER);
                warn.add(south, BorderLayout.SOUTH);
                warn.pack();
                warn.setLocationRelativeTo(null);
                warn.setVisible(true);
                timer.start();
            } catch (Throwable t) {
                out.println("[modsync] 无法显示提示窗口: " + t);
                done.countDown();
            }
        });
        try {
            done.await(seconds + 15L, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
