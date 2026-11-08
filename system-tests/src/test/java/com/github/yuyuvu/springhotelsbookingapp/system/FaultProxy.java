package com.github.yuyuvu.springhotelsbookingapp.system;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Вспомогательный сервер между тестируемыми приложениями, который позволяет задержать ответ,
 * потерять его или вернуть ошибку вместо успешного обращения.
 */
final class FaultProxy implements AutoCloseable {
  /**
   * Перечисление режимов тестового сервера, которые задают обычную передачу запроса или
   * воспроизводят определённую ошибку связи.
   */
  enum Mode {
    NORMAL,
    ERROR,
    LOST_RESPONSE,
    DELAY
  }

  private final HttpServer server;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private final HttpClient client = HttpClient.newHttpClient();
  private final int upstream;
  final List<String> releases = new CopyOnWriteArrayList<>();
  final List<Integer> delayedResponses = new CopyOnWriteArrayList<>();
  volatile Mode mode = Mode.NORMAL;
  volatile boolean denyRelease;
  volatile CountDownLatch delayed = new CountDownLatch(0);

  /**
   * Конструктор запускает HTTP-сервер между тестируемыми приложениями, чтобы можно было управлять
   * ответами сервиса отелей.
   */
  FaultProxy(int upstream) throws Exception {
    this.upstream = upstream;
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(executor);
    server.createContext("/", this::handle);
    server.start();
  }

  /**
   * Метод для получения порта промежуточного сервера, на который будут направляться обращения
   * тестируемого сервиса.
   */
  int port() {
    return server.getAddress().getPort();
  }

  /**
   * Передаём запрос к отелю или воспроизводим выбранный сетевой сбой. Для отдельных режимов можно
   * задержать обращение, вернуть ошибку или не передать уже полученный успешный ответ.
   */
  private void handle(HttpExchange exchange) {
    String path = exchange.getRequestURI().toString();
    boolean reserve =
        path.equals("/internal/reservations") || path.endsWith("/confirm-availability");
    boolean release = path.endsWith("/release");
    Mode current = mode;
    if (release) releases.add(path);
    try (exchange) {
      byte[] body = exchange.getRequestBody().readAllBytes();
      if ((reserve && current == Mode.ERROR) || (release && denyRelease)) {
        exchange.sendResponseHeaders(503, -1);
        return;
      }
      if (reserve && current == Mode.DELAY) Thread.sleep(3500);
      var request =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + upstream + path))
              .timeout(Duration.ofSeconds(5))
              .method(exchange.getRequestMethod(), HttpRequest.BodyPublishers.ofByteArray(body));
      for (var header : List.of("Authorization", "Content-Type", "X-Trace-Id")) {
        var value = exchange.getRequestHeaders().getFirst(header);
        if (value != null) request.header(header, value);
      }
      var response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
      if (reserve && current == Mode.DELAY) delayedResponses.add(response.statusCode());
      if (reserve && current == Mode.LOST_RESPONSE) return;
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(
          response.statusCode(), response.body().length == 0 ? -1 : response.body().length);
      exchange.getResponseBody().write(response.body());
    } catch (java.io.IOException ignored) {
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    } finally {
      if (reserve && current == Mode.DELAY) delayed.countDown();
    }
  }

  /** Останавливаем промежуточный сервер, его обработчики и HTTP-клиент после окончания теста. */
  @Override
  public void close() {
    server.stop(0);
    executor.shutdownNow();
    client.close();
  }
}
