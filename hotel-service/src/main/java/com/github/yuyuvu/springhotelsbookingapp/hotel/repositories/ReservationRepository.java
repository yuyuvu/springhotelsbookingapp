package com.github.yuyuvu.springhotelsbookingapp.hotel.repositories;

import com.github.yuyuvu.springhotelsbookingapp.hotel.entities.Reservation;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Интерфейс для получения записей календаря номера, которые нужны при проверке свободных дат,
 * подсчёте нагрузки и освобождении брони.
 */
public interface ReservationRepository extends JpaRepository<Reservation, UUID> {
  /**
   * Метод для получения данных нагрузки сразу по всем номерам-кандидатам одним запросом. При
   * изменении брони её собственные интервалы исключаются из расчёта, чтобы прежнее назначение не
   * влияло на выбор.
   */
  @Query(
      "select r from Reservation r where r.state='ACTIVE' and r.roomId in :rooms and (:excludedBooking is null or r.bookingId<>:excludedBooking) and ((r.startDate<:historyEnd and r.endDate>:historyStart) or (r.startDate>=:historyEnd and r.startDate<:arrivalsEnd) or (r.createdAt>=:recentStart and r.createdAt<=:now))")
  List<Reservation> allocationData(
      Collection<Long> rooms,
      LocalDate historyStart,
      LocalDate historyEnd,
      LocalDate arrivalsEnd,
      Instant recentStart,
      Instant now,
      UUID excludedBooking);

  /**
   * Метод для получения активных интервалов номера, которые пересекаются с нужными датами
   * проживания.
   */
  @Query(
      "select r from Reservation r where r.roomId=:room and r.state='ACTIVE' and r.startDate<:end and r.endDate>:start")
  List<Reservation> overlaps(long room, LocalDate start, LocalDate end);

  /** Метод для получения всех записей календаря, которые были созданы для одной брони. */
  List<Reservation> findByBookingId(UUID bookingId);

  /** Метод для получения записей календаря отдельного номера с определённым состоянием. */
  List<Reservation> findByRoomIdAndState(Long roomId, String state);
}
