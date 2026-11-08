package com.github.yuyuvu.springhotelsbookingapp.booking.security;

import com.github.yuyuvu.springhotelsbookingapp.booking.entities.User;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

/**
 * Сервис для создания и подписания токенов доступа. Выдаёт пользовательские токены на час и
 * отдельные служебные токены для запросов к отелю.
 */
@Service
public class TokenService {
  private final JwtEncoder encoder;
  private final Clock clock;

  /** Конструктор загружает открытый и закрытый ключи и создаёт компонент для подписания токенов. */
  public TokenService(
      @Value("${app.security.private-key}") Resource privateKey,
      @Value("${app.security.public-key}") Resource publicKey,
      Clock clock)
      throws Exception {
    this.clock = clock;
    var factory = KeyFactory.getInstance("RSA");
    var priv = (RSAPrivateKey) factory.generatePrivate(new PKCS8EncodedKeySpec(bytes(privateKey)));
    var pub = (RSAPublicKey) factory.generatePublic(new X509EncodedKeySpec(bytes(publicKey)));
    encoder =
        new NimbusJwtEncoder(
            new ImmutableJWKSet<>(
                new JWKSet(
                    new com.nimbusds.jose.jwk.RSAKey.Builder(pub)
                        .privateKey(priv)
                        .keyID("demo-rsa-1")
                        .build())));
  }

  /** Получаем байты ключа из его текстового файла, убирая служебные строки и символы пробелов. */
  private byte[] bytes(Resource resource) throws java.io.IOException {
    try (var in = resource.getInputStream()) {
      return Base64.getDecoder()
          .decode(
              new String(in.readAllBytes(), StandardCharsets.US_ASCII)
                  .replaceAll("-----[A-Z ]+-----", "")
                  .replaceAll("\\s", ""));
    }
  }

  /**
   * Метод для создания токена пользователя со сроком действия один час, идентификатором владельца и
   * его ролью.
   */
  public String user(User user) {
    return issue(
        user.getId().toString(),
        "hotels-api",
        Duration.ofHours(1),
        Map.of("roles", List.of(user.getRole()), "token_use", "user"));
  }

  /**
   * Метод для создания служебного токена на две минуты, который позволяет сервису бронирований
   * занимать и освобождать номера.
   */
  public String service() {
    return issue(
        "booking-service",
        "hotel-internal",
        Duration.ofMinutes(2),
        Map.of("scope", "inventory.write", "token_use", "service"));
  }

  /**
   * Создаём содержимое токена с владельцем, назначением и временем действия, затем подписываем его
   * закрытым ключом.
   */
  private String issue(String subject, String audience, Duration ttl, Map<String, Object> claims) {
    var now = clock.instant();
    var builder =
        JwtClaimsSet.builder()
            .issuer("hotels-booking")
            .subject(subject)
            .audience(List.of(audience))
            .issuedAt(now)
            .notBefore(now)
            .expiresAt(now.plus(ttl))
            .id(UUID.randomUUID().toString());
    claims.forEach(builder::claim);
    return encoder
        .encode(
            JwtEncoderParameters.from(
                JwsHeader.with(
                        org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS256)
                    .build(),
                builder.build()))
        .getTokenValue();
  }
}
