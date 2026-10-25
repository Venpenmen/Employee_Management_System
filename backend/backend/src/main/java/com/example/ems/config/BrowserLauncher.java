package com.example.ems.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.net.URI;

/**
 * 开发启动（IDEA 运行 EmsApplication.main 或 mvn spring-boot:run）就绪后自动打开浏览器。
 * 便携版由启动器负责打开浏览器（ems.browser.auto-open=false），测试或无界面环境可设 EMS_BROWSER_AUTO_OPEN=false。
 */
@Component
public class BrowserLauncher {
    private final boolean enabled;
    public BrowserLauncher(@Value("${ems.browser.auto-open:true}") boolean enabled) { this.enabled = enabled; }

    @EventListener(ApplicationReadyEvent.class)
    public void openBrowserAfterStartup(ApplicationReadyEvent event) {
        if (!enabled || "false".equalsIgnoreCase(System.getenv("EMS_BROWSER_AUTO_OPEN"))) return;
        int port = event.getApplicationContext() instanceof WebServerApplicationContext web && web.getWebServer() != null
                ? web.getWebServer().getPort() : 8080;
        String url = "http://localhost:" + port;
        try {
            if (!GraphicsEnvironment.isHeadless() && Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
                return;
            }
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
            else if (os.contains("mac")) new ProcessBuilder("open", url).start();
            else new ProcessBuilder("xdg-open", url).start();
        } catch (Exception ex) {
            System.out.println("浏览器未能自动打开，请手动访问：" + url);
        }
    }
}
