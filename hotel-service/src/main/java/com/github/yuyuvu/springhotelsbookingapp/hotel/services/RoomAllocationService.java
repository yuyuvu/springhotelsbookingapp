package com.github.yuyuvu.springhotelsbookingapp.hotel.services;

import com.github.yuyuvu.springhotelsbookingapp.hotel.controllers.HotelApi;
import com.github.yuyuvu.springhotelsbookingapp.hotel.entities.Room;
import java.util.List;
import java.util.UUID;

/**
 * Интерфейс сервиса для сравнения свободных номеров по нагрузке и определения порядка их
 * автоматического выбора.
 */
public interface RoomAllocationService {
  /**
   * Метод для определения порядка автоматического выбора номера по ночам нагрузки, недавним броням,
   * общему счётчику и идентификатору. Полученный ранг относится только к текущему набору кандидатов
   * и в базу не записывается.
   */
  List<RankedRoom> rank(List<Room> candidates, UUID excludedBooking);

  /**
   * Объект для хранения номера вместе с его рассчитанной нагрузкой и рангом среди остальных
   * свободных кандидатов.
   */
  record RankedRoom(Room room, int allocationRank, HotelApi.AllocationLoad load) {}
}
