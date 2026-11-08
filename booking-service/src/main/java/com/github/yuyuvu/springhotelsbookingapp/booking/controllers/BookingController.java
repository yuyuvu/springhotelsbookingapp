package com.github.yuyuvu.springhotelsbookingapp.booking.controllers;

import com.github.yuyuvu.springhotelsbookingapp.booking.client.HotelPort;
import com.github.yuyuvu.springhotelsbookingapp.booking.services.BookingSaga;
import com.github.yuyuvu.springhotelsbookingapp.booking.services.BookingTransactions;
import com.github.yuyuvu.springhotelsbookingapp.booking.services.UserService;
import com.github.yuyuvu.springhotelsbookingapp.booking.support.ApiException;
import com.github.yuyuvu.springhotelsbookingapp.booking.support.DateRange;
import com.github.yuyuvu.springhotelsbookingapp.booking.support.Errors;
import com.github.yuyuvu.springhotelsbookingapp.booking.support.PageDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Контроллер для запросов к личным бронированиям пользователя. Передаёт создание, изменение и
 * отмену в сервис, который согласует действия с отелем.
 */
@RestController
@Tag(name = "Бронирования")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('USER','ADMIN')")
public class BookingController {
  private final BookingSaga saga;
  private final BookingTransactions transactions;
  private final UserService users;
  private final HotelPort hotel;

  /**
   * Метод для создания брони от имени вошедшего пользователя. В ответ передаётся результат
   * подтверждения или ожидающая бронь, если обработка ещё продолжается.
   */
  @PostMapping({"/api/bookings", "/booking"})
  @Operation(
      summary = "Создание брони с новым requestId или Idempotency-Key для каждого нового действия")
  public ResponseEntity<?> create(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "Idempotency-Key", required = false) UUID key,
      @Valid @RequestBody BookingApi.Create input,
      HttpServletRequest request) {
    return response(
        saga.submit(users.activeId(jwt.getSubject()), null, key(key, input.requestId()), input),
        request);
  }

  /**
   * Метод для изменения собственной подтверждённой брони с новым ключом запроса. Старый интервал
   * остаётся занятым до успешного подтверждения нового.
   */
  @PatchMapping({"/api/bookings/{id}", "/booking/{id}"})
  @Operation(summary = "Изменение своей брони с сохранением прежних дат до подтверждения новых")
  public ResponseEntity<?> amend(
      @PathVariable UUID id,
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "Idempotency-Key", required = false) UUID key,
      @Valid @RequestBody BookingApi.Create input,
      HttpServletRequest request) {
    return response(
        saga.submit(users.activeId(jwt.getSubject()), id, key(key, input.requestId()), input),
        request);
  }

  /**
   * Получаем историю только текущего пользователя, применяя нужные фильтры, сортировку и параметры
   * страницы.
   */
  @GetMapping({"/api/bookings", "/bookings"})
  @Operation(summary = "Получение своей истории бронирований с фильтрами и выводом по страницам")
  public PageDto<BookingApi.View> history(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) LocalDate from,
      @RequestParam(required = false) LocalDate to,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "createdAt,desc") String sort) {
    return transactions.history(
        users.activeId(jwt.getSubject()),
        status,
        from,
        to,
        PageDto.request(
            page, size, sort, Set.of("id", "createdAt", "startDate", "endDate", "status")));
  }

  /**
   * Метод для получения одной своей брони по идентификатору с проверкой того, что она принадлежит
   * текущему пользователю.
   */
  @GetMapping({"/api/bookings/{id}", "/booking/{id}"})
  @Operation(summary = "Получение отдельной своей брони по идентификатору")
  public BookingApi.View get(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
    return transactions.get(id, users.activeId(jwt.getSubject()));
  }

  /** Метод для отмены собственной брони и запуска освобождения занятого номера в сервисе отелей. */
  @DeleteMapping({"/api/bookings/{id}", "/booking/{id}"})
  @Operation(summary = "Отмена своей брони с возможностью безопасно повторить запрос")
  public BookingApi.View cancel(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
    return saga.cancel(id, users.activeId(jwt.getSubject()));
  }

  /**
   * Получаем рекомендации от сервиса отелей и возвращаем их по возрастанию счётчика бронирований и
   * идентификатора. Рассчитанные нагрузка и ранг сохраняются в отдельных полях ответа.
   */
  @GetMapping("/api/bookings/recommendations")
  @Operation(
      summary =
          "Получение свободных номеров по timesBooked и id с отдельным allocationRank для выбора по нагрузке")
  public List<BookingApi.RoomRecommendation> recommendations(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false) Long hotelId,
      @RequestParam LocalDate startDate,
      @RequestParam LocalDate endDate) {
    users.activeId(jwt.getSubject());
    new DateRange(startDate, endDate);
    return hotel.recommend(hotelId, startDate, endDate, jwt.getTokenValue()).stream()
        .sorted(
            Comparator.comparingLong(BookingApi.RoomRecommendation::timesBooked)
                .thenComparing(BookingApi.RoomRecommendation::id))
        .toList();
  }

  /**
   * Определяем общий ключ запроса по заголовку или полю тела. Если указаны оба варианта, значения
   * должны совпадать, иначе запрос отклоняется.
   */
  private UUID key(UUID header, UUID body) {
    if (header == null && body == null)
      throw ApiException.invalid("Необходимо указать Idempotency-Key или requestId");
    if (header != null && body != null && !header.equals(body))
      throw ApiException.invalid("Значения Idempotency-Key и requestId должны совпадать");
    return header != null ? header : body;
  }

  /**
   * Метод для формирования HTTP-ответа из результата операции с адресом брони в заголовке. В случае
   * ошибки добавляются сведения о брони и необходимости дальнейшего освобождения номера.
   */
  private ResponseEntity<?> response(BookingApi.Outcome outcome, HttpServletRequest request) {
    var location = java.net.URI.create("/api/bookings/" + outcome.booking().id());
    if (outcome.httpStatus() < 400)
      return ResponseEntity.status(outcome.httpStatus()).location(location).body(outcome.booking());
    var fields = new TreeMap<String, String>();
    fields.put("bookingId", outcome.booking().id().toString());
    fields.put("bookingStatus", outcome.booking().status());
    fields.put("compensationPending", Boolean.toString(outcome.compensationPending()));
    return Errors.response(
        HttpStatus.valueOf(outcome.httpStatus()),
        outcome.code(),
        outcome.message(),
        request,
        fields);
  }
}
