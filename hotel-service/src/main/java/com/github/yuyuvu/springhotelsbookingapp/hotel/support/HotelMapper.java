package com.github.yuyuvu.springhotelsbookingapp.hotel.support;

import com.github.yuyuvu.springhotelsbookingapp.hotel.controllers.HotelApi;
import com.github.yuyuvu.springhotelsbookingapp.hotel.entities.Hotel;
import com.github.yuyuvu.springhotelsbookingapp.hotel.entities.Room;
import com.github.yuyuvu.springhotelsbookingapp.hotel.services.RoomAllocationService;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/**
 * Интерфейс для преобразования сущностей отелей и номеров в объекты ответов API, а также для
 * добавления данных нагрузки к рекомендациям.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface HotelMapper {
  /** Метод для преобразования сущности отеля в объект, который можно передать в ответе API. */
  HotelApi.HotelView hotel(Hotel hotel);

  /** Метод для преобразования сущности номера в объект ответа с его открытыми данными. */
  HotelApi.RoomView room(Room room);

  /**
   * Метод для получения рекомендации из данных номера, рассчитанной нагрузки и ранга
   * автоматического выбора.
   */
  @Mapping(target = "id", source = "room.id")
  @Mapping(target = "hotelId", source = "room.hotelId")
  @Mapping(target = "number", source = "room.number")
  @Mapping(target = "available", source = "room.available")
  @Mapping(target = "timesBooked", source = "room.timesBooked")
  @Mapping(target = "archived", source = "room.archived")
  @Mapping(target = "version", source = "room.version")
  @Mapping(target = "allocationLoad", source = "load")
  HotelApi.RoomRecommendation recommendation(RoomAllocationService.RankedRoom candidate);
}
