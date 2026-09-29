package gov.mirror.app;

import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.Map;
import java.util.UUID;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler({SecurityException.class, IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<?> rejected(RuntimeException failure, jakarta.servlet.http.HttpServletRequest request) {
        int status = failure instanceof SecurityException ? 403 : failure instanceof IllegalArgumentException ? 400 : 409;
        String trace = UUID.randomUUID().toString();
        LoggerFactory.getLogger(ApiErrors.class).warn("Rejected trace={} actor={} path={} category={}", trace,
                request.getUserPrincipal() == null ? "anonymous" : request.getUserPrincipal().getName(),
                request.getRequestURI(), failure.getClass().getSimpleName());
        return ResponseEntity.status(status).body(Map.of("error", failure.getMessage() == null ? "Operation rejected" : failure.getMessage(),
                "trace", trace));
    }
}
