package com.github.yuyuvu.springhotelsbookingapp.booking.services;

import com.github.yuyuvu.springhotelsbookingapp.booking.controllers.BookingApi;
import com.github.yuyuvu.springhotelsbookingapp.booking.entities.BookingOperation;
import com.github.yuyuvu.springhotelsbookingapp.booking.support.PageDto;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;

/**
 * Интерфейс для операций с бронями, они целиком сохраняются в собственной базе в одной транзакции и
 * не требуют сетевых вызовов.
 */
public interface BookingTransactions {
  /**
   * Метод для сохранения ожидающей операции перед обращением к отелю с проверкой пользователя и
   * повторного запроса. При изменении брони её прежние подтверждённые данные остаются действующими
   * до получения результата.
   */
  UUID begin(long userId, UUID bookingId, UUID requestId, BookingApi.Create input);

  /**
   * Закрепляем готовое действие за одним обработчиком на ограниченное время. Если срок
   * подтверждения уже истёк, сохраняем отказ и задание освобождения возможного удержания.
   */
  Optional<BookingOperation> claim(UUID id);

  /**
   * Сохраняем успешное подтверждение и следующее действие завершения операции. Если бронь уже
   * отменена или срок ожидания истёк, запоздавший успех не возвращает её в подтверждённое
   * состояние.
   */
  void confirmed(UUID id, BookingApi.Reserved result);

  /**
   * Метод для сохранения ошибки ожидающей операции и действия, которое должно освободить занятые
   * даты в отеле.
   */
  void failed(UUID id, int status, String code, String message);

  /**
   * Отмечаем действие завершённым, если в записи всё ещё ожидается именно этот шаг, чтобы поздний
   * ответ не затёр другое назначенное действие.
   */
  void completed(UUID id, String expectedAction);

  /**
   * Снимаем временную блокировку обработчика и задаём следующую попытку с увеличенной задержкой,
   * чтобы продолжить действие после сбоя.
   */
  void deferred(UUID id, String expectedAction);

  /**
   * Метод для сохранения отмены своей брони и заданий освобождения всех её вариантов. При повторной
   * отмене возвращаются только действия, которые ещё нужно выполнить.
   */
  List<UUID> cancel(UUID id, long userId);

  /**
   * Метод для получения сохранённого результата отдельного запроса его владельцем. Если изменение
   * ещё ожидается, возвращаются прежние данные брони и идентификатор незавершённой операции.
   */
  BookingApi.Outcome outcome(UUID id, long userId);

  /**
   * Метод для получения текущего состояния брони с проверкой принадлежности пользователю, который
   * её запросил.
   */
  BookingApi.View get(UUID id, long userId);

  /**
   * Метод для получения страницы собственных бронирований с проверкой допустимого статуса и периода
   * поиска.
   */
  PageDto<BookingApi.View> history(
      long userId, String status, LocalDate from, LocalDate to, Pageable page);

  /** Получаем следующую порцию операций, которые пора продолжить фоновому обработчику. */
  List<UUID> due();
}
