package com.github.yuyuvu.springhotelsbookingapp.hotel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Основной класс для запуска сервиса, который отвечает за отели, номера и календарь их занятости.
 */
@SpringBootApplication
public class HotelServiceApplication {
  /**
   * Точка входа в приложение, которая запускает Spring Boot и создаёт необходимые компоненты на
   * основе настроек проекта.
   */
  public static void main(String[] args) {
    SpringApplication.run(HotelServiceApplication.class, args);
  }
}
