package ru.koren.cases;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import ru.koren.cases.application.CaseException;

/** Maps application failures to the documented problem+json codes. Never leaks another user's object. */
@RestControllerAdvice
public class CaseExceptionHandler {

    @ExceptionHandler(CaseException.class)
    ProblemDetail handle(CaseException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.valueOf(e.httpStatus()));
        problem.setTitle(switch (e.code()) {
            case "RESOURCE_NOT_FOUND" -> "Обращение недоступно";
            case "ACTION_FORBIDDEN" -> "Действие недоступно этому аккаунту";
            case "CASE_VERSION_CONFLICT" -> "Карточка изменилась, перечитайте её";
            case "IDEMPOTENCY_KEY_REUSED" -> "Тот же ключ использован с другим телом";
            case "CASE_ALREADY_CONFIRMED" -> "Обращение уже подтверждено";
            default -> "Запрос отклонён";
        });
        problem.setProperty("code", e.code());
        return problem;
    }
}
