package com.github.yuyuvu.springhotelsbookingapp.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.yuyuvu.springhotelsbookingapp.booking.client.HotelPort;
import com.github.yuyuvu.springhotelsbookingapp.booking.client.RemoteFailure;
import com.github.yuyuvu.springhotelsbookingapp.booking.controllers.BookingApi;
import com.github.yuyuvu.springhotelsbookingapp.booking.repositories.BookingRepository;
import com.github.yuyuvu.springhotelsbookingapp.booking.repositories.OperationRepository;
import com.github.yuyuvu.springhotelsbookingapp.booking.services.BookingSaga;
import com.github.yuyuvu.springhotelsbookingapp.booking.services.BookingTransactions;
import com.github.yuyuvu.springhotelsbookingapp.booking.services.UserService;
import com.github.yuyuvu.springhotelsbookingapp.booking.support.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.UUID;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Класс для тестов создания, изменения и отмены брони. Проверяются также ошибки связи и повторная
 * отправка запроса. Ответы сервиса отелей подменяются, чтобы воспроизвести нужную ситуацию.
 */
@SpringBootTest(
    properties = {
      "eureka.client.enabled=false",
      "spring.cloud.discovery.enabled=false",
      "spring.flyway.locations=classpath:db/migration",
      "app.hotel.discovery=false",
      "app.saga.recovery-enabled=false",
      "spring.datasource.url=jdbc:h2:mem:bookingtests;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
    })
@AutoConfigureMockMvc
class BookingSagaIntegrationTest {
  /**
   * Отклоняем перенос брони и проверяем, что последующая отмена использует действительный номер
   * текущей брони, несмотря на другой номер в запросе неудачного изменения.
   */
  @Test
  void cancellingAfterRejectedAmendmentUsesActualRoomForEveryCleanup() {
    var key = UUID.randomUUID();
    var original = saga.submit(user, null, key, input(key));
    doThrow(new RemoteFailure(409, "Номер занят", false)).when(hotel).reserve(any());
    var amendment = UUID.randomUUID();
    saga.submit(
        user,
        original.booking().id(),
        amendment,
        new BookingApi.Create(amendment, 2L, 1L, false, start, start.plusDays(2)));
    saga.cancel(original.booking().id(), user);
    verify(hotel, times(2)).releaseBooking(original.booking().id(), 1L);
    verify(hotel, never()).releaseBooking(original.booking().id(), 2L);
    assertThat(operations.findAll()).extracting(o -> o.getAction()).containsOnly("NONE");
  }

  /**
   * Воспроизводим потерю ответа автоподбора и проверяем освобождение по идентификатору брони, так
   * как выбранный номер в этом случае неизвестен.
   */
  @Test
  void lostAutomaticSelectionResponseCompensatesWithoutRoomIdentifier() {
    doThrow(new RemoteFailure(504, "Ответ потерян", true)).when(hotel).reserve(any());
    var key = UUID.randomUUID();
    var result =
        saga.submit(
            user, null, key, new BookingApi.Create(key, 999L, 1L, true, start, start.plusDays(2)));
    assertThat(result.booking().roomId()).isNull();
    assertThat(result.booking().status()).isEqualTo("CANCELLED");
    verify(hotel).releaseBooking(result.booking().id(), null);
  }

  /**
   * Проверяем ответ при ещё не завершённом изменении брони, в котором должны остаться прежние
   * данные и идентификатор ожидающей операции.
   */
  @Test
  void pendingAmendmentExposesCurrentReservationAndPendingOperation() {
    var key = UUID.randomUUID();
    var original = saga.submit(user, null, key, input(key));
    var amendment = UUID.randomUUID();
    var operation =
        transactions.begin(
            user,
            original.booking().id(),
            amendment,
            new BookingApi.Create(amendment, 2L, 1L, false, start.plusDays(1), start.plusDays(3)));
    var pending = transactions.outcome(operation, user);
    assertThat(pending.httpStatus()).isEqualTo(202);
    assertThat(pending.booking().roomId()).isEqualTo(1);
    assertThat(pending.booking().startDate()).isEqualTo(start);
    assertThat(pending.booking().pendingOperation()).isEqualTo(operation);
  }

  @Autowired tools.jackson.databind.ObjectMapper json;
  @Autowired org.springframework.security.oauth2.jwt.JwtDecoder decoder;
  @Autowired com.github.yuyuvu.springhotelsbookingapp.booking.security.TokenService tokenService;

  /**
   * Проверяем подпись выданного токена, сведения о пользователе и назначении, а также отказ при
   * попытке изменить его содержимое.
   */
  @Test
  void issuedTokensAreSignedHaveExpectedClaimsAndRejectTampering() {
    var issued = users.login(new BookingApi.Login("user1", "user1password"));
    var decoded = decoder.decode(issued.accessToken());
    assertThat(decoded.getSubject()).isEqualTo(Long.toString(user));
    assertThat(Duration.between(decoded.getIssuedAt(), decoded.getExpiresAt()))
        .isEqualTo(Duration.ofHours(1));
    assertThat(decoded.getAudience()).containsExactly("hotels-api");
    var service = decoder.decode(tokenService.service());
    assertThat(service.getClaimAsString("token_use")).isEqualTo("service");
    assertThat(service.getClaimAsString("scope")).isEqualTo("inventory.write");
    String token = issued.accessToken();
    int last = token.length() - 8;
    String tampered =
        token.substring(0, last)
            + (token.charAt(last) == 'A' ? 'B' : 'A')
            + token.substring(last + 1);
    assertThatThrownBy(() -> decoder.decode(tampered))
        .isInstanceOf(org.springframework.security.oauth2.jwt.JwtException.class);
  }

  /**
   * Проверяем регистрацию и операции администратора с пользователями через HTTP, после чего
   * проверяем получение истории личных бронирований.
   */
  @Test
  void registrationUserCrudAndBookingHistoryWorkThroughHttp() throws Exception {
    var admin = jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    var owner =
        jwt()
            .jwt(j -> j.subject(Long.toString(user)))
            .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    var created =
        mvc.perform(
                post("/api/users")
                    .with(admin)
                    .contentType("application/json")
                    .content(
                        json.writeValueAsString(
                            new BookingApi.UserInput(null, "created", "CreatedDemo!2026", "USER"))))
            .andExpect(status().isCreated())
            .andReturn();
    long createdId = json.readTree(created.getResponse().getContentAsString()).get("id").asLong();
    mvc.perform(
            patch("/api/users/" + createdId)
                .with(admin)
                .contentType("application/json")
                .content(
                    json.writeValueAsString(
                        new BookingApi.UserInput(null, "renamed", null, "USER"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.username").value("renamed"));
    mvc.perform(delete("/api/users/" + createdId).with(admin)).andExpect(status().isNoContent());
    mvc.perform(get("/api/users/" + createdId).with(admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.active").value(false));
    var key = UUID.randomUUID();
    mvc.perform(
            post("/api/bookings")
                .with(owner)
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(json.writeValueAsString(input(key))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("CONFIRMED"));
    mvc.perform(get("/api/bookings?size=1&status=CONFIRMED").with(owner))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1));
    mvc.perform(get("/api/bookings?size=0").with(owner)).andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/auth/register")
                .contentType("application/json")
                .content(
                    json.writeValueAsString(
                        new BookingApi.Register("public_user", "PublicPassword!2026"))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.user.role").value("USER"));
  }

  @Autowired BookingSaga saga;
  @Autowired BookingTransactions transactions;
  @Autowired UserService users;
  @Autowired BookingRepository bookings;
  @Autowired OperationRepository operations;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @Autowired Clock clock;
  @MockitoBean HotelPort hotel;
  long user;
  LocalDate start;

  /**
   * Подготавливаем чистые таблицы и обычного пользователя перед каждым тестом. Также задаём
   * успешный ответ подменённого сервиса отелей, который отдельный тест может заменить ошибкой.
   */
  @BeforeEach
  void setup() {
    jdbc.update("DELETE FROM booking_operations");
    jdbc.update("DELETE FROM bookings");
    jdbc.update("DELETE FROM users");
    user = users.create(new BookingApi.UserInput(null, "user1", "user1password", "USER")).id();
    start = LocalDate.now(clock).plusDays(10);
    when(hotel.reserve(any()))
        .thenAnswer(
            a -> {
              BookingApi.Reserve r = a.getArgument(0);
              assertThat(bookings.findById(r.bookingId()).orElseThrow().getStatus())
                  .isIn("PENDING", "CONFIRMED");
              return new BookingApi.Reserved(
                  r.bookingId(),
                  r.operationId(),
                  r.autoSelect() ? 7L : r.roomId(),
                  r.startDate(),
                  r.endDate());
            });
  }

  /**
   * Вспомогательный метод для создания обычного запроса бронирования с нужным ключом и будущими
   * датами.
   */
  BookingApi.Create input(UUID key) {
    return new BookingApi.Create(key, 1L, 1L, false, start, start.plusDays(2));
  }

  /**
   * Проверяем, что ожидающая бронь уже сохранена к моменту обращения к отелю. Повторная отправка
   * того же запроса должна вернуть прежний результат без второго подтверждения.
   */
  @Test
  void successCommitsPendingBeforeRemoteCallAndConfirmsOnce() {
    var key = UUID.randomUUID();
    var first = saga.submit(user, null, key, input(key));
    var second = saga.submit(user, null, key, input(key));
    assertThat(first.httpStatus()).isEqualTo(201);
    assertThat(second).isEqualTo(first);
    assertThat(bookings.count()).isEqualTo(1);
    verify(hotel, times(1)).reserve(any());
    assertThat(transactions.get(first.booking().id(), user).status()).isEqualTo("CONFIRMED");
  }

  /**
   * Подменяем ответ отеля ошибкой и проверяем отмену новой брони вместе с вызовом освобождения
   * возможного удержания.
   */
  @Test
  void remoteFailureCancelsAndCompensates() {
    doThrow(new RemoteFailure(503, "unavailable", true)).when(hotel).reserve(any());
    var key = UUID.randomUUID();
    var result = saga.submit(user, null, key, input(key));
    assertThat(result.httpStatus()).isEqualTo(503);
    assertThat(result.booking().status()).isEqualTo("CANCELLED");
    assertThat(result.compensationPending()).isFalse();
    verify(hotel).releaseBooking(result.booking().id(), 1L);
  }

  /**
   * Воспроизводим таймаут после возможного занятия номера и проверяем, что освобождение вызывается
   * даже при неизвестном результате подтверждения.
   */
  @Test
  void timeoutWithUnknownOutcomeAlwaysReleases() {
    doThrow(new RemoteFailure(504, "timeout after reservation", true)).when(hotel).reserve(any());
    var key = UUID.randomUUID();
    var result = saga.submit(user, null, key, input(key));
    assertThat(result.httpStatus()).isEqualTo(504);
    verify(hotel).releaseBooking(result.booking().id(), 1L);
    assertThat(bookings.findById(result.booking().id()).orElseThrow().getStatus())
        .isEqualTo("CANCELLED");
  }

  /**
   * Сначала возвращаем ошибку освобождения, затем восстанавливаем связь и проверяем выполнение
   * сохранённого задания следующей попыткой.
   */
  @Test
  void failedCompensationRemainsRecoverable() {
    doThrow(new RemoteFailure(504, "timeout", true)).when(hotel).reserve(any());
    doThrow(new RemoteFailure(503, "offline", true)).when(hotel).releaseBooking(any(), any());
    var key = UUID.randomUUID();
    var result = saga.submit(user, null, key, input(key));
    assertThat(result.compensationPending()).isTrue();
    var operation = operations.findAll().getFirst();
    assertThat(operation.getAction()).isEqualTo("CANCEL_BOOKING");
    doNothing().when(hotel).releaseBooking(any(), any());
    jdbc.update(
        "UPDATE booking_operations SET next_attempt=?,lock_until=NULL WHERE id=?",
        java.sql.Timestamp.from(clock.instant().minusSeconds(1)),
        operation.getId());
    saga.process(operation.getId());
    assertThat(operations.findById(operation.getId()).orElseThrow().getAction()).isEqualTo("NONE");
  }

  /**
   * Повторяем ключ запроса с другими данными и проверяем отказ, чтобы прежний запрос нельзя было
   * подменить новым действием.
   */
  @Test
  void requestIdentifierCannotBeReusedForDifferentPayload() {
    var key = UUID.randomUUID();
    saga.submit(user, null, key, input(key));
    assertThatThrownBy(
            () ->
                saga.submit(
                    user,
                    null,
                    key,
                    new BookingApi.Create(key, 2L, 1L, false, start, start.plusDays(2))))
        .isInstanceOf(ApiException.class);
    assertThat(bookings.count()).isEqualTo(1);
  }

  /**
   * Одновременно отправляем несколько одинаковых запросов и проверяем, что в базе появилась только
   * одна бронь.
   */
  @Test
  void simultaneousRepeatedDeliveryCreatesOneBooking() throws Exception {
    var key = UUID.randomUUID();
    try (var executor = Executors.newFixedThreadPool(8)) {
      var go = new CountDownLatch(1);
      var futures = new ArrayList<Future<BookingApi.Outcome>>();
      for (int i = 0; i < 8; i++)
        futures.add(
            executor.submit(
                () -> {
                  go.await();
                  return saga.submit(user, null, key, input(key));
                }));
      go.countDown();
      var ids = new HashSet<UUID>();
      for (var f : futures) ids.add(f.get(20, TimeUnit.SECONDS).booking().id());
      assertThat(ids).hasSize(1);
    }
    assertThat(bookings.count()).isEqualTo(1);
    verify(hotel, times(1)).reserve(any());
  }

  /**
   * Проверяем, что после ошибки изменения сохраняются исходные подтверждённые данные брони и ранее
   * занятые даты.
   */
  @Test
  void failedAmendmentPreservesPreviousBooking() {
    var key = UUID.randomUUID();
    var original = saga.submit(user, null, key, input(key));
    doThrow(new RemoteFailure(409, "occupied", false)).when(hotel).reserve(any());
    var amendment = UUID.randomUUID();
    var result =
        saga.submit(
            user,
            original.booking().id(),
            amendment,
            new BookingApi.Create(amendment, 2L, 1L, false, start.plusDays(1), start.plusDays(3)));
    assertThat(result.httpStatus()).isEqualTo(409);
    assertThat(transactions.get(original.booking().id(), user).roomId()).isEqualTo(1);
    assertThat(transactions.get(original.booking().id(), user).status()).isEqualTo("CONFIRMED");
    verify(hotel).releaseOperation(eq(original.booking().id()), any());
  }

  /**
   * Успешно изменяем бронь и проверяем подтверждение нового варианта вместе с освобождением
   * прежнего интервала.
   */
  @Test
  void successfulAmendmentReleasesPreviousReservation() {
    var key = UUID.randomUUID();
    var original = saga.submit(user, null, key, input(key));
    var previous = bookings.findById(original.booking().id()).orElseThrow().getCurrentOperation();
    var amendment = UUID.randomUUID();
    var result =
        saga.submit(
            user,
            original.booking().id(),
            amendment,
            new BookingApi.Create(amendment, 2L, 1L, false, start.plusDays(1), start.plusDays(3)));
    assertThat(result.httpStatus()).isEqualTo(200);
    assertThat(transactions.get(original.booking().id(), user).roomId()).isEqualTo(2);
    verify(hotel).releaseOperation(original.booking().id(), previous);
  }

  /**
   * Дважды отменяем одну бронь и проверяем, что повтор не приводит к лишнему изменению доступности
   * номера.
   */
  @Test
  void cancellingTwiceHasNoDuplicateRemoteEffect() {
    var key = UUID.randomUUID();
    var result = saga.submit(user, null, key, input(key));
    saga.cancel(result.booking().id(), user);
    saga.cancel(result.booking().id(), user);
    verify(hotel, times(1)).releaseBooking(result.booking().id(), 1L);
  }

  /**
   * Проверяем обработку просроченной ожидающей операции, для которой уже нельзя выполнять
   * подтверждение, но нужно освободить возможное удержание.
   */
  @Test
  void expiredPendingOperationIsCompensatedWithoutConfirmation() {
    var key = UUID.randomUUID();
    var op = transactions.begin(user, null, key, input(key));
    jdbc.update(
        "UPDATE booking_operations SET deadline=? WHERE id=?",
        java.sql.Timestamp.from(clock.instant().minusSeconds(1)),
        op);
    saga.process(op);
    assertThat(transactions.outcome(op, user).booking().status()).isEqualTo("CANCELLED");
    verify(hotel, never()).reserve(any());
    verify(hotel).releaseBooking(any(), any());
  }

  /** Проверяем отказ без входа, запрет доступа к чужой брони и отклонение некорректного запроса. */
  @Test
  void authenticationOwnershipAndValidationAreEnforced() throws Exception {
    mvc.perform(get("/api/bookings")).andExpect(status().isUnauthorized());
    var key = UUID.randomUUID();
    var result = saga.submit(user, null, key, input(key));
    var other =
        users.create(new BookingApi.UserInput(null, "other", "OtherDemo!2026", "USER")).id();
    mvc.perform(
            get("/api/bookings/" + result.booking().id())
                .with(
                    jwt()
                        .jwt(j -> j.subject(Long.toString(other)))
                        .authorities(new SimpleGrantedAuthority("ROLE_USER"))))
        .andExpect(status().isNotFound());
    mvc.perform(
            post("/api/bookings")
                .with(
                    jwt()
                        .jwt(j -> j.subject(Long.toString(user)))
                        .authorities(new SimpleGrantedAuthority("ROLE_USER")))
                .contentType("application/json")
                .content("{} "))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/users").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
        .andExpect(status().isForbidden());
  }

  /**
   * Проверяем, что при самостоятельной регистрации создаётся только обычный пользователь, а
   * выданный токен имеет срок действия один час.
   */
  @Test
  void registrationNeverCreatesAdminAndTokensHaveOneHourLifetime() {
    var token = users.register(new BookingApi.Register("newuser", "StrongPassword!2026"));
    assertThat(token.expiresIn()).isEqualTo(3600);
    assertThat(token.user().role()).isEqualTo("USER");
    assertThat(users.login(new BookingApi.Login("newuser", "StrongPassword!2026")).user().id())
        .isEqualTo(token.user().id());
    assertThatThrownBy(() -> users.login(new BookingApi.Login("newuser", "wrong")))
        .isInstanceOf(ApiException.class);
  }
}
