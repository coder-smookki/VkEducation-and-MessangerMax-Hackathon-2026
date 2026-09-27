package ru.koren.identity;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import ru.koren.config.RequestIdFilter;

/** Unverified initData is a 401, never a 500. The reason, raw data and bot token stay out of the response. */
@RestControllerAdvice
public class AuthExceptionHandler {

    @ExceptionHandler(InvalidInitDataException.class)
    ProblemDetail invalidInitData(InvalidInitDataException e, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        problem.setTitle("Вход через MAX не подтверждён");
        problem.setProperty("code", "INVALID_MAX_INIT_DATA");
        RequestIdFilter.addTo(problem, request);
        return problem;
    }

    @ExceptionHandler(InvitationException.class)
    ProblemDetail invitation(InvitationException e, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.valueOf(e.httpStatus()));
        problem.setTitle("Приглашение отклонено");
        problem.setProperty("code", e.code());
        RequestIdFilter.addTo(problem, request);
        return problem;
    }
}
