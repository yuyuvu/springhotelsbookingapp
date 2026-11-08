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
 * Объект Room представляет собой отдельный номер отеля с его данными и счётчиком бронирований.
 * Признак доступности относится к возможности эксплуатации, а занятость на даты определяется по
 * календарю.
 */
@Entity
@Table(name = "rooms")
@Getter
@Setter
@NoArgsConstructor
public class Room {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long hotelId;

  @Column(nullable = false, length = 30)
  private String number;

  private boolean available;
  private long timesBooked;
  private boolean archived;
  @Version private long version;

  /**
   * Конструктор для создания номера, который относится к определённому отелю и имеет признак
   * доступности для проживания.
   */
  public Room(Long hotelId, String number, boolean available) {
    this.hotelId = hotelId;
    this.number = number;
    this.available = available;
  }
}
