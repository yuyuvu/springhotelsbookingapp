package com.github.yuyuvu.springhotelsbookingapp.apigateway.support;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/**
 * Класс для формирования ответов об ошибках шлюза в общем формате, который используется остальными
 * сервисами.
 */
@Component
@Order(-2)
public class GatewayErrors implements WebExceptionHandler {
  private final ObjectMapper mapper;

  /**
   * Конструктор сохраняет компонент для преобразования данных ошибки в JSON при формировании
   * ответа.
   */
  public GatewayErrors(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  /**
   * Метод для обработки ошибки шлюза и выбора сообщения для клиента. Если отправка ответа уже
   * началась, повторно записывать его не пытаемся.
   */
  @Override
  public Mono<Void> handle(ServerWebExchange exchange, Throwable e) {
    if (exchange.getResponse().isCommitted()) return Mono.error(e);
    int status = e instanceof ResponseStatusException r ? r.getStatusCode().value() : 503;
    return write(
        exchange,
        status,
        status == 404 ? "NOT_FOUND" : "GATEWAY_ERROR",
        status == 404 ? "Ресурс не найден" : "Не удалось направить запрос в сервис");
  }

  /**
   * Метод для записи ответа об ошибке в общем формате с понятным сообщением и идентификатором
   * исходного запроса.
   */
  public Mono<Void> write(ServerWebExchange e, int status, String code, String detail) {
    var body = new LinkedHashMap<String, Object>();
    body.put("type", "urn:hotels:error:" + code.toLowerCase(Locale.ROOT));
    body.put("title", detail);
    body.put("status", status);
    body.put("code", code);
    body.put("detail", detail);
    body.put("instance", e.getRequest().getPath().value());
    body.put("timestamp", Instant.now().toString());
    body.put("traceId", Objects.toString(e.getResponse().getHeaders().getFirst("X-Trace-Id"), ""));
    body.put("errors", Map.of());
    e.getResponse().setStatusCode(HttpStatus.valueOf(status));
    e.getResponse().getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
    return e.getResponse()
        .writeWith(Mono.just(e.getResponse().bufferFactory().wrap(mapper.writeValueAsBytes(body))));
  }
}
