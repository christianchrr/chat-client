package com.localai.apihost;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

@Configuration
public class LocalLoopbackAuthFilter {

    @Bean
    public FilterRegistrationBean<Filter> localLoopbackSecurity(
            @Value("${local.api.token:dev-local-token}") String expectedToken
    ) {
        Filter filter = new Filter() {
            @Override
            public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                    throws IOException, ServletException {
                HttpServletRequest httpRequest = (HttpServletRequest) request;
                HttpServletResponse httpResponse = (HttpServletResponse) response;

                String authHeader = httpRequest.getHeader("X-Local-Auth");
                String remoteAddress = httpRequest.getRemoteAddr();
                boolean loopback = "127.0.0.1".equals(remoteAddress)
                        || "0:0:0:0:0:0:0:1".equals(remoteAddress)
                        || "localhost".equalsIgnoreCase(remoteAddress);

                if (!loopback || authHeader == null || !expectedToken.equals(authHeader)) {
                    httpResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    httpResponse.setContentType("application/json");
                    httpResponse.getWriter().write("{\"error\":\"unauthorized_loopback_request\"}");
                    return;
                }

                chain.doFilter(request, response);
            }
        };

        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/api/*", "/health/*");
        registration.setOrder(1);
        return registration;
    }
}
