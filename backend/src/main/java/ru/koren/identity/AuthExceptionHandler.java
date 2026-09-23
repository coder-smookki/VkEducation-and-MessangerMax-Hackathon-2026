package ru.koren.identity;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Unverified initData is a 401, never a 500. The reason, raw data and bot token stay out of the response. */
@RestControllerAdvice
public class AuthExceptionHandler {

    @ExceptionHandler(InvalidInitDataException.class)
    ProblemDetail invalidInitData(InvalidInitDataException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        problem.setTitle("Вход через MAX не подтверждён");
        problem.setProperty("code", "INVALID_MAX_INIT_DATA");
        return problem;
    }
}
