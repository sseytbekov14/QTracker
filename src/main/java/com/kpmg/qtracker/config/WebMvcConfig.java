package com.kpmg.qtracker.config;

import com.kpmg.qtracker.security.ReadOnlyAccessInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final ReadOnlyAccessInterceptor readOnlyAccessInterceptor;

    public WebMvcConfig(ReadOnlyAccessInterceptor readOnlyAccessInterceptor) {
        this.readOnlyAccessInterceptor = readOnlyAccessInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(readOnlyAccessInterceptor).addPathPatterns("/api/**");
    }
}
