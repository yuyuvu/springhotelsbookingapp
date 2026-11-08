package com.github.yuyuvu.springhotelsbookingapp.booking.services.impl;

import com.github.yuyuvu.springhotelsbookingapp.booking.controllers.BookingApi;
import com.github.yuyuvu.springhotelsbookingapp.booking.entities.Booking;
import com.github.yuyuvu.springhotelsbookingapp.booking.entities.BookingOperation;
import com.github.yuyuvu.springhotelsbookingapp.booking.entities.User;
import com.github.yuyuvu.springhotelsbookingapp.booking.repositories.BookingRepository;
import com.github.yuyuvu.springhotelsbookingapp.booking.repositories.OperationRepository;
import com.github.yuyuvu.springhotelsbookingapp.booking.repositories.UserRepository;
import com.github.yuyuvu.springhotelsbookingapp.booking.services.BookingTransactions;
import com.github.yuyuvu.springhotelsbookingapp.booking.support.ApiException;
import com.github.yuyuvu.springhotelsbookingapp.booking.support.BookingMapper;
import com.github.yuyuvu.springhotelsbookingapp.booking.support.DateRange;
import com.github.yuyuvu.springhotelsbookingapp.booking.support.PageDto;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Сервис для сохранения состояний бронирования, результатов отдельных запросов и заданий следующего
 * шага. Блокировки нужны, чтобы одновременные обработчики согласованно меняли одну бронь.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class BookingTransactionsImpl implements BookingTransactions {
  private final BookingRepository bookings;
  private final OperationRepository operations;
  private final UserRepository users;
  private final BookingMapper mapper;
  private final Clock clock;

  @Value("${app.saga.deadline}")
  private Duration deadline;

  /**
   * Метод для сохранения ожидающей операции перед обращением к отелю с проверкой пользователя и
   * повторного запроса. При изменении брони её прежние подтверждённые данные остаются действующими
   * до получения результата.
   */
  @Override
  public UUID begin(long userId, UUID bookingId, UUID requestId, BookingApi.Create input) {
    var dates = new DateRange(input.startDate(), input.endDate());
    if (!input.autoSelect() && (input.roomId() == null || input.roomId() < 1))
      throw ApiException.invalid("При autoSelect=false необходимо указать положительный roomId");
    if (input.hotelId() != null && input.hotelId() < 1)
      throw ApiException.invalid("hotelId должен быть положительным числом");
    var user = users.lock(userId).filter(User::isActive).orElseThrow(ApiException::missing);
    String fingerprint = fingerprint(bookingId, input);
    var existing = operations.findByUserIdAndRequestId(user.getId(), requestId);
    if (existing.isPresent()) {
      if (!existing.get().getFingerprint().equals(fingerprint))
        throw ApiException.conflict("Этот requestId уже использован с другими параметрами");
      return existing.get().getId();
    }
    if (dates.start().isBefore(LocalDate.now(clock)))
      throw ApiException.invalid("Дата заезда не может быть в прошлом");
    var now = clock.instant();
    Booking booking;
    if (bookingId == null) {
      booking = new Booking();
      booking.setId(UUID.randomUUID());
      booking.setUserId(userId);
      booking.setRoomId(input.autoSelect() ? null : input.roomId());
      booking.setHotelId(input.hotelId());
      booking.setStartDate(dates.start());
      booking.setEndDate(dates.end());
      booking.setStatus("PENDING");
      booking.setCreatedAt(now);
      booking.setUpdatedAt(now);
      bookings.save(booking);
    } else {
      booking = owned(bookingId, userId);
      if (!booking.getStatus().equals("CONFIRMED"))
        throw ApiException.conflict("Изменять можно только подтверждённые бронирования");
      if (!booking.getStartDate().isAfter(LocalDate.now(clock)))
        throw ApiException.conflict("Нельзя изменить бронирование после начала проживания");
      if (booking.getPendingOperation() != null)
        throw ApiException.conflict("Изменение этого бронирования уже выполняется");
    }
    var op = new BookingOperation();
    op.setId(UUID.randomUUID());
    op.setBookingId(booking.getId());
    op.setUserId(userId);
    op.setRequestId(requestId);
    op.setFingerprint(fingerprint);
    op.setKind(bookingId == null ? "CREATE" : "AMEND");
    op.setState("PENDING");
    op.setAction("RESERVE");
    op.setRoomId(input.autoSelect() ? null : input.roomId());
    op.setHotelId(input.hotelId());
    op.setAutoSelect(input.autoSelect());
    op.setStartDate(dates.start());
    op.setEndDate(dates.end());
    op.setPreviousOperation(booking.getCurrentOperation());
    op.setCreatedAt(now);
    op.setDeadline(now.plus(deadline));
    op.setNextAttempt(now);
    op.setTraceId(MDC.get("traceId"));
    operations.save(op);
    booking.setPendingOperation(op.getId());
    event("booking.pending", op);
    return op.getId();
  }

  /**
   * Закрепляем готовое действие за одним обработчиком на ограниченное время. Если срок
   * подтверждения уже истёк, сохраняем отказ и задание освобождения возможного удержания.
   */
  @Override
  public Optional<BookingOperation> claim(UUID id) {
    bookings
        .lock(operations.bookingId(id).orElseThrow(ApiException::missing))
        .orElseThrow(ApiException::missing);
    var op = operations.findById(id).orElseThrow();
    var now = clock.instant();
    if (op.getAction().equals("NONE")
        || op.getNextAttempt().isAfter(now)
        || (op.getLockUntil() != null && op.getLockUntil().isAfter(now))) return Optional.empty();
    if (op.getAction().equals("RESERVE") && !op.getDeadline().isAfter(now)) {
      failLocked(op, 504, "BOOKING_TIMEOUT", "Истёк срок подтверждения бронирования");
    }
    op.setLockUntil(now.plusSeconds(30));
    op.setAttempts(op.getAttempts() + 1);
    return Optional.of(op);
  }

  /**
   * Сохраняем успешное подтверждение и следующее действие завершения операции. Если бронь уже
   * отменена или срок ожидания истёк, запоздавший успех не возвращает её в подтверждённое
   * состояние.
   */
  @Override
  public void confirmed(UUID id, BookingApi.Reserved result) {
    var booking = bookings.lock(operations.bookingId(id).orElseThrow()).orElseThrow();
    var op = operations.findById(id).orElseThrow();
    if (!op.getState().equals("PENDING")
        || booking.getStatus().equals("CANCELLED")
        || !op.getDeadline().isAfter(clock.instant())) {
      if (op.getState().equals("PENDING"))
        failLocked(
            op, 504, "BOOKING_TIMEOUT", "Бронирование отменено или срок подтверждения истёк");
      return;
    }
    op.setRoomId(result.roomId());
    op.setState("SUCCEEDED");
    op.setAction("COMPLETE_CONFIRMATION");
    op.setNextAttempt(clock.instant());
    op.setLockUntil(null);
    booking.setRoomId(result.roomId());
    booking.setHotelId(op.getHotelId());
    booking.setStartDate(op.getStartDate());
    booking.setEndDate(op.getEndDate());
    booking.setStatus("CONFIRMED");
    booking.setCurrentOperation(op.getId());
    booking.setPendingOperation(null);
    booking.setUpdatedAt(clock.instant());
    event("booking.confirmed", op);
  }

  /**
   * Метод для сохранения ошибки ожидающей операции и действия, которое должно освободить занятые
   * даты в отеле.
   */
  @Override
  public void failed(UUID id, int status, String code, String message) {
    bookings.lock(operations.bookingId(id).orElseThrow()).orElseThrow();
    var op = operations.findById(id).orElseThrow();
    if (op.getState().equals("PENDING")) failLocked(op, status, code, message);
  }

  /**
   * Сохраняем отказ операции после получения блокировки её брони. Для новой брони устанавливаем
   * отмену, а при отклонённом изменении оставляем прежнюю бронь действующей.
   */
  private void failLocked(BookingOperation op, int status, String code, String message) {
    var booking = bookings.findById(op.getBookingId()).orElseThrow();
    op.setState("FAILED");
    op.setErrorStatus(status);
    op.setErrorCode(code);
    op.setErrorMessage(message);
    op.setAction(op.getKind().equals("CREATE") ? "CANCEL_BOOKING" : "CANCEL_OPERATION");
    op.setLockUntil(null);
    op.setNextAttempt(clock.instant());
    if (op.getKind().equals("CREATE")) booking.setStatus("CANCELLED");
    booking.setPendingOperation(null);
    booking.setUpdatedAt(clock.instant());
    event("booking.cancelled-or-amendment-rejected", op);
  }

  /**
   * Отмечаем действие завершённым, если в записи всё ещё ожидается именно этот шаг, чтобы поздний
   * ответ не затёр другое назначенное действие.
   */
  @Override
  public void completed(UUID id, String expectedAction) {
    bookings.lock(operations.bookingId(id).orElseThrow()).orElseThrow();
    var op = operations.findById(id).orElseThrow();
    if (op.getAction().equals(expectedAction)) {
      op.setAction("NONE");
      op.setLockUntil(null);
      event("saga.action.completed", op);
    }
  }

  /**
   * Снимаем временную блокировку обработчика и задаём следующую попытку с увеличенной задержкой,
   * чтобы продолжить действие после сбоя.
   */
  @Override
  public void deferred(UUID id, String expectedAction) {
    bookings.lock(operations.bookingId(id).orElseThrow()).orElseThrow();
    var op = operations.findById(id).orElseThrow();
    if (op.getAction().equals(expectedAction)) {
      op.setLockUntil(null);
      op.setNextAttempt(
          clock.instant().plusSeconds(Math.min(60, 1L << Math.min(op.getAttempts(), 6))));
      event("compensation.deferred", op);
    }
  }

  /**
   * Метод для сохранения отмены своей брони и заданий освобождения всех её вариантов. При повторной
   * отмене возвращаются только действия, которые ещё нужно выполнить.
   */
  @Override
  public List<UUID> cancel(UUID id, long userId) {
    var booking = owned(id, userId);
    if (booking.getStatus().equals("CANCELLED"))
      return operations.findByBookingId(id).stream()
          .filter(o -> !o.getAction().equals("NONE"))
          .map(BookingOperation::getId)
          .toList();
    booking.setStatus("CANCELLED");
    booking.setUpdatedAt(clock.instant());
    booking.setPendingOperation(null);
    var all = operations.findByBookingId(id);
    for (var op : all) {
      if (op.getState().equals("PENDING")) {
        op.setState("FAILED");
        op.setErrorStatus(409);
        op.setErrorCode("BOOKING_CANCELLED");
        op.setErrorMessage("Бронирование отменено владельцем");
      }
      op.setAction("CANCEL_BOOKING");
      op.setLockUntil(null);
      op.setNextAttempt(clock.instant());
      event("booking.cancelled", op);
    }
    return all.stream().map(BookingOperation::getId).toList();
  }

  /**
   * Метод для получения сохранённого результата отдельного запроса его владельцем. Если изменение
   * ещё ожидается, возвращаются прежние данные брони и идентификатор незавершённой операции.
   */
  @Transactional(readOnly = true)
  @Override
  public BookingApi.Outcome outcome(UUID id, long userId) {
    var op =
        operations
            .findById(id)
            .filter(o -> o.getUserId() == userId)
            .orElseThrow(ApiException::missing);
    var booking = bookings.findById(op.getBookingId()).orElseThrow();
    var snapshot =
        new BookingApi.View(
            booking.getId(),
            userId,
            op.getRoomId(),
            op.getStartDate(),
            op.getEndDate(),
            op.getState().equals("SUCCEEDED")
                ? "CONFIRMED"
                : op.getState().equals("FAILED") && op.getKind().equals("CREATE")
                    ? "CANCELLED"
                    : booking.getStatus(),
            booking.getCreatedAt(),
            op.getCreatedAt(),
            op.getState().equals("PENDING") ? op.getId() : null);
    if (op.getState().equals("PENDING") && op.getKind().equals("AMEND"))
      snapshot = mapper.booking(booking);
    return new BookingApi.Outcome(
        op.getState().equals("SUCCEEDED")
            ? (op.getKind().equals("CREATE") ? 201 : 200)
            : op.getState().equals("FAILED") ? op.getErrorStatus() : 202,
        snapshot,
        op.getErrorCode(),
        op.getErrorMessage(),
        !op.getAction().equals("NONE") && !op.getAction().equals("RESERVE"));
  }

  /**
   * Метод для получения текущего состояния брони с проверкой принадлежности пользователю, который
   * её запросил.
   */
  @Transactional(readOnly = true)
  @Override
  public BookingApi.View get(UUID id, long userId) {
    return mapper.booking(
        bookings
            .findById(id)
            .filter(b -> b.getUserId() == userId)
            .orElseThrow(ApiException::missing));
  }

  /**
   * Метод для получения страницы собственных бронирований с проверкой допустимого статуса и периода
   * поиска.
   */
  @Transactional(readOnly = true)
  @Override
  public PageDto<BookingApi.View> history(
      long userId, String status, LocalDate from, LocalDate to, Pageable page) {
    if (status != null && !Set.of("PENDING", "CONFIRMED", "CANCELLED").contains(status))
      throw ApiException.invalid("Недопустимый статус бронирования");
    if (from != null && to != null && from.isAfter(to))
      throw ApiException.invalid("Некорректный период поиска в истории");
    return PageDto.from(
        bookings
            .findAll(
                (b, q, c) -> {
                  var p = new ArrayList<jakarta.persistence.criteria.Predicate>();
                  p.add(c.equal(b.get("userId"), userId));
                  if (status != null) p.add(c.equal(b.get("status"), status));
                  if (from != null) p.add(c.greaterThanOrEqualTo(b.get("startDate"), from));
                  if (to != null) p.add(c.lessThanOrEqualTo(b.get("startDate"), to));
                  return c.and(p.toArray(jakarta.persistence.criteria.Predicate[]::new));
                },
                page)
            .map(mapper::booking));
  }

  /** Получаем следующую порцию операций, которые пора продолжить фоновому обработчику. */
  @Transactional(readOnly = true)
  @Override
  public List<UUID> due() {
    return operations.due(clock.instant(), PageRequest.of(0, 50));
  }

  /**
   * Получаем бронь с блокировкой и проверяем её владельца. Для чужой записи возвращается такая же
   * ошибка, как при отсутствии брони.
   */
  private Booking owned(UUID id, long userId) {
    return bookings
        .lock(id)
        .filter(b -> b.getUserId() == userId)
        .orElseThrow(ApiException::missing);
  }

  /**
   * Вычисляем контрольное значение данных запроса для проверки его повторной отправки. При
   * автоматическом подборе переданное поле номера игнорируется, как и при самом выборе.
   */
  private String fingerprint(UUID booking, BookingApi.Create r) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(
                      (booking
                              + "|"
                              + (r.autoSelect() ? null : r.roomId())
                              + "|"
                              + r.hotelId()
                              + "|"
                              + r.autoSelect()
                              + "|"
                              + r.startDate()
                              + "|"
                              + r.endDate())
                          .getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Добавляем идентификаторы брони, операции и запроса к записи перехода состояния. После
   * логирования восстанавливаем прежний контекст, чтобы эти данные не перешли в чужое действие.
   */
  private void event(String name, BookingOperation op) {
    var previous = MDC.getCopyOfContextMap();
    try {
      MDC.put("bookingId", op.getBookingId().toString());
      MDC.put("operationId", op.getId().toString());
      MDC.put("requestId", op.getRequestId().toString());
      LoggerFactory.getLogger(getClass())
          .atInfo()
          .addKeyValue("state", op.getState())
          .addKeyValue("action", op.getAction())
          .log(name);
    } finally {
      if (previous == null) MDC.clear();
      else MDC.setContextMap(previous);
    }
  }
}
