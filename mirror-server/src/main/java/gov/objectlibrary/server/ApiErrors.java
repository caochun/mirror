package gov.objectlibrary.server;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(BusinessConflict.class) @ResponseStatus(HttpStatus.CONFLICT)
    Map<String, String> conflict(BusinessConflict exception) { return Map.of("message", exception.getMessage()); }
    @ExceptionHandler(AuthenticationException.class) @ResponseStatus(HttpStatus.UNAUTHORIZED)
    Map<String, String> authentication() { return Map.of("message", "账号或密码错误"); }
    @ExceptionHandler(AccessDeniedException.class) @ResponseStatus(HttpStatus.FORBIDDEN)
    Map<String, String> forbidden() { return Map.of("message", "无权访问此数据"); }
    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class}) @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> invalid() { return Map.of("message", "参数无效，请检查输入"); }
    @ExceptionHandler(HttpMessageNotReadableException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> unreadable() { return Map.of("message", "请求内容格式无效"); }
}
