package com.github.yuyuvu.springhotelsbookingapp.booking;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Основной класс для запуска сервиса пользователей и бронирований, а также фонового обработчика
 * незавершённых действий.
 */
@SpringBootApplication
@EnableScheduling
public class BookingServiceApplication {
  /**
   * Точка входа в приложение, которая запускает Spring Boot и создаёт необходимые компоненты на
   * основе настроек проекта.
   */
  public static void main(String[] args) {
    SpringApplication.run(BookingServiceApplication.class, args);
  }
}
