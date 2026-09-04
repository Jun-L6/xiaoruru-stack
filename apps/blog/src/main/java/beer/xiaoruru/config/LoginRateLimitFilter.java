package beer.xiaoruru.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class LoginRateLimitFilter extends OncePerRequestFilter {
    private static final int MAX_FAILURES = 5;
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final int MAX_TRACKED_ADDRESSES = 10_000;
    private final Map<String, AttemptWindow> attempts = new ConcurrentHashMap<>();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !"/admin/login".equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String address = request.getRemoteAddr();
        Instant now = Instant.now();
        discardExpired(now);
        AttemptWindow current = attempts.get(address);
        if (current != null && current.isBlocked(now)) {
            byte[] body = "登录尝试过于频繁，请 15 分钟后再试。".getBytes(StandardCharsets.UTF_8);
            response.setStatus(429);
            response.setContentType("text/plain;charset=UTF-8");
            response.setContentLength(body.length);
            response.getOutputStream().write(body);
            return;
        }

        chain.doFilter(request, response);
        boolean authenticated = SecurityContextHolder.getContext().getAuthentication() != null
                && SecurityContextHolder.getContext().getAuthentication().isAuthenticated();
        if (authenticated) {
            attempts.remove(address);
        } else if (attempts.size() < MAX_TRACKED_ADDRESSES || attempts.containsKey(address)) {
            attempts.compute(address, (key, old) -> old == null || old.expired(now)
                    ? new AttemptWindow(1, now.plus(WINDOW))
                    : new AttemptWindow(old.failures() + 1, old.expiresAt()));
        }
    }

    private void discardExpired(Instant now) {
        if (attempts.size() > 100) {
            attempts.entrySet().removeIf(entry -> entry.getValue().expired(now));
        }
    }

    private record AttemptWindow(int failures, Instant expiresAt) {
        boolean expired(Instant now) { return !expiresAt.isAfter(now); }
        boolean isBlocked(Instant now) { return !expired(now) && failures >= MAX_FAILURES; }
    }
}
