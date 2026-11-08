package com.github.yuyuvu.springhotelsbookingapp.hotel.repositories;

import com.github.yuyuvu.springhotelsbookingapp.hotel.entities.Room;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

/** Интерфейс для работы с номерами в базе данных и поиска свободных номеров на нужный период. */
public interface RoomRepository extends JpaRepository<Room, Long>, JpaSpecificationExecutor<Room> {
  /**
   * Получаем действующие номера, которые доступны для проживания и не заняты на нужные даты. При
   * изменении брони её прежний интервал можно исключить из проверки пересечения.
   */
  @Query(
      "select r from Room r where r.archived=false and r.available=true and (:hotel is null or r.hotelId=:hotel) and (:room is null or r.id=:room) and exists (select h.id from Hotel h where h.id=r.hotelId and h.archived=false) and not exists (select a.id from Reservation a where a.roomId=r.id and a.state='ACTIVE' and a.startDate<:end and a.endDate>:start and (:excluded is null or a.id<>:excluded)) order by r.timesBooked,r.id")
  java.util.List<Room> candidates(
      Long hotel,
      Long room,
      java.time.LocalDate start,
      java.time.LocalDate end,
      java.util.UUID excluded);
}
