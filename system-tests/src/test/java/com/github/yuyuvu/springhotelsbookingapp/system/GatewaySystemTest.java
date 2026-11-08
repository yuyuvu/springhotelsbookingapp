package com.github.yuyuvu.springhotelsbookingapp.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;

/**
 * Класс для интеграционных тестов всей системы через шлюз с отдельными процессами и базами H2,
 * чтобы проверки не меняли рабочие демонстрационные данные.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(120)
class GatewaySystemTest {
  private static Applications applications;
  private static int port;

  /**
   * Подготавливаем отдельное тестовое окружение с Eureka, сервисами отелей и бронирований и шлюзом
   * для проверки запросов через общий вход.
   */
  @BeforeAll
  static void start() throws Exception {
    applications = new Applications("gateway");
    int registry = Applications.port();
    int hotel = Applications.port();
    int booking = Applications.port();
    port = Applications.port();
    String registryUrl =
        "--eureka.client.service-url.defaultZone=http://eurekalogin:eurekapassword@127.0.0.1:"
            + registry
            + "/eureka/";
    try {
      applications.start("discovery-server", registry, registryUrl);
      applications.start("hotel-service", hotel, registryUrl);
      applications.start("booking-service", booking, registryUrl);
      applications.start("api-gateway", port, registryUrl);
    } catch (Throwable failure) {
      applications.close();
      throw failure;
    }
  }

  /** Завершаем все процессы тестовых приложений после выполнения интеграционных тестов. */
  @AfterAll
  static void stop() throws Exception {
    if (applications != null) applications.close();
  }

  /**
   * Проверяем пользователей, отели, номера и четыре согласованные брони, которые создаются при
   * запуске миграций. Также проверяем исходную статистику за период в 30 дней.
   */
  @Test
  @Order(1)
  void migrationsPrepareExactlyTheRequestedDemoAndThirtyDayStatistics() throws Exception {
    try (var admin = new Api(port);
        var first = new Api(port);
        var second = new Api(port)) {
      admin.login("admin", "adminpassword");
      first.login("user1", "user1password");
      second.login("user2", "user2password");
      var users = admin.get("/api/users", 200).body();
      assertThat(users.get("totalElements").asInt()).isEqualTo(3);
      var usernames = new ArrayList<String>();
      for (var user : users.get("content")) usernames.add(user.get("username").asText());
      assertThat(usernames).containsExactlyInAnyOrder("admin", "user1", "user2");
      assertThat(first.get("/api/bookings", 200).body().get("totalElements").asInt()).isEqualTo(2);
      assertThat(second.get("/api/bookings", 200).body().get("totalElements").asInt()).isEqualTo(2);
      assertThat(first.get("/api/hotels", 200).body().get("totalElements").asInt()).isEqualTo(2);
      var rooms = admin.get("/api/admin/rooms", 200).body().get("content");
      assertThat(rooms.size()).isEqualTo(14);
      int unavailable = 0;
      long counts = 0;
      for (var room : rooms) {
        if (!room.get("available").asBoolean()) unavailable++;
        counts += room.get("timesBooked").asLong();
        String number = room.get("number").asText();
        assertThat(number).isNotEqualTo("42");
        if (room.get("id").asLong() == 1) assertThat(number).isEqualTo("59");
        if (number.equals("49") || number.equals("6011"))
          assertThat(room.get("timesBooked").asLong()).isZero();
        int numeric = Integer.parseInt(number);
        if (room.get("hotelId").asLong() == 1) assertThat(numeric).isBetween(40, 90);
        else assertThat(numeric).isBetween(6000, 7000);
      }
      assertThat(unavailable).isEqualTo(3);
      assertThat(counts).isEqualTo(4);
      var statistics = admin.get("/api/rooms/1/statistics", 200).body();
      assertThat(statistics.get("periodNights").asInt()).isEqualTo(30);
      assertThat(statistics.get("occupiedNights").asInt()).isEqualTo(4);
      assertThat(statistics.get("occupancyPercent").asDouble())
          .isCloseTo(100.0 * 4 / 30, org.assertj.core.data.Offset.offset(0.000001));
      var today = LocalDate.now(Clock.systemUTC());
      var invalid =
          admin
              .get(
                  "/api/hotels/1/statistics?startDate=" + today + "&endDate=" + today.plusDays(10),
                  400)
              .body();
      assertThat(invalid.get("detail").asText()).contains("30 календарных дней");
    }
  }

  /**
   * Проверяем доступность Swagger и описаний обоих сервисов через шлюз, а также вход без токена и
   * отсутствие закрытых внутренних операций в документации.
   */
  @Test
  @Order(2)
  void swaggerServesUsablePublicSpecificationsWithoutExtraCommands() throws Exception {
    try (var api = new Api(port)) {
      assertThat(api.get("/swagger-ui/index.html", 200).raw()).contains("Swagger UI");
      for (var name : new String[] {"booking", "hotel"}) {
        var response = api.get("/docs/" + name, 200);
        var document = response.body();
        assertThat(document.get("servers").get(0).get("url").asText()).isEqualTo("/");
        assertThat(
                document
                    .get("components")
                    .get("schemas")
                    .get("RoomRecommendation")
                    .get("properties")
                    .has("allocationRank"))
            .isTrue();
        assertThat(
                document
                    .get("components")
                    .get("schemas")
                    .get("AllocationLoad")
                    .get("properties")
                    .get("upcomingArrivalNights")
                    .get("description")
                    .asText())
            .contains("120");
        for (var path : document.get("paths").properties()) {
          assertThat(path.getKey())
              .doesNotContain("/internal/", "/release", "/confirm-availability");
        }
        if (name.equals("booking"))
          for (var auth : new String[] {"login", "register"})
            assertThat(
                    document
                        .get("paths")
                        .get("/api/auth/" + auth)
                        .get("post")
                        .get("security")
                        .isEmpty())
                .isTrue();
        Files.createDirectories(Path.of("target/openapi"));
        Files.writeString(Path.of("target/openapi", name + ".json"), response.raw());
      }
    }
  }

  /**
   * Проверяем операции через общий шлюз с разными ролями и владельцами, повторную отправку,
   * изменение и отмену брони. Также выполняем параллельный автоподбор для проверки распределения
   * номеров.
   */
  @Test
  @Order(3)
  void fullGatewayFlowEnforcesSecurityIdempotencyAmendmentsAndFairParallelAllocation()
      throws Exception {
    try (var admin = new Api(port);
        var user = new Api(port);
        var other = new Api(port);
        var anonymous = new Api(port)) {
      admin.login("admin", "adminpassword");
      user.login("user1", "user1password");
      other.login("user2", "user2password");
      assertThat(anonymous.get("/api/bookings", 401).body().get("detail").asText())
          .isEqualTo("Требуется авторизация");
      var denied =
          user.post("/api/hotels", Map.of("name", "Запрещённый", "address", "Адрес"), 403).body();
      assertThat(denied.get("detail").asText()).isEqualTo("Недостаточно прав доступа");
      var hotel =
          admin
              .post("/api/hotels", Map.of("name", "Тестовый отель", "address", "Адрес теста"), 201)
              .body();
      long hotelId = hotel.get("id").asLong();
      var rooms = new ArrayList<Long>();
      for (int i = 0; i < 4; i++)
        rooms.add(
            admin
                .post(
                    "/api/rooms",
                    Map.of(
                        "hotelId", hotelId, "number", Integer.toString(300 + i), "available", true),
                    201)
                .body()
                .get("id")
                .asLong());
      var start = LocalDate.now(Clock.systemUTC()).plusDays(50);
      var command = command(hotelId, rooms.getFirst(), start, false);
      var accepted = user.post("/api/bookings", command, 201);
      assertThat(accepted.headers().firstValue("X-Trace-Id").orElseThrow()).matches("[a-f0-9]{32}");
      assertThat(user.post("/api/bookings", command, 201).body()).isEqualTo(accepted.body());
      var id = accepted.body().get("id").asText();
      var duplicateKey = new HashMap<>(command);
      duplicateKey.put("roomId", rooms.get(1));
      user.post("/api/bookings", duplicateKey, 409);
      user.post("/api/bookings", command(hotelId, rooms.getFirst(), start, false), 409);
      other.get("/api/bookings/" + id, 404);
      admin.post("/api/rooms/" + rooms.getFirst() + "/release", Map.of("bookingId", id), 403);
      var blocked =
          other.post("/api/bookings", command(hotelId, rooms.get(2), start, false), 201).body();
      user.patch("/api/bookings/" + id, command(hotelId, rooms.get(2), start, false), 409);
      assertThat(user.get("/api/bookings/" + id, 200).body().get("roomId").asLong())
          .isEqualTo(rooms.getFirst());
      other.delete("/api/bookings/" + blocked.get("id").asText(), 200);
      var changed =
          user.patch(
                  "/api/bookings/" + id,
                  command(hotelId, rooms.get(1), start.plusDays(1), false),
                  200)
              .body();
      assertThat(changed.get("roomId").asLong()).isEqualTo(rooms.get(1));
      for (int i = 0; i < 2; i++)
        assertThat(user.delete("/api/bookings/" + id, 200).body().get("status").asText())
            .isEqualTo("CANCELLED");
      try (var executor = Executors.newFixedThreadPool(4)) {
        var go = new CountDownLatch(1);
        var attempts = new ArrayList<Future<JsonNode>>();
        for (int i = 0; i < 12; i++) {
          int offset = i;
          attempts.add(
              executor.submit(
                  () -> {
                    go.await();
                    return user.post(
                            "/api/bookings",
                            command(hotelId, -999L, start.plusDays(10 + 2L * offset), true),
                            201)
                        .body();
                  }));
        }
        go.countDown();
        var bookings = new ArrayList<String>();
        for (var attempt : attempts)
          bookings.add(attempt.get(30, TimeUnit.SECONDS).get("id").asText());
        var allocated = admin.get("/api/admin/rooms?hotelId=" + hotelId, 200).body().get("content");
        for (var room : allocated) assertThat(room.get("timesBooked").asLong()).isEqualTo(3);
        var recommended =
            user.get(
                    "/api/bookings/recommendations?hotelId="
                        + hotelId
                        + "&startDate="
                        + start.plusDays(100)
                        + "&endDate="
                        + start.plusDays(101),
                    200)
                .body();
        var sortedIds = new ArrayList<Long>();
        for (var room : recommended) sortedIds.add(room.get("id").asLong());
        assertThat(sortedIds).isSorted();
        for (var booking : bookings) user.delete("/api/bookings/" + booking, 200);
      }
      var malformed = user.post("/api/bookings", Map.of("autoSelect", true), 400).body();
      assertThat(malformed.get("detail").asText()).contains("Некорректные");
      assertThat(malformed.get("errors").toString()).contains("Поле обязательно");
      assertThat(applications.log("booking-service"))
          .doesNotContain("saga.action.deferred", "failed to append");
    }
  }

  /**
   * Проверяем одинаковые ранги рекомендаций в обоих сервисах, обязательную сортировку списка и
   * выбор по количеству ночей нагрузки. Дополнительно проверяем отличие от отчётного процента и
   * отсутствие влияния дальней брони на основной показатель.
   */
  @Test
  @Order(4)
  void recommendationRankUsesNightLoadAcrossServicesWhileCounterOrderAndReportsRemainStable()
      throws Exception {
    try (var admin = new Api(port);
        var user = new Api(port)) {
      admin.login("admin", "adminpassword");
      user.login("user1", "user1password");
      long hotel =
          admin
              .post(
                  "/api/hotels",
                  Map.of("name", "Проверка нагрузки", "address", "Тестовый адрес"),
                  201)
              .body()
              .get("id")
              .asLong();
      long longStay =
          admin
              .post("/api/rooms", Map.of("hotelId", hotel, "number", "71", "available", true), 201)
              .body()
              .get("id")
              .asLong();
      long shortStays =
          admin
              .post("/api/rooms", Map.of("hotelId", hotel, "number", "73", "available", true), 201)
              .body()
              .get("id")
              .asLong();
      var today = LocalDate.now(Clock.systemUTC());
      var first = new HashMap<>(command(hotel, longStay, today.plusDays(10), false));
      first.put("endDate", today.plusDays(15).toString());
      var longBooking = user.post("/api/bookings", first, 201).body();
      var shortBookings = new ArrayList<String>();
      for (int offset : new int[] {10, 12}) {
        var input = new HashMap<>(command(hotel, shortStays, today.plusDays(offset), false));
        input.put("endDate", today.plusDays(offset + 1).toString());
        shortBookings.add(user.post("/api/bookings", input, 201).body().get("id").asText());
      }
      var query =
          "?hotelId="
              + hotel
              + "&startDate="
              + today.plusDays(60)
              + "&endDate="
              + today.plusDays(62);
      var fromHotel = user.get("/api/rooms/recommend" + query, 200).body();
      var fromBooking = user.get("/api/bookings/recommendations" + query, 200).body();
      for (var recommended : new JsonNode[] {fromHotel, fromBooking}) {
        assertThat(recommended.get(0).get("id").asLong()).isEqualTo(longStay);
        assertThat(recommended.get(0).get("allocationRank").asInt()).isEqualTo(2);
        assertThat(recommended.get(0).get("allocationLoad").get("loadNights").asLong())
            .isEqualTo(5);
        assertThat(recommended.get(1).get("id").asLong()).isEqualTo(shortStays);
        assertThat(recommended.get(1).get("allocationRank").asInt()).isEqualTo(1);
        assertThat(recommended.get(1).get("allocationLoad").get("loadNights").asLong())
            .isEqualTo(2);
      }
      assertThat(
              admin
                  .get("/api/rooms/" + longStay + "/statistics", 200)
                  .body()
                  .get("occupancyPercent")
                  .asDouble())
          .isZero();
      var distant =
          user.post("/api/bookings", command(hotel, longStay, today.plusYears(2), false), 201)
              .body();
      assertThat(
              user.get("/api/rooms/recommend" + query, 200)
                  .body()
                  .get(0)
                  .get("allocationLoad")
                  .get("loadNights")
                  .asLong())
          .isEqualTo(5);
      var automaticInput = command(hotel, -999L, today.plusDays(60), true);
      var automatic = user.post("/api/bookings", automaticInput, 201).body();
      assertThat(automatic.get("roomId").asLong()).isEqualTo(shortStays);
      assertThat(user.post("/api/bookings", automaticInput, 201).body()).isEqualTo(automatic);
      user.delete("/api/bookings/" + automatic.get("id").asText(), 200);
      user.delete("/api/bookings/" + distant.get("id").asText(), 200);
      user.delete("/api/bookings/" + longBooking.get("id").asText(), 200);
      for (var id : shortBookings) user.delete("/api/bookings/" + id, 200);
      var released = user.get("/api/rooms/recommend" + query, 200).body();
      for (var room : released)
        assertThat(room.get("allocationLoad").get("loadNights").asLong()).isZero();
    }
  }

  /**
   * Вспомогательный метод для получения запроса к нужному отелю и номеру с новым ключом для
   * отдельного действия.
   */
  private Map<String, Object> command(long hotel, long room, LocalDate start, boolean automatic) {
    return Map.of(
        "requestId",
        UUID.randomUUID().toString(),
        "roomId",
        room,
        "hotelId",
        hotel,
        "autoSelect",
        automatic,
        "startDate",
        start.toString(),
        "endDate",
        start.plusDays(2).toString());
  }
}
