package com.github.yuyuvu.springhotelsbookingapp.booking.services;

import com.github.yuyuvu.springhotelsbookingapp.booking.controllers.BookingApi;
import com.github.yuyuvu.springhotelsbookingapp.booking.support.PageDto;
import org.springframework.data.domain.Pageable;

/** Интерфейс сервиса регистрации, входа и управления пользователями. */
public interface UserService {
  /**
   * Метод для регистрации пользователя с обычной ролью и получения токена сразу после создания
   * учётной записи.
   */
  BookingApi.Token register(BookingApi.Register r);

  /**
   * Метод для проверки имени пользователя, пароля и активности аккаунта. Если данные корректны,
   * возвращается токен доступа.
   */
  BookingApi.Token login(BookingApi.Login input);

  /** Метод для создания учётной записи с указанной ролью и сохранения пароля в виде хеша. */
  BookingApi.UserView create(BookingApi.UserInput input);

  /**
   * Метод для изменения данных учётной записи и при необходимости её пароля. Дополнительно
   * проверяется, что последний активный администратор не потеряет свою роль.
   */
  BookingApi.UserView update(long id, BookingApi.UserInput input);

  /**
   * Метод для отключения пользователя с сохранением связанных записей. Удаление запрещается для
   * последнего активного администратора и пользователя с действующими бронями.
   */
  void delete(long id);

  /** Метод для получения данных одного пользователя по идентификатору без передачи его пароля. */
  BookingApi.UserView get(long id);

  /** Метод для получения страницы учётных записей с данными пользователей без паролей. */
  PageDto<BookingApi.UserView> list(Pageable page);

  /**
   * Получаем идентификатор пользователя из токена и проверяем, что его учётная запись всё ещё
   * активна.
   */
  long activeId(String subject);
}
