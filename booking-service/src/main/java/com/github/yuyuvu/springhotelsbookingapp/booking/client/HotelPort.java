package com.github.yuyuvu.springhotelsbookingapp.booking.client;

import com.github.yuyuvu.springhotelsbookingapp.booking.controllers.BookingApi;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Интерфейс для обращения к сервису отелей. Позволяет выполнять логику бронирования через этот
 * интерфейс и подменять ответы удалённого сервиса в тестах.
 */
public interface HotelPort {
  /**
   * Метод для занятия номера на даты одной операции бронирования. При повторной передаче того же
   * запроса используется ранее сохранённый результат, поэтому второе удержание не создаётся.
   */
  BookingApi.Reserved reserve(BookingApi.Reserve input);

  /**
   * Метод для установки отметки о том, что занятие номера подтверждено. После этого ночи проживания
   * учитываются в подтверждённой нагрузке номера.
   */
  void commit(UUID bookingId, UUID operationId);

  /**
   * Метод для освобождения всех интервалов, относящихся к одной брони. Также сохраняется отметка
   * отмены, чтобы позднее подтверждение не заняло эти даты снова.
   */
  void releaseBooking(UUID bookingId, Long roomId);

  /**
   * Метод для освобождения интервала только одной операции, например отклонённого изменения брони.
   * Другие варианты той же брони сохраняются, а повторное освобождение не уменьшает счётчик ещё
   * раз.
   */
  void releaseOperation(UUID bookingId, UUID operationId);

  /**
   * Метод для получения свободных номеров и их нагрузки из сервиса отелей с проверкой доступа
   * пользователя.
   */
  List<BookingApi.RoomRecommendation> recommend(
      Long hotelId, LocalDate start, LocalDate end, String bearer);
}
