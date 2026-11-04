package com.minty.assistant;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.*;

/** Local personal app: prevent other websites from driving the execution API. */
@Component
public class LocalOriginFilter extends OncePerRequestFilter {
    private final Set<String> allowedOrigins;
    LocalOriginFilter(@Value("${AGENT_ALLOWED_ORIGINS:http://localhost:5173,http://127.0.0.1:5173,http://localhost:8080,http://127.0.0.1:8080,http://localhost:3000,http://127.0.0.1:3000}") String origins) {
        this.allowedOrigins = new HashSet<>(Arrays.asList(origins.split(",")));
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String origin = request.getHeader("Origin");
        String fetchSite = request.getHeader("Sec-Fetch-Site");
        if (request.getRequestURI().startsWith("/api/") &&
                ((origin != null && !allowedOrigins.contains(origin)) || (origin == null && "cross-site".equals(fetchSite)))) {
            response.setStatus(403); response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"message\":\"只允许本机应用访问此接口\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
