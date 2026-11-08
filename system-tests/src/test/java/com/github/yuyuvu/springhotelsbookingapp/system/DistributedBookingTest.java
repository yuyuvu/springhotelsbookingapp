package com.github.yuyuvu.springhotelsbookingapp.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Класс для проверки взаимодействия настоящих сервисов бронирования и отелей при сетевых сбоях и
 * конкурентных запросах.
 */
class DistributedBookingTest {
  private final LocalDate start = LocalDate.now(java.time.Clock.systemUTC()).plusDays(50);

  /**
   * Проверяем работу двух настоящих сервисов при успешном подтверждении, повторе запроса, сетевых
   * ошибках и восстановлении освобождения номера. Затем одновременно создаём пересекающиеся брони и
   * проверяем единственное успешное занятие дат.
   */
  @Test
  @Timeout(180)
  void realDatabasesSurviveRemoteFaultsLostResponsesAndConcurrentBookings() throws Exception {
    int hotelPort = Applications.port();
    int bookingPort = Applications.port();
    try (var applications = new Applications("faults");
        var proxy = new FaultProxy(hotelPort);
        var booking = new Api(bookingPort);
        var hotel = new Api(hotelPort)) {
      applications.start(
          "hotel-service",
          hotelPort,
          "--eureka.client.enabled=false",
          "--spring.cloud.discovery.enabled=false");
      applications.start(
          "booking-service",
          bookingPort,
          "--eureka.client.enabled=false",
          "--spring.cloud.discovery.enabled=false",
          "--app.hotel.discovery=false",
          "--app.hotel.url=http://127.0.0.1:" + proxy.port(),
          "--app.hotel.connect-timeout=200ms",
          "--app.hotel.read-timeout=1500ms",
          "--app.hotel.attempts=2",
          "--app.hotel.retry-delay=20ms",
          "--app.saga.recovery-delay=100");
      booking.login("user1", "user1password");
      hotel.useToken(booking);
      hotel.get("/api/hotels", 200);
      var successful = command(0, false);
      var accepted = booking.post("/api/bookings", successful, 201).body();
      assertThat(booking.post("/api/bookings", successful, 201).body()).isEqualTo(accepted);
      booking.delete("/api/bookings/" + accepted.get("id").asText(), 200);
      assertThat(proxy.releases).contains("/api/rooms/3/release");
      for (var mode :
          new FaultProxy.Mode[] {
            FaultProxy.Mode.ERROR, FaultProxy.Mode.LOST_RESPONSE, FaultProxy.Mode.DELAY
          }) {
        proxy.mode = mode;
        if (mode == FaultProxy.Mode.DELAY) proxy.delayed = new CountDownLatch(2);
        int expected = mode == FaultProxy.Mode.ERROR ? 503 : 504;
        var input = command(4, false);
        var failed = booking.post("/api/bookings", input, expected).body();
        String id = failed.get("errors").get("bookingId").asText();
        assertThat(booking.get("/api/bookings/" + id, 200).body().get("status").asText())
            .isEqualTo("CANCELLED");
        assertThat(
                booking
                    .post("/api/bookings", input, expected)
                    .body()
                    .get("errors")
                    .get("bookingId")
                    .asText())
            .isEqualTo(id);
        assertThat(proxy.delayed.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(available(hotel, 4)).isTrue();
        if (mode == FaultProxy.Mode.DELAY) assertThat(proxy.delayedResponses).containsOnly(409);
        assertThat(applications.log("booking-service"))
            .contains(id, "booking.pending", "booking.cancelled-or-amendment-rejected");
      }
      proxy.mode = FaultProxy.Mode.LOST_RESPONSE;
      proxy.denyRelease = true;
      var failed = booking.post("/api/bookings", command(10, true), 504).body();
      String failedId = failed.get("errors").get("bookingId").asText();
      assertThat(failed.get("errors").get("compensationPending").asText()).isEqualTo("true");
      assertThat(available(hotel, 10)).isFalse();
      assertThat(proxy.releases).contains("/internal/reservations/" + failedId + "/release");
      proxy.mode = FaultProxy.Mode.NORMAL;
      proxy.denyRelease = false;
      Applications.await(() -> available(hotel, 10), Duration.ofSeconds(20));
      Thread.sleep(10_200);
      try (var executor = Executors.newFixedThreadPool(8)) {
        var ready = new CountDownLatch(8);
        var go = new CountDownLatch(1);
        var attempts = new ArrayList<Future<Api.Response>>();
        for (int i = 0; i < 8; i++)
          attempts.add(
              executor.submit(
                  () -> {
                    ready.countDown();
                    go.await();
                    return booking.post("/api/bookings", command(20, false), 201, 409);
                  }));
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        int winners = 0;
        String winner = null;
        for (var attempt : attempts) {
          var result = attempt.get(30, TimeUnit.SECONDS);
          if (result.status() == 201) {
            winners++;
            winner = result.body().get("id").asText();
          }
        }
        assertThat(winners).isEqualTo(1);
        booking.delete("/api/bookings/" + winner, 200);
      }
      assertThat(applications.log("hotel-service"))
          .contains("reservation.accepted", "reservation.released");
      assertThat(applications.log("hotel-service")).doesNotContain("failed to append");
      assertThat(applications.log("booking-service")).doesNotContain("failed to append");
    }
  }

  /**
   * Вспомогательный метод для проверки через API отеля, что нужный тестовый номер свободен на
   * выбранный период.
   */
  private boolean available(Api hotel, int offset) throws Exception {
    var rooms =
        hotel
            .get(
                "/api/rooms?startDate="
                    + start.plusDays(offset)
                    + "&endDate="
                    + start.plusDays(offset + 2),
                200)
            .body();
    for (var room : rooms) if (room.get("id").asLong() == 3) return true;
    return false;
  }

  /**
   * Вспомогательный метод для создания запроса бронирования с новым ключом и отдельными будущими
   * датами для текущей проверки.
   */
  private Map<String, Object> command(int offset, boolean automatic) {
    var input = new HashMap<String, Object>();
    input.put("requestId", UUID.randomUUID().toString());
    input.put("roomId", 3);
    input.put("hotelId", 1);
    input.put("autoSelect", automatic);
    input.put("startDate", start.plusDays(offset).toString());
    input.put("endDate", start.plusDays(offset + 2).toString());
    return input;
  }
}
