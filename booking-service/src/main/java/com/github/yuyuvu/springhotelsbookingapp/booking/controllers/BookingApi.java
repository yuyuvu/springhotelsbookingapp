package com.github.yuyuvu.springhotelsbookingapp.booking.controllers;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * В данном классе собраны объекты с данными запросов и ответов сервиса бронирований: пользователи,
 * токены и операции с бронями.
 */
public final class BookingApi {
  /**
   * Закрываем конструктор, так как класс только объединяет описания данных API и создание его
   * объекта не требуется.
   */
  private BookingApi() {}

  /** Объект с именем и паролем для самостоятельной регистрации пользователя с обычной ролью. */
  public record Register(
      @NotBlank @Pattern(regexp = "[A-Za-z0-9_.-]{3,80}") String username,
      @NotBlank @Size(min = 10, max = 72) String password) {}

  /** Объект с именем пользователя и паролем, которые передаются для входа и получения токена. */
  public record Login(
      @Schema(example = "user1") @NotBlank String username,
      @Schema(example = "user1password") @NotBlank String password) {}

  /** Объект с данными учётной записи для её создания или изменения администратором. */
  public record UserInput(
      Long id,
      @NotBlank @Pattern(regexp = "[A-Za-z0-9_.-]{3,80}") String username,
      @Size(min = 10, max = 72) String password,
      @NotNull @Pattern(regexp = "USER|ADMIN") String role) {}

  /** Объект с данными пользователя для ответа API, в который не передаются пароль и его хеш. */
  public record UserView(
      Long id, String username, String role, boolean active, Instant createdAt) {}

  /** Объект с выданным токеном доступа, сроком его действия и данными вошедшего пользователя. */
  public record Token(String accessToken, String tokenType, long expiresIn, UserView user) {}

  /**
   * Объект с данными для создания или изменения брони. Позволяет указать конкретный номер либо
   * запросить автоматический подбор.
   */
  public record Create(
      @Schema(
              description =
                  "Идентификатор нового действия в формате UUID, который нужно сохранить при повторной отправке того же запроса.")
          UUID requestId,
      @Schema(
              description =
                  "Внутренний идентификатор номера из API, который игнорируется при autoSelect=true. Номер на двери передаётся в отдельном поле данных номера.",
              example = "3")
          Long roomId,
      @Schema(
              description =
                  "Идентификатор отеля, если автоматический подбор нужно ограничить его номерами",
              example = "1")
          Long hotelId,
      @Schema(
              description =
                  "Признак автоматического подбора свободного номера по наименьшей нагрузке и рангу allocationRank",
              example = "true")
          boolean autoSelect,
      @NotNull LocalDate startDate,
      @NotNull LocalDate endDate) {}

  /**
   * Объект для передачи текущих данных брони пользователю, в том числе её состояние и идентификатор
   * ещё не завершённого изменения.
   */
  public record View(
      UUID id,
      Long userId,
      Long roomId,
      LocalDate startDate,
      LocalDate endDate,
      String status,
      Instant createdAt,
      Instant updatedAt,
      UUID pendingOperation) {}

  /**
   * Объект с результатом отдельного запроса к бронированию, который используется в том числе при
   * повторной отправке того же запроса.
   */
  public record Outcome(
      int httpStatus, View booking, String code, String message, boolean compensationPending) {}

  /**
   * Объект с данными для занятия номера на определённые даты. Идентификаторы брони и операции нужны
   * в том числе для распознавания повторно пришедших запросов.
   */
  public record Reserve(
      UUID bookingId,
      UUID operationId,
      Long roomId,
      Long hotelId,
      boolean autoSelect,
      LocalDate startDate,
      LocalDate endDate,
      UUID replacesOperationId) {}

  /**
   * Объект с результатом занятия номера, в котором передаются его идентификатор, даты проживания и
   * сведения об операции.
   */
  public record Reserved(
      UUID bookingId, UUID operationId, Long roomId, LocalDate startDate, LocalDate endDate) {}

  /**
   * Объект с данными свободного номера и рассчитанной для него нагрузкой. Также содержит ранг, по
   * которому этот номер сравнивается с остальными при автоматическом подборе.
   */
  public record RoomRecommendation(
      Long id,
      Long hotelId,
      String number,
      boolean available,
      long timesBooked,
      boolean archived,
      long version,
      @Schema(
              description =
                  "Ранг номера среди свободных вариантов, где 1 соответствует первому выбору при автоподборе. Сам список отсортирован по timesBooked и затем id.")
          int allocationRank,
      AllocationLoad allocationLoad) {}

  /**
   * Объект с показателями нагрузки номера, которые используются для автоматического подбора.
   * Содержит занятые и удержанные ночи за 30 дней, ночи будущих заездов и количество недавних
   * бронирований.
   */
  @Schema(
      description =
          "Показатели нагрузки, по которым сравниваются номера при автоматическом подборе. Начало периода включается в расчёт, конец не включается.")
  public record AllocationLoad(
      @Schema(
              description =
                  "Сумма подтверждённых и удержанных ночей за 30 дней и всех ночей будущих заездов, попавших в период отбора.")
          long loadNights,
      long occupiedNights30,
      long heldNights30,
      @Schema(
              description =
                  "Количество всех ночей активных броней, у которых заезд назначен начиная с завтра и до 120-го дня включительно. Дата создания не ограничивается, а выезд может быть позднее этого периода.")
          long upcomingArrivalNights,
      @Schema(
              description =
                  "Количество разных бронирований с активным вариантом, принятым за последние 30 календарных дней. Используется для сравнения при одинаковом количестве ночей нагрузки.")
          long recentBookings,
      LocalDate historyStartDate,
      LocalDate historyEndDate,
      LocalDate arrivalsStartDate,
      LocalDate arrivalsEndDate,
      Instant calculatedAt) {}
}
