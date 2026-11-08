package com.github.yuyuvu.springhotelsbookingapp.booking.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Объект User представляет собой учётную запись пользователя с именем, хешем пароля, ролью и
 * признаком активности.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class User {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 80)
  private String username;

  @Column(nullable = false, length = 100)
  private String password;

  private String role;
  private boolean active = true;
  private Instant createdAt;
  @Version private long version;
}
