package com.example.ems.config;

import com.example.ems.web.AuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final AuthInterceptor authInterceptor;
    public WebConfig(AuthInterceptor authInterceptor) { this.authInterceptor = authInterceptor; }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/**")
                // /internal/** 是便携版生命周期接口，使用独立的实例令牌校验，不走登录会话
                .excludePathPatterns("/", "/login", "/register", "/css/**", "/js/**", "/error", "/favicon.ico", "/internal/**");
    }
}
