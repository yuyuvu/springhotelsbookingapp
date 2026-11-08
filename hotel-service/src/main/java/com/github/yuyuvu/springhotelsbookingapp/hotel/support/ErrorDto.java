package com.github.yuyuvu.springhotelsbookingapp.hotel.support;

import java.time.Instant;
import java.util.Map;

/**
 * Объект с данными ошибки, которые возвращаются клиенту в едином формате, содержит в том числе
 * сообщение, время и идентификатор запроса.
 */
public record ErrorDto(
    String type,
    String title,
    int status,
    String code,
    String detail,
    String instance,
    Instant timestamp,
    String traceId,
    Map<String, String> errors) {}
