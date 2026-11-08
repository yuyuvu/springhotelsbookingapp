package com.github.yuyuvu.springhotelsbookingapp.apigateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/**
 * Класс для тестов маршрутизации и проверки токенов на шлюзе. Запросы передаются на тестовый
 * сервер, где можно проверить полученные заголовки.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"eureka.client.enabled=false"})
@AutoConfigureWebTestClient
class GatewayIntegrationTest {
  static HttpServer upstream;

  static {
    try {
      upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      upstream.createContext(
          "/",
          e -> {
            String body =
                e.getRequestURI().getPath()
                    + "|"
                    + e.getRequestHeaders().getFirst("Authorization")
                    + "|"
                    + e.getRequestHeaders().getFirst("X-User-Id")
                    + "|"
                    + e.getRequestHeaders().getFirst("X-Trace-Id");
            var bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            e.sendResponseHeaders(200, bytes.length);
            e.getResponseBody().write(bytes);
            e.close();
          });
      upstream.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /**
   * Задаём адреса тестового HTTP-сервера вместо настоящих приложений, чтобы проверить маршруты без
   * отдельного запуска Eureka.
   */
  @DynamicPropertySource
  static void discovery(DynamicPropertyRegistry r) {
    r.add(
        "spring.cloud.discovery.client.simple.instances.booking-service[0].uri",
        () -> "http://127.0.0.1:" + upstream.getAddress().getPort());
    r.add(
        "spring.cloud.discovery.client.simple.instances.hotel-service[0].uri",
        () -> "http://127.0.0.1:" + upstream.getAddress().getPort());
  }

  @Autowired WebTestClient web;
  @MockitoBean ReactiveJwtDecoder decoder;

  /**
   * Подменяем проверку токенов так, чтобы можно было проверить успешный запрос и отказ при неверном
   * токене.
   */
  @BeforeEach
  void token() {
    when(decoder.decode("valid"))
        .thenReturn(
            Mono.just(
                new Jwt(
                    "valid",
                    Instant.now(),
                    Instant.now().plusSeconds(3600),
                    Map.of("alg", "RS256"),
                    Map.of(
                        "sub",
                        "1",
                        "roles",
                        java.util.List.of("USER"),
                        "aud",
                        java.util.List.of("hotels-api"),
                        "token_use",
                        "user"))));
    when(decoder.decode("invalid")).thenReturn(Mono.error(new BadJwtException("invalid")));
  }

  /** Останавливаем тестовый HTTP-сервер после выполнения всех проверок шлюза. */
  @AfterAll
  static void stop() {
    upstream.stop(0);
  }

  /**
   * Проверяем передачу токена и идентификатора запроса в следующий сервис. Дополнительно проверяем
   * удаление заголовка пользователя, который был подставлен клиентом.
   */
  @Test
  void tokenAndCorrelationAreForwardedAndSpoofedIdentityRemoved() {
    String trace = "123456789012345678901234567890ab";
    web.get()
        .uri("/api/bookings")
        .headers(
            h -> {
              h.setBearerAuth("valid");
              h.set("X-Trace-Id", trace);
              h.set("X-User-Id", "999");
            })
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals("X-Trace-Id", trace)
        .expectBody(String.class)
        .value(body -> assertThat(body).isEqualTo("/api/bookings|Bearer valid|null|" + trace));
  }

  /** Проверяем, что запрос без токена или с неверным токеном получает отказ с кодом 401. */
  @Test
  void unauthenticatedAndInvalidTokensReturn401() {
    web.get()
        .uri("/api/bookings")
        .exchange()
        .expectStatus()
        .isUnauthorized()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("UNAUTHORIZED");
    web.get()
        .uri("/api/hotels")
        .headers(h -> h.setBearerAuth("invalid"))
        .exchange()
        .expectStatus()
        .isUnauthorized();
  }

  /**
   * Проверяем доступность регистрации без предварительного входа и передачу запроса через
   * дополнительный путь.
   */
  @Test
  void registrationIsPublicAndLegacyRouteIsAvailable() {
    web.post().uri("/user/register").exchange().expectStatus().isOk();
    web.get()
        .uri("/bookings")
        .headers(h -> h.setBearerAuth("valid"))
        .exchange()
        .expectStatus()
        .isOk();
  }

  /** Проверяем запрет доступа через шлюз к служебным путям подтверждения и освобождения номера. */
  @Test
  void internalRoutesAreNeverPublished() {
    web.post()
        .uri("/api/rooms/1/release")
        .headers(h -> h.setBearerAuth("valid"))
        .exchange()
        .expectStatus()
        .isForbidden();
    web.post()
        .uri("/api/rooms/1/confirm-availability")
        .headers(h -> h.setBearerAuth("valid"))
        .exchange()
        .expectStatus()
        .isForbidden();
    web.post()
        .uri("/internal/reservations")
        .headers(h -> h.setBearerAuth("valid"))
        .exchange()
        .expectStatus()
        .isForbidden();
  }
}
