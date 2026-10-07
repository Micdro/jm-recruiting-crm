package com.janemichael.jmrecruitingcrm;

import com.janemichael.jmrecruitingcrm.company.CompanyHasContactsException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidationErrors(MethodArgumentNotValidException exception) {
        List<String> messages = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(error -> error.getDefaultMessage())
                .toList();

        ApiErrorResponse response = new ApiErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "Validation failed",
                messages
        );
        return ResponseEntity.badRequest().body(response);

    }

    @ExceptionHandler(CompanyHasContactsException.class)
    public ResponseEntity<ApiErrorResponse> handleCompanyHasContacts(CompanyHasContactsException exception) {
        ApiErrorResponse response = new ApiErrorResponse(
                HttpStatus.CONFLICT.value(),
                "Conflict",
                List.of(exception.getMessage())
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }
}
