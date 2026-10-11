package com.janemichael.jmrecruitingcrm;

import com.janemichael.jmrecruitingcrm.common.BadRequestException;
import com.janemichael.jmrecruitingcrm.common.ConflictException;
import com.janemichael.jmrecruitingcrm.common.ForbiddenException;
import com.janemichael.jmrecruitingcrm.common.NotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

/** Turns errors into the JSON shape the React app shows: { status, error, messages[] }. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidationErrors(MethodArgumentNotValidException exception) {
        List<String> messages = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(error -> error.getDefaultMessage())
                .toList();

        return respond(HttpStatus.BAD_REQUEST, "Validation failed", messages);
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ApiErrorResponse> handleBadRequest(BadRequestException exception) {
        return respond(HttpStatus.BAD_REQUEST, "Bad request", List.of(exception.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadable(HttpMessageNotReadableException exception) {
        return respond(HttpStatus.BAD_REQUEST, "Bad request",
                List.of("The request has a value the server doesn't understand. Check dates and dropdowns."));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        return respond(HttpStatus.BAD_REQUEST, "Bad request",
                List.of("Invalid value for " + exception.getName()));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(NotFoundException exception) {
        return respond(HttpStatus.NOT_FOUND, "Not found", List.of(exception.getMessage()));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleConflict(ConflictException exception) {
        return respond(HttpStatus.CONFLICT, "Conflict", List.of(exception.getMessage()));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ApiErrorResponse> handleForbidden(ForbiddenException exception) {
        return respond(HttpStatus.FORBIDDEN, "Forbidden", List.of(exception.getMessage()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleDataIntegrity(DataIntegrityViolationException exception) {
        return respond(HttpStatus.CONFLICT, "Conflict",
                List.of("That change conflicts with other records. Refresh and try again."));
    }

    private static ResponseEntity<ApiErrorResponse> respond(HttpStatus status, String error, List<String> messages) {
        return ResponseEntity.status(status).body(new ApiErrorResponse(status.value(), error, messages));
    }
}
