package com.example.alerts.mock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@ConditionalOnProperty(prefix = "hummingbird.mock", name = "enabled", havingValue = "true")
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final ApiInterceptor interceptor;

    public WebConfig(ApiInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns(
                MockProperties.BASE_PATH + "/alert_imports/**", MockProperties.BASE_PATH + "/alerts/**",
                MockProperties.BASE_PATH + "/cases/**", MockProperties.BASE_PATH + "/reviews/**");
    }
}
