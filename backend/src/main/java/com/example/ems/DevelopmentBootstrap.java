package com.example.ems;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** IDE source-run bootstrap. Never executed by the standalone release JAR. */
final class DevelopmentBootstrap {
    private DevelopmentBootstrap() { }

    static void prepare() {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path script = root.resolve("launcher/ensure-development-db.ps1");
        if (!Files.isDirectory(root.resolve(".git")) || !Files.isRegularFile(script)) {
            return;
        }
        try {
            Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script.toString())
                    .directory(root.toFile()).redirectErrorStream(true).start();
            boolean finished = process.waitFor(Duration.ofMinutes(5).toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("Timed out waiting for the shared development database");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                throw new IllegalStateException("Shared database startup failed: " + output);
            }
            ObjectMapper mapper = new ObjectMapper();
            JsonNode state = mapper.readTree(Files.readString(root.resolve("data/instance.json")));
            JsonNode credentials = mapper.readTree(Files.readString(root.resolve("data/credentials.json")));
            int port = state.path("dbPort").asInt(0);
            String password = credentials.path("app").asText("");
            if (port < 1 || password.isBlank()) {
                throw new IllegalStateException("Shared database settings are incomplete");
            }
            System.setProperty("spring.datasource.url", "jdbc:mysql://127.0.0.1:" + port + "/ems_db?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false");
            System.setProperty("spring.datasource.username", "ems_app");
            System.setProperty("spring.datasource.password", password);
            System.setProperty("spring.sql.init.mode", "never");
            System.setProperty("server.port", "8081");
            System.out.println("Shared EMS database ready; IDEA web address: http://127.0.0.1:8081");
        } catch (IOException e) {
            throw new IllegalStateException("Could not prepare the shared development database", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while preparing the shared database", e);
        }
    }
}
