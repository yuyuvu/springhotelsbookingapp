package com.github.yuyuvu.springhotelsbookingapp.booking.repositories;

import com.github.yuyuvu.springhotelsbookingapp.booking.entities.User;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/**
 * Интерфейс для получения и сохранения учётных записей, поиска пользователя при входе и проверок
 * при изменении ролей.
 */
public interface UserRepository extends JpaRepository<User, Long> {
  /** Метод для получения учётной записи по имени, который используется при входе в приложение. */
  Optional<User> findByUsername(String username);

  /**
   * Метод для подсчёта активных пользователей с определённой ролью, чтобы защитить последнего
   * администратора от удаления или изменения роли.
   */
  long countByRoleAndActiveTrue(String role);

  /**
   * Получаем пользователя с блокировкой записи, чтобы изменение его данных и создание брони не
   * выполнялись несогласованно.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select u from User u where u.id=:id")
  Optional<User> lock(long id);
}
