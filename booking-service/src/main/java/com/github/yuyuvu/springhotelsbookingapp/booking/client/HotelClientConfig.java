package com.github.yuyuvu.springhotelsbookingapp.booking.client;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Scope;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Класс для настройки HTTP-клиента, через который сервис бронирований обращается к сервису отелей.
 * Также задаёт ограничения времени соединения и ожидания ответа.
 */
@Configuration
public class HotelClientConfig {
  /**
   * Создаём отдельный HTTP-клиент для служебных компонентов, которым не требуется искать адрес
   * через реестр Eureka.
   */
  @Bean
  @Primary
  @Scope("prototype")
  RestClient.Builder infrastructureRestClientBuilder() {
    return RestClient.builder();
  }

  /**
   * Метод для создания HTTP-клиента с ограничением времени установки соединения и ожидания ответа
   * от отеля.
   */
  private RestClient.Builder builder(Duration connect, Duration read) {
    var factory =
        new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(connect).build());
    factory.setReadTimeout(read);
    return RestClient.builder().requestFactory(factory);
  }

  /**
   * Метод для создания клиента, который обращается к сервису отелей по имени и получает его адрес
   * через реестр.
   */
  @Bean("hotelRestClientBuilder")
  @LoadBalanced
  @ConditionalOnProperty(name = "app.hotel.discovery", havingValue = "true", matchIfMissing = true)
  RestClient.Builder discovered(
      @Value("${app.hotel.connect-timeout}") Duration connect,
      @Value("${app.hotel.read-timeout}") Duration read) {
    return builder(connect, read);
  }

  /**
   * Метод для создания клиента с прямым обращением по заданному адресу, что используется в том
   * числе для тестового HTTP-сервера.
   */
  @Bean("hotelRestClientBuilder")
  @ConditionalOnProperty(name = "app.hotel.discovery", havingValue = "false")
  RestClient.Builder direct(
      @Value("${app.hotel.connect-timeout}") Duration connect,
      @Value("${app.hotel.read-timeout}") Duration read) {
    return builder(connect, read);
  }
}
