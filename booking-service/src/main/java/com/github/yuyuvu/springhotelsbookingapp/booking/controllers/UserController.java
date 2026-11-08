package com.github.yuyuvu.springhotelsbookingapp.booking.controllers;

import com.github.yuyuvu.springhotelsbookingapp.booking.services.UserService;
import com.github.yuyuvu.springhotelsbookingapp.booking.support.ApiException;
import com.github.yuyuvu.springhotelsbookingapp.booking.support.PageDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
 * Контроллер для регистрации, входа и операций администратора с учётными записями пользователей.
 */
@RestController
@Tag(name = "Пользователи и вход")
@RequiredArgsConstructor
public class UserController {
  private final UserService users;

  /**
   * Метод для регистрации пользователя с обычной ролью и получения токена сразу после создания
   * учётной записи.
   */
  @PostMapping({"/api/auth/register", "/user/register"})
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Регистрация обычного пользователя с ролью USER и получением JWT")
  public BookingApi.Token register(@Valid @RequestBody BookingApi.Register r) {
    return users.register(r);
  }

  /**
   * Метод для проверки имени пользователя, пароля и активности аккаунта. Если данные корректны,
   * возвращается токен доступа.
   */
  @PostMapping({"/api/auth/login", "/user/auth"})
  @Operation(
      summary =
          "Вход по логину и паролю, например user1 / user1password, user2 / user2password или admin / adminpassword")
  public BookingApi.Token login(@Valid @RequestBody BookingApi.Login r) {
    return users.login(r);
  }

  /** Метод для создания учётной записи с указанной ролью и сохранения пароля в виде хеша. */
  @PostMapping({"/api/users", "/user"})
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "ADMIN: создание учётной записи пользователя")
  public BookingApi.UserView create(@Valid @RequestBody BookingApi.UserInput r) {
    return users.create(r);
  }

  /** Метод для получения страницы учётных записей с данными пользователей без паролей. */
  @GetMapping("/api/users")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "ADMIN: получение списка пользователей по страницам")
  public PageDto<BookingApi.UserView> list(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "id,asc") String sort) {
    return users.list(PageDto.request(page, size, sort, Set.of("id", "username", "createdAt")));
  }

  /** Метод для получения данных одного пользователя по идентификатору без передачи его пароля. */
  @GetMapping("/api/users/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "ADMIN: получение данных отдельного пользователя")
  public BookingApi.UserView get(@PathVariable long id) {
    return users.get(id);
  }

  /**
   * Метод для изменения данных учётной записи и при необходимости её пароля. Дополнительно
   * проверяется, что последний активный администратор не потеряет свою роль.
   */
  @PatchMapping("/api/users/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "ADMIN: изменение данных учётной записи")
  public BookingApi.UserView update(
      @PathVariable long id, @Valid @RequestBody BookingApi.UserInput r) {
    return users.update(id, r);
  }

  /**
   * Метод для изменения пользователя через дополнительный путь с передачей идентификатора в теле
   * запроса. Использует тот же сервис и проверки, что и основной путь.
   */
  @PatchMapping("/user")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(
      summary = "ADMIN: изменение пользователя (дубль /api/users/{id}, id передаётся в теле)")
  public BookingApi.UserView legacyUpdate(@Valid @RequestBody BookingApi.UserInput r) {
    if (r.id() == null) throw ApiException.invalid("Необходимо указать id");
    return users.update(r.id(), r);
  }

  /**
   * Метод для отключения пользователя с сохранением связанных записей. Удаление запрещается для
   * последнего активного администратора и пользователя с действующими бронями.
   */
  @DeleteMapping("/api/users/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "ADMIN: отключение учётной записи с сохранением истории")
  public void delete(@PathVariable long id) {
    users.delete(id);
  }

  /**
   * Метод для отключения пользователя через дополнительный путь с идентификатором в параметре
   * запроса. Для него действуют обычные проверки удаления.
   */
  @DeleteMapping("/user")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(
      summary = "ADMIN: отключение пользователя (дубль /api/users/{id}, id передаётся в параметре)")
  public void legacyDelete(@RequestParam long id) {
    users.delete(id);
  }
}
