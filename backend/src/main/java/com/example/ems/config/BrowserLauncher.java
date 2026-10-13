package com.example.ems.config;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.awt.Desktop;
import java.net.URI;

@Component
public class BrowserLauncher {
    private static final String HOME_URL = "http://localhost:8080";

    @EventListener(ApplicationReadyEvent.class)
    public void openBrowserAfterStartup() {
        if ("false".equalsIgnoreCase(System.getenv("EMS_BROWSER_AUTO_OPEN"))) {
            return;
        }
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(HOME_URL));
                return;
            }
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) {
                new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", HOME_URL).start();
            } else if (os.contains("mac")) {
                new ProcessBuilder("open", HOME_URL).start();
            } else {
                new ProcessBuilder("xdg-open", HOME_URL).start();
            }
        } catch (Exception ex) {
            System.out.println("浏览器未能自动打开，请手动访问：" + HOME_URL);
        }
    }
}
