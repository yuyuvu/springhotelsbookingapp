package com.github.yuyuvu.springhotelsbookingapp.hotel.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Класс для настройки документации API, которая отображается в Swagger, и способа передачи токена
 * при проверке запросов.
 */
@Configuration
public class OpenApiConfig {
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
                .title("Отели, номера и загрузка")
                .description(
                    "Пользователь с ролью USER может просматривать отели и свободные номера, администратор управляет ими и получает статистику за 30 календарных дней. Поле available отражает возможность эксплуатации номера, а занятость проверяется на нужные даты.")
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
