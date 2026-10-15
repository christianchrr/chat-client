package com.localai.launcher;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.plaf.basic.BasicProgressBarUI;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicReference;

final class FirstRunSetupWindow implements AutoCloseable {
    private static final Color BACKGROUND = new Color(0xE8EDF5);
    private static final Color CARD = Color.WHITE;
    private static final Color BORDER = new Color(0xC8D1DF);
    private static final Color ACCENT = new Color(0x4F46E5);
    private static final Color TITLE = new Color(0x172033);
    private static final Color MUTED = new Color(0x58657A);
    private static final Color TRACK = new Color(0xE5EAF2);
    private static final String FONT = "Segoe UI";

    private final JFrame frame = new JFrame("Local AI Assistant");
    private final JLabel message = new JLabel("Preparing your private local model...");
    private final JLabel percent = new JLabel(" ");
    private final JProgressBar progress = new JProgressBar(0, 100);

    private FirstRunSetupWindow() {
        frame.setUndecorated(true);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setResizable(false);

        JLabel brand = new JLabel("AI", SwingConstants.CENTER) {
            @Override
            protected void paintComponent(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(ACCENT);
                g.fillRoundRect(0, 0, getWidth(), getHeight(), 14, 14);
                g.dispose();
                super.paintComponent(graphics);
            }
        };
        brand.setOpaque(false);
        brand.setForeground(Color.WHITE);
        brand.setFont(new Font(FONT, Font.BOLD, 16));
        brand.setPreferredSize(new Dimension(46, 46));

        JLabel title = new JLabel("Local AI Assistant");
        title.setFont(new Font(FONT, Font.BOLD, 20));
        title.setForeground(TITLE);
        JLabel subtitle = new JLabel("Setting up your private workspace");
        subtitle.setFont(new Font(FONT, Font.PLAIN, 12));
        subtitle.setForeground(MUTED);
        JPanel titles = new JPanel(new GridLayout(2, 1, 0, 2));
        titles.setOpaque(false);
        titles.add(title);
        titles.add(subtitle);
        JPanel header = new JPanel(new BorderLayout(14, 0));
        header.setOpaque(false);
        header.add(brand, BorderLayout.WEST);
        header.add(titles, BorderLayout.CENTER);

        message.setFont(new Font(FONT, Font.PLAIN, 13));
        message.setForeground(TITLE);
        percent.setFont(new Font(FONT, Font.BOLD, 12));
        percent.setForeground(ACCENT);
        JPanel status = new JPanel(new BorderLayout(10, 0));
        status.setOpaque(false);
        status.add(message, BorderLayout.CENTER);
        status.add(percent, BorderLayout.EAST);

        progress.setBorderPainted(false);
        progress.setPreferredSize(new Dimension(10, 8));
        progress.setUI(new RoundedProgressUI());
        JLabel hint = new JLabel("Please keep this window open. Downloads only happen the first time.");
        hint.setFont(new Font(FONT, Font.PLAIN, 11));
        hint.setForeground(MUTED);
        JPanel bottom = new JPanel(new BorderLayout(0, 10));
        bottom.setOpaque(false);
        bottom.add(progress, BorderLayout.NORTH);
        bottom.add(hint, BorderLayout.SOUTH);

        JPanel card = new JPanel(new BorderLayout(0, 22)) {
            @Override
            protected void paintComponent(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(CARD);
                g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 18, 18);
                g.setColor(BORDER);
                g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 18, 18);
                g.dispose();
            }
        };
        card.setOpaque(false);
        card.setBorder(BorderFactory.createEmptyBorder(26, 28, 22, 28));
        card.add(header, BorderLayout.NORTH);
        card.add(status, BorderLayout.CENTER);
        card.add(bottom, BorderLayout.SOUTH);

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(BACKGROUND);
        root.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        root.add(card, BorderLayout.CENTER);
        frame.setContentPane(root);
        frame.setPreferredSize(new Dimension(560, 250));
        frame.pack();
        frame.setShape(new RoundRectangle2D.Double(0, 0, frame.getWidth(), frame.getHeight(), 22, 22));
        frame.setLocationRelativeTo(null);
        progress.setIndeterminate(true);
    }

    private static final class RoundedProgressUI extends BasicProgressBarUI {
        @Override
        public void paint(Graphics graphics, JComponent component) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int width = component.getWidth();
            int height = component.getHeight();
            g.setColor(TRACK);
            g.fillRoundRect(0, 0, width, height, height, height);
            g.setColor(ACCENT);
            if (progressBar.isIndeterminate()) {
                int segment = width / 4;
                int travel = width - segment;
                int frameIndex = getAnimationIndex();
                int cycle = Math.max(1, getFrameCount());
                double fraction = (double) frameIndex / cycle;
                double position = fraction <= 0.5 ? fraction * 2 : (1 - fraction) * 2;
                g.fillRoundRect((int) (travel * position), 0, segment, height, height, height);
            } else {
                int fill = (int) (width * progressBar.getPercentComplete());
                if (fill > 0) {
                    g.fillRoundRect(0, 0, Math.max(fill, height), height, height, height);
                }
            }
            g.dispose();
        }
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
            message.setText("<html>" + escape(text) + "</html>");
            if (total > 0) {
                int percentValue = (int) Math.min(100, completed * 100 / total);
                progress.setIndeterminate(false);
                progress.setValue(percentValue);
                this.percent.setText(percentValue + "%");
            } else {
                progress.setIndeterminate(true);
                this.percent.setText(" ");
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


