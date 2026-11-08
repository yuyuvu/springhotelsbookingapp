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
 * Объект с данными отдельного запроса создания или изменения брони и результатом его обработки.
 * Следующее действие сохраняется здесь же, чтобы его можно было продолжить после временной ошибки.
 */
@Entity
@Table(name = "booking_operations")
@Getter
@Setter
@NoArgsConstructor
public class BookingOperation {
  @Id private UUID id;
  private UUID bookingId;
  private Long userId;
  private UUID requestId;
  private String fingerprint;
  private String kind;
  private String state;
  private String action;
  private Long roomId;
  private Long hotelId;
  private boolean autoSelect;
  private LocalDate startDate;
  private LocalDate endDate;
  private UUID previousOperation;
  private Instant createdAt;
  private Instant deadline;
  private Instant nextAttempt;
  private Instant lockUntil;
  private int attempts;
  private Integer errorStatus;
  private String errorCode;
  private String errorMessage;
  private String traceId;
  @Version private long version;
}
