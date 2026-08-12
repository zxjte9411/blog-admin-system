package com.blogadmin.identity.web;

import com.blogadmin.identity.application.AuthenticationService;
import com.blogadmin.identity.application.RegistrationService;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {
  @ExceptionHandler(AuthenticationService.BadCredentialsException.class)
  ResponseEntity<ProblemDetail> unauthorized() {
    var p = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Authentication failed");
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(p);
  }

  @ExceptionHandler(AuthenticationService.SessionNotFoundException.class)
  ResponseEntity<ProblemDetail> sessionNotFound() {
    var p = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Session not found");
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(p);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ProblemDetail> unreadable() {
    var problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
    problem.setProperty("fieldErrors", Map.of());
    return ResponseEntity.badRequest()
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(problem);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ProblemDetail> invalid(MethodArgumentNotValidException exception) {
    var problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
    Map<String, String> errors =
        exception.getBindingResult().getFieldErrors().stream()
            .collect(Collectors.toMap(e -> e.getField(), e -> e.getDefaultMessage(), (a, b) -> a));
    problem.setProperty("fieldErrors", errors);
    return ResponseEntity.badRequest()
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(problem);
  }

  @ExceptionHandler(ResponseStatusException.class)
  ResponseEntity<ProblemDetail> responseStatus(ResponseStatusException exception) {
    return ResponseEntity.status(exception.getStatusCode())
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(ProblemDetail.forStatusAndDetail(exception.getStatusCode(), exception.getReason()));
  }

  @ExceptionHandler(RegistrationService.RateLimitedException.class)
  ResponseEntity<ProblemDetail> limited(RegistrationService.RateLimitedException exception) {
    var problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, "Too many requests");
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .header("Retry-After", Long.toString(exception.retryAfterSeconds()))
        .body(problem);
  }

  @ExceptionHandler(RegistrationService.InvalidRegistrationException.class)
  ResponseEntity<ProblemDetail> commonPassword() {
    var problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
    problem.setProperty("fieldErrors", Map.of("password", "Choose a less common password"));
    return ResponseEntity.badRequest()
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(problem);
  }
}
