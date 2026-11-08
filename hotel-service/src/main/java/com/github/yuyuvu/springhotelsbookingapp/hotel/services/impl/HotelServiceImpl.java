package com.github.yuyuvu.springhotelsbookingapp.hotel.services.impl;

import com.github.yuyuvu.springhotelsbookingapp.hotel.controllers.HotelApi;
import com.github.yuyuvu.springhotelsbookingapp.hotel.entities.Hotel;
import com.github.yuyuvu.springhotelsbookingapp.hotel.entities.Room;
import com.github.yuyuvu.springhotelsbookingapp.hotel.repositories.HotelRepository;
import com.github.yuyuvu.springhotelsbookingapp.hotel.repositories.ReservationRepository;
import com.github.yuyuvu.springhotelsbookingapp.hotel.repositories.RoomRepository;
import com.github.yuyuvu.springhotelsbookingapp.hotel.services.HotelService;
import com.github.yuyuvu.springhotelsbookingapp.hotel.services.RoomAllocationService;
import com.github.yuyuvu.springhotelsbookingapp.hotel.support.AllocationLock;
import com.github.yuyuvu.springhotelsbookingapp.hotel.support.ApiException;
import com.github.yuyuvu.springhotelsbookingapp.hotel.support.DateRange;
import com.github.yuyuvu.springhotelsbookingapp.hotel.support.HotelMapper;
import com.github.yuyuvu.springhotelsbookingapp.hotel.support.NightCalendar;
import com.github.yuyuvu.springhotelsbookingapp.hotel.support.PageDto;
import com.github.yuyuvu.springhotelsbookingapp.hotel.support.StatisticsPeriod;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Сервис отвечает за создание, получение, изменение и архивирование отелей и номеров. Также
 * получает рекомендации свободных номеров и рассчитывает статистику загрузки.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HotelServiceImpl implements HotelService {
  private final HotelRepository hotels;
  private final RoomRepository rooms;
  private final ReservationRepository reservations;
  private final HotelMapper mapper;
  private final AllocationLock lock;
  private final Clock clock;
  private final RoomAllocationService allocation;

  /**
   * Метод для получения страницы действующих отелей с поиском по названию и сортировкой по
   * выбранному полю.
   */
  @Override
  public PageDto<HotelApi.HotelView> hotels(String search, Pageable page) {
    return PageDto.from(
        hotels
            .findAll(
                (root, q, cb) ->
                    cb.and(
                        cb.isFalse(root.get("archived")),
                        cb.like(
                            cb.lower(root.get("name")),
                            "%" + (search == null ? "" : search.toLowerCase(Locale.ROOT)) + "%")),
                page)
            .map(mapper::hotel));
  }

  /**
   * Метод для получения отдельного отеля по его идентификатору. Если отель отсутствует или уже
   * помещён в архив, возвращается ошибка о том, что он не найден.
   */
  @Override
  public HotelApi.HotelView hotel(long id) {
    return mapper.hotel(
        hotels.findById(id).filter(h -> !h.isArchived()).orElseThrow(ApiException::missing));
  }

  /** Метод для получения данных отдельного номера, который ещё не помещён в архив. */
  @Override
  public HotelApi.RoomView room(long id) {
    return mapper.room(
        rooms.findById(id).filter(r -> !r.isArchived()).orElseThrow(ApiException::missing));
  }

  /** Метод для создания нового отеля и сохранения его названия и адреса в базе данных. */
  @Transactional
  @Override
  public HotelApi.HotelView createHotel(HotelApi.HotelInput input) {
    lock.acquire();
    return mapper.hotel(hotels.save(new Hotel(input.name().strip(), input.address().strip())));
  }

  /** Метод для изменения названия и адреса существующего отеля, который ещё доступен в сервисе. */
  @Transactional
  @Override
  public HotelApi.HotelView updateHotel(long id, HotelApi.HotelInput input) {
    lock.acquire();
    var h = hotels.findById(id).filter(a -> !a.isArchived()).orElseThrow(ApiException::missing);
    h.setName(input.name().strip());
    h.setAddress(input.address().strip());
    return mapper.hotel(hotels.saveAndFlush(h));
  }

  /**
   * Метод для архивирования отеля вместе с его номерами, чтобы сохранить историю связанных записей.
   * При наличии текущих или будущих проживаний удаление запрещается.
   */
  @Transactional
  @Override
  public void deleteHotel(long id) {
    lock.acquire();
    var h = hotels.findById(id).orElseThrow(ApiException::missing);
    var all = rooms.findAll().stream().filter(r -> r.getHotelId() == id).toList();
    all.forEach(this::ensureNoFuture);
    all.forEach(r -> r.setArchived(true));
    h.setArchived(true);
  }

  /** Метод для добавления нового номера в существующий отель, который не находится в архиве. */
  @Transactional
  @Override
  public HotelApi.RoomView createRoom(HotelApi.RoomInput input) {
    lock.acquire();
    hotels
        .findById(input.hotelId())
        .filter(h -> !h.isArchived())
        .orElseThrow(ApiException::missing);
    return mapper.room(
        rooms.save(new Room(input.hotelId(), input.number().strip(), input.available())));
  }

  /**
   * Метод для изменения номера на двери и доступности проживания без переноса в другой отель. Перед
   * отключением номера проверяется отсутствие текущих и будущих броней.
   */
  @Transactional
  @Override
  public HotelApi.RoomView updateRoom(long id, HotelApi.RoomInput input) {
    lock.acquire();
    var r = rooms.findById(id).filter(a -> !a.isArchived()).orElseThrow(ApiException::missing);
    if (!r.getHotelId().equals(input.hotelId()))
      throw ApiException.invalid("Нельзя перенести номер в другой отель");
    if (!input.available()) ensureNoFuture(r);
    r.setNumber(input.number().strip());
    r.setAvailable(input.available());
    return mapper.room(rooms.saveAndFlush(r));
  }

  /**
   * Метод для переноса номера в архив с сохранением связанных данных. Если у номера есть текущее
   * или будущее проживание, возвращается ошибка.
   */
  @Transactional
  @Override
  public void deleteRoom(long id) {
    lock.acquire();
    var r = rooms.findById(id).orElseThrow(ApiException::missing);
    ensureNoFuture(r);
    r.setArchived(true);
  }

  /**
   * Проверяем отсутствие текущих и будущих проживаний, прежде чем убрать номер из использования.
   * Если такие брони есть, изменение отклоняется.
   */
  private void ensureNoFuture(Room r) {
    if (reservations.findByRoomIdAndState(r.getId(), "ACTIVE").stream()
        .anyMatch(a -> a.getEndDate().isAfter(LocalDate.now(clock))))
      throw ApiException.conflict("У номера есть текущие или будущие бронирования");
  }

  /**
   * Метод для получения свободных номеров с обычной сортировкой по идентификатору или с сортировкой
   * по количеству бронирований.
   */
  @Override
  public List<HotelApi.RoomView> available(
      Long hotelId, LocalDate start, LocalDate end, boolean recommend) {
    var sort =
        recommend
            ? Comparator.comparingLong(Room::getTimesBooked).thenComparing(Room::getId)
            : Comparator.comparing(Room::getId);
    return candidates(hotelId, start, end).stream().sorted(sort).map(mapper::room).toList();
  }

  /**
   * Для свободных номеров рассчитываем нагрузку и добавляем ранг автоматического выбора. В самом
   * ответе сохраняется сортировка по общему счётчику бронирований и идентификатору номера.
   */
  @Override
  public List<HotelApi.RoomRecommendation> recommendations(
      Long hotelId, LocalDate start, LocalDate end) {
    return allocation.rank(candidates(hotelId, start, end), null).stream()
        .map(mapper::recommendation)
        .sorted(
            Comparator.comparingLong(HotelApi.RoomRecommendation::timesBooked)
                .thenComparing(HotelApi.RoomRecommendation::id))
        .toList();
  }

  /**
   * Сначала проверяем корректность периода проживания, затем получаем номера, свободные на все
   * указанные даты.
   */
  private List<Room> candidates(Long hotelId, LocalDate start, LocalDate end) {
    new DateRange(start, end);
    return rooms.candidates(hotelId, null, start, end, null);
  }

  /**
   * Метод для получения страницы неархивных номеров с фильтрами по отелю и доступности (в том числе
   * временно недоступных для проживания номеров).
   */
  @Override
  public PageDto<HotelApi.RoomView> allRooms(Long hotelId, Boolean available, Pageable page) {
    return PageDto.from(
        rooms
            .findAll(
                (r, q, c) -> {
                  var p = new ArrayList<jakarta.persistence.criteria.Predicate>();
                  p.add(c.isFalse(r.get("archived")));
                  if (hotelId != null) p.add(c.equal(r.get("hotelId"), hotelId));
                  if (available != null) p.add(c.equal(r.get("available"), available));
                  return c.and(p.toArray(jakarta.persistence.criteria.Predicate[]::new));
                },
                page)
            .map(mapper::room));
  }

  /**
   * Считаем подтверждённые и удержанные ночи номера за выбранные 30 дней. Совпадающие даты разных
   * вариантов брони учитываются один раз, чтобы процент загрузки не завышался.
   */
  @Override
  public HotelApi.RoomStatistics roomStatistics(long id, LocalDate start, LocalDate end) {
    var period = StatisticsPeriod.resolve(start, end, clock);
    start = period.start();
    end = period.end();
    var r = rooms.findById(id).orElseThrow(ApiException::missing);
    var nights = NightCalendar.measure(reservations.overlaps(id, start, end), start, end);
    long total = ChronoUnit.DAYS.between(period.start(), period.end());
    return new HotelApi.RoomStatistics(
        id,
        r.getTimesBooked(),
        nights.occupiedNights(),
        nights.heldNights(),
        total,
        100.0 * nights.occupiedNights() / total,
        start,
        end);
  }

  /**
   * Метод для подсчёта общей загрузки отеля по его неархивным номерам. В общее количество номеров
   * включаются также те, которые временно недоступны для проживания.
   */
  @Override
  public HotelApi.HotelStatistics hotelStatistics(long id, LocalDate start, LocalDate end) {
    hotel(id);
    var period = StatisticsPeriod.resolve(start, end, clock);
    var reportStart = period.start();
    var reportEnd = period.end();
    var all =
        rooms.findAll().stream()
            .filter(r -> r.getHotelId() == id && !r.isArchived())
            .map(r -> roomStatistics(r.getId(), reportStart, reportEnd))
            .sorted(
                Comparator.comparingLong(HotelApi.RoomStatistics::timesBooked)
                    .thenComparingLong(HotelApi.RoomStatistics::roomId))
            .toList();
    long occupied = all.stream().mapToLong(HotelApi.RoomStatistics::occupiedNights).sum();
    long denominator = all.size() * 30L;
    return new HotelApi.HotelStatistics(
        id,
        all.size(),
        all.stream().filter(r -> r.occupiedNights() == 0).count(),
        occupied,
        denominator == 0 ? 0 : 100.0 * occupied / denominator,
        all,
        reportStart,
        reportEnd,
        30);
  }
}
