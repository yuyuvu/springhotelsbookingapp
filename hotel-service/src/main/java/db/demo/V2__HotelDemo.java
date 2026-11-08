package db.demo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Миграция для загрузки демонстрационных отелей, номеров и четырёх бронирований в календарь. Данные
 * создаются при запуске новой базы и согласованы с начальными бронями другого сервиса.
 */
public class V2__HotelDemo extends BaseJavaMigration {
  /**
   * Метод для загрузки исходных демонстрационных данных в новую базу до того, как приложение начнёт
   * принимать пользовательские запросы.
   */
  @Override
  public void migrate(Context context) throws Exception {
    var connection = context.getConnection();
    insert(
        connection,
        "INSERT INTO hotels(id,name,address) VALUES(?,?,?)",
        1L,
        "Хороший отдых",
        "Москва, улица Свободы, 17");
    insert(
        connection,
        "INSERT INTO hotels(id,name,address) VALUES(?,?,?)",
        2L,
        "Премиум отель",
        "Казань, улица Рождественская, 8");
    String[] numbers = {
      "59", "45", "49", "58", "67", "81", "88", "6002", "6011", "6124", "6338", "6507", "6801",
      "6992"
    };
    for (int i = 0; i < numbers.length; i++) {
      long id = i + 1;
      boolean available = id != 6 && id != 12 && id != 14;
      insert(
          connection,
          "INSERT INTO rooms(id,hotel_id,number,available) VALUES(?,?,?,?)",
          id,
          id <= 7 ? 1L : 2L,
          numbers[i],
          available);
    }
    try (var statement = connection.createStatement()) {
      statement.execute("ALTER TABLE hotels ALTER COLUMN id RESTART WITH 3");
      statement.execute("ALTER TABLE rooms ALTER COLUMN id RESTART WITH 15");
    }
    long[] roomIds = {1, 2, 8, 10};
    int[] daysAgo = {24, 16, 9, 3};
    int[] nights = {4, 3, 2, 4};
    var today = LocalDate.now(Clock.systemUTC());
    for (int i = 0; i < roomIds.length; i++) {
      var id = new UUID(0, i + 1);
      long hotel = roomIds[i] <= 7 ? 1 : 2;
      var start = today.minusDays(daysAgo[i]);
      var end = start.plusDays(nights[i]);
      var created = start.minusDays(5).atStartOfDay().atOffset(ZoneOffset.UTC);
      String fingerprint =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(
                          (id
                                  + "|"
                                  + roomIds[i]
                                  + "|"
                                  + hotel
                                  + "|false|"
                                  + start
                                  + "|"
                                  + end
                                  + "|null")
                              .getBytes(StandardCharsets.UTF_8)));
      insert(
          connection,
          "INSERT INTO reservations(id,booking_id,room_id,start_date,end_date,state,confirmed,fingerprint,created_at) VALUES(?,?,?,?,?,'ACTIVE',TRUE,?,?)",
          id,
          id,
          roomIds[i],
          start,
          end,
          fingerprint,
          created);
      insert(connection, "UPDATE rooms SET times_booked=times_booked+1 WHERE id=?", roomIds[i]);
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
