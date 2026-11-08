package com.github.yuyuvu.springhotelsbookingapp.hotel.entities;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Объект Reservation представляет собой интервал, занятый в номере одной операцией бронирования.
 * Сохраняется также после освобождения, чтобы повторный или запоздавший запрос не занял эти даты
 * ещё раз.
 */
@Entity
@Table(name = "reservations")
@Getter
@Setter
@NoArgsConstructor
public class Reservation {
  @Id private UUID id;
  private UUID bookingId;
  private Long roomId;
  private LocalDate startDate;
  private LocalDate endDate;
  private String state;
  private boolean confirmed;
  private String fingerprint;
  private Instant createdAt;
}
