package com.github.yuyuvu.springhotelsbookingapp.booking.repositories;

import com.github.yuyuvu.springhotelsbookingapp.booking.entities.Booking;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/** Интерфейс для работы с бронированиями в базе данных. */
public interface BookingRepository
    extends JpaRepository<Booking, UUID>, JpaSpecificationExecutor<Booking> {
  /**
   * Получаем бронь с блокировкой до окончания транзакции, чтобы её состояние не изменялось
   * одновременно двумя обработчиками.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select b from Booking b where b.id=:id")
  Optional<Booking> lock(UUID id);

  /**
   * Метод для проверки текущих и будущих бронирований пользователя в ожидающем или подтверждённом
   * состоянии.
   */
  @Query(
      "select count(b)>0 from Booking b where b.userId=:userId and b.status in ('PENDING','CONFIRMED') and b.endDate>:today")
  boolean existsActive(Long userId, java.time.LocalDate today);
}
