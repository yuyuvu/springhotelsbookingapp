package com.github.yuyuvu.springhotelsbookingapp.hotel.services;

import com.github.yuyuvu.springhotelsbookingapp.hotel.controllers.HotelApi;
import com.github.yuyuvu.springhotelsbookingapp.hotel.support.PageDto;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.Pageable;

/**
 * Интерфейс сервиса для операций с отелями и номерами, получения свободных номеров и подсчёта
 * статистики.
 */
public interface HotelService {
  /**
   * Метод для получения страницы действующих отелей с поиском по названию и сортировкой по
   * выбранному полю.
   */
  PageDto<HotelApi.HotelView> hotels(String search, Pageable page);

  /**
   * Метод для получения отдельного отеля по его идентификатору. Если отель отсутствует или уже
   * помещён в архив, возвращается ошибка о том, что он не найден.
   */
  HotelApi.HotelView hotel(long id);

  /** Метод для получения данных отдельного номера, который ещё не помещён в архив. */
  HotelApi.RoomView room(long id);

  /** Метод для создания нового отеля и сохранения его названия и адреса в базе данных. */
  HotelApi.HotelView createHotel(HotelApi.HotelInput input);

  /** Метод для изменения названия и адреса существующего отеля, который ещё доступен в сервисе. */
  HotelApi.HotelView updateHotel(long id, HotelApi.HotelInput input);

  /**
   * Метод для архивирования отеля вместе с его номерами, чтобы сохранить историю связанных записей.
   * При наличии текущих или будущих проживаний удаление запрещается.
   */
  void deleteHotel(long id);

  /** Метод для добавления нового номера в существующий отель, который не находится в архиве. */
  HotelApi.RoomView createRoom(HotelApi.RoomInput input);

  /**
   * Метод для изменения номера на двери и доступности проживания без переноса в другой отель. Перед
   * отключением номера проверяется отсутствие текущих и будущих броней.
   */
  HotelApi.RoomView updateRoom(long id, HotelApi.RoomInput input);

  /**
   * Метод для переноса номера в архив с сохранением связанных данных. Если у номера есть текущее
   * или будущее проживание, возвращается ошибка.
   */
  void deleteRoom(long id);

  /**
   * Метод для получения свободных номеров с обычной сортировкой по идентификатору или с сортировкой
   * по количеству бронирований.
   */
  List<HotelApi.RoomView> available(
      Long hotelId, LocalDate start, LocalDate end, boolean recommend);

  /**
   * Для свободных номеров рассчитываем нагрузку и добавляем ранг автоматического выбора. В самом
   * ответе сохраняется сортировка по общему счётчику бронирований и идентификатору номера.
   */
  List<HotelApi.RoomRecommendation> recommendations(Long hotelId, LocalDate start, LocalDate end);

  /**
   * Метод для получения страницы неархивных номеров с фильтрами по отелю и доступности (в том числе
   * временно недоступных для проживания номеров).
   */
  PageDto<HotelApi.RoomView> allRooms(Long hotelId, Boolean available, Pageable page);

  /**
   * Считаем подтверждённые и удержанные ночи номера за выбранные 30 дней. Совпадающие даты разных
   * вариантов брони учитываются один раз, чтобы процент загрузки не завышался.
   */
  HotelApi.RoomStatistics roomStatistics(long id, LocalDate start, LocalDate end);

  /**
   * Метод для подсчёта общей загрузки отеля по его неархивным номерам. В общее количество номеров
   * включаются также те, которые временно недоступны для проживания.
   */
  HotelApi.HotelStatistics hotelStatistics(long id, LocalDate start, LocalDate end);
}
