package com.github.yuyuvu.springhotelsbookingapp.apigateway.support;

import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Фильтр для добавления общего идентификатора запроса и записи его результата в журнал шлюза. */
@Component
public class GatewayCorrelation implements WebFilter, Ordered {
  /**
   * Определяем ранний порядок выполнения фильтра, чтобы общий идентификатор уже был доступен для
   * остальных обработчиков и ответов об ошибках.
   */
  @Override
  public int getOrder() {
    return -200;
  }

  /**
   * Получаем или создаём идентификатор запроса, передаём его дальше и возвращаем клиенту. После
   * обработки записываем в журнал адрес, код ответа и длительность обращения.
   */
  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    var value = exchange.getRequest().getHeaders().getFirst("X-Trace-Id");
    final String trace =
        value != null && value.matches("[a-f0-9]{32}")
            ? value
            : UUID.randomUUID().toString().replace("-", "");
    var changed =
        exchange.mutate().request(r -> r.headers(h -> h.set("X-Trace-Id", trace))).build();
    changed.getResponse().getHeaders().set("X-Trace-Id", trace);
    long start = System.nanoTime();
    return chain
        .filter(changed)
        .doFinally(
            signal ->
                LoggerFactory.getLogger(getClass())
                    .atInfo()
                    .addKeyValue("traceId", trace)
                    .addKeyValue("method", exchange.getRequest().getMethod())
                    .addKeyValue("path", exchange.getRequest().getPath().value())
                    .addKeyValue("status", exchange.getResponse().getStatusCode())
                    .addKeyValue("elapsedMs", (System.nanoTime() - start) / 1_000_000)
                    .log("gateway.request"));
  }
}
