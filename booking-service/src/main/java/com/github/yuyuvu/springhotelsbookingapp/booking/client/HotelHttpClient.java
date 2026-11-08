package com.github.yuyuvu.springhotelsbookingapp.booking.client;

import com.github.yuyuvu.springhotelsbookingapp.booking.controllers.BookingApi;
import com.github.yuyuvu.springhotelsbookingapp.booking.security.TokenService;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Класс для отправки запросов в сервис отелей с нужным токеном и идентификатором запроса. Отвечает
 * также за повторы временных ошибок и ограничение обращений при сбоях.
 */
@Component
public class HotelHttpClient implements HotelPort {
  private final RestClient client;
  private final TokenService tokens;
  private final Retry retry;
  private final CircuitBreaker reserveBreaker;
  private final CircuitBreaker releaseBreaker;
  private final Bulkhead bulkhead;

  /**
   * Конструктор для создания HTTP-клиента с повторными попытками и ограничением обращений при
   * сбоях. Для резервирования и освобождения номера настраивается отдельная защита.
   */
  public HotelHttpClient(
      @Qualifier("hotelRestClientBuilder") RestClient.Builder builder,
      TokenService tokens,
      @Value("${app.hotel.url}") String url,
      @Value("${app.hotel.attempts}") int attempts,
      @Value("${app.hotel.retry-delay}") Duration delay) {
    this.client = builder.baseUrl(url).build();
    this.tokens = tokens;
    retry =
        Retry.of(
            "hotel",
            RetryConfig.custom()
                .maxAttempts(attempts)
                .intervalFunction(
                    IntervalFunction.ofExponentialRandomBackoff(delay.toMillis(), 2.0, 0.2))
                .retryOnException(e -> e instanceof RemoteFailure r && r.retryable())
                .build());
    var config =
        CircuitBreakerConfig.custom()
            .slidingWindowSize(10)
            .minimumNumberOfCalls(5)
            .failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofSeconds(10))
            .ignoreException(e -> e instanceof RemoteFailure r && !r.retryable())
            .build();
    reserveBreaker = CircuitBreaker.of("hotel-confirm", config);
    releaseBreaker = CircuitBreaker.of("hotel-release", config);
    retry
        .getEventPublisher()
        .onRetry(
            event ->
                LoggerFactory.getLogger(getClass())
                    .atWarn()
                    .addKeyValue("attempt", event.getNumberOfRetryAttempts())
                    .log("hotel.retry"));
    reserveBreaker
        .getEventPublisher()
        .onStateTransition(
            event ->
                LoggerFactory.getLogger(getClass())
                    .atWarn()
                    .addKeyValue("transition", event.getStateTransition())
                    .log("hotel.circuit.transition"));
    bulkhead =
        Bulkhead.of(
            "hotel",
            BulkheadConfig.custom().maxConcurrentCalls(32).maxWaitDuration(Duration.ZERO).build());
  }

  /**
   * Метод для занятия номера на даты одной операции бронирования. При повторной передаче того же
   * запроса используется ранее сохранённый результат, поэтому второе удержание не создаётся.
   */
  @Override
  public BookingApi.Reserved reserve(BookingApi.Reserve input) {
    return invoke(
        reserveBreaker,
        () -> {
          String path =
              input.autoSelect()
                  ? "/internal/reservations"
                  : "/api/rooms/" + input.roomId() + "/confirm-availability";
          var result =
              request(client.post().uri(path), tokens.service())
                  .body(input)
                  .retrieve()
                  .body(BookingApi.Reserved.class);
          if (result == null
              || !result.bookingId().equals(input.bookingId())
              || !result.operationId().equals(input.operationId())
              || result.roomId() == null
              || (!input.autoSelect() && !Objects.equals(input.roomId(), result.roomId()))
              || !result.startDate().equals(input.startDate())
              || !result.endDate().equals(input.endDate()))
            throw new RemoteFailure(503, "Сервис отелей вернул некорректный ответ", false);
          return result;
        });
  }

  /**
   * Если выбранный номер известен, вызываем освобождение через его адрес, иначе используем
   * идентификатор брони. Второй случай нужен, когда ответ автоподбора потерян и узнать номер не
   * удалось.
   */
  @Override
  public void releaseBooking(UUID id, Long roomId) {
    invoke(
        releaseBreaker,
        () -> {
          if (roomId == null) {
            request(client.post().uri("/internal/reservations/{id}/release", id), tokens.service())
                .retrieve()
                .toBodilessEntity();
          } else {
            request(client.post().uri("/api/rooms/{id}/release", roomId), tokens.service())
                .body(Map.of("bookingId", id))
                .retrieve()
                .toBodilessEntity();
          }
          return true;
        });
  }

  /**
   * Метод для установки отметки о том, что занятие номера подтверждено. После этого ночи проживания
   * учитываются в подтверждённой нагрузке номера.
   */
  @Override
  public void commit(UUID bookingId, UUID operationId) {
    invoke(
        releaseBreaker,
        () -> {
          request(
                  client.post().uri("/internal/operations/{id}/commit", operationId),
                  tokens.service())
              .body(Map.of("bookingId", bookingId))
              .retrieve()
              .toBodilessEntity();
          return true;
        });
  }

  /**
   * Метод для освобождения интервала только одной операции, например отклонённого изменения брони.
   * Другие варианты той же брони сохраняются, повторное освобождение не уменьшает счётчик ещё раз.
   */
  @Override
  public void releaseOperation(UUID bookingId, UUID operationId) {
    invoke(
        releaseBreaker,
        () -> {
          request(
                  client.post().uri("/internal/operations/{id}/release", operationId),
                  tokens.service())
              .body(Map.of("bookingId", bookingId))
              .retrieve()
              .toBodilessEntity();
          return true;
        });
  }

  /**
   * Метод для запроса рекомендаций у сервиса отелей с передачей пользовательского токена и общего
   * идентификатора запроса.
   */
  @Override
  public List<BookingApi.RoomRecommendation> recommend(
      Long hotelId, LocalDate start, LocalDate end, String bearer) {
    return invoke(
        reserveBreaker,
        () ->
            client
                .get()
                .uri(
                    b -> {
                      b.path("/api/rooms/recommend")
                          .queryParam("startDate", start)
                          .queryParam("endDate", end);
                      if (hotelId != null) b.queryParam("hotelId", hotelId);
                      return b.build();
                    })
                .headers(
                    h -> {
                      h.setBearerAuth(bearer);
                      h.set("X-Trace-Id", Objects.toString(MDC.get("traceId"), ""));
                    })
                .retrieve()
                .body(new ParameterizedTypeReference<List<BookingApi.RoomRecommendation>>() {}));
  }

  /**
   * Добавляем к внутреннему HTTP-запросу формат данных, служебный токен и идентификатор, по
   * которому вызов можно найти в журнале.
   */
  private RestClient.RequestBodySpec request(RestClient.RequestBodySpec spec, String token) {
    return spec.contentType(MediaType.APPLICATION_JSON)
        .headers(
            h -> {
              h.setBearerAuth(token);
              h.set("X-Trace-Id", Objects.toString(MDC.get("traceId"), ""));
            });
  }

  /**
   * Выполняем обращение к отелю с ограничением одновременных вызовов и повторами. При частых сбоях
   * новые обращения временно прекращаются, чтобы не нагружать недоступный сервис.
   */
  private <T> T invoke(CircuitBreaker breaker, Supplier<T> operation) {
    Supplier<T> translated =
        () -> {
          try {
            return operation.get();
          } catch (HttpStatusCodeException e) {
            int s = e.getStatusCode().value();
            throw new RemoteFailure(
                s == 409 ? 409 : 503,
                s == 409 ? "Номер недоступен на выбранные даты" : "Сервис отелей отклонил запрос",
                s >= 500);
          } catch (ResourceAccessException e) {
            throw new RemoteFailure(
                504,
                "Истекло время ожидания сервиса отелей или не удалось установить соединение",
                true);
          }
        };
    try {
      return Bulkhead.decorateSupplier(
              bulkhead,
              CircuitBreaker.decorateSupplier(breaker, Retry.decorateSupplier(retry, translated)))
          .get();
    } catch (CallNotPermittedException | BulkheadFullException e) {
      throw new RemoteFailure(503, "Сервис отелей временно недоступен", false);
    }
  }
}
