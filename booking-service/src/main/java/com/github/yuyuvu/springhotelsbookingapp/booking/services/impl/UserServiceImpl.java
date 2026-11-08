package com.github.yuyuvu.springhotelsbookingapp.booking.services.impl;

import com.github.yuyuvu.springhotelsbookingapp.booking.controllers.BookingApi;
import com.github.yuyuvu.springhotelsbookingapp.booking.entities.User;
import com.github.yuyuvu.springhotelsbookingapp.booking.repositories.BookingRepository;
import com.github.yuyuvu.springhotelsbookingapp.booking.repositories.UserRepository;
import com.github.yuyuvu.springhotelsbookingapp.booking.security.TokenService;
import com.github.yuyuvu.springhotelsbookingapp.booking.services.UserService;
import com.github.yuyuvu.springhotelsbookingapp.booking.support.ApiException;
import com.github.yuyuvu.springhotelsbookingapp.booking.support.BookingMapper;
import com.github.yuyuvu.springhotelsbookingapp.booking.support.PageDto;
import java.time.Clock;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Сервис отвечает за учётные записи, проверку паролей и получение токена при входе. Также
 * проверяет, что последнего активного администратора нельзя удалить или лишить роли.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserServiceImpl implements UserService {
  private final UserRepository users;
  private final BookingRepository bookings;
  private final PasswordEncoder passwords;
  private final TokenService tokens;
  private final BookingMapper mapper;
  private final Clock clock;
  private final org.springframework.jdbc.core.JdbcTemplate jdbc;

  /**
   * Метод для регистрации пользователя с обычной ролью и получения токена сразу после создания
   * учётной записи.
   */
  @Transactional
  @Override
  public BookingApi.Token register(BookingApi.Register r) {
    var user = create(new BookingApi.UserInput(null, r.username(), r.password(), "USER"));
    var entity = users.findById(user.id()).orElseThrow();
    return token(entity);
  }

  /**
   * Метод для проверки имени пользователя, пароля и активности аккаунта. Если данные корректны,
   * возвращается токен доступа.
   */
  @Override
  public BookingApi.Token login(BookingApi.Login input) {
    var user = users.findByUsername(normalize(input.username()));
    var hash =
        user.map(User::getPassword)
            .orElse("$2a$12$W1BhJrPC5ODG6eUrBzzS0uL8zzfvGLSuMJY5UXv7MsljM6J0.NVya");
    boolean valid = passwords.matches(input.password(), hash);
    if (user.isEmpty() || !valid || !user.get().isActive())
      throw new ApiException(
          HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "Неверное имя пользователя или пароль");
    return token(user.get());
  }

  /** Метод для создания учётной записи с указанной ролью и сохранения пароля в виде хеша. */
  @Transactional
  @Override
  public BookingApi.UserView create(BookingApi.UserInput input) {
    if (input.password() == null) throw ApiException.invalid("Необходимо указать пароль");
    checkPassword(input.password());
    var user = new User();
    user.setUsername(normalize(input.username()));
    user.setPassword(passwords.encode(input.password()));
    user.setRole(input.role());
    user.setCreatedAt(clock.instant());
    return mapper.user(users.saveAndFlush(user));
  }

  /**
   * Метод для изменения данных учётной записи и при необходимости её пароля. Дополнительно
   * проверяется, что последний активный администратор не потеряет свою роль.
   */
  @Transactional
  @Override
  public BookingApi.UserView update(long id, BookingApi.UserInput input) {
    jdbc.queryForObject("SELECT id FROM user_lock WHERE id=1 FOR UPDATE", Integer.class);
    var user = users.lock(id).orElseThrow(ApiException::missing);
    if (user.getRole().equals("ADMIN")
        && !input.role().equals("ADMIN")
        && users.countByRoleAndActiveTrue("ADMIN") <= 1)
      throw ApiException.conflict("Нельзя изменить роль последнего активного администратора");
    user.setUsername(normalize(input.username()));
    user.setRole(input.role());
    if (input.password() != null) {
      checkPassword(input.password());
      user.setPassword(passwords.encode(input.password()));
    }
    return mapper.user(users.saveAndFlush(user));
  }

  /**
   * Метод для отключения пользователя с сохранением связанных записей. Удаление запрещается для
   * последнего активного администратора и пользователя с действующими бронями.
   */
  @Transactional
  @Override
  public void delete(long id) {
    jdbc.queryForObject("SELECT id FROM user_lock WHERE id=1 FOR UPDATE", Integer.class);
    var user = users.lock(id).orElseThrow(ApiException::missing);
    if (!user.isActive()) return;
    if (user.getRole().equals("ADMIN") && users.countByRoleAndActiveTrue("ADMIN") <= 1)
      throw ApiException.conflict("Нельзя удалить последнего активного администратора");
    if (bookings.existsActive(id, java.time.LocalDate.now(clock)))
      throw ApiException.conflict("У пользователя есть действующие бронирования");
    user.setActive(false);
  }

  /** Метод для получения данных одного пользователя по идентификатору без передачи его пароля. */
  @Override
  public BookingApi.UserView get(long id) {
    return mapper.user(users.findById(id).orElseThrow(ApiException::missing));
  }

  /** Метод для получения страницы учётных записей с данными пользователей без паролей. */
  @Override
  public PageDto<BookingApi.UserView> list(Pageable page) {
    return PageDto.from(users.findAll(page).map(mapper::user));
  }

  /**
   * Получаем идентификатор пользователя из токена и проверяем, что его учётная запись всё ещё
   * активна.
   */
  @Override
  public long activeId(String subject) {
    try {
      long id = Long.parseLong(subject);
      if (!users.findById(id).filter(User::isActive).isPresent())
        throw new ApiException(
            HttpStatus.UNAUTHORIZED, "INACTIVE_USER", "Учётная запись пользователя неактивна");
      return id;
    } catch (NumberFormatException e) {
      throw new ApiException(
          HttpStatus.UNAUTHORIZED,
          "INVALID_SUBJECT",
          "Некорректный идентификатор пользователя в токене");
    }
  }

  /**
   * Метод для формирования ответа с пользовательским токеном на один час и данными учётной записи.
   */
  private BookingApi.Token token(User user) {
    return new BookingApi.Token(tokens.user(user), "Bearer", 3600, mapper.user(user));
  }

  /**
   * Убираем лишние пробелы по краям имени и приводим его к нижнему регистру, чтобы одинаковые имена
   * одинаково находились при входе.
   */
  private String normalize(String username) {
    return username.strip().toLowerCase(Locale.ROOT);
  }

  /** Проверяем, что пароль занимает не больше 72 байт в UTF-8, так как это ограничение BCrypt. */
  private void checkPassword(String password) {
    if (password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
      throw ApiException.invalid("Пароль должен занимать не более 72 байт в кодировке UTF-8");
  }
}
