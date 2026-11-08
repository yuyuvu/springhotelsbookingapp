package com.github.yuyuvu.springhotelsbookingapp.hotel;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.yuyuvu.springhotelsbookingapp.hotel.entities.Reservation;
import com.github.yuyuvu.springhotelsbookingapp.hotel.support.NightCalendar;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Класс для тестов подсчёта ночей, работы с совпадающими датами и пограничными случаями. */
class NightCalendarTest {
  private final LocalDate today = LocalDate.of(2026, 11, 1);

  /**
   * Проверяем подсчёт совпадающих ночей в нескольких вариантах брони и ограничение дат границами
   * отчёта. Одна подтверждённая ночь не должна дополнительно учитываться как удержанная.
   */
  @Test
  void confirmedAndHeldRevisionsCountEachDateOnlyOnceAndClipHistory() {
    var confirmed = reservation(today.minusDays(40), today, true);
    var held = reservation(today.minusDays(5), today.plusDays(3), false);
    var released = reservation(today, today.plusDays(5), true);
    released.setState("RELEASED");
    var counts =
        NightCalendar.measure(
            List.of(confirmed, held, released), today.minusDays(29), today.plusDays(1));
    assertThat(counts.occupiedNights()).isEqualTo(29);
    assertThat(counts.heldNights()).isEqualTo(1);
  }

  /**
   * Проверяем учёт всего проживания при заезде на 120-й день, а также исключение заездов на 121-й
   * день и через два года.
   */
  @Test
  void arrivalHorizonIncludesWholeStayStartingOnDay120ButExcludesDay121AndTwoYears() {
    var inside = reservation(today.plusDays(120), today.plusDays(125), false);
    var sameDates = reservation(today.plusDays(120), today.plusDays(125), true);
    var after = reservation(today.plusDays(121), today.plusDays(130), true);
    var distant = reservation(today.plusYears(2), today.plusYears(2).plusDays(20), true);
    var ongoing = reservation(today.minusDays(1), today.plusDays(4), true);
    assertThat(
            NightCalendar.upcomingArrivalNights(
                List.of(inside, sameDates, after, distant, ongoing),
                today.plusDays(1),
                today.plusDays(121)))
        .isEqualTo(5);
  }

  /**
   * Вспомогательный метод для создания записи календаря с нужными датами и состоянием при проверке
   * подсчёта ночей.
   */
  private Reservation reservation(LocalDate start, LocalDate end, boolean confirmed) {
    var result = new Reservation();
    result.setStartDate(start);
    result.setEndDate(end);
    result.setState("ACTIVE");
    result.setConfirmed(confirmed);
    return result;
  }
}
