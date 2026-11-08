package com.github.yuyuvu.springhotelsbookingapp.booking.services;

import com.github.yuyuvu.springhotelsbookingapp.booking.controllers.BookingApi;
import java.util.UUID;

/**
 * Интерфейс сервиса для выполнения связанных шагов создания, изменения и отмены брони в двух
 * приложениях.
 */
public interface BookingSaga {
  /**
   * Сначала сохраняем запрос в собственной базе, затем выполняем резервирование в отеле и следующий
   * шаг согласования. В ответ возвращается сохранённый результат этой операции, в том числе при
   * повторе запроса.
   */
  BookingApi.Outcome submit(long userId, UUID bookingId, UUID requestId, BookingApi.Create input);

  /**
   * Сохраняем отмену своей брони и выполняем доступные действия освобождения номера, после чего
   * получаем текущее состояние для ответа.
   */
  BookingApi.View cancel(UUID bookingId, long userId);

  /**
   * Получаем одно доступное действие и выполняем нужное обращение к отелю для подтверждения или
   * освобождения. Если действие завершить не удалось, оно остаётся для следующей попытки.
   */
  void process(UUID id);
}
