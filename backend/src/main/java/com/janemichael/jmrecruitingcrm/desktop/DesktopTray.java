package com.janemichael.jmrecruitingcrm.desktop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.AWTError;
import java.awt.AWTException;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

/**
 * JM CRM's icon in the Windows notification area, next to the clock. Clicking it, or one of its
 * notifications, opens the CRM in the browser. Anything that goes wrong here is logged and ignored:
 * the CRM works the same without it.
 */
final class DesktopTray {

    private static final Logger log = LoggerFactory.getLogger(DesktopTray.class);
    /** The app's own icon, from the web app's files. */
    private static final String ICON = "/static/icon-192.png";

    /** Read without the lock, so closing never waits on a slow start of the icon. */
    private static volatile TrayIcon icon;

    private DesktopTray() {
    }

    static boolean installed() {
        return icon != null;
    }

    static synchronized void install(String url) {
        if (icon != null || !DesktopOs.isWindows()) {
            return;
        }
        try {
            if (!SystemTray.isSupported()) {
                log.info("Windows has no notification area here, so there's no JM CRM icon or reminder");
                return;
            }
            SystemTray tray = SystemTray.getSystemTray();
            TrayIcon trayIcon = new TrayIcon(image(tray.getTrayIconSize()), DesktopOs.APP_NAME);
            PopupMenu menu = new PopupMenu();
            MenuItem open = new MenuItem("Open " + DesktopOs.APP_NAME);
            open.addActionListener(event -> DesktopOs.openBrowser(url));
            menu.add(open);
            trayIcon.setPopupMenu(menu);
            // A double-click on the icon, or a click on a notification.
            trayIcon.addActionListener(event -> DesktopOs.openBrowser(url));
            tray.add(trayIcon);
            icon = trayIcon;
        } catch (AWTException | RuntimeException | AWTError | LinkageError problem) {
            log.warn("Could not add the JM CRM icon to the notification area: {}", problem.toString());
        }
    }

    /** Shows a Windows notification. False if there's no icon to show it from. */
    static boolean notify(String title, String body) {
        TrayIcon current = icon;
        if (current == null) {
            return false;
        }
        try {
            current.displayMessage(title, body, TrayIcon.MessageType.NONE);
            return true;
        } catch (RuntimeException | AWTError | LinkageError problem) {
            log.warn("Could not show a notification: {}", problem.toString());
            return false;
        }
    }

    /**
     * Takes the icon away when the CRM closes, so Windows doesn't leave a stale one behind. Gives up
     * after two seconds rather than hold up closing (an update waits for the CRM to close).
     */
    static void remove() {
        TrayIcon current = icon;
        icon = null;
        if (current == null) {
            return;
        }
        Thread remover = new Thread(() -> {
            try {
                SystemTray.getSystemTray().remove(current);
            } catch (RuntimeException | AWTError | LinkageError ignored) {
                // Closing anyway.
            }
        }, "desktop-tray-remove");
        remover.setDaemon(true);
        remover.start();
        try {
            remover.join(2000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static Image image(Dimension size) {
        int width = Math.max(16, size.width);
        int height = Math.max(16, size.height);
        try (InputStream in = DesktopTray.class.getResourceAsStream(ICON)) {
            BufferedImage source = in == null ? null : ImageIO.read(in);
            if (source != null) {
                return source.getScaledInstance(width, height, Image.SCALE_SMOOTH);
            }
        } catch (IOException exception) {
            log.debug("Could not read the app icon", exception);
        }
        return drawn(width, height);
    }

    /** "JM" on the firm's navy, for builds without the web app's files. */
    private static Image drawn(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setColor(new Color(0x003366));
        graphics.fillRoundRect(0, 0, width, height, width / 3, height / 3);
        graphics.setColor(Color.WHITE);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.round(height * 0.5f)));
        String text = "JM";
        int textWidth = graphics.getFontMetrics().stringWidth(text);
        int baseline = (height + graphics.getFontMetrics().getAscent() - graphics.getFontMetrics().getDescent()) / 2;
        graphics.drawString(text, (width - textWidth) / 2, baseline);
        graphics.dispose();
        return image;
    }
}
