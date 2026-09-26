package ru.koren.config;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Replaces Spring Boot's /error page. Whatever failed - a malformed UUID, a missing route, an uncaught
 * exception - the client gets problem+json with a stable code and nothing else: no message, no path,
 * no exception class, because any of them may carry user text or internals.
 */
@RestController
public class ApiErrorController implements ErrorController {

    @RequestMapping("/error")
    ResponseEntity<ProblemDetail> error(HttpServletRequest request) {
        HttpStatus status = statusOf(request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE));
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setTitle(titleFor(status));
        problem.setProperty("code", codeFor(status));
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }

    /** For security handlers, which answer before any controller runs. Titles are constants: no escaping. */
    static void write(HttpServletResponse response, HttpStatus status, String code, String title)
        throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/problem+json;charset=UTF-8");
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + title + "\",\"status\":"
            + status.value() + ",\"code\":\"" + code + "\"}");
    }

    private static HttpStatus statusOf(Object attribute) {
        HttpStatus status = attribute instanceof Integer code ? HttpStatus.resolve(code) : null;
        // An error page reached without a status is itself a failure, not a success.
        return status == null || !status.isError() ? HttpStatus.INTERNAL_SERVER_ERROR : status;
    }

    static String codeFor(HttpStatus status) {
        if (status.is5xxServerError()) return "INTERNAL_ERROR";
        return switch (status) {
            case BAD_REQUEST -> "BAD_REQUEST";
            case UNAUTHORIZED -> "AUTH_REQUIRED";
            case FORBIDDEN -> "ACTION_FORBIDDEN";
            case NOT_FOUND -> "NOT_FOUND";
            case METHOD_NOT_ALLOWED -> "METHOD_NOT_ALLOWED";
            case NOT_ACCEPTABLE -> "NOT_ACCEPTABLE";
            case CONTENT_TOO_LARGE -> "PAYLOAD_TOO_LARGE";
            case UNSUPPORTED_MEDIA_TYPE -> "UNSUPPORTED_MEDIA_TYPE";
            default -> "REQUEST_REJECTED";
        };
    }

    private static String titleFor(HttpStatus status) {
        if (status.is5xxServerError()) return "Внутренняя ошибка, попробуйте позже";
        return switch (status) {
            case BAD_REQUEST -> "Некорректный запрос";
            case UNAUTHORIZED -> "Нужен вход через MAX";
            case FORBIDDEN -> "Действие недоступно этому аккаунту";
            case NOT_FOUND -> "Не найдено";
            case METHOD_NOT_ALLOWED -> "Метод не поддерживается";
            case CONTENT_TOO_LARGE -> "Слишком большой запрос";
            case UNSUPPORTED_MEDIA_TYPE -> "Неподдерживаемый формат запроса";
            default -> "Запрос отклонён";
        };
    }
}
