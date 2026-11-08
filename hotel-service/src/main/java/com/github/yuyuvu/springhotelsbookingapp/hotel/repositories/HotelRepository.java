package com.github.yuyuvu.springhotelsbookingapp.hotel.repositories;

import com.github.yuyuvu.springhotelsbookingapp.hotel.entities.Hotel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Интерфейс для работы с сохранёнными отелями, в том числе для поиска и вывода списков по заданным
 * условиям.
 */
public interface HotelRepository
    extends JpaRepository<Hotel, Long>, JpaSpecificationExecutor<Hotel> {}
