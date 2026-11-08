package com.github.yuyuvu.springhotelsbookingapp.hotel.support;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** Объект с границами периода для отчёта, который должен занимать ровно 30 календарных дней. */
public record StatisticsPeriod(LocalDate start, LocalDate end) {
  /**
   * Метод для определения границ отчёта за 30 календарных дней с проверкой заданного периода. Если
   * даты не указаны, берём последние 30 дней вместе с сегодняшним.
   */
  public static StatisticsPeriod resolve(LocalDate start, LocalDate end, Clock clock) {
    if (start == null && end == null) end = LocalDate.now(clock).plusDays(1);
    if (start == null) start = end.minusDays(30);
    if (end == null) end = start.plusDays(30);
    if (ChronoUnit.DAYS.between(start, end) != 30)
      throw ApiException.invalid("Период статистики должен составлять ровно 30 календарных дней");
    return new StatisticsPeriod(start, end);
  }
}
