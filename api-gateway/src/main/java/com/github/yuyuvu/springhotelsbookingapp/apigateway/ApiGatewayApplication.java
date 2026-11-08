package com.github.yuyuvu.springhotelsbookingapp.apigateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Основной класс для запуска шлюза, через который пользователи обращаются к остальным сервисам
 * системы.
 */
@SpringBootApplication
public class ApiGatewayApplication {
  /**
   * Точка входа в приложение, которая запускает Spring Boot и создаёт необходимые компоненты на
   * основе настроек проекта.
   */
  public static void main(String[] args) {
    SpringApplication.run(ApiGatewayApplication.class, args);
  }
}
