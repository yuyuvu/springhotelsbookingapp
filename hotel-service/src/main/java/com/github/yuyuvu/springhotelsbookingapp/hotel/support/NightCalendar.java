package com.github.yuyuvu.springhotelsbookingapp.hotel.support;

import com.github.yuyuvu.springhotelsbookingapp.hotel.entities.Reservation;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Вспомогательный класс для подсчёта занятых ночей по календарю. Совпадающие даты нескольких
 * вариантов брони учитываются один раз, чтобы не завышать загрузку.
 */
public final class NightCalendar {
  /**
   * Конструктор закрыт, так как данный класс используется только для вызова общих методов подсчёта
   * ночей.
   */
  private NightCalendar() {}

  /**
   * Получаем набор подтверждённых и временно удержанных ночей внутри выбранного периода.
   * Совпадающие даты объединяем, а подтверждённые ночи исключаем из удержаний, чтобы одни сутки не
   * учитывались дважды.
   */
  public static Counts measure(
      Collection<Reservation> reservations, LocalDate start, LocalDate end) {
    var occupied = new HashSet<LocalDate>();
    var held = new HashSet<LocalDate>();
    for (var reservation : reservations) {
      if (!"ACTIVE".equals(reservation.getState())) continue;
      var from = reservation.getStartDate().isBefore(start) ? start : reservation.getStartDate();
      var to = reservation.getEndDate().isAfter(end) ? end : reservation.getEndDate();
      if (from.isBefore(to)) add(reservation.isConfirmed() ? occupied : held, from, to);
    }
    held.removeAll(occupied);
    return new Counts(occupied.size(), held.size());
  }

  /**
   * Метод для подсчёта всех ночей активных броней, у которых дата заезда попадает в нужный будущий
   * период. Если выезд позже конца периода отбора заездов, оставшиеся ночи всё равно учитываются.
   */
  public static long upcomingArrivalNights(
      Collection<Reservation> reservations, LocalDate start, LocalDate end) {
    var nights = new HashSet<LocalDate>();
    for (var reservation : reservations) {
      if ("ACTIVE".equals(reservation.getState())
          && !reservation.getStartDate().isBefore(start)
          && reservation.getStartDate().isBefore(end)) {
        add(nights, reservation.getStartDate(), reservation.getEndDate());
      }
    }
    return nights.size();
  }

  /**
   * Добавляем в набор все даты проживания от заезда до дня перед выездом, чтобы совпавшие ночи
   * хранились в одном экземпляре.
   */
  private static void add(Set<LocalDate> nights, LocalDate start, LocalDate end) {
    start.datesUntil(end).forEach(nights::add);
  }

  /**
   * Объект с результатом подсчёта ночей, в котором отдельно хранятся подтверждённые ночи и
   * временные удержания.
   */
  public record Counts(long occupiedNights, long heldNights) {}
}
