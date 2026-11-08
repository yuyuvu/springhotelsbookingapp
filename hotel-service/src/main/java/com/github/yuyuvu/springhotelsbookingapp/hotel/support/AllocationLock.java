package com.github.yuyuvu.springhotelsbookingapp.hotel.support;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Класс для общей блокировки изменения календаря и сохранения отметок об отменённых бронях. Отметки
 * нужны, чтобы запрос подтверждения, пришедший после отмены, не занял номер повторно.
 */
@Component
@RequiredArgsConstructor
public class AllocationLock {
  private final EntityManager entityManager;

  /**
   * Блокируем общую строку до окончания текущей транзакции, чтобы проверка свободных дат и их
   * занятие выполнялись без вмешательства другого запроса.
   */
  public void acquire() {
    entityManager
        .createNativeQuery("SELECT id FROM room_lock WHERE id=1 FOR UPDATE")
        .getSingleResult();
  }

  /**
   * Метод для проверки того, что бронь уже была отменена и новый запрос подтверждения по ней
   * выполнять нельзя.
   */
  public boolean cancelled(java.util.UUID booking) {
    return !entityManager
        .createNativeQuery("SELECT id FROM cancelled_bookings WHERE id=:id")
        .setParameter("id", booking)
        .getResultList()
        .isEmpty();
  }

  /**
   * Сохраняем отметку об отмене всей брони, которая будет проверяться при запоздавших запросах
   * занятия номера.
   */
  public void cancel(java.util.UUID booking) {
    if (!cancelled(booking))
      entityManager
          .createNativeQuery("INSERT INTO cancelled_bookings(id) VALUES(:id)")
          .setParameter("id", booking)
          .executeUpdate();
  }
}
