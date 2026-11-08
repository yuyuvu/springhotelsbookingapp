package com.github.yuyuvu.springhotelsbookingapp.booking.entities;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Объект Booking представляет собой текущую бронь пользователя с номером, датами и состоянием.
 * Также хранит ссылки на действующую операцию и ещё не завершённое изменение.
 */
@Entity
@Table(name = "bookings")
@Getter
@Setter
@NoArgsConstructor
public class Booking {
  @Id private UUID id;
  private Long userId;
  private Long roomId;
  private Long hotelId;
  private LocalDate startDate;
  private LocalDate endDate;
  private String status;
  private Instant createdAt;
  private Instant updatedAt;
  private UUID currentOperation;
  private UUID pendingOperation;
  @Version private long version;
}
