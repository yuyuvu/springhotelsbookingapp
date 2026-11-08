import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Класс для проверки готовности приложения из контейнера через HTTP, чтобы для этой проверки не
 * требовались дополнительные инструменты.
 */
public class Healthcheck {
  /**
   * Отправляем запрос на адрес проверки готовности приложения. Если успешного ответа нет, завершаем
   * процесс с ошибкой, чтобы контейнер не считался готовым к использованию.
   */
  public static void main(String[] args) throws Exception {
    try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
      var request =
          HttpRequest.newBuilder(URI.create(args[0]))
              .timeout(Duration.ofSeconds(3))
              .GET()
              .build();
      var response = client.send(request, HttpResponse.BodyHandlers.discarding());
      if (response.statusCode() != 200) System.exit(1);
    }
  }
}
