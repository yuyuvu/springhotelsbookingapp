package com.github.yuyuvu.springhotelsbookingapp.discoveryserver.security;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/** Класс с настройками проверки JWT в реестре сервисов. */
@Configuration
public class RegistryJwt {
  /**
   * Метод для настройки проверки подписи токена по открытому ключу, его срока действия и назначения
   * для данного API.
   */
  @Bean
  JwtDecoder jwtDecoder(@Value("${JWT_PUBLIC_KEY:classpath:keys/public.pem}") Resource key)
      throws Exception {
    String pem;
    try (var in = key.getInputStream()) {
      pem =
          new String(in.readAllBytes(), StandardCharsets.US_ASCII)
              .replaceAll("-----[A-Z ]+-----", "")
              .replaceAll("\\s", "");
    }
    var publicKey =
        (RSAPublicKey)
            KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pem)));
    var decoder = NimbusJwtDecoder.withPublicKey(publicKey).build();
    OAuth2TokenValidator<Jwt> audience =
        jwt ->
            jwt.getAudience().contains("hotels-api")
                    && "user".equals(jwt.getClaimAsString("token_use"))
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer("hotels-booking"), audience));
    return decoder;
  }
}
