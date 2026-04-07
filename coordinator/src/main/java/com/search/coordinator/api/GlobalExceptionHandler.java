package com.search.coordinator.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.NoSuchElementException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> notFound(NoSuchElementException ex) {
        return status(HttpStatus.NOT_FOUND, ex);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException ex) {
        return status(HttpStatus.BAD_REQUEST, ex);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException ex) {
        return status(HttpStatus.CONFLICT, ex);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> internal(Exception ex) {
        return status(HttpStatus.INTERNAL_SERVER_ERROR, ex);
    }

    private static ResponseEntity<Map<String, String>> status(HttpStatus s, Exception ex) {
        return ResponseEntity.status(s).body(Map.of("error", ex.getMessage()));
    }
}
