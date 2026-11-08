package com.github.yuyuvu.springhotelsbookingapp.hotel.services;

import com.github.yuyuvu.springhotelsbookingapp.hotel.controllers.HotelApi;
import java.util.UUID;

/**
 * Интерфейс сервиса, через который другое приложение может занять номер, подтвердить бронь или
 * освободить ранее занятые даты.
 */
public interface InventoryService {
  /**
   * Метод для занятия номера на даты одной операции бронирования. При повторной передаче того же
   * запроса используется ранее сохранённый результат, поэтому второе удержание не создаётся.
   */
  HotelApi.Reserved reserve(HotelApi.Reserve input);

  /**
   * Метод для установки отметки о том, что занятие номера подтверждено. После этого ночи проживания
   * учитываются в подтверждённой загрузке для отчёта.
   */
  void commit(UUID bookingId, UUID operationId);

  /**
   * Метод для освобождения всех интервалов, относящихся к одной брони. Также сохраняется отметка
   * отмены, чтобы позднее подтверждение не заняло эти даты снова.
   */
  void releaseBooking(UUID bookingId, Long expectedRoom);

  /**
   * Метод для освобождения интервала только одной операции, например отклонённого изменения брони.
   * Другие варианты той же брони сохраняются, а повторное освобождение не уменьшает счётчик ещё
   * раз.
   */
  void releaseOperation(UUID operationId, UUID bookingId);
}
