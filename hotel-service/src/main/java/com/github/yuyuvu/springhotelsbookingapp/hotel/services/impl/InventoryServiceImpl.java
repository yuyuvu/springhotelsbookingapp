package com.github.yuyuvu.springhotelsbookingapp.hotel.services.impl;

import com.github.yuyuvu.springhotelsbookingapp.hotel.controllers.HotelApi;
import com.github.yuyuvu.springhotelsbookingapp.hotel.entities.Reservation;
import com.github.yuyuvu.springhotelsbookingapp.hotel.repositories.ReservationRepository;
import com.github.yuyuvu.springhotelsbookingapp.hotel.repositories.RoomRepository;
import com.github.yuyuvu.springhotelsbookingapp.hotel.services.InventoryService;
import com.github.yuyuvu.springhotelsbookingapp.hotel.services.RoomAllocationService;
import com.github.yuyuvu.springhotelsbookingapp.hotel.support.AllocationLock;
import com.github.yuyuvu.springhotelsbookingapp.hotel.support.ApiException;
import com.github.yuyuvu.springhotelsbookingapp.hotel.support.DateRange;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Сервис для изменения календаря номеров и счётчиков бронирований. Проверка и изменение выполняются
 * под общей блокировкой, чтобы одновременные запросы не заняли один номер на пересекающиеся даты.
 */
@Service
@RequiredArgsConstructor
public class InventoryServiceImpl implements InventoryService {
  private final AllocationLock lock;
  private final RoomRepository rooms;
  private final RoomAllocationService allocation;
  private final ReservationRepository reservations;
  private final Clock clock;

  /**
   * Под общей блокировкой проверяем, не обрабатывалась ли эта операция, затем выбираем свободный
   * номер и сохраняем удержание вместе со счётчиком. При автоматическом выборе учитывается нагрузка
   * кандидатов, которую следующий запрос уже увидит изменённой.
   */
  @Transactional
  @Override
  public HotelApi.Reserved reserve(HotelApi.Reserve input) {
    var dates = new DateRange(input.startDate(), input.endDate());
    if (!input.autoSelect() && input.roomId() == null)
      throw ApiException.invalid("При autoSelect=false необходимо указать roomId");
    lock.acquire();
    if (lock.cancelled(input.bookingId())) throw ApiException.conflict("Бронирование отменено");
    var fingerprint = fingerprint(input);
    var prior = reservations.findById(input.operationId());
    if (prior.isPresent()) {
      var r = prior.get();
      if (!r.getBookingId().equals(input.bookingId())
          || !Objects.equals(fingerprint, r.getFingerprint()))
        throw ApiException.conflict("Идентификатор операции уже использован");
      if (!r.getState().equals("ACTIVE")) throw ApiException.conflict("Резервирование уже снято");
      return view(r);
    }
    if (input.replacesOperationId() != null) {
      var old =
          reservations.findById(input.replacesOperationId()).orElseThrow(ApiException::missing);
      if (!old.getBookingId().equals(input.bookingId()) || !old.getState().equals("ACTIVE"))
        throw ApiException.conflict("Предыдущее резервирование неактивно");
    }
    var candidates =
        rooms.candidates(
            input.autoSelect() ? input.hotelId() : null,
            input.autoSelect() ? null : input.roomId(),
            dates.start(),
            dates.end(),
            input.replacesOperationId());
    if (candidates.isEmpty())
      throw ApiException.conflict("На выбранные даты нет свободных номеров");
    var ranked =
        input.autoSelect() ? allocation.rank(candidates, input.bookingId()).getFirst() : null;
    var selected = ranked == null ? candidates.getFirst() : ranked.room();
    var reservation = new Reservation();
    reservation.setId(input.operationId());
    reservation.setBookingId(input.bookingId());
    reservation.setRoomId(selected.getId());
    reservation.setStartDate(dates.start());
    reservation.setEndDate(dates.end());
    reservation.setState("ACTIVE");
    reservation.setFingerprint(fingerprint);
    reservation.setCreatedAt(clock.instant());
    reservations.save(reservation);
    selected.setTimesBooked(selected.getTimesBooked() + 1);
    var event =
        LoggerFactory.getLogger(getClass())
            .atInfo()
            .addKeyValue("bookingId", input.bookingId())
            .addKeyValue("operationId", input.operationId())
            .addKeyValue("roomId", selected.getId())
            .addKeyValue("autoSelect", input.autoSelect());
    if (ranked != null) {
      event
          .addKeyValue("allocationRank", ranked.allocationRank())
          .addKeyValue("loadNights", ranked.load().loadNights())
          .addKeyValue("recentBookings", ranked.load().recentBookings());
    }
    event.log("reservation.accepted");
    return view(reservation);
  }

  /**
   * Метод для установки отметки о том, что занятие номера подтверждено. После этого ночи проживания
   * учитываются в подтверждённой загрузке для отчёта.
   */
  @Transactional
  @Override
  public void commit(UUID bookingId, UUID operationId) {
    lock.acquire();
    var r = reservations.findById(operationId).orElseThrow(ApiException::missing);
    if (!r.getBookingId().equals(bookingId))
      throw ApiException.conflict("Операция относится к другому бронированию");
    if (r.getState().equals("ACTIVE") && !lock.cancelled(bookingId)) r.setConfirmed(true);
  }

  /**
   * Метод для освобождения всех интервалов, относящихся к одной брони. Также сохраняется отметка
   * отмены, чтобы позднее подтверждение не заняло эти даты снова.
   */
  @Transactional
  @Override
  public void releaseBooking(UUID bookingId, Long expectedRoom) {
    lock.acquire();
    var all = reservations.findByBookingId(bookingId);
    var knownRooms = all.stream().map(Reservation::getRoomId).filter(Objects::nonNull).toList();
    if (expectedRoom != null && !knownRooms.isEmpty() && !knownRooms.contains(expectedRoom))
      throw ApiException.conflict("Номер не соответствует бронированию");
    lock.cancel(bookingId);
    all.forEach(this::release);
    LoggerFactory.getLogger(getClass())
        .atInfo()
        .addKeyValue("bookingId", bookingId)
        .log("reservation.released");
  }

  /**
   * Метод для освобождения интервала только одной операции, например отклонённого изменения брони.
   * Другие варианты той же брони сохраняются, а повторное освобождение не уменьшает счётчик ещё
   * раз.
   */
  @Transactional
  @Override
  public void releaseOperation(UUID operationId, UUID bookingId) {
    lock.acquire();
    var reservation =
        reservations
            .findById(operationId)
            .orElseGet(
                () -> {
                  var r = new Reservation();
                  r.setId(operationId);
                  r.setBookingId(bookingId);
                  r.setCreatedAt(clock.instant());
                  r.setState("RELEASED");
                  return reservations.save(r);
                });
    if (!reservation.getBookingId().equals(bookingId))
      throw ApiException.conflict("Операция относится к другому бронированию");
    release(reservation);
  }

  /**
   * Снимаем удержание только в том случае, если оно ещё активно, и уменьшаем счётчик номера.
   * Благодаря проверке состояния повторное освобождение ничего не меняет.
   */
  private void release(Reservation reservation) {
    if (!"ACTIVE".equals(reservation.getState())) return;
    var room = rooms.findById(reservation.getRoomId()).orElseThrow(ApiException::missing);
    room.setTimesBooked(room.getTimesBooked() - 1);
    reservation.setState("RELEASED");
  }

  /**
   * Метод для получения объекта ответа из сохранённого интервала с выбранным номером и датами
   * проживания.
   */
  private HotelApi.Reserved view(Reservation r) {
    return new HotelApi.Reserved(
        r.getBookingId(), r.getId(), r.getRoomId(), r.getStartDate(), r.getEndDate());
  }

  /**
   * Вычисляем контрольное значение значимых данных запроса, чтобы по нему отличить обычный повтор
   * от другой операции с прежним идентификатором.
   */
  private String fingerprint(HotelApi.Reserve r) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(
                      (r.bookingId()
                              + "|"
                              + (r.autoSelect() ? null : r.roomId())
                              + "|"
                              + r.hotelId()
                              + "|"
                              + r.autoSelect()
                              + "|"
                              + r.startDate()
                              + "|"
                              + r.endDate()
                              + "|"
                              + r.replacesOperationId())
                          .getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
