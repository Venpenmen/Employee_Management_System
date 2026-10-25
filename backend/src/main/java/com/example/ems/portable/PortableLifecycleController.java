package com.example.ems.portable;

import com.example.ems.EmsApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 便携版生命周期接口，仅在 portable 配置下存在，供 launcher 就绪检查与正常停止使用。
 * 每次启动由启动器生成实例令牌（环境变量 EMS_INSTANCE_TOKEN），请求必须携带 X-EMS-Token，
 * 令牌缺失、为空或不匹配时一律 403；服务只监听 127.0.0.1。
 */
@RestController
@Profile("portable")
@RequestMapping("/internal/portable")
public class PortableLifecycleController {
    public static final String TOKEN_HEADER = "X-EMS-Token";
    private static final Logger log = LoggerFactory.getLogger(PortableLifecycleController.class);

    private final ConfigurableApplicationContext context;
    private final byte[] token;
    private final AtomicBoolean stopping = new AtomicBoolean(false);

    public PortableLifecycleController(ConfigurableApplicationContext context, @Value("${ems.portable.token:}") String token) {
        this.context = context;
        this.token = token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
        if (this.token.length == 0) log.warn("EMS_INSTANCE_TOKEN is not set; portable lifecycle endpoints will reject every request.");
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status(@RequestHeader(value = TOKEN_HEADER, required = false) String supplied) {
        if (!authorized(supplied)) return forbidden();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", EmsApplication.VERSION);
        body.put("pid", ProcessHandle.current().pid());
        body.put("status", stopping.get() ? "stopping" : "ready");
        return ResponseEntity.ok(body);
    }

    @PostMapping("/stop")
    public ResponseEntity<Map<String, Object>> stop(@RequestHeader(value = TOKEN_HEADER, required = false) String supplied) {
        if (!authorized(supplied)) return forbidden();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", EmsApplication.VERSION);
        if (!stopping.compareAndSet(false, true)) {
            body.put("status", "stopping");
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
        }
        log.info("Portable stop requested by the launcher; shutting down gracefully.");
        Thread shutdown = new Thread(() -> {
            try { Thread.sleep(300); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            int code = SpringApplication.exit(context, () -> 0);
            System.exit(code);
        }, "ems-portable-stop");
        shutdown.setDaemon(false);
        shutdown.start();
        body.put("status", "stopping");
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
    }

    private boolean authorized(String supplied) {
        return token.length > 0 && supplied != null
                && MessageDigest.isEqual(token, supplied.getBytes(StandardCharsets.UTF_8));
    }

    private static ResponseEntity<Map<String, Object>> forbidden() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 403);
        body.put("msg", "实例令牌无效");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }
}
