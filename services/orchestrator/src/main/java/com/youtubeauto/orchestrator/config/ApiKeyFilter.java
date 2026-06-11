package com.youtubeauto.orchestrator.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Optional shared-secret gate on the mutating API (code-review 2026-06-10,
 * finding S1: the entire pipeline is unauthenticated).
 *
 * DEFAULT OFF: with APP_API_KEY unset (the normal local single-operator
 * setup) this filter does nothing, so nothing changes for the dashboard.
 *
 * Set APP_API_KEY when the orchestrator ever becomes reachable beyond
 * localhost (port-forward, VPN, reverse proxy): every /api/** request must
 * then carry header {@code X-Api-Key: <value>}. Static assets and the
 * dashboard pages stay open (they contain no secrets; all data flows through
 * /api). This is deliberately NOT a full auth system — it is the smallest
 * possible lock for a one-person studio; see BACKLOG for the real one.
 */
@Slf4j
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    @Value("${APP_API_KEY:}")
    private String apiKey;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (apiKey == null || apiKey.isBlank()) return true; // feature off
        String p = request.getRequestURI();
        return !p.startsWith("/api/"); // static UI + media blijven open
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        if (apiKey.equals(req.getHeader("X-Api-Key"))) {
            chain.doFilter(req, res);
            return;
        }
        log.warn("401 — /api call zonder geldige X-Api-Key vanaf {}", req.getRemoteAddr());
        res.setStatus(401);
        res.setContentType("application/json");
        res.getWriter().write("{\"detail\":\"X-Api-Key header required (APP_API_KEY is set)\"}");
    }
}
