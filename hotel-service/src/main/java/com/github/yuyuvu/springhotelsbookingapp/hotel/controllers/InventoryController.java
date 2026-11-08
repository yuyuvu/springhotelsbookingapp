package com.github.yuyuvu.springhotelsbookingapp.hotel.controllers;

import com.github.yuyuvu.springhotelsbookingapp.hotel.services.InventoryService;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Контроллер для внутренних запросов занятия, подтверждения и освобождения номера. К нему
 * обращается сервис бронирований, используя отдельный служебный токен.
 */
@Hidden
@RestController
@RequiredArgsConstructor
@PreAuthorize(
    "hasAuthority('SCOPE_inventory.write') and authentication.token.claims['token_use'] == 'service'")
public class InventoryController {
  private final InventoryService inventory;

  /**
   * Метод для занятия номера на даты одной операции бронирования. При повторной передаче того же
   * запроса используется ранее сохранённый результат, поэтому второе удержание не создаётся.
   */
  @PostMapping("/internal/reservations")
  public HotelApi.Reserved reserve(@Valid @RequestBody HotelApi.Reserve input) {
    return inventory.reserve(input);
  }

  /**
   * Метод для установки отметки о том, что занятие номера подтверждено. После этого ночи проживания
   * учитываются в подтверждённой загрузке для отчёта.
   */
  @PostMapping("/internal/operations/{operationId}/commit")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void commit(@PathVariable UUID operationId, @Valid @RequestBody HotelApi.Release r) {
    inventory.commit(r.bookingId(), operationId);
  }

  /**
   * Метод для проверки свободных дат и занятия конкретного номера. Для него используется та же
   * логика резервирования, что и при автоматическом подборе.
   */
  @PostMapping("/api/rooms/{id}/confirm-availability")
  public HotelApi.Reserved confirm(@PathVariable long id, @Valid @RequestBody HotelApi.Reserve r) {
    return inventory.reserve(
        new HotelApi.Reserve(
            r.bookingId(),
            r.operationId(),
            id,
            null,
            false,
            r.startDate(),
            r.endDate(),
            r.replacesOperationId()));
  }

  /**
   * Освобождаем бронь через адрес с известным номером, дополнительно проверяя, что этот номер
   * действительно относится к данной брони.
   */
  @PostMapping("/api/rooms/{id}/release")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void release(@PathVariable long id, @Valid @RequestBody HotelApi.Release r) {
    inventory.releaseBooking(r.bookingId(), id);
  }

  /**
   * Метод для освобождения всех интервалов, относящихся к одной брони. Также сохраняется отметка
   * отмены, чтобы позднее подтверждение не заняло эти даты снова.
   */
  @PostMapping("/internal/reservations/{bookingId}/release")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void releaseBooking(@PathVariable UUID bookingId) {
    inventory.releaseBooking(bookingId, null);
  }

  /**
   * Метод для освобождения интервала только одной операции, например отклонённого изменения брони.
   * Другие варианты той же брони сохраняются, а повторное освобождение не уменьшает счётчик ещё
   * раз.
   */
  @PostMapping("/internal/operations/{operationId}/release")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void releaseOperation(
      @PathVariable UUID operationId, @Valid @RequestBody HotelApi.Release r) {
    inventory.releaseOperation(operationId, r.bookingId());
  }
}
