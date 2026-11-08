package com.github.yuyuvu.springhotelsbookingapp.discoveryserver;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Класс для тестов готовности Eureka и доступа к реестру с правильными служебными учётными данными.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RegistryIntegrationTest {
  @LocalServerPort int port;

  /**
   * Проверяем доступность готовности Eureka без входа, отказ при обращении к реестру без
   * авторизации и успех с правильным служебным логином и паролем.
   */
  @Test
  void healthIsPublicButRegistryRequiresServiceCredentials() throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      String base = "http://localhost:" + port;
      assertThat(
              client
                  .send(
                      HttpRequest.newBuilder(URI.create(base + "/actuator/health/readiness"))
                          .build(),
                      HttpResponse.BodyHandlers.ofString())
                  .statusCode())
          .isEqualTo(200);
      assertThat(
              client
                  .send(
                      HttpRequest.newBuilder(URI.create(base + "/eureka/apps")).build(),
                      HttpResponse.BodyHandlers.ofString())
                  .statusCode())
          .isEqualTo(401);
      String basic =
          Base64.getEncoder()
              .encodeToString(
                  "eurekalogin:eurekapassword".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      assertThat(
              client
                  .send(
                      HttpRequest.newBuilder(URI.create(base + "/eureka/apps"))
                          .header("Authorization", "Basic " + basic)
                          .header("Accept", "application/json")
                          .build(),
                      HttpResponse.BodyHandlers.ofString())
                  .statusCode())
          .isEqualTo(200);
    }
  }
}
