package com.github.yuyuvu.springhotelsbookingapp.apigateway.support;

import java.time.Duration;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.ReactiveHealthIndicator;
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Класс для проверки, что шлюз уже нашёл оба рабочих сервиса в Eureka и может направлять к ним
 * запросы.
 */
@Component("backendDiscovery")
public class BackendDiscoveryHealth implements ReactiveHealthIndicator {
  private final ReactiveDiscoveryClient discovery;

  /**
   * Конструктор сохраняет компонент поиска приложений в Eureka, который будет использоваться для
   * проверки готовности шлюза.
   */
  public BackendDiscoveryHealth(ReactiveDiscoveryClient discovery) {
    this.discovery = discovery;
  }

  /**
   * Проверяем, что оба рабочих сервиса найдены в реестре, ограничивая время ожидания. Если хотя бы
   * одного сервиса нет или проверка завершилась ошибкой, шлюз ещё не считается готовым.
   */
  @Override
  public Mono<Health> health() {
    return Mono.zip(
            discovery.getInstances("booking-service").hasElements(),
            discovery.getInstances("hotel-service").hasElements())
        .map(found -> found.getT1() && found.getT2() ? Health.up().build() : Health.down().build())
        .timeout(Duration.ofSeconds(2))
        .onErrorReturn(Health.down().build());
  }
}
