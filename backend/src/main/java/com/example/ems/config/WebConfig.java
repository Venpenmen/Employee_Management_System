package com.example.ems.config;

import com.example.ems.web.AuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final AuthInterceptor authInterceptor;
    private final com.example.ems.web.ProfileCsrfInterceptor profileCsrf;
    public WebConfig(AuthInterceptor authInterceptor, com.example.ems.web.ProfileCsrfInterceptor profileCsrf) {
        this.authInterceptor = authInterceptor; this.profileCsrf = profileCsrf;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(profileCsrf).addPathPatterns("/**").excludePathPatterns("/css/**","/js/**","/health","/error","/favicon.ico").order(1);
        registry.addInterceptor(authInterceptor).order(0)
                .addPathPatterns("/**")
                .excludePathPatterns("/", "/login", "/register", "/css/**", "/js/**", "/error", "/favicon.ico", "/health", "/internal/portable/**");
    }
}
