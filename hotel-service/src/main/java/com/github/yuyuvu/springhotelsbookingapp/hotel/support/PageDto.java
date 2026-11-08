package com.github.yuyuvu.springhotelsbookingapp.hotel.support;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * Объект с одной страницей результатов и сведениями об общем количестве записей. Используется для
 * одинакового формата списков в ответах API.
 */
public record PageDto<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
  /**
   * Метод для получения общего формата ответа из страницы результатов базы данных вместе с
   * количеством записей и страниц.
   */
  public static <T> PageDto<T> from(Page<T> page) {
    return new PageDto<>(
        page.getContent(),
        page.getNumber(),
        page.getSize(),
        page.getTotalElements(),
        page.getTotalPages());
  }

  /**
   * Проверяем номер и размер страницы, а также разрешённое поле и направление сортировки, после
   * чего создаём настройки получения списка.
   */
  public static org.springframework.data.domain.Pageable request(
      int page, int size, String sort, java.util.Set<String> allowed) {
    if (page < 0 || size < 1 || size > 100)
      throw ApiException.invalid("Параметр page должен быть не меньше 0, size - от 1 до 100");
    var p = sort.split(",", -1);
    if (!allowed.contains(p[0])
        || p.length > 2
        || (p.length == 2 && !p[1].equalsIgnoreCase("asc") && !p[1].equalsIgnoreCase("desc")))
      throw ApiException.invalid("Недопустимое поле или направление сортировки");
    var direction =
        p.length == 2 && p[1].equalsIgnoreCase("desc")
            ? org.springframework.data.domain.Sort.Direction.DESC
            : org.springframework.data.domain.Sort.Direction.ASC;
    return org.springframework.data.domain.PageRequest.of(
        page,
        size,
        org.springframework.data.domain.Sort.by(direction, p[0])
            .and(org.springframework.data.domain.Sort.by("id")));
  }
}
