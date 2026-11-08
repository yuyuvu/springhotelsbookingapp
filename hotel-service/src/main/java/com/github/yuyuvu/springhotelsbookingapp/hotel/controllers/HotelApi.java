package com.github.yuyuvu.springhotelsbookingapp.hotel.controllers;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * В данном классе собраны объекты с данными, которые передаются при обращении к сервису отелей. Они
 * используются в контроллерах для получения запросов и формирования ответов.
 */
public final class HotelApi {
  /**
   * Закрываем конструктор, так как класс нужен для группировки объектов запросов и ответов и сам по
   * себе не используется как объект.
   */
  private HotelApi() {}

  /** Объект с названием и адресом отеля, которые нужны для его создания или изменения. */
  public record HotelInput(
      @NotBlank @Size(max = 160) String name, @NotBlank @Size(max = 500) String address) {}

  /**
   * Объект с данными для создания или изменения номера в отеле, в том числе с признаком его
   * доступности для проживания.
   */
  public record RoomInput(
      @NotNull @Positive Long hotelId,
      @NotBlank @Size(max = 30) String number,
      @NotNull Boolean available) {}

  /**
   * Объект для передачи данных об отеле в ответе API, чтобы за пределы сервиса не передавалась сама
   * сущность из базы данных.
   */
  public record HotelView(Long id, String name, String address, boolean archived, long version) {}

  /**
   * Объект для передачи данных о номере, его доступности и количестве бронирований в ответе API.
   */
  public record RoomView(
      Long id,
      Long hotelId,
      String number,
      boolean available,
      long timesBooked,
      boolean archived,
      long version) {}

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

  /**
   * Объект с данными для занятия номера на определённые даты. Идентификаторы брони и операции нужны
   * в том числе для распознавания повторно пришедших запросов.
   */
  public record Reserve(
      @NotNull UUID bookingId,
      @NotNull UUID operationId,
      Long roomId,
      Long hotelId,
      boolean autoSelect,
      @NotNull LocalDate startDate,
      @NotNull LocalDate endDate,
      UUID replacesOperationId) {}

  /**
   * Объект с результатом занятия номера, в котором передаются его идентификатор, даты проживания и
   * сведения об операции.
   */
  public record Reserved(
      UUID bookingId, UUID operationId, Long roomId, LocalDate startDate, LocalDate endDate) {}

  /**
   * Объект с идентификатором брони, который передаётся при подтверждении или освобождении занятого
   * интервала.
   */
  public record Release(@NotNull UUID bookingId) {}

  /**
   * Объект для передачи статистики отдельного номера, в том числе занятых ночей, временных
   * удержаний и процента загрузки за выбранные 30 дней.
   */
  public record RoomStatistics(
      long roomId,
      long timesBooked,
      long occupiedNights,
      long reservedNights,
      long periodNights,
      double occupancyPercent,
      LocalDate startDate,
      LocalDate endDate) {}

  /**
   * Объект с общей статистикой отеля и показателями каждого его номера за период в 30 календарных
   * дней.
   */
  public record HotelStatistics(
      long hotelId,
      long roomCount,
      long idleRoomCount,
      long occupiedNights,
      double occupancyPercent,
      java.util.List<RoomStatistics> rooms,
      LocalDate startDate,
      LocalDate endDate,
      long periodNights) {}
}
