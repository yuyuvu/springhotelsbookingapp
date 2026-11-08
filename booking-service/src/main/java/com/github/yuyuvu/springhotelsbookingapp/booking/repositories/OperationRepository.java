package com.github.yuyuvu.springhotelsbookingapp.booking.repositories;

import com.github.yuyuvu.springhotelsbookingapp.booking.entities.BookingOperation;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Интерфейс для работы с операциями бронирования, сохранёнными результатами запросов и заданиями
 * фонового обработчика.
 */
public interface OperationRepository extends JpaRepository<BookingOperation, UUID> {
  /**
   * Получаем идентификатор брони по операции, чтобы перед её изменением заблокировать саму запись
   * бронирования.
   */
  @Query("select o.bookingId from BookingOperation o where o.id=:id")
  Optional<UUID> bookingId(UUID id);

  /**
   * Метод для поиска уже сохранённого запроса пользователя по его ключу, который нужен для
   * распознавания повторной отправки.
   */
  Optional<BookingOperation> findByUserIdAndRequestId(Long userId, UUID requestId);

  /**
   * Получаем ограниченное количество незавершённых операций, для которых уже наступило время
   * следующей попытки и нет действующей блокировки обработчика.
   */
  @Query(
      "select o.id from BookingOperation o where o.action<>'NONE' and o.nextAttempt<=:now and (o.lockUntil is null or o.lockUntil<=:now) order by o.nextAttempt,o.id")
  List<UUID> due(Instant now, Pageable page);

  /**
   * Метод для получения всех операций одной брони, которые нужны при её полной отмене и
   * освобождении всех вариантов.
   */
  List<BookingOperation> findByBookingId(UUID id);
}
