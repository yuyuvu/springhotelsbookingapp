package com.github.yuyuvu.springhotelsbookingapp.booking.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Класс для настройки документации API, которая отображается в Swagger, и способа передачи токена
 * при проверке запросов.
 */
@Configuration
public class OpenApiConfig {
  /**
   * Убираем требование токена из документации входа и регистрации, так как пользователь получает
   * свой первый токен через эти запросы.
   */
  @Bean
  OpenApiCustomizer anonymousAuthenticationRoutes() {
    return api ->
        api.getPaths()
            .forEach(
                (path, item) -> {
                  if (path.startsWith("/api/auth/")
                      || path.equals("/user/register")
                      || path.equals("/user/auth")) {
                    item.readOperations()
                        .forEach(operation -> operation.setSecurity(java.util.List.of()));
                  }
                });
  }

  /**
   * Метод для создания описания API, которое отображается в Swagger вместе со способом авторизации
   * по токену.
   */
  @Bean
  OpenAPI api() {
    return new OpenAPI()
        .addServersItem(new io.swagger.v3.oas.models.servers.Server().url("/"))
        .info(
            new Info()
                .title("Бронирования и пользователи")
                .description(
                    "Для входа получите токен через login, нажмите Authorize и вставьте accessToken без слова Bearer. Для каждого нового действия используйте новый requestId, при повторе запроса сохраняйте прежний ключ.")
                .version("1.0.0"))
        .components(
            new Components()
                .addSecuritySchemes(
                    "bearerAuth",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
        .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
  }
}
