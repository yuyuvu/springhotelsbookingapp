package com.github.yuyuvu.springhotelsbookingapp.hotel.support;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Фильтр для добавления общего идентификатора запроса в ответ и данные журнала. С помощью этого
 * идентификатора можно найти связанные действия в разных сервисах.
 */
@Component
@Order(-200)
public class CorrelationFilter extends OncePerRequestFilter {
  /**
   * Получаем корректный идентификатор запроса или создаём новый и передаём его в ответ и данные
   * журнала. После завершения обработки очищаем данные текущего потока, чтобы они не попали в
   * следующий запрос.
   */
  @Override
  protected void doFilterInternal(HttpServletRequest r, HttpServletResponse s, FilterChain chain)
      throws ServletException, IOException {
    String trace = r.getHeader("X-Trace-Id");
    if (trace == null || !trace.matches("[a-f0-9]{32}"))
      trace = UUID.randomUUID().toString().replace("-", "");
    MDC.put("traceId", trace);
    s.setHeader("X-Trace-Id", trace);
    try {
      chain.doFilter(r, s);
    } finally {
      MDC.clear();
    }
  }
}
