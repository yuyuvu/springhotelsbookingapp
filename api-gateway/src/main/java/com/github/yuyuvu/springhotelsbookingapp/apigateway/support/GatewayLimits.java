package com.github.yuyuvu.springhotelsbookingapp.apigateway.support;

import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Фильтр для ограничения размера тела запроса, чтобы через шлюз нельзя было передать слишком
 * крупные запросы.
 */
@Component
public class GatewayLimits implements WebFilter, Ordered {
  private static final int LIMIT = 65536;
  private final GatewayErrors errors;

  /**
   * Конструктор подключает общий обработчик ошибок для ответа на запросы со слишком большим телом.
   */
  public GatewayLimits(GatewayErrors errors) {
    this.errors = errors;
  }

  /**
   * Задаём выполнение проверки размера после назначения идентификатора и до передачи запроса
   * следующим обработчикам.
   */
  @Override
  public int getOrder() {
    return -150;
  }

  /**
   * Проверяем размер тела и по заявленной длине, и по реально прочитанным данным. При превышении 64
   * КиБ возвращаем ошибку, иначе передаём данные запроса дальше.
   */
  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    if (exchange.getRequest().getHeaders().getContentLength() > LIMIT) {
      return errors.write(
          exchange, 413, "REQUEST_TOO_LARGE", "Размер тела запроса превышает 64 КиБ");
    }
    return DataBufferUtils.join(exchange.getRequest().getBody(), LIMIT)
        .defaultIfEmpty(exchange.getResponse().bufferFactory().wrap(new byte[0]))
        .flatMap(
            buffer -> {
              byte[] bytes = new byte[buffer.readableByteCount()];
              buffer.read(bytes);
              DataBufferUtils.release(buffer);
              var decorated =
                  new ServerHttpRequestDecorator(exchange.getRequest()) {
                    /**
                     * Передаём сохранённые при проверке байты тела следующему обработчику, так как
                     * исходный поток уже был прочитан.
                     */
                    @Override
                    public Flux<DataBuffer> getBody() {
                      return Flux.defer(
                          () -> Flux.just(exchange.getResponse().bufferFactory().wrap(bytes)));
                    }
                  };
              return chain.filter(exchange.mutate().request(decorated).build());
            })
        .onErrorResume(
            DataBufferLimitException.class,
            e ->
                errors.write(
                    exchange, 413, "REQUEST_TOO_LARGE", "Размер тела запроса превышает 64 КиБ"));
  }
}
