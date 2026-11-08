package com.tradingstrat.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    public record ErrorResponse(Instant timestamp, int status, String error, String message) {}

    /** Invalid interval string or other bad input. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /** Bean validation failures (@NotBlank etc.). */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String details = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, details);
    }

    /** Binance API returned a 4xx (e.g. unknown symbol). */
    @ExceptionHandler(HttpClientErrorException.class)
    public ResponseEntity<ErrorResponse> handleBinance4xx(HttpClientErrorException ex) {
        return build(HttpStatus.BAD_GATEWAY,
                "Binance API error (" + ex.getStatusCode() + "): " + ex.getResponseBodyAsString());
    }

    /** Generic network / REST errors. */
    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<ErrorResponse> handleRestClient(RestClientException ex) {
        return build(HttpStatus.BAD_GATEWAY, "Failed to reach chart API: " + ex.getMessage());
    }

    /** Catch-all. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage());
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .body(new ErrorResponse(Instant.now(), status.value(), status.getReasonPhrase(), message));
    }
}
