package ru.koren.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Outermost filter. Every response carries X-Request-Id, and every log line written while serving the
 * request carries the same id, so a user or tester can quote one value and the log shows the whole story.
 * It is also the one place an unforeseen exception is logged: by class and frames, never by message,
 * because a message may hold user text or SQL values. The container never sees the exception, so it
 * never logs it a second time with the message included.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".id";
    static final String MDC_KEY = "requestId";

    /** A caller's id is kept only if it cannot forge a log line or flood one. */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9-]{8,64}");
    private static final int CAUSES = 5;
    private static final int FRAMES = 12;

    private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String id = incoming != null && SAFE_ID.matcher(incoming).matches()
            ? incoming : UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE, id);
        response.setHeader(HEADER, id);
        MDC.put(MDC_KEY, id);
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException failure) {
            log.error("Unhandled failure in {} {}{}", request.getMethod(), request.getRequestURI(),
                describe(failure));
            // /error turns this into a plain 500 problem; a committed response can no longer change.
            if (!response.isCommitted()) {
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            }
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /** Class names and frames of the cause chain: enough to find the bug, nothing a user typed. */
    static String describe(Throwable failure) {
        var out = new StringBuilder();
        Throwable current = failure;
        for (int depth = 0; current != null && depth < CAUSES; depth++) {
            out.append(depth == 0 ? "\n  " : "\n  caused by ").append(current.getClass().getName());
            StackTraceElement[] frames = current.getStackTrace();
            for (int i = 0; i < Math.min(frames.length, FRAMES); i++) {
                out.append("\n    at ").append(frames[i]);
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return out.toString();
    }
}
