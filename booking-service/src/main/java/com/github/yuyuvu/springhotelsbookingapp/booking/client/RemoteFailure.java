package com.github.yuyuvu.springhotelsbookingapp.booking.client;

/**
 * Исключение для ошибок при обращении к сервису отелей. Хранит код ответа и признак, указывающий,
 * можно ли повторить обращение.
 */
public class RemoteFailure extends RuntimeException {
  private final int status;
  private final boolean retryable;

  /**
   * Конструктор исключения для сохранения сообщения об ошибке удалённого сервиса и признака
   * допустимости повторного вызова.
   */
  public RemoteFailure(int status, String message, boolean retryable) {
    super(message);
    this.status = status;
    this.retryable = retryable;
  }

  /**
   * Метод для получения кода состояния, с которым должна быть обработана ошибка обращения к отелю.
   */
  public int status() {
    return status;
  }

  /** Метод для проверки, имеет ли смысл повторить обращение после этой ошибки. */
  public boolean retryable() {
    return retryable;
  }
}
