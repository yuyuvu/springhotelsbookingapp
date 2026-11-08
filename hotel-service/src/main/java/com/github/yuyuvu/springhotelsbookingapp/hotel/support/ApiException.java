package com.github.yuyuvu.springhotelsbookingapp.hotel.support;

import org.springframework.http.HttpStatus;

/**
 * Исключение для ожидаемых ошибок при работе с API. Содержит сообщение для пользователя, код ошибки
 * и код состояния ответа.
 */
public class ApiException extends RuntimeException {
  private final HttpStatus status;
  private final String code;

  /**
   * Конструктор исключения с сообщением для клиента и кодами, которые нужны при формировании ответа
   * API.
   */
  public ApiException(HttpStatus status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  /**
   * Метод для получения кода состояния, который должен быть возвращён клиенту вместе с данной
   * ошибкой.
   */
  public HttpStatus status() {
    return status;
  }

  /** Метод для получения постоянного кода ошибки, по которому клиент может определить её тип. */
  public String code() {
    return code;
  }

  /**
   * Метод для создания ошибки в случае конфликта запроса с текущими данными или состоянием записи.
   */
  public static ApiException conflict(String message) {
    return new ApiException(HttpStatus.CONFLICT, "CONFLICT", message);
  }

  /**
   * Метод для создания ошибки, когда запись не найдена или не принадлежит текущему пользователю.
   */
  public static ApiException missing() {
    return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Ресурс не найден");
  }

  /** Метод для создания ошибки проверки некорректных данных запроса. */
  public static ApiException invalid(String message) {
    return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", message);
  }
}
