package com.blogadmin.publishing.web.exception;

import com.blogadmin.publishing.application.ArticleException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class PublishingExceptionHandler {
  @ExceptionHandler(ArticleException.class)
  ResponseEntity<ProblemDetail> handle(ArticleException exception, HttpServletRequest request) {
    HttpStatus status =
        switch (exception.code()) {
          case BAD_REQUEST -> HttpStatus.BAD_REQUEST;
          case FORBIDDEN -> HttpStatus.FORBIDDEN;
          case NOT_FOUND -> HttpStatus.NOT_FOUND;
          case CONFLICT -> HttpStatus.CONFLICT;
          case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
        };
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, exception.getMessage());
    ResponseEntity.BodyBuilder response =
        ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON);
    if (request.getRequestURI().startsWith("/api/v1/public/"))
      response.header("Cache-Control", "no-cache");
    return response.body(problem);
  }
}
