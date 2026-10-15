package com.localai.launcher;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/** Frameless, theme-aware startup window shown while the local model runtime is prepared. */
final class FirstRunSetupWindow implements AutoCloseable {
    private static final String FONT = "Segoe UI";
    private static final int SHADOW = 14;

    private final Palette palette;
    private final JFrame frame = new JFrame("Local AI Assistant");
    private final JLabel message = new JLabel("Preparing your private local model...", SwingConstants.CENTER);
    private final JLabel percent = new JLabel(" ", SwingConstants.CENTER);
    private final SmoothBar progress = new SmoothBar();
    private final Timer pulseTimer;
    private volatile double pulse;

    private FirstRunSetupWindow(boolean dark) {
        this.palette = dark ? Palette.DARK : Palette.LIGHT;
        frame.setUndecorated(true);
        frame.setBackground(new Color(0, 0, 0, 0));
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setResizable(false);

        JComponent logo = new JComponent() {
            @Override
            protected void paintComponent(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int size = 64;
                int x = (getWidth() - size) / 2;
                int y = (getHeight() - size) / 2;
                for (int ring = 0; ring < 2; ring++) {
                    double phase = (pulse + ring * 0.5) % 1.0;
                    int grow = (int) (phase * 22);
                    float alpha = (float) (0.35 * (1 - phase));
                    g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
                    g.setColor(palette.accent);
                    g.setStroke(new BasicStroke(2f));
                    g.drawRoundRect(x - grow, y - grow, size + grow * 2, size + grow * 2,
                            20 + grow, 20 + grow);
                }
                g.setComposite(AlphaComposite.SrcOver);
                g.setPaint(new GradientPaint(x, y, palette.accentLight, x + size, y + size, palette.accent));
                g.fillRoundRect(x, y, size, size, 20, 20);
                g.setColor(Color.WHITE);
                g.setFont(new Font(FONT, Font.BOLD, 24));
                int textWidth = g.getFontMetrics().stringWidth("AI");
                g.drawString("AI", x + (size - textWidth) / 2, y + size / 2 + 9);
                g.dispose();
            }
        };
        logo.setPreferredSize(new Dimension(120, 110));
        logo.setMaximumSize(new Dimension(120, 110));

        JLabel title = label("Local AI Assistant", Font.BOLD, 22, palette.title);
        JLabel subtitle = label("Setting up your private workspace", Font.PLAIN, 13, palette.muted);
        message.setFont(new Font(FONT, Font.PLAIN, 13));
        message.setForeground(palette.text);
        message.setAlignmentX(Component.CENTER_ALIGNMENT);
        percent.setFont(new Font(FONT, Font.BOLD, 12));
        percent.setForeground(palette.accent);
        percent.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel hint = label("Downloads only happen the first time. Please keep this window open.",
                Font.PLAIN, 11, palette.muted);

        progress.setPreferredSize(new Dimension(360, 8));
        progress.setMaximumSize(new Dimension(360, 8));
        progress.setAlignmentX(Component.CENTER_ALIGNMENT);

        JPanel card = new JPanel() {
            @Override
            protected void paintComponent(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                for (int i = SHADOW; i > 0; i--) {
                    g.setColor(new Color(0, 0, 0, Math.max(1, palette.shadowAlpha * (SHADOW - i + 1) / SHADOW / 3)));
                    g.fillRoundRect(i - 2, i, getWidth() - i * 2 + 4, getHeight() - i * 2 + 2, 28, 28);
                }
                int w = getWidth() - SHADOW * 2;
                int h = getHeight() - SHADOW * 2;
                g.translate(SHADOW, SHADOW - 4);
                g.setPaint(new GradientPaint(0, 0, palette.top, 0, h, palette.bottom));
                g.fillRoundRect(0, 0, w, h, 26, 26);
                g.setColor(palette.border);
                g.drawRoundRect(0, 0, w - 1, h - 1, 26, 26);
                g.dispose();
            }
        };
        card.setOpaque(false);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(BorderFactory.createEmptyBorder(SHADOW + 14, SHADOW + 40, SHADOW + 14, SHADOW + 40));
        card.add(logo);
        card.add(title);
        card.add(javax.swing.Box.createVerticalStrut(4));
        card.add(subtitle);
        card.add(javax.swing.Box.createVerticalStrut(30));
        card.add(message);
        card.add(javax.swing.Box.createVerticalStrut(14));
        card.add(progress);
        card.add(javax.swing.Box.createVerticalStrut(8));
        card.add(percent);
        card.add(javax.swing.Box.createVerticalStrut(10));
        card.add(hint);

        frame.setContentPane(card);
        frame.setPreferredSize(new Dimension(560 + SHADOW * 2, 366 + SHADOW * 2));
        frame.pack();
        frame.setLocationRelativeTo(null);
        progress.setIndeterminate(true);
        pulseTimer = new Timer(16, event -> {
            pulse = (pulse + 0.006) % 1.0;
            logo.repaint();
            progress.tick();
        });
    }

    private JLabel label(String text, int style, int size, Color color) {
        JLabel label = new JLabel(text, SwingConstants.CENTER);
        label.setFont(new Font(FONT, style, size));
        label.setForeground(color);
        label.setAlignmentX(Component.CENTER_ALIGNMENT);
        return label;
    }

    /** Progress bar that eases toward its target and animates indeterminate state at a steady frame rate. */
    private final class SmoothBar extends JComponent {
        private boolean indeterminate = true;
        private double target;
        private double shown;

        void setIndeterminate(boolean value) {
            indeterminate = value;
        }

        void setFraction(double value) {
            target = Math.max(0, Math.min(1, value));
        }

        void tick() {
            shown += (target - shown) * 0.12;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int width = getWidth();
            int height = getHeight();
            g.setColor(palette.track);
            g.fillRoundRect(0, 0, width, height, height, height);
            g.setClip(new java.awt.geom.RoundRectangle2D.Double(0, 0, width, height, height, height));
            g.setPaint(new GradientPaint(0, 0, palette.accentLight, width, 0, palette.accent));
            if (indeterminate) {
                double cycle = (System.nanoTime() / 1_000_000 % 1800) / 1800.0;
                int segment = width * 2 / 5;
                int x = (int) (-segment + (width + segment) * cycle);
                g.fillRoundRect(x, 0, segment, height, height, height);
            } else {
                int fill = (int) Math.round(width * shown);
                if (fill > 0) {
                    g.fillRoundRect(0, 0, Math.max(fill, height), height, height, height);
                }
            }
            g.dispose();
        }
    }
    static FirstRunSetupWindow show(Path dataDirectory) {
        boolean dark = isDarkTheme(dataDirectory);
        AtomicReference<FirstRunSetupWindow> result = new AtomicReference<>();
        onEventThread(() -> {
            FirstRunSetupWindow window = new FirstRunSetupWindow(dark);
            window.frame.setVisible(true);
            window.pulseTimer.start();
            result.set(window);
        });
        return result.get();
    }

    static boolean isDarkTheme(Path dataDirectory) {
        try {
            Path file = dataDirectory.resolve("config").resolve("theme.txt");
            return !Files.isRegularFile(file) || !"light".equalsIgnoreCase(Files.readString(file, StandardCharsets.UTF_8).trim());
        } catch (Exception e) {
            return true;
        }
    }

    void update(String text, long completed, long total) {
        SwingUtilities.invokeLater(() -> {
            message.setText("<html><div style='text-align:center'>" + escape(text) + "</div></html>");
            if (total > 0) {
                int percentValue = (int) Math.min(100, completed * 100 / total);
                progress.setIndeterminate(false);
                progress.setFraction(percentValue / 100.0);
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
        onEventThread(() -> {
            pulseTimer.stop();
            frame.dispose();
        });
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

    private record Palette(Color top, Color bottom, Color border, Color title, Color text, Color muted,
                           Color accent, Color accentLight, Color track, int shadowAlpha) {
        static final Palette LIGHT = new Palette(
                new Color(0xFFFFFF), new Color(0xEEF0FF), new Color(0xDADFF0), new Color(0x111827),
                new Color(0x1F2937), new Color(0x6B7280), new Color(0x5B5BD6), new Color(0x8585F5),
                new Color(0xE4E7F5), 60);
        static final Palette DARK = new Palette(
                new Color(0x1A1D2B), new Color(0x0F1117), new Color(0x2C3042), new Color(0xE8EAF0),
                new Color(0xC9CDD8), new Color(0x8B93A5), new Color(0x6A6AE6), new Color(0x8F8FFA),
                new Color(0x2A2E40), 150);
    }
}