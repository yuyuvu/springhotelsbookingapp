package com.github.yuyuvu.springhotelsbookingapp.hotel.services.impl;

import com.github.yuyuvu.springhotelsbookingapp.hotel.controllers.HotelApi;
import com.github.yuyuvu.springhotelsbookingapp.hotel.entities.Reservation;
import com.github.yuyuvu.springhotelsbookingapp.hotel.entities.Room;
import com.github.yuyuvu.springhotelsbookingapp.hotel.repositories.ReservationRepository;
import com.github.yuyuvu.springhotelsbookingapp.hotel.services.RoomAllocationService;
import com.github.yuyuvu.springhotelsbookingapp.hotel.support.NightCalendar;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Сервис рассчитывает нагрузку свободных номеров и определяет, какой из них предпочтительнее для
 * автоматического выбора. Сначала учитывается актуальная нагрузка и только при её равенстве
 * накопленное количество бронирований.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RoomAllocationServiceImpl implements RoomAllocationService {
  private final ReservationRepository reservations;
  private final Clock clock;

  /**
   * Метод для определения порядка автоматического выбора номера по ночам нагрузки, недавним броням,
   * общему счётчику и идентификатору. Полученный ранг относится только к текущему набору кандидатов
   * и в базу не записывается.
   */
  @Override
  public List<RankedRoom> rank(List<Room> candidates, UUID excludedBooking) {
    if (candidates.isEmpty()) return List.of();
    var now = clock.instant();
    var today = LocalDate.ofInstant(now, clock.getZone());
    var historyStart = today.minusDays(29);
    var historyEnd = today.plusDays(1);
    var arrivalsEnd = today.plusDays(121);
    var recentStart = historyStart.atStartOfDay(clock.getZone()).toInstant();
    var byRoom =
        reservations
            .allocationData(
                candidates.stream().map(Room::getId).toList(),
                historyStart,
                historyEnd,
                arrivalsEnd,
                recentStart,
                now,
                excludedBooking)
            .stream()
            .collect(Collectors.groupingBy(Reservation::getRoomId));
    var loads = new ArrayList<RankedRoom>();
    for (var room : candidates) {
      var calendar = byRoom.getOrDefault(room.getId(), List.of());
      var history = NightCalendar.measure(calendar, historyStart, historyEnd);
      long upcoming = NightCalendar.upcomingArrivalNights(calendar, historyEnd, arrivalsEnd);
      long recentBookings =
          calendar.stream()
              .filter(
                  r -> !r.getCreatedAt().isBefore(recentStart) && !r.getCreatedAt().isAfter(now))
              .map(Reservation::getBookingId)
              .distinct()
              .count();
      var load =
          new HotelApi.AllocationLoad(
              history.occupiedNights() + history.heldNights() + upcoming,
              history.occupiedNights(),
              history.heldNights(),
              upcoming,
              recentBookings,
              historyStart,
              historyEnd,
              historyEnd,
              arrivalsEnd,
              now);
      loads.add(new RankedRoom(room, 0, load));
    }
    loads.sort(
        Comparator.comparingLong((RankedRoom r) -> r.load().loadNights())
            .thenComparingLong(r -> r.load().recentBookings())
            .thenComparingLong(r -> r.room().getTimesBooked())
            .thenComparing(r -> r.room().getId()));
    var result = new ArrayList<RankedRoom>();
    for (int i = 0; i < loads.size(); i++) {
      var ranked = loads.get(i);
      result.add(new RankedRoom(ranked.room(), i + 1, ranked.load()));
    }
    return List.copyOf(result);
  }
}
