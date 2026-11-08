package com.github.yuyuvu.springhotelsbookingapp.booking.services;

import lombok.RequiredArgsConstructor;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Класс представляет фоновое задание для продолжения незавершённых операций бронирования и
 * повторного освобождения номера после временного сбоя.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "app.saga.recovery-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class SagaRecovery {
  private final BookingTransactions transactions;
  private final BookingSaga saga;

  /**
   * Получаем готовые к продолжению задания и последовательно передаём их на обработку. Ошибка одной
   * операции записывается в журнал и не мешает перейти к остальным.
   */
  @Scheduled(fixedDelayString = "${app.saga.recovery-delay}")
  public void recover() {
    for (var id : transactions.due()) {
      try {
        saga.process(id);
      } catch (RuntimeException e) {
        LoggerFactory.getLogger(getClass())
            .atError()
            .addKeyValue("operationId", id)
            .setCause(e)
            .log("saga.recovery.failed");
      }
    }
  }
}
