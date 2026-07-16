package dev.oranegonzales.ledgerrail.transfer;

import dev.oranegonzales.ledgerrail.outbox.OutboxReplayNotAllowedException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiProblemHandler {

    @ExceptionHandler(TransferNotFoundException.class)
    ResponseEntity<ProblemDetail> notFound(TransferNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, "Transfer not found", exception.getMessage());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<ProblemDetail> idempotencyConflict(IdempotencyConflictException exception) {
        return problem(HttpStatus.CONFLICT, "Idempotency conflict", exception.getMessage());
    }

    @ExceptionHandler(InvalidIdempotencyKeyException.class)
    ResponseEntity<ProblemDetail> invalidIdempotencyKey(InvalidIdempotencyKeyException exception) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid idempotency key", exception.getMessage());
    }

    @ExceptionHandler(OutboxReplayNotAllowedException.class)
    ResponseEntity<ProblemDetail> replayNotAllowed(OutboxReplayNotAllowedException exception) {
        return problem(HttpStatus.CONFLICT, "Outbox replay rejected", exception.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> unreadableRequest(HttpMessageNotReadableException exception) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request body", "The JSON body could not be read or contains an unsupported value");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> validationFailure(MethodArgumentNotValidException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "One or more request fields are invalid");
        detail.setTitle("Validation failed");
        List<Map<String, String>> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(this::fieldError)
                .toList();
        detail.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(detail);
    }

    private Map<String, String> fieldError(FieldError error) {
        Map<String, String> value = new LinkedHashMap<>();
        value.put("field", error.getField());
        value.put("message", error.getDefaultMessage() == null ? "Invalid value" : error.getDefaultMessage());
        return value;
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String title, String message) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, message);
        detail.setTitle(title);
        return ResponseEntity.status(status).body(detail);
    }
}
