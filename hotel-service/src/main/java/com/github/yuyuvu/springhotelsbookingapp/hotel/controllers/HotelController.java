package com.github.yuyuvu.springhotelsbookingapp.hotel.controllers;

import com.github.yuyuvu.springhotelsbookingapp.hotel.services.HotelService;
import com.github.yuyuvu.springhotelsbookingapp.hotel.support.PageDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Контроллер для обработки запросов к отелям и номерам. Проверяет доступ к отдельным операциям и
 * обращается в сервис, который выполняет основную логику.
 */
@RestController
@Tag(name = "Отели, номера и статистика")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('USER','ADMIN')")
public class HotelController {
  private final HotelService service;

  /** Метод для создания нового отеля и сохранения его названия и адреса в базе данных. */
  @PostMapping("/api/hotels")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "ADMIN: создание нового отеля")
  public HotelApi.HotelView createHotel(@Valid @RequestBody HotelApi.HotelInput r) {
    return service.createHotel(r);
  }

  /**
   * Метод для получения страницы действующих отелей с поиском по названию и сортировкой по
   * выбранному полю.
   */
  @GetMapping("/api/hotels")
  @Operation(summary = "Получение отелей с поиском по названию, сортировкой и выводом по страницам")
  public PageDto<HotelApi.HotelView> hotels(
      @RequestParam(required = false) String search,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "id,asc") String sort) {
    return service.hotels(
        search, PageDto.request(page, size, sort, Set.of("id", "name", "address")));
  }

  /**
   * Метод для получения отдельного отеля по его идентификатору. Если отель отсутствует или уже
   * помещён в архив, возвращается ошибка о том, что он не найден.
   */
  @GetMapping("/api/hotels/{id}")
  @Operation(summary = "Получение данных отдельного отеля")
  public HotelApi.HotelView hotel(@PathVariable long id) {
    return service.hotel(id);
  }

  /** Метод для изменения названия и адреса существующего отеля, который ещё доступен в сервисе. */
  @PatchMapping("/api/hotels/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "ADMIN: изменение названия и адреса отеля")
  public HotelApi.HotelView updateHotel(
      @PathVariable long id, @Valid @RequestBody HotelApi.HotelInput r) {
    return service.updateHotel(id, r);
  }

  /**
   * Метод для архивирования отеля вместе с его номерами, чтобы сохранить историю связанных записей.
   * При наличии текущих или будущих проживаний удаление запрещается.
   */
  @DeleteMapping("/api/hotels/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "ADMIN: перенос отеля в архив с сохранением истории")
  public void deleteHotel(@PathVariable long id) {
    service.deleteHotel(id);
  }

  /** Метод для добавления нового номера в существующий отель, который не находится в архиве. */
  @PostMapping("/api/rooms")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "ADMIN: добавление нового номера в отель")
  public HotelApi.RoomView createRoom(@Valid @RequestBody HotelApi.RoomInput r) {
    return service.createRoom(r);
  }

  /** Метод для получения данных отдельного номера, который ещё не помещён в архив. */
  @GetMapping("/api/rooms/{id}")
  @Operation(summary = "Получение данных отдельного номера")
  public HotelApi.RoomView room(@PathVariable long id) {
    return service.room(id);
  }

  /**
   * Метод для изменения номера на двери и доступности проживания без переноса в другой отель. Перед
   * отключением номера проверяется отсутствие текущих и будущих броней.
   */
  @PatchMapping("/api/rooms/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "ADMIN: изменение данных номера и его доступности для проживания")
  public HotelApi.RoomView updateRoom(
      @PathVariable long id, @Valid @RequestBody HotelApi.RoomInput r) {
    return service.updateRoom(id, r);
  }

  /**
   * Метод для переноса номера в архив с сохранением связанных данных. Если у номера есть текущее
   * или будущее проживание, возвращается ошибка.
   */
  @DeleteMapping("/api/rooms/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "ADMIN: перенос номера в архив с сохранением истории")
  public void deleteRoom(@PathVariable long id) {
    service.deleteRoom(id);
  }

  /** Получаем номера, которые доступны для проживания и свободны на весь выбранный период. */
  @GetMapping("/api/rooms")
  @Operation(summary = "Получение номеров, свободных на весь указанный период")
  public List<HotelApi.RoomView> rooms(
      @RequestParam(required = false) Long hotelId,
      @RequestParam LocalDate startDate,
      @RequestParam LocalDate endDate) {
    return service.available(hotelId, startDate, endDate, false);
  }

  /**
   * Получаем свободные номера в порядке увеличения общего счётчика бронирований и затем
   * идентификатора. Вместе с ними передаётся ранг, который показывает предпочтение при
   * автоматическом выборе.
   */
  @GetMapping("/api/rooms/recommend")
  @Operation(
      summary =
          "Получение свободных номеров по timesBooked и id с рангом автоматического выбора по нагрузке")
  public List<HotelApi.RoomRecommendation> recommend(
      @RequestParam(required = false) Long hotelId,
      @RequestParam LocalDate startDate,
      @RequestParam LocalDate endDate) {
    return service.recommendations(hotelId, startDate, endDate);
  }

  /**
   * Метод для получения страницы неархивных номеров с фильтрами по отелю и доступности (в том числе
   * временно недоступных для проживания номеров).
   */
  @GetMapping("/api/admin/rooms")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(
      summary =
          "ADMIN: получение всех неархивных номеров, в том числе операционно недоступных, с фильтрами и выводом по страницам")
  public PageDto<HotelApi.RoomView> allRooms(
      @RequestParam(required = false) Long hotelId,
      @RequestParam(required = false) Boolean available,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "id,asc") String sort) {
    return service.allRooms(
        hotelId,
        available,
        PageDto.request(page, size, sort, Set.of("id", "number", "timesBooked", "hotelId")));
  }

  /**
   * Получаем статистику отдельного номера за 30 дней, в которой отдельно указаны подтверждённые
   * ночи, временные удержания и процент загрузки.
   */
  @GetMapping("/api/rooms/{id}/statistics")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "ADMIN: загрузка номера за 30 дней, по умолчанию включая сегодняшний день")
  public HotelApi.RoomStatistics roomStats(
      @PathVariable long id,
      @RequestParam(required = false) LocalDate startDate,
      @RequestParam(required = false) LocalDate endDate) {
    return service.roomStatistics(id, startDate, endDate);
  }

  /** Получаем статистику всего отеля за 30 дней вместе с показателями его отдельных номеров. */
  @GetMapping("/api/hotels/{id}/statistics")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "ADMIN: загрузка отеля за 30 дней, по умолчанию включая сегодняшний день")
  public HotelApi.HotelStatistics hotelStats(
      @PathVariable long id,
      @RequestParam(required = false) LocalDate startDate,
      @RequestParam(required = false) LocalDate endDate) {
    return service.hotelStatistics(id, startDate, endDate);
  }
}
