package com.github.yuyuvu.springhotelsbookingapp.discoveryserver.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Класс для настройки доступа к Eureka по отдельному служебному логину и паролю. Проверка
 * готовности реестра доступна без входа.
 */
@Configuration
public class RegistrySecurity {
  /**
   * Метод для создания служебного пользователя реестра с паролем, сохранённым в виде хеша BCrypt.
   */
  @Bean
  UserDetailsService registryUser(
      @Value("${app.registry.username}") String user,
      @Value("${app.registry.password}") String password) {
    var encoder = new BCryptPasswordEncoder(12);
    return new InMemoryUserDetailsManager(
        User.withUsername(user)
            .password("{bcrypt}" + encoder.encode(password))
            .roles("REGISTRY")
            .build());
  }

  /**
   * Настраиваем доступ к реестру по служебному логину и паролю, оставляя открытой проверку его
   * готовности.
   */
  @Bean
  SecurityFilterChain security(HttpSecurity http) throws Exception {
    var roles = new JwtGrantedAuthoritiesConverter();
    roles.setAuthoritiesClaimName("roles");
    roles.setAuthorityPrefix("ROLE_");
    var converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(roles);
    return http.csrf(c -> c.ignoringRequestMatchers("/eureka/**"))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers("/actuator/health/**")
                    .permitAll()
                    .requestMatchers("/eureka/**")
                    .hasRole("REGISTRY")
                    .anyRequest()
                    .hasAnyRole("REGISTRY", "ADMIN"))
        .httpBasic(org.springframework.security.config.Customizer.withDefaults())
        .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter)))
        .build();
  }
}
