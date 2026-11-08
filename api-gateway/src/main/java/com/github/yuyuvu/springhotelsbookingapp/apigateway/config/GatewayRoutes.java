package com.github.yuyuvu.springhotelsbookingapp.apigateway.config;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Класс с настройкой маршрутов шлюза, который определяет нужный сервис по адресу входящего запроса.
 */
@Configuration
public class GatewayRoutes {
  /** Настраиваем передачу запросов нужным сервисам по адресам из Eureka. */
  @Bean
  RouteLocator routes(RouteLocatorBuilder b) {
    return b.routes()
        .route(
            "booking",
            r ->
                r.path(
                        "/api/auth/**",
                        "/api/users/**",
                        "/api/bookings/**",
                        "/booking/**",
                        "/bookings",
                        "/user/**")
                    .filters(
                        f ->
                            f.removeRequestHeader("X-User-Id")
                                .removeRequestHeader("X-User-Roles")
                                .setRequestSize(65536L))
                    .uri("lb://booking-service"))
        .route(
            "hotel",
            r ->
                r.path("/api/hotels/**", "/api/rooms/**", "/api/admin/rooms/**")
                    .filters(
                        f ->
                            f.removeRequestHeader("X-User-Id")
                                .removeRequestHeader("X-User-Roles")
                                .setRequestSize(65536L))
                    .uri("lb://hotel-service"))
        .route(
            "booking-docs",
            r ->
                r.path("/docs/booking")
                    .filters(f -> f.setPath("/v3/api-docs"))
                    .uri("lb://booking-service"))
        .route(
            "hotel-docs",
            r ->
                r.path("/docs/hotel")
                    .filters(f -> f.setPath("/v3/api-docs"))
                    .uri("lb://hotel-service"))
        .build();
  }
}
