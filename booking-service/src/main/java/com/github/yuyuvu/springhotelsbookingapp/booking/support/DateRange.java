package com.github.yuyuvu.springhotelsbookingapp.booking.support;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Объект для проверки и хранения периода проживания с датой заезда и датой выезда. День выезда в
 * занятый интервал не включается.
 */
public record DateRange(LocalDate start, LocalDate end) {
  /**
   * При создании периода проверяем, что обе даты заданы, выезд позже заезда и проживание занимает
   * не более 366 ночей.
   */
  public DateRange {
    if (start == null
        || end == null
        || !start.isBefore(end)
        || ChronoUnit.DAYS.between(start, end) > 366)
      throw ApiException.invalid(
          "Дата выезда должна быть позже даты заезда; срок проживания - не более 366 ночей");
  }

  /**
   * Метод для проверки пересечения двух периодов проживания. День выезда не включается, поэтому
   * выезд и следующий заезд в один день не создают конфликт.
   */
  public boolean overlaps(DateRange other) {
    return start.isBefore(other.end) && end.isAfter(other.start);
  }
}
