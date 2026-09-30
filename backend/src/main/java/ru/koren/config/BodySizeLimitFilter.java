package ru.koren.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses an oversized API body before a single byte of it is read, so a 100 MB JSON never reaches
 * memory, the parser or the database. A chunked body cannot be measured in advance, and no client of
 * this API needs one, so it is refused too. The webhook keeps its own, smaller limit in its controller.
 * Runs right after RequestIdFilter: the refusal already carries a request id, and costs no session lookup.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class BodySizeLimitFilter extends OncePerRequestFilter {

    private final long maxBytes;

    public BodySizeLimitFilter(@Value("${koren.http.max-body-bytes:262144}") long maxBytes) {
        this.maxBytes = maxBytes;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        if (request.getHeader("Transfer-Encoding") != null && request.getContentLengthLong() < 0) {
            ApiErrorController.write(request, response, HttpStatus.LENGTH_REQUIRED, "LENGTH_REQUIRED",
                "Нужен заголовок Content-Length");
            return;
        }
        if (request.getContentLengthLong() > maxBytes) {
            ApiErrorController.write(request, response, HttpStatus.CONTENT_TOO_LARGE, "PAYLOAD_TOO_LARGE",
                "Слишком большой запрос");
            return;
        }
        chain.doFilter(request, response);
    }
}
