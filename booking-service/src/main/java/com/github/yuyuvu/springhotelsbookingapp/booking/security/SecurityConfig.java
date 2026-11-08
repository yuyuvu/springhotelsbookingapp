package com.github.yuyuvu.springhotelsbookingapp.booking.security;

import com.github.yuyuvu.springhotelsbookingapp.booking.support.Errors;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.ObjectMapper;

/**
 * Класс с настройками безопасности приложения, проверкой токенов и правилами доступа к отдельным
 * запросам.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {
  /**
   * Метод для получения текущего времени в UTC, которое используется при проверке дат и выполнении
   * расчётов в приложении.
   */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  /**
   * Метод для создания компонента BCrypt, который сохраняет пароль в виде хеша и проверяет пароль
   * при входе.
   */
  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(12);
  }

  /**
   * Настраиваем проверку подписи токена по открытому ключу, а также проверку его срока действия,
   * издателя и назначения.
   */
  @Bean
  JwtDecoder jwtDecoder(@Value("${app.security.public-key}") Resource key) throws Exception {
    String pem =
        new String(key.getInputStream().readAllBytes(), StandardCharsets.US_ASCII)
            .replaceAll("-----[A-Z ]+-----", "")
            .replaceAll("\\s", "");
    var rsa =
        (RSAPublicKey)
            KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pem)));
    var decoder = NimbusJwtDecoder.withPublicKey(rsa).build();
    OAuth2TokenValidator<Jwt> audience =
        t ->
            ("service".equals(t.getClaimAsString("token_use"))
                    ? t.getAudience().contains("hotel-internal")
                        && "booking-service".equals(t.getSubject())
                    : "user".equals(t.getClaimAsString("token_use"))
                        && t.getAudience().contains("hotels-api"))
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer("hotels-booking"), audience));
    return decoder;
  }

  /**
   * Задаём правила доступа к запросам и проверку токенов, а также ответы для случаев запросов без
   * авторизации или с недостаточными правами.
   */
  @Bean
  SecurityFilterChain security(HttpSecurity http, ObjectMapper mapper) throws Exception {
    var roles = new JwtGrantedAuthoritiesConverter();
    roles.setAuthoritiesClaimName("roles");
    roles.setAuthorityPrefix("ROLE_");
    var scopes = new JwtGrantedAuthoritiesConverter();
    var converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(
        jwt -> {
          var a =
              new ArrayList<org.springframework.security.core.GrantedAuthority>(roles.convert(jwt));
          a.addAll(scopes.convert(jwt));
          return a;
        });
    return http.csrf(c -> c.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers(
                        "/actuator/health/**",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html",
                        "/api/auth/**",
                        "/user/register",
                        "/user/auth")
                    .permitAll()
                    .requestMatchers("/actuator/**")
                    .hasRole("ADMIN")
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(
            o ->
                o.jwt(j -> j.jwtAuthenticationConverter(converter))
                    .authenticationEntryPoint(
                        (r, s, e) -> {
                          s.setStatus(401);
                          s.setContentType("application/problem+json");
                          mapper.writeValue(
                              s.getOutputStream(),
                              Errors.response(
                                      org.springframework.http.HttpStatus.UNAUTHORIZED,
                                      "UNAUTHORIZED",
                                      "Требуется авторизация",
                                      r,
                                      Map.of())
                                  .getBody());
                        })
                    .accessDeniedHandler(
                        (r, s, e) -> {
                          s.setStatus(403);
                          s.setContentType("application/problem+json");
                          mapper.writeValue(
                              s.getOutputStream(),
                              Errors.response(
                                      org.springframework.http.HttpStatus.FORBIDDEN,
                                      "FORBIDDEN",
                                      "Недостаточно прав доступа",
                                      r,
                                      Map.of())
                                  .getBody());
                        }))
        .build();
  }
}
