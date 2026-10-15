package com.localai.launcher;

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicReference;

final class FirstRunSetupWindow implements AutoCloseable {
    private final JFrame frame = new JFrame("Local AI Assistant setup");
    private final JLabel message = new JLabel("Preparing your private local model...", SwingConstants.CENTER);
    private final JProgressBar progress = new JProgressBar();

    private FirstRunSetupWindow() {
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setResizable(false);
        JLabel title = new JLabel("Setting up Local AI Assistant", SwingConstants.CENTER);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
        JPanel content = new JPanel(new BorderLayout(12, 18));
        content.setBorder(javax.swing.BorderFactory.createEmptyBorder(24, 28, 24, 28));
        progress.setStringPainted(true);
        progress.setString("Please keep this window open");
        content.add(title, BorderLayout.NORTH);
        content.add(message, BorderLayout.CENTER);
        content.add(progress, BorderLayout.SOUTH);
        frame.setContentPane(content);
        frame.setPreferredSize(new Dimension(540, 170));
        frame.pack();
        frame.setLocationRelativeTo(null);
    }

    static FirstRunSetupWindow show() {
        AtomicReference<FirstRunSetupWindow> result = new AtomicReference<>();
        onEventThread(() -> {
            FirstRunSetupWindow window = new FirstRunSetupWindow();
            window.frame.setVisible(true);
            result.set(window);
        });
        return result.get();
    }

    void update(String text, long completed, long total) {
        SwingUtilities.invokeLater(() -> {
            message.setText("<html><div style='text-align:center'>" + escape(text) + "</div></html>");
            if (total > 0) {
                int percent = (int) Math.min(100, completed * 100 / total);
                progress.setIndeterminate(false);
                progress.setValue(percent);
                progress.setString(percent + "%");
            } else {
                progress.setIndeterminate(true);
                progress.setString("Please keep this window open");
            }
        });
    }

    boolean askRetry(String reason) {
        AtomicReference<Boolean> retry = new AtomicReference<>(false);
        onEventThread(() -> retry.set(JOptionPane.showConfirmDialog(
                frame,
                "Local model setup could not finish:\n\n" + reason
                        + "\n\nCheck your internet connection and available disk space. Retry setup?",
                "Local model setup failed",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.ERROR_MESSAGE) == JOptionPane.YES_OPTION));
        return retry.get();
    }

    @Override
    public void close() {
        onEventThread(frame::dispose);
    }

    private static void onEventThread(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(action);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Setup window operation was interrupted", e);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("Setup window operation failed", e.getCause());
        }
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
