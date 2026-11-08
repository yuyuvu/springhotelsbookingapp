# Для сборки используем Java 21 и указываем точный образ, чтобы при следующих запусках основа сборки оставалась той же.
FROM eclipse-temurin:21-jdk-noble@sha256:bd6ac06e2a2df928af21c75c4a8a0f2829a93377a65761d6a577bb32281e2ac3 AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw
COPY src src
COPY docker/Healthcheck.java /health/Healthcheck.java
# Выполняем сборку через Maven вместе с тестами и проверкой стиля, сохраняя скачанные зависимости для следующих сборок.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp verify && javac --release 21 /health/Healthcheck.java
# После сборки переносим готовый JAR и класс проверки готовности в отдельный образ для запуска приложения.
FROM eclipse-temurin:21-jre-noble@sha256:161310cd5bd41fd549d19cf9f90b614897cb4a1532555c613b43b7e0628fe5d6
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/target/booking-service-1.0.0.jar /app/application.jar
COPY --from=build /health/Healthcheck.class /health/Healthcheck.class
# Запускаем приложение от обычного пользователя контейнера, которому не нужны права администратора.
USER 10001:10001
EXPOSE 8082
# Задаём ограничение памяти для Java, кодировку UTF-8 и время в UTC, чтобы настройки совпадали у всех приложений.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -Dfile.encoding=UTF-8 -Duser.timezone=UTC"
# Проверяем готовность через HTTP, чтобы Compose запускал зависимые приложения после завершения инициализации этого сервиса.
HEALTHCHECK --interval=10s --timeout=5s --start-period=90s --retries=15 CMD ["java", "-cp", "/health", "Healthcheck", "http://localhost:8082/actuator/health/readiness"]
ENTRYPOINT ["java", "-jar", "/app/application.jar"]
