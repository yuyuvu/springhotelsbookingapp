package com.github.yuyuvu.springhotelsbookingapp.booking.services.impl;

import com.github.yuyuvu.springhotelsbookingapp.booking.client.HotelPort;
import com.github.yuyuvu.springhotelsbookingapp.booking.client.RemoteFailure;
import com.github.yuyuvu.springhotelsbookingapp.booking.controllers.BookingApi;
import com.github.yuyuvu.springhotelsbookingapp.booking.services.BookingSaga;
import com.github.yuyuvu.springhotelsbookingapp.booking.services.BookingTransactions;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * Сервис отвечает за согласование брони с отелем и освобождение номера при ошибке. Обращения по
 * сети выполняются между отдельными транзакциями сохранения состояния брони.
 */
@Service
@RequiredArgsConstructor
public class BookingSagaImpl implements BookingSaga {
  private final BookingTransactions transactions;
  private final HotelPort hotel;

  /**
   * Сначала сохраняем запрос в собственной базе, затем выполняем резервирование в отеле и следующий
   * шаг согласования. В ответ возвращается сохранённый результат этой операции, в том числе при
   * повторе запроса.
   */
  @Override
  public BookingApi.Outcome submit(
      long userId, UUID bookingId, UUID requestId, BookingApi.Create input) {
    var id = transactions.begin(userId, bookingId, requestId, input);
    process(id);
    process(id);
    return transactions.outcome(id, userId);
  }

  /**
   * Сохраняем отмену своей брони и выполняем доступные действия освобождения номера, после чего
   * получаем текущее состояние для ответа.
   */
  @Override
  public BookingApi.View cancel(UUID bookingId, long userId) {
    for (var id : transactions.cancel(bookingId, userId)) process(id);
    return transactions.get(bookingId, userId);
  }

  /**
   * Получаем одно доступное действие и выполняем нужное обращение к отелю для подтверждения или
   * освобождения. Если действие завершить не удалось, оно остаётся для следующей попытки.
   */
  @Override
  public void process(UUID id) {
    var claimed = transactions.claim(id);
    if (claimed.isEmpty()) return;
    var op = claimed.get();
    String action = op.getAction();
    var previous = MDC.getCopyOfContextMap();
    if (op.getTraceId() != null) MDC.put("traceId", op.getTraceId());
    MDC.put("bookingId", op.getBookingId().toString());
    MDC.put("operationId", op.getId().toString());
    MDC.put("requestId", op.getRequestId().toString());
    try {
      switch (action) {
        case "RESERVE" -> {
          try {
            var result =
                hotel.reserve(
                    new BookingApi.Reserve(
                        op.getBookingId(),
                        op.getId(),
                        op.getRoomId(),
                        op.getHotelId(),
                        op.isAutoSelect(),
                        op.getStartDate(),
                        op.getEndDate(),
                        op.getPreviousOperation()));
            transactions.confirmed(id, result);
          } catch (RemoteFailure e) {
            transactions.failed(
                id,
                e.status(),
                e.status() == 409
                    ? "ROOM_UNAVAILABLE"
                    : e.status() == 504 ? "HOTEL_TIMEOUT" : "HOTEL_UNAVAILABLE",
                e.getMessage());
          } catch (RuntimeException e) {
            LoggerFactory.getLogger(getClass()).error("booking.confirmation.failed", e);
            transactions.failed(id, 503, "BOOKING_FAILED", "Не удалось завершить бронирование");
          }
        }
        case "CANCEL_BOOKING" -> {
          var current = transactions.get(op.getBookingId(), op.getUserId());
          hotel.releaseBooking(op.getBookingId(), current.roomId());
          transactions.completed(id, action);
        }
        case "CANCEL_OPERATION" -> {
          hotel.releaseOperation(op.getBookingId(), op.getId());
          transactions.completed(id, action);
        }
        case "COMPLETE_CONFIRMATION" -> {
          hotel.commit(op.getBookingId(), op.getId());
          if (op.getPreviousOperation() != null)
            hotel.releaseOperation(op.getBookingId(), op.getPreviousOperation());
          transactions.completed(id, action);
        }
        default -> throw new IllegalStateException("Неизвестное действие саги");
      }
    } catch (RuntimeException e) {
      LoggerFactory.getLogger(getClass())
          .atWarn()
          .addKeyValue("action", action)
          .setCause(e)
          .log("saga.action.deferred");
      transactions.deferred(id, action);
    } finally {
      if (previous == null) MDC.clear();
      else MDC.setContextMap(previous);
    }
  }
}
