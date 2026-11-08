package db.demo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Миграция для создания демонстрационных пользователей и четырёх подтверждённых бронирований. Для
 * них также сохраняются операции, которые соответствуют начальному календарю сервиса отелей.
 */
public class V2__BookingDemo extends BaseJavaMigration {
  /**
   * Метод для загрузки исходных демонстрационных данных в новую базу до того, как приложение начнёт
   * принимать пользовательские запросы.
   */
  @Override
  public void migrate(Context context) throws Exception {
    var connection = context.getConnection();
    var passwords = new BCryptPasswordEncoder(12);
    var now = OffsetDateTime.now(Clock.systemUTC());
    insert(
        connection,
        "INSERT INTO users(id,username,password,role,created_at) VALUES(?,?,?,?,?)",
        1L,
        "admin",
        passwords.encode("adminpassword"),
        "ADMIN",
        now);
    insert(
        connection,
        "INSERT INTO users(id,username,password,role,created_at) VALUES(?,?,?,?,?)",
        2L,
        "user1",
        passwords.encode("user1password"),
        "USER",
        now);
    insert(
        connection,
        "INSERT INTO users(id,username,password,role,created_at) VALUES(?,?,?,?,?)",
        3L,
        "user2",
        passwords.encode("user2password"),
        "USER",
        now);
    try (var statement = connection.createStatement()) {
      statement.execute("ALTER TABLE users ALTER COLUMN id RESTART WITH 4");
    }
    long[] roomIds = {1, 2, 8, 10};
    int[] daysAgo = {24, 16, 9, 3};
    int[] nights = {4, 3, 2, 4};
    var today = LocalDate.now(Clock.systemUTC());
    for (int i = 0; i < roomIds.length; i++) {
      var id = new UUID(0, i + 1);
      long user = i % 2 == 0 ? 2 : 3;
      long hotel = roomIds[i] <= 7 ? 1 : 2;
      var start = today.minusDays(daysAgo[i]);
      var end = start.plusDays(nights[i]);
      var created = start.minusDays(5).atStartOfDay().atOffset(ZoneOffset.UTC);
      insert(
          connection,
          "INSERT INTO bookings(id,user_id,room_id,hotel_id,start_date,end_date,status,created_at,updated_at,current_operation) VALUES(?,?,?,?,?,?,'CONFIRMED',?,?,?)",
          id,
          user,
          roomIds[i],
          hotel,
          start,
          end,
          created,
          created,
          id);
      String fingerprint =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(
                          ("null|" + roomIds[i] + "|" + hotel + "|false|" + start + "|" + end)
                              .getBytes(StandardCharsets.UTF_8)));
      insert(
          connection,
          "INSERT INTO booking_operations(id,booking_id,user_id,request_id,fingerprint,kind,state,action,room_id,hotel_id,auto_select,start_date,end_date,created_at,deadline,next_attempt) VALUES(?,?,?,?,?,'CREATE','SUCCEEDED','NONE',?,?,FALSE,?,?,?,?,?)",
          id,
          id,
          user,
          id,
          fingerprint,
          roomIds[i],
          hotel,
          start,
          end,
          created,
          created.plusSeconds(15),
          created);
    }
  }

  /**
   * Выполняем подготовленный SQL-запрос с отдельной передачей значений, чтобы данные не добавлялись
   * непосредственно в текст запроса.
   */
  private void insert(Connection connection, String sql, Object... values) throws Exception {
    try (var statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
      statement.executeUpdate();
    }
  }
}
