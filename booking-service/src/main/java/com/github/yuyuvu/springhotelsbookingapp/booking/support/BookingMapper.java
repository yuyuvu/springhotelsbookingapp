package com.github.yuyuvu.springhotelsbookingapp.booking.support;

import com.github.yuyuvu.springhotelsbookingapp.booking.controllers.BookingApi;
import com.github.yuyuvu.springhotelsbookingapp.booking.entities.Booking;
import com.github.yuyuvu.springhotelsbookingapp.booking.entities.User;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

/**
 * Интерфейс для преобразования бронирований и пользователей в данные ответа API. Внутренние
 * сведения и хеш пароля в ответ не включаются.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface BookingMapper {
  /**
   * Метод для преобразования сохранённой брони в объект ответа с её текущими данными и ожидающей
   * операцией.
   */
  BookingApi.View booking(Booking value);

  /** Метод для получения объекта ответа из учётной записи пользователя без передачи хеша пароля. */
  BookingApi.UserView user(User value);
}
