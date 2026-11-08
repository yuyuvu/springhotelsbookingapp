package com.github.yuyuvu.springhotelsbookingapp.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.yuyuvu.springhotelsbookingapp.booking.client.HotelHttpClient;
import com.github.yuyuvu.springhotelsbookingapp.booking.client.RemoteFailure;
import com.github.yuyuvu.springhotelsbookingapp.booking.controllers.BookingApi;
import com.github.yuyuvu.springhotelsbookingapp.booking.security.TokenService;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Класс для тестов HTTP-клиента на тестовом сервере. */
class HotelHttpClientTest {
  /**
   * Проверяем, что при известном номере освобождение отправляется на путь этого номера с
   * идентификатором брони в теле.
   */
  @Test
  void knownRoomCompensationUsesRequiredRoomReleaseEndpoint() throws Exception {
    var path = new java.util.concurrent.atomic.AtomicReference<String>();
    var body = new java.util.concurrent.atomic.AtomicReference<String>();
    server.createContext(
        "/",
        exchange -> {
          path.set(exchange.getRequestURI().getPath());
          body.set(
              new String(
                  exchange.getRequestBody().readAllBytes(),
                  java.nio.charset.StandardCharsets.UTF_8));
          exchange.sendResponseHeaders(204, -1);
          exchange.close();
        });
    server.start();
    client(2, Duration.ofSeconds(1)).releaseBooking(input.bookingId(), 49L);
    assertThat(path.get()).isEqualTo("/api/rooms/49/release");
    assertThat(body.get()).contains(input.bookingId().toString());
  }

  /**
   * Проверяем освобождение через идентификатор брони, когда узнать выбранный номер после сетевого
   * сбоя не удалось.
   */
  @Test
  void unknownRoomCompensationUsesBookingIdentifierWithoutGuessingRoom() throws Exception {
    var path = new java.util.concurrent.atomic.AtomicReference<String>();
    server.createContext(
        "/",
        exchange -> {
          path.set(exchange.getRequestURI().getPath());
          exchange.sendResponseHeaders(204, -1);
          exchange.close();
        });
    server.start();
    client(2, Duration.ofSeconds(1)).releaseBooking(input.bookingId(), null);
    assertThat(path.get()).isEqualTo("/internal/reservations/" + input.bookingId() + "/release");
  }

  HttpServer server;
  ExecutorService executor;
  AtomicInteger requests;
  TokenService tokens;
  BookingApi.Reserve input;

  /**
   * Подготавливаем отдельный HTTP-сервер и запрос с постоянными идентификаторами перед каждым
   * тестом, чтобы можно было проверить сетевые повторы.
   */
  @BeforeEach
  void setup() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    executor = Executors.newCachedThreadPool();
    server.setExecutor(executor);
    requests = new AtomicInteger();
    tokens = mock(TokenService.class);
    when(tokens.service()).thenReturn("service-token");
    var id = UUID.randomUUID();
    input =
        new BookingApi.Reserve(
            id, id, 1L, 1L, false, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 3), null);
  }

  /** Останавливаем HTTP-сервер и его обработчики после выполнения каждого теста. */
  @AfterEach
  void stop() {
    server.stop(0);
    executor.shutdownNow();
  }

  /**
   * Вспомогательный метод для создания клиента с коротким ожиданием ответа и нужным количеством
   * попыток для теста.
   */
  HotelHttpClient client(int attempts, Duration timeout) {
    var factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(Duration.ofMillis(200)).build());
    factory.setReadTimeout(timeout);
    return new HotelHttpClient(
        RestClient.builder().requestFactory(factory),
        tokens,
        "http://127.0.0.1:" + server.getAddress().getPort(),
        attempts,
        Duration.ofMillis(10));
  }

  /**
   * Вспомогательный метод для получения успешного ответа HTTP-сервера с данными, соответствующими
   * исходному запросу.
   */
  String success() {
    return "{\"bookingId\":\""
        + input.bookingId()
        + "\",\"operationId\":\""
        + input.operationId()
        + "\",\"roomId\":1,\"startDate\":\"2026-11-01\",\"endDate\":\"2026-11-03\"}";
  }

  /**
   * Возвращаем временные ошибки до успешного ответа и проверяем число повторов, а также сохранение
   * прежнего идентификатора операции.
   */
  @Test
  void retriesTransientErrorsAndKeepsOperationIdentifier() throws Exception {
    server.createContext(
        "/",
        e -> {
          int attempt = requests.incrementAndGet();
          assertThat(e.getRequestHeaders().getFirst("Authorization"))
              .isEqualTo("Bearer service-token");
          assertThat(
                  new String(
                      e.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
              .contains(input.operationId().toString());
          var bytes = success().getBytes(java.nio.charset.StandardCharsets.UTF_8);
          e.getResponseHeaders().set("Content-Type", "application/json");
          e.sendResponseHeaders(attempt < 3 ? 503 : 200, bytes.length);
          e.getResponseBody().write(bytes);
          e.close();
        });
    server.start();
    var result = client(3, Duration.ofSeconds(1)).reserve(input);
    assertThat(result.roomId()).isEqualTo(1);
    assertThat(requests).hasValue(3);
  }

  /**
   * Проверяем, что конфликт занятых дат с кодом 409 возвращается сразу и не вызывает повторных
   * обращений к отелю.
   */
  @Test
  void businessConflictIsNotRetried() throws Exception {
    server.createContext(
        "/",
        e -> {
          requests.incrementAndGet();
          e.sendResponseHeaders(409, -1);
          e.close();
        });
    server.start();
    assertThatThrownBy(() -> client(3, Duration.ofSeconds(1)).reserve(input))
        .isInstanceOfSatisfying(RemoteFailure.class, e -> assertThat(e.status()).isEqualTo(409));
    assertThat(requests).hasValue(1);
  }

  /**
   * Задерживаем ответ тестового сервера и проверяем завершение вызова по таймауту после
   * ограниченного количества попыток.
   */
  @Test
  void timeoutHasBoundedAttempts() throws Exception {
    server.createContext(
        "/",
        e -> {
          requests.incrementAndGet();
          try {
            Thread.sleep(500);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
          try {
            e.sendResponseHeaders(200, -1);
          } catch (java.io.IOException ignored) {
          }
          e.close();
        });
    server.start();
    assertThatThrownBy(() -> client(2, Duration.ofMillis(100)).reserve(input))
        .isInstanceOfSatisfying(RemoteFailure.class, e -> assertThat(e.status()).isEqualTo(504));
    assertThat(requests).hasValue(2);
  }

  /**
   * Вызываем остановку подтверждений после частых ошибок и проверяем, что обращение для
   * освобождения номера остаётся доступным.
   */
  @Test
  void openConfirmationCircuitDoesNotBlockCompensation() throws Exception {
    server.createContext(
        "/",
        e -> {
          requests.incrementAndGet();
          e.sendResponseHeaders(e.getRequestURI().getPath().endsWith("/release") ? 204 : 503, -1);
          e.close();
        });
    server.start();
    var client = client(1, Duration.ofSeconds(1));
    for (int i = 0; i < 5; i++)
      assertThatThrownBy(() -> client.reserve(input)).isInstanceOf(RemoteFailure.class);
    int before = requests.get();
    assertThatThrownBy(() -> client.reserve(input)).isInstanceOf(RemoteFailure.class);
    assertThat(requests.get()).isEqualTo(before);
    client.releaseBooking(input.bookingId(), 1L);
    assertThat(requests.get()).isEqualTo(before + 1);
  }
}
