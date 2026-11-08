package com.github.yuyuvu.springhotelsbookingapp.hotel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.yuyuvu.springhotelsbookingapp.hotel.controllers.HotelApi;
import com.github.yuyuvu.springhotelsbookingapp.hotel.entities.Room;
import com.github.yuyuvu.springhotelsbookingapp.hotel.repositories.RoomRepository;
import com.github.yuyuvu.springhotelsbookingapp.hotel.services.HotelService;
import com.github.yuyuvu.springhotelsbookingapp.hotel.services.InventoryService;
import com.github.yuyuvu.springhotelsbookingapp.hotel.support.ApiException;
import com.github.yuyuvu.springhotelsbookingapp.hotel.support.DateRange;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Класс для тестов сервиса отелей на настоящей базе H2. Проверяются работа календаря,
 * автоматический подбор, статистика, проверки доступа.
 */
@SpringBootTest(
    properties = {
      "eureka.client.enabled=false",
      "spring.cloud.discovery.enabled=false",
      "spring.flyway.locations=classpath:db/migration",
      "spring.datasource.url=jdbc:h2:mem:hoteltests;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
    })
@AutoConfigureMockMvc
class InventoryIntegrationTest {
  /**
   * Создаём два номера с тысячей прошлых броней у каждого и добавляем десять новых номеров.
   * Проверяем, что новые назначения используют все номера без ожидания выравнивания старых
   * счётчиков.
   */
  @Test
  void tenNewRoomsDoNotMakeRoomsWithAThousandOldBookingsWaitForCounterCatchup() {
    var oldOther = service.createRoom(new HotelApi.RoomInput(hotel, "old-other", true));
    seedHistory(room, 1000);
    seedHistory(oldOther.id(), 1000);
    var expected = new java.util.HashSet<Long>();
    expected.add(room);
    expected.add(oldOther.id());
    for (int i = 0; i < 10; i++)
      expected.add(service.createRoom(new HotelApi.RoomInput(hotel, "new-" + i, true)).id());
    var chosen = new java.util.HashSet<Long>();
    for (int i = 0; i < 12; i++) {
      var id = UUID.randomUUID();
      chosen.add(
          inventory
              .reserve(
                  new HotelApi.Reserve(
                      id,
                      id,
                      null,
                      hotel,
                      true,
                      start.plusDays(i * 2L),
                      start.plusDays(i * 2L + 1),
                      null))
              .roomId());
    }
    assertThat(chosen).containsExactlyInAnyOrderElementsOf(expected);
    assertThat(rooms.findById(room).orElseThrow().getTimesBooked()).isEqualTo(1001);
    assertThat(rooms.findById(oldOther.id()).orElseThrow().getTimesBooked()).isEqualTo(1001);
  }

  /**
   * Проверяем, что номер с меньшим количеством ночей получает преимущество даже при большем числе
   * броней. Отдельно проверяем порядок списка рекомендаций и отчётный процент загрузки.
   */
  @Test
  void nightLoadSelectsRoomWithMoreBookingsButFewerNightsAndPreservesPublicOrder()
      throws Exception {
    var other = service.createRoom(new HotelApi.RoomInput(hotel, "short-stays", true));
    reserve(room, start, 5);
    reserve(other.id(), start, 1);
    reserve(other.id(), start.plusDays(2), 1);
    var recommendations = service.recommendations(hotel, start.plusDays(40), start.plusDays(41));
    assertThat(recommendations)
        .extracting(HotelApi.RoomRecommendation::id)
        .containsExactly(room, other.id());
    assertThat(recommendations.get(0).allocationRank()).isEqualTo(2);
    assertThat(recommendations.get(0).allocationLoad().loadNights()).isEqualTo(5);
    assertThat(recommendations.get(1).allocationRank()).isEqualTo(1);
    assertThat(recommendations.get(1).allocationLoad().loadNights()).isEqualTo(2);
    assertThat(service.roomStatistics(room, null, null).occupiedNights()).isZero();
    mvc.perform(
            get("/api/rooms/recommend")
                .param("hotelId", Long.toString(hotel))
                .param("startDate", start.plusDays(40).toString())
                .param("endDate", start.plusDays(41).toString())
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].allocationRank").value(2))
        .andExpect(jsonPath("$[1].allocationLoad.upcomingArrivalNights").value(2));
    var id = UUID.randomUUID();
    assertThat(
            inventory
                .reserve(
                    new HotelApi.Reserve(
                        id, id, null, hotel, true, start.plusDays(40), start.plusDays(41), null))
                .roomId())
        .isEqualTo(other.id());
  }

  /**
   * Проверяем подсчёт будущей нагрузки по заездам в ближайшие 120 дней независимо от даты создания
   * брони. Также проверяем, что поздний выезд учитывается целиком, а заезды за границей периода не
   * добавляют ночи.
   */
  @Test
  void arrivalDatesBoundFutureLoadInsteadOfCreationDatesOrDateOfDeparture() {
    var today = LocalDate.now(clock);
    var other = service.createRoom(new HotelApi.RoomInput(hotel, "distant", true));
    reserve(room, today, 1);
    reserve(room, today.plusDays(1), 2);
    var lastDay = reserve(room, today.plusDays(120), 5);
    jdbc.update(
        "UPDATE reservations SET created_at=? WHERE id=?",
        java.sql.Timestamp.from(clock.instant().minusSeconds(90L * 86400)),
        lastDay);
    reserve(other.id(), today.plusDays(121), 1);
    reserve(other.id(), today.plusYears(2), 4);
    var result = service.recommendations(hotel, today.plusDays(500), today.plusDays(501));
    var first =
        result.stream().filter(r -> r.id() == room).findFirst().orElseThrow().allocationLoad();
    var second =
        result.stream()
            .filter(r -> r.id().equals(other.id()))
            .findFirst()
            .orElseThrow()
            .allocationLoad();
    assertThat(first.heldNights30()).isEqualTo(1);
    assertThat(first.upcomingArrivalNights()).isEqualTo(7);
    assertThat(first.loadNights()).isEqualTo(8);
    assertThat(first.recentBookings()).isEqualTo(2);
    assertThat(second.upcomingArrivalNights()).isZero();
    assertThat(second.loadNights()).isZero();
    assertThat(second.recentBookings()).isEqualTo(2);
    assertThat(first.arrivalsStartDate()).isEqualTo(today.plusDays(1));
    assertThat(first.arrivalsEndDate()).isEqualTo(today.plusDays(121));
  }

  /**
   * Создаём временное удержание и проверяем его влияние на ранг номера. Повторяем запрос и
   * освобождение, чтобы убедиться в отсутствии двойного изменения нагрузки.
   */
  @Test
  void provisionalHoldsInfluenceRankAndRepeatedCompensationRemovesTheirLoadOnce() {
    var other = service.createRoom(new HotelApi.RoomInput(hotel, "other", true));
    var id = UUID.randomUUID();
    var input = command(id, id, room, start, start.plusDays(3));
    inventory.reserve(input);
    inventory.reserve(input);
    var ranked = service.recommendations(hotel, start.plusDays(40), start.plusDays(41));
    assertThat(
            ranked.stream()
                .filter(r -> r.id() == room)
                .findFirst()
                .orElseThrow()
                .allocationLoad()
                .upcomingArrivalNights())
        .isEqualTo(3);
    assertThat(
            ranked.stream()
                .filter(r -> r.id().equals(other.id()))
                .findFirst()
                .orElseThrow()
                .allocationRank())
        .isEqualTo(1);
    inventory.releaseBooking(id, room);
    inventory.releaseBooking(id, room);
    var released =
        service.recommendations(hotel, start.plusDays(40), start.plusDays(41)).getFirst();
    assertThat(released.id()).isEqualTo(room);
    assertThat(released.allocationRank()).isEqualTo(1);
    assertThat(released.allocationLoad().loadNights()).isZero();
    assertThat(released.allocationLoad().recentBookings()).isZero();
  }

  /**
   * Проверяем, что при изменении брони её собственная нагрузка не влияет на подбор. Также проверяем
   * объединение совпавших ночей и подсчёт разных вариантов одной брони без дублирования.
   */
  @Test
  void amendmentExcludesItsOwnLoadAndActiveRevisionsDoNotDuplicateNightsOrBookingCount() {
    var other = service.createRoom(new HotelApi.RoomInput(hotel, "other", true));
    var booking = UUID.randomUUID();
    var initial = UUID.randomUUID();
    inventory.reserve(command(booking, initial, room, start, start.plusDays(5)));
    inventory.commit(booking, initial);
    reserve(other.id(), start, 2);
    var amendment = UUID.randomUUID();
    var selected =
        inventory.reserve(
            new HotelApi.Reserve(
                booking,
                amendment,
                null,
                hotel,
                true,
                start.plusDays(1),
                start.plusDays(6),
                initial));
    assertThat(selected.roomId()).isEqualTo(room);
    var load =
        service.recommendations(hotel, start.plusDays(40), start.plusDays(41)).stream()
            .filter(r -> r.id() == room)
            .findFirst()
            .orElseThrow()
            .allocationLoad();
    assertThat(load.upcomingArrivalNights()).isEqualTo(6);
    assertThat(load.recentBookings()).isEqualTo(1);
    inventory.releaseOperation(initial, booking);
    assertThat(
            service.recommendations(hotel, start.plusDays(40), start.plusDays(41)).stream()
                .filter(r -> r.id() == room)
                .findFirst()
                .orElseThrow()
                .allocationLoad()
                .upcomingArrivalNights())
        .isEqualTo(5);
  }

  /**
   * Проверяем порядок выбора при одинаковом количестве ночей, когда преимущество сначала должно
   * определяться количеством недавних броней и только потом общим счётчиком.
   */
  @Test
  void equalNightLoadsPreferFewerRecentBookingsBeforeLifetimeCounter() {
    seedHistory(room, 1000);
    var other = service.createRoom(new HotelApi.RoomInput(hotel, "other", true));
    reserve(room, start, 2);
    reserve(other.id(), start, 1);
    reserve(other.id(), start.plusDays(2), 1);
    var result = service.recommendations(hotel, start.plusDays(40), start.plusDays(41));
    assertThat(result)
        .extracting(HotelApi.RoomRecommendation::id)
        .containsExactly(other.id(), room);
    assertThat(result.getFirst().allocationRank()).isEqualTo(2);
    assertThat(result.get(1).allocationRank()).isEqualTo(1);
  }

  /**
   * Вспомогательный метод для занятия дат в тестовом номере с новыми идентификаторами брони и
   * операции.
   */
  private UUID reserve(long roomId, LocalDate from, int nights) {
    var id = UUID.randomUUID();
    inventory.reserve(command(id, id, roomId, from, from.plusDays(nights)));
    return id;
  }

  /**
   * Подготавливаем большую историю настоящих давних бронирований и согласованный счётчик номера,
   * чтобы проверить влияние накопленных данных на выбор.
   */
  private void seedHistory(long roomId, int count) {
    var data = new ArrayList<Object[]>();
    var today = LocalDate.now(clock);
    var created = java.sql.Timestamp.from(clock.instant().minusSeconds(3000L * 86400));
    for (int i = 0; i < count; i++) {
      var id = UUID.randomUUID();
      var from = today.minusDays(2000 - i);
      data.add(new Object[] {id, id, roomId, from, from.plusDays(1), created});
    }
    jdbc.batchUpdate(
        "INSERT INTO reservations(id,booking_id,room_id,start_date,end_date,state,confirmed,created_at) VALUES(?,?,?,?,?,'ACTIVE',TRUE,?)",
        data);
    jdbc.update("UPDATE rooms SET times_booked=times_booked+? WHERE id=?", count, roomId);
  }

  /**
   * Проверяем, что без указания дат статистика возвращается за 30 календарных дней, а попытка
   * запросить более короткий период отклоняется.
   */
  @Test
  void statisticsDefaultToThirtyCalendarDaysAndRejectShorterPeriods() throws Exception {
    mvc.perform(
            get("/api/rooms/" + room + "/statistics")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.periodNights").value(30))
        .andExpect(jsonPath("$.startDate").value(LocalDate.now(clock).minusDays(29).toString()))
        .andExpect(jsonPath("$.endDate").value(LocalDate.now(clock).plusDays(1).toString()));
    assertThatThrownBy(() -> service.roomStatistics(room, start, start.plusDays(10)))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("30 календарных дней");
  }

  /**
   * Переносим бронь в другой номер и проверяем, что последующая отмена через путь конкретного
   * номера освобождает все её варианты.
   */
  @Test
  void roomSpecificReleaseWorksAfterMovingBookingToAnotherRoom() {
    var booking = UUID.randomUUID();
    var initial = UUID.randomUUID();
    inventory.reserve(command(booking, initial, room, start, start.plusDays(2)));
    var destination = service.createRoom(new HotelApi.RoomInput(hotel, "202", true));
    var amendment = UUID.randomUUID();
    inventory.reserve(
        new HotelApi.Reserve(
            booking, amendment, destination.id(), hotel, false, start, start.plusDays(2), initial));
    inventory.releaseOperation(initial, booking);
    inventory.releaseBooking(booking, destination.id());
    inventory.releaseBooking(booking, destination.id());
    assertThat(rooms.findById(room).orElseThrow().getTimesBooked()).isZero();
    assertThat(rooms.findById(destination.id()).orElseThrow().getTimesBooked()).isZero();
  }

  @Autowired tools.jackson.databind.ObjectMapper json;

  /**
   * Проверяем создание, получение, изменение и архивирование отелей и номеров через HTTP от имени
   * администратора. Также проверяем фильтрацию возвращаемых списков.
   */
  @Test
  void administrativeCrudAndFilteredListsWorkThroughHttp() throws Exception {
    var admin = jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    var reader = jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"));
    var created =
        mvc.perform(
                post("/api/hotels")
                    .with(admin)
                    .contentType("application/json")
                    .content(
                        json.writeValueAsString(
                            Map.of("name", "New hotel", "address", "New address"))))
            .andExpect(status().isCreated())
            .andReturn();
    long hotelId = json.readTree(created.getResponse().getContentAsString()).get("id").asLong();
    mvc.perform(
            patch("/api/hotels/" + hotelId)
                .with(admin)
                .contentType("application/json")
                .content(
                    json.writeValueAsString(Map.of("name", "Renamed", "address", "New address"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Renamed"));
    mvc.perform(get("/api/hotels?search=Renamed&size=1").with(reader))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1));
    var roomCreated =
        mvc.perform(
                post("/api/rooms")
                    .with(admin)
                    .contentType("application/json")
                    .content(
                        json.writeValueAsString(
                            Map.of("hotelId", hotelId, "number", "301", "available", true))))
            .andExpect(status().isCreated())
            .andReturn();
    long roomId = json.readTree(roomCreated.getResponse().getContentAsString()).get("id").asLong();
    mvc.perform(
            patch("/api/rooms/" + roomId)
                .with(admin)
                .contentType("application/json")
                .content(
                    json.writeValueAsString(
                        Map.of("hotelId", hotelId, "number", "302", "available", false))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.available").value(false));
    mvc.perform(get("/api/admin/rooms?hotelId=" + hotelId + "&available=false").with(admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1));
    mvc.perform(delete("/api/rooms/" + roomId).with(admin)).andExpect(status().isNoContent());
    mvc.perform(delete("/api/hotels/" + hotelId).with(admin)).andExpect(status().isNoContent());
    mvc.perform(get("/api/hotels/" + hotelId).with(reader)).andExpect(status().isNotFound());
  }

  /**
   * Проверяем доступ к подтверждению и освобождению для служебного токена и отказ при использовании
   * обычного пользовательского токена.
   */
  @Test
  void authenticatedServiceCanConfirmAndReleaseButUserCannot() throws Exception {
    var id = UUID.randomUUID();
    var internal =
        jwt()
            .jwt(j -> j.claim("token_use", "service"))
            .authorities(new SimpleGrantedAuthority("SCOPE_inventory.write"));
    var payload = new HotelApi.Reserve(id, id, room, hotel, false, start, start.plusDays(2), null);
    mvc.perform(
            post("/api/rooms/" + room + "/confirm-availability")
                .with(internal)
                .contentType("application/json")
                .content(json.writeValueAsString(payload)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.roomId").value(room));
    for (int i = 0; i < 2; i++)
      mvc.perform(
              post("/api/rooms/" + room + "/release")
                  .with(internal)
                  .contentType("application/json")
                  .content(json.writeValueAsString(new HotelApi.Release(id))))
          .andExpect(status().isNoContent());
    assertThat(rooms.findById(room).orElseThrow().getTimesBooked()).isZero();
  }

  @Autowired InventoryService inventory;
  @Autowired HotelService service;
  @Autowired RoomRepository rooms;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @Autowired Clock clock;
  long hotel;
  long room;
  LocalDate start;

  /**
   * Подготавливаем чистую базу и создаём отель с одним номером перед каждым тестом, чтобы
   * результаты не зависели от предыдущих проверок.
   */
  @BeforeEach
  void setup() {
    jdbc.update("DELETE FROM reservations");
    jdbc.update("DELETE FROM cancelled_bookings");
    jdbc.update("DELETE FROM rooms");
    jdbc.update("DELETE FROM hotels");
    hotel = service.createHotel(new HotelApi.HotelInput("Test", "Address")).id();
    room = service.createRoom(new HotelApi.RoomInput(hotel, "101", true)).id();
    start = LocalDate.now(clock).plusDays(5);
  }

  /** Вспомогательный метод для создания внутреннего запроса занятия номера на нужные даты. */
  HotelApi.Reserve command(
      UUID booking, UUID operation, long roomId, LocalDate from, LocalDate to) {
    return new HotelApi.Reserve(booking, operation, roomId, hotel, false, from, to, null);
  }

  /**
   * Повторно подтверждаем и освобождаем один интервал, проверяя, что количество записей и счётчик
   * номера изменяются только один раз.
   */
  @Test
  void repeatedConfirmationAndReleaseHaveOneEffect() {
    var id = UUID.randomUUID();
    var command = command(id, id, room, start, start.plusDays(2));
    assertThat(inventory.reserve(command)).isEqualTo(inventory.reserve(command));
    assertThat(rooms.findById(room).orElseThrow().getTimesBooked()).isEqualTo(1);
    inventory.releaseBooking(id, null);
    inventory.releaseBooking(id, null);
    assertThat(rooms.findById(room).orElseThrow().getTimesBooked()).isZero();
    assertThatThrownBy(() -> inventory.reserve(command)).isInstanceOf(ApiException.class);
  }

  /**
   * Сначала отменяем бронь и затем передаём её запоздавшее подтверждение. Проверяем, что отменённые
   * даты не занимаются снова.
   */
  @Test
  void releaseBeforeDelayedConfirmationPreventsResurrection() {
    var id = UUID.randomUUID();
    inventory.releaseBooking(id, null);
    assertThatThrownBy(() -> inventory.reserve(command(id, id, room, start, start.plusDays(2))))
        .isInstanceOf(ApiException.class);
    assertThat(service.available(hotel, start, start.plusDays(2), true)).hasSize(1);
  }

  /**
   * Проверяем отсутствие конфликта при выезде и новом заезде в один день, а также отказ при
   * реальном пересечении занятых дат.
   */
  @Test
  void adjacentDatesCanBeBookedButOverlapsCannot() {
    var first = UUID.randomUUID();
    inventory.reserve(command(first, first, room, start, start.plusDays(2)));
    var adjacent = UUID.randomUUID();
    inventory.reserve(command(adjacent, adjacent, room, start.plusDays(2), start.plusDays(3)));
    var conflict = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                inventory.reserve(
                    command(conflict, conflict, room, start.plusDays(1), start.plusDays(3))))
        .isInstanceOf(ApiException.class);
  }

  /**
   * Одновременно отправляем несколько запросов на один номер с пересекающимися датами и проверяем,
   * что успешным оказался только один из них.
   */
  @Test
  void concurrentOverlappingRequestsHaveExactlyOneWinner() throws Exception {
    try (var executor = Executors.newFixedThreadPool(12)) {
      var ready = new CountDownLatch(12);
      var go = new CountDownLatch(1);
      var work = new ArrayList<Future<Boolean>>();
      for (int i = 0; i < 12; i++)
        work.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  go.await();
                  var id = UUID.randomUUID();
                  try {
                    inventory.reserve(command(id, id, room, start, start.plusDays(2)));
                    return true;
                  } catch (ApiException e) {
                    assertThat(e.status().value()).isEqualTo(409);
                    return false;
                  }
                }));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      go.countDown();
      int winners = 0;
      for (var future : work) if (future.get(20, TimeUnit.SECONDS)) winners++;
      assertThat(winners).isEqualTo(1);
      assertThat(rooms.findById(room).orElseThrow().getTimesBooked()).isEqualTo(1);
    }
  }

  /**
   * Проверяем распределение номеров при одновременных запросах автоподбора на разные периоды, чтобы
   * назначения не сосредоточились на одном номере.
   */
  @Test
  void parallelAutoSelectionBalancesDifferentDatePeriods() throws Exception {
    for (int i = 2; i <= 4; i++) service.createRoom(new HotelApi.RoomInput(hotel, "10" + i, true));
    try (var executor = Executors.newFixedThreadPool(8)) {
      var tasks = new ArrayList<Callable<Long>>();
      for (int i = 0; i < 32; i++) {
        int offset = i;
        tasks.add(
            () -> {
              var id = UUID.randomUUID();
              return inventory
                  .reserve(
                      new HotelApi.Reserve(
                          id,
                          id,
                          999L,
                          hotel,
                          true,
                          start.plusDays(offset * 2L),
                          start.plusDays(offset * 2L + 1),
                          null))
                  .roomId();
            });
      }
      for (var future : executor.invokeAll(tasks)) assertThat(future.get()).isNotNull();
    }
    assertThat(rooms.findAll()).extracting(Room::getTimesBooked).containsOnly(8L);
    var recommended = service.available(hotel, start.plusDays(100), start.plusDays(101), true);
    assertThat(recommended).extracting(HotelApi.RoomView::id).isSorted();
  }

  /**
   * Проверяем отдельно запрет проживания из-за недоступности номера и занятость на даты, чтобы одно
   * условие не подменяло другое.
   */
  @Test
  void operationalAvailabilityAndCalendarAreIndependent() {
    var r = service.createRoom(new HotelApi.RoomInput(hotel, "repair", false));
    assertThat(service.available(hotel, start, start.plusDays(1), false))
        .extracting(HotelApi.RoomView::id)
        .doesNotContain(r.id());
    var id = UUID.randomUUID();
    inventory.reserve(command(id, id, room, start, start.plusDays(1)));
    assertThat(service.room(room).available()).isTrue();
    assertThat(service.available(hotel, start, start.plusDays(1), false)).isEmpty();
  }

  /**
   * Создаём проживание, выходящее за границы отчёта, и проверяем, что считаются только ночи внутри
   * выбранного периода.
   */
  @Test
  void statisticsClipIntervalsToRequestedPeriod() {
    var id = UUID.randomUUID();
    inventory.reserve(command(id, id, room, start, start.plusDays(5)));
    inventory.commit(id, id);
    var stats = service.roomStatistics(room, start.plusDays(2), start.plusDays(32));
    assertThat(stats.occupiedNights()).isEqualTo(3);
    assertThat(stats.occupancyPercent()).isEqualTo(10);
    inventory.releaseBooking(id, null);
    assertThat(service.roomStatistics(room, start, start.plusDays(30)).occupiedNights()).isZero();
  }

  /**
   * Проверяем ограничения для разных ролей, обработку некорректных данных и запрет доступа
   * пользователя к служебным операциям.
   */
  @Test
  void rolesValidationAndInternalEndpointsAreProtected() throws Exception {
    mvc.perform(get("/api/hotels"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    mvc.perform(
            post("/api/hotels")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))
                .contentType("application/json")
                .content("{\"name\":\"Hotel\",\"address\":\"A\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/hotels")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType("application/json")
                .content("{\"name\":\"\",\"address\":\"A\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/internal/reservations/" + UUID.randomUUID() + "/release")
                .with(
                    jwt()
                        .jwt(j -> j.claim("token_use", "user"))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isForbidden());
  }

  /**
   * Проверяем ошибочные периоды с отсутствующими датами, выездом до заезда и превышением допустимой
   * длительности проживания.
   */
  @Test
  void invalidDateRangesAreRejected() {
    assertThatThrownBy(() -> service.available(hotel, start, start, false))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> new DateRange(start, start.plusDays(367)))
        .isInstanceOf(ApiException.class);
  }
}
