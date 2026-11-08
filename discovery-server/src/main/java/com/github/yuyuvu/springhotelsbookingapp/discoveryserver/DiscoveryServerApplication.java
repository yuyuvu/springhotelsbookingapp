package com.github.yuyuvu.springhotelsbookingapp.discoveryserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

/**
 * Основной класс для запуска Eureka, в которой приложения регистрируют свои адреса и находят адреса
 * других сервисов.
 */
@SpringBootApplication
@EnableEurekaServer
public class DiscoveryServerApplication {
  /**
   * Точка входа в приложение, которая запускает Spring Boot и создаёт необходимые компоненты на
   * основе настроек проекта.
   */
  public static void main(String[] args) {
    SpringApplication.run(DiscoveryServerApplication.class, args);
  }
}
