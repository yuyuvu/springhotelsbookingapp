package com.github.yuyuvu.springhotelsbookingapp.hotel.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Объект Hotel представляет собой отель с названием, адресом и признаком архивирования.
 * Архивирование позволяет сохранить связанные данные, когда отель убирается из списка доступных.
 */
@Entity
@Table(name = "hotels")
@Getter
@Setter
@NoArgsConstructor
public class Hotel {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 160)
  private String name;

  @Column(nullable = false, length = 500)
  private String address;

  private boolean archived;
  @Version private long version;

  /** Конструктор для создания отеля с основными данными перед его сохранением в базу. */
  public Hotel(String name, String address) {
    this.name = name;
    this.address = address;
  }
}
