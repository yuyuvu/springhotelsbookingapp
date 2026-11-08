package com.github.yuyuvu.springhotelsbookingapp.apigateway.security;

import com.github.yuyuvu.springhotelsbookingapp.apigateway.support.GatewayErrors;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Класс для проверки пользовательских токенов на входе в систему и запрета внешнего доступа к
 * внутренним операциям сервисов.
 */
@Configuration
public class GatewaySecurity {
  /**
   * Метод для настройки проверки подписи токена по открытому ключу, его срока действия и назначения
   * для данного API.
   */
  @Bean
  ReactiveJwtDecoder jwtDecoder(@Value("${app.security.public-key}") Resource key)
      throws Exception {
    String pem;
    try (var in = key.getInputStream()) {
      pem =
          new String(in.readAllBytes(), StandardCharsets.US_ASCII)
              .replaceAll("-----[A-Z ]+-----", "")
              .replaceAll("\\s", "");
    }
    var rsa =
        (RSAPublicKey)
            KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pem)));
    var decoder = NimbusReactiveJwtDecoder.withPublicKey(rsa).build();
    OAuth2TokenValidator<Jwt> audience =
        t ->
            t.getAudience().contains("hotels-api") && "user".equals(t.getClaimAsString("token_use"))
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer("hotels-booking"), audience));
    return decoder;
  }

  /**
   * Оставляем открытыми вход и документацию, запрещаем использование внутренних путей и требуем
   * пользовательский токен для остальных запросов.
   */
  @Bean
  SecurityWebFilterChain security(ServerHttpSecurity http, GatewayErrors errors) {
    return http.csrf(c -> c.disable())
        .httpBasic(c -> c.disable())
        .formLogin(c -> c.disable())
        .authorizeExchange(
            a ->
                a.pathMatchers(
                        "/internal/**", "/api/rooms/*/confirm-availability", "/api/rooms/*/release")
                    .denyAll()
                    .pathMatchers(
                        "/actuator/health/**",
                        "/api/auth/register",
                        "/api/auth/login",
                        "/user/register",
                        "/user/auth",
                        "/docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html",
                        "/webjars/**",
                        "/v3/api-docs/**")
                    .permitAll()
                    .pathMatchers("/actuator/**")
                    .denyAll()
                    .anyExchange()
                    .authenticated())
        .oauth2ResourceServer(
            o ->
                o.jwt(org.springframework.security.config.Customizer.withDefaults())
                    .authenticationEntryPoint(
                        (e, x) -> errors.write(e, 401, "UNAUTHORIZED", "Требуется авторизация"))
                    .accessDeniedHandler(
                        (e, x) -> errors.write(e, 403, "FORBIDDEN", "Недостаточно прав доступа")))
        .build();
  }
}
