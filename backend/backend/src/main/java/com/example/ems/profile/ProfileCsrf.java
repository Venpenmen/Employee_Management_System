package com.example.ems.profile;

import com.example.ems.common.ForbiddenException;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 个人档案表单的 CSRF 令牌：每个会话一个随机令牌，模板以隐藏域 _csrf 提交，
 * 服务端常量时间比较。登录轮换会话 ID 时属性随会话保留，退出登录后失效。
 */
@Component
public class ProfileCsrf {
    public static final String SESSION_KEY = "PROFILE_CSRF_TOKEN";
    public static final String PARAMETER = "_csrf";
    private static final SecureRandom RANDOM = new SecureRandom();

    public String token(HttpSession session) {
        Object existing = session.getAttribute(SESSION_KEY);
        if (existing instanceof String s && !s.isEmpty()) return s;
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        session.setAttribute(SESSION_KEY, token);
        return token;
    }

    /** 令牌缺失或不匹配时拒绝（403），不执行任何写入。 */
    public void verify(HttpSession session, String submitted) {
        Object expected = session.getAttribute(SESSION_KEY);
        if (!(expected instanceof String s) || submitted == null
                || !MessageDigest.isEqual(s.getBytes(StandardCharsets.UTF_8), submitted.getBytes(StandardCharsets.UTF_8)))
            throw new ForbiddenException("页面安全令牌无效或已过期，请重新打开个人档案页面后再提交。");
    }
}
