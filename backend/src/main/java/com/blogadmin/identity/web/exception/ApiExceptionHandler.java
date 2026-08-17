package com.blogadmin.identity.web.exception;

import com.blogadmin.identity.application.AccountService.InvalidAccountException;
import com.blogadmin.identity.application.AdminUserService.AlreadyExistsException;
import com.blogadmin.identity.application.AdminUserService.ForbiddenException;
import com.blogadmin.identity.application.AdminUserService.InvalidMinimumException;
import com.blogadmin.identity.application.AdminUserService.LastAdminException;
import com.blogadmin.identity.application.AuthenticationService.BadCredentialsException;
import com.blogadmin.identity.application.AuthenticationService.InvitationInvalidatedException;
import com.blogadmin.identity.application.AuthenticationService.SessionNotFoundException;
import com.blogadmin.identity.application.RegistrationService.InvalidRegistrationException;
import com.blogadmin.identity.application.RegistrationService.RateLimitedException;
import jakarta.persistence.OptimisticLockException;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {
  @ExceptionHandler(InvalidAccountException.class)
  ResponseEntity<ProblemDetail> invalidAccount() {
    return problem(HttpStatus.BAD_REQUEST, "Request validation failed");
  }

  @ExceptionHandler(ForbiddenException.class)
  ResponseEntity<ProblemDetail> forbidden() {
    return problem(HttpStatus.FORBIDDEN, "Operation not allowed");
  }

  @ExceptionHandler(LastAdminException.class)
  ResponseEntity<ProblemDetail> lastAdmin() {
    return problem(HttpStatus.CONFLICT, "Last enabled verified Admin cannot be removed");
  }

  @ExceptionHandler(AlreadyExistsException.class)
  ResponseEntity<ProblemDetail> exists() {
    return problem(HttpStatus.CONFLICT, "User already exists");
  }

  @ExceptionHandler(InvalidMinimumException.class)
  ResponseEntity<ProblemDetail> minimum() {
    return problem(HttpStatus.BAD_REQUEST, "Password Minimum Length must be 8-128");
  }

  @ExceptionHandler({ObjectOptimisticLockingFailureException.class, OptimisticLockException.class})
  ResponseEntity<ProblemDetail> optimisticLockingConflict() {
    return problem(HttpStatus.CONFLICT, "Optimistic locking conflict");
  }

  private ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
    return ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(ProblemDetail.forStatusAndDetail(status, detail));
  }

  @ExceptionHandler(BadCredentialsException.class)
  ResponseEntity<ProblemDetail> unauthorized() {
    var problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Authentication failed");
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(problem);
  }

  @ExceptionHandler(InvitationInvalidatedException.class)
  ResponseEntity<ProblemDetail> invitationInvalidated() {
    var problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Authentication failed");
    problem.setProperty("code", "invitation_invalidated");
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(problem);
  }

  @ExceptionHandler(SessionNotFoundException.class)
  ResponseEntity<ProblemDetail> sessionNotFound() {
    var problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Session not found");
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(problem);
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
            .collect(
                Collectors.toMap(
                    FieldError::getField,
                    FieldError::getDefaultMessage,
                    (existing, replacement) -> existing));
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

  @ExceptionHandler(RateLimitedException.class)
  ResponseEntity<ProblemDetail> limited(RateLimitedException exception) {
    var problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, "Too many requests");
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .header("Retry-After", Long.toString(exception.retryAfterSeconds()))
        .body(problem);
  }

  @ExceptionHandler(InvalidRegistrationException.class)
  ResponseEntity<ProblemDetail> commonPassword() {
    var problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
    problem.setProperty("fieldErrors", Map.of("password", "Choose a less common password"));
    return ResponseEntity.badRequest()
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(problem);
  }
}
