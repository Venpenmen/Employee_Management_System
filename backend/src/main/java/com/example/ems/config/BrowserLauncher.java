package com.example.ems.config;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.core.env.Environment;

import java.awt.Desktop;
import java.net.URI;

@Component
public class BrowserLauncher {
    private final Environment environment;

    public BrowserLauncher(Environment environment) { this.environment = environment; }

    @EventListener(ApplicationReadyEvent.class)
    public void openBrowserAfterStartup() {
        if (!environment.getProperty("ems.browser-auto-open", Boolean.class, true)
                || "false".equalsIgnoreCase(System.getenv("EMS_BROWSER_AUTO_OPEN"))) {
            return;
        }
        String homeUrl = "http://localhost:" + environment.getProperty("local.server.port", "8080");
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(homeUrl));
                return;
            }
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) {
                new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", homeUrl).start();
            } else if (os.contains("mac")) {
                new ProcessBuilder("open", homeUrl).start();
            } else {
                new ProcessBuilder("xdg-open", homeUrl).start();
            }
        } catch (Exception ex) {
            System.out.println("浏览器未能自动打开，请手动访问：" + homeUrl);
        }
    }
}
