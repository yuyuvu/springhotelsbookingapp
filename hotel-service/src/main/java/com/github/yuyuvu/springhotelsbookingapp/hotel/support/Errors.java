package com.github.yuyuvu.springhotelsbookingapp.hotel.support;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Класс для обработки исключений и формирования ответов об ошибках. Для разных ошибок выбираются
 * соответствующие коды состояния и понятные пользователю сообщения.
 */
@RestControllerAdvice
public class Errors {
  /** Метод для формирования ошибки, если по указанному адресу не найден доступный ресурс. */
  @ExceptionHandler({
    org.springframework.web.servlet.resource.NoResourceFoundException.class,
    org.springframework.web.servlet.NoHandlerFoundException.class
  })
  ResponseEntity<ErrorDto> notFound(Exception e, HttpServletRequest r) {
    return response(HttpStatus.NOT_FOUND, "NOT_FOUND", "Ресурс не найден", r, Map.of());
  }

  /** Возвращаем ошибку, когда указанный HTTP-метод не поддерживается для данного адреса. */
  @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
  ResponseEntity<ErrorDto> method(Exception e, HttpServletRequest r) {
    return response(
        HttpStatus.METHOD_NOT_ALLOWED,
        "METHOD_NOT_ALLOWED",
        "Метод запроса не поддерживается",
        r,
        Map.of());
  }

  /** Возвращаем ошибку, если тело запроса передано в формате, который сервис не поддерживает. */
  @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
  ResponseEntity<ErrorDto> media(Exception e, HttpServletRequest r) {
    return response(
        HttpStatus.UNSUPPORTED_MEDIA_TYPE,
        "UNSUPPORTED_MEDIA_TYPE",
        "Формат данных не поддерживается",
        r,
        Map.of());
  }

  /**
   * Формируем ответ для ожидаемой ошибки, которая возникла при проверке запроса или состояния
   * данных.
   */
  @ExceptionHandler(ApiException.class)
  ResponseEntity<ErrorDto> business(ApiException e, HttpServletRequest r) {
    return response(e.status(), e.code(), e.getMessage(), r, Map.of());
  }

  /**
   * Получаем сообщения о некорректных полях запроса и включаем их в ответ, чтобы пользователь мог
   * исправить введённые данные.
   */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ErrorDto> validation(MethodArgumentNotValidException e, HttpServletRequest r) {
    var errors = new TreeMap<String, String>();
    e.getBindingResult()
        .getFieldErrors()
        .forEach(f -> errors.put(f.getField(), f.getDefaultMessage()));
    return response(HttpStatus.BAD_REQUEST, "VALIDATION", "Некорректные данные запроса", r, errors);
  }

  /**
   * Метод для ответа об ошибке при неверном формате тела запроса, его параметров или отсутствии
   * обязательных данных.
   */
  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class,
    org.springframework.web.bind.MissingServletRequestParameterException.class,
    jakarta.validation.ConstraintViolationException.class
  })
  ResponseEntity<ErrorDto> malformed(Exception e, HttpServletRequest r) {
    return response(
        HttpStatus.BAD_REQUEST, "VALIDATION", "Некорректный формат запроса", r, Map.of());
  }

  /**
   * Возвращаем ошибку конфликта, если нарушено ограничение базы или одна запись одновременно
   * изменяется несколькими запросами.
   */
  @ExceptionHandler({DataIntegrityViolationException.class, ConcurrencyFailureException.class})
  ResponseEntity<ErrorDto> conflict(Exception e, HttpServletRequest r) {
    return response(
        HttpStatus.CONFLICT,
        "CONFLICT",
        "Конфликт данных или параллельное изменение ресурса",
        r,
        Map.of());
  }

  /**
   * Метод для ответа об ошибке, когда у вошедшего пользователя нет прав на выполнение нужной
   * операции.
   */
  @ExceptionHandler(AccessDeniedException.class)
  ResponseEntity<ErrorDto> forbidden(Exception e, HttpServletRequest r) {
    return response(HttpStatus.FORBIDDEN, "FORBIDDEN", "Недостаточно прав доступа", r, Map.of());
  }

  /**
   * Записываем непредвиденную ошибку в журнал и возвращаем общее сообщение, чтобы не передавать
   * клиенту внутренние сведения приложения.
   */
  @ExceptionHandler(Exception.class)
  ResponseEntity<ErrorDto> unexpected(Exception e, HttpServletRequest r) {
    LoggerFactory.getLogger(Errors.class).error("Unhandled request failure", e);
    return response(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "INTERNAL_ERROR",
        "Внутренняя ошибка сервера",
        r,
        Map.of());
  }

  /**
   * Метод для создания ответа об ошибке с общими полями, адресом запроса, временем и
   * идентификатором для поиска в журнале.
   */
  public static ResponseEntity<ErrorDto> response(
      HttpStatus s, String code, String message, HttpServletRequest r, Map<String, String> fields) {
    return ResponseEntity.status(s)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(
            new ErrorDto(
                "urn:hotels:error:" + code.toLowerCase(),
                message,
                s.value(),
                code,
                message,
                r.getRequestURI(),
                Instant.now(),
                MDC.get("traceId"),
                fields));
  }
}
