package com.github.yuyuvu.springhotelsbookingapp.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Вспомогательный класс для отправки HTTP-запросов в интеграционных тестах и проверки ожидаемых
 * кодов ответа.
 */
final class Api implements AutoCloseable {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  private final String base;
  private String token;

  /**
   * Конструктор для создания HTTP-клиента, который обращается к одному из запущенных тестовых
   * приложений.
   */
  Api(int port) {
    base = "http://127.0.0.1:" + port;
  }

  /**
   * Выполняем вход под нужным пользователем и сохраняем полученный токен для последующих запросов
   * этого клиента.
   */
  void login(String username, String password) throws Exception {
    token =
        post("/api/auth/login", Map.of("username", username, "password", password), 200)
            .body()
            .get("accessToken")
            .asText();
  }

  /**
   * Метод для использования уже полученного токена другого тестового клиента без повторного
   * выполнения входа.
   */
  void useToken(Api source) {
    token = source.token;
  }

  /**
   * Вспомогательный метод для отправки запроса чтения и проверки того, что код ответа входит в
   * ожидаемые для данного теста.
   */
  Response get(String path, int... expected) throws Exception {
    return call("GET", path, null, expected);
  }

  /**
   * Отправляем запрос создания и проверяем ответ. Для ожидающей брони дополнительно дожидаемся
   * окончательного результата повтором того же запроса.
   */
  Response post(String path, Object body, int... expected) throws Exception {
    return path.equals("/api/bookings") || path.equals("/booking")
        ? bookingRequest("POST", path, body, expected)
        : call("POST", path, body, expected);
  }

  /**
   * Отправляем запрос изменения, при необходимости продолжая ожидание результата операции
   * бронирования с прежним ключом.
   */
  Response patch(String path, Object body, int... expected) throws Exception {
    return path.startsWith("/api/bookings/")
        ? bookingRequest("PATCH", path, body, expected)
        : call("PATCH", path, body, expected);
  }

  /**
   * Если операция ещё выполняется, повторяем тот же запрос за ограниченное время до получения
   * окончательного ответа. Ключ при этом сохраняется, чтобы повтор не создал новую операцию.
   */
  private Response bookingRequest(String method, String path, Object body, int... expected)
      throws Exception {
    int[] accepted =
        java.util.stream.IntStream.concat(
                java.util.Arrays.stream(expected), java.util.stream.IntStream.of(202))
            .distinct()
            .toArray();
    var result =
        new java.util.concurrent.atomic.AtomicReference<>(call(method, path, body, accepted));
    if (result.get().status() == 202) {
      Applications.await(
          () -> {
            result.set(call(method, path, body, accepted));
            return result.get().status() != 202;
          },
          Duration.ofSeconds(20));
    }
    return result.get();
  }

  /** Вспомогательный метод для отправки удаления или отмены с проверкой ожидаемого кода ответа. */
  Response delete(String path, int... expected) throws Exception {
    return call("DELETE", path, null, expected);
  }

  /**
   * Создаём HTTP-запрос с токеном и телом, после чего проверяем полученный код состояния. Если он
   * оказался неожиданным, в ошибку теста добавляется тело ответа для выяснения причины.
   */
  private Response call(String method, String path, Object body, int... expected) throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json");
    if (token != null) builder.header("Authorization", "Bearer " + token);
    builder.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
    var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode())
        .as("%s %s: %s", method, path, response.body())
        .isIn(java.util.Arrays.stream(expected).boxed().toList());
    var content = response.body();
    var node =
        content.isBlank() || content.stripLeading().startsWith("<")
            ? JSON.nullNode()
            : JSON.readTree(content);
    return new Response(response.statusCode(), node, response.headers(), content);
  }

  /** Закрываем HTTP-клиент и освобождаем его ресурсы после завершения проверок. */
  @Override
  public void close() {
    client.close();
  }

  /**
   * Объект с полученным HTTP-ответом, в котором сохраняются код состояния, заголовки и тело для
   * проверок в тесте.
   */
  record Response(int status, JsonNode body, java.net.http.HttpHeaders headers, String raw) {}
}
