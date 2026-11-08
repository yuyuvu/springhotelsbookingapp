# Для подготовки тестов используем Java 21 и тот же закреплённый образ, что и при сборке приложений.
FROM eclipse-temurin:21-jdk-noble@sha256:bd6ac06e2a2df928af21c75c4a8a0f2829a93377a65761d6a577bb32281e2ac3 AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY src src
# Сначала компилируем тесты и сохраняем Maven вместе с зависимостями, чтобы проверка могла выполняться без скачивания из сети.
# Выполнение самих тестов оставляем на запуск контейнера, когда в нём уже будут все собранные приложения.
RUN --mount=type=cache,target=/root/.m2 sh ./mvnw -B -ntp -DskipTests verify && maven_bin="$(find /root/.m2/wrapper/dists -type f -path '*/bin/mvn' -print -quit)" && cp -a "${maven_bin%/bin/mvn}" /opt/maven && cp -a /root/.m2/repository /opt/m2
# Используем JDK и при запуске тестов, так как для проверки нужны Maven и отдельные тестируемые Java-приложения сервисов
FROM eclipse-temurin:21-jdk-noble@sha256:bd6ac06e2a2df928af21c75c4a8a0f2829a93377a65761d6a577bb32281e2ac3
WORKDIR /workspace
COPY --from=build /opt /opt
COPY --from=build /workspace/pom.xml ./
COPY --from=build /workspace/src ./src
# Получаем готовые приложения из образов соответствующих сервисов, чтобы не выполнять повторную сборку их исходников здесь.
COPY --from=hotel_image /app/application.jar /apps/hotel-service.jar
COPY --from=booking_image /app/application.jar /apps/booking-service.jar
COPY --from=gateway_image /app/application.jar /apps/api-gateway.jar
COPY --from=discovery_image /app/application.jar /apps/discovery-server.jar
# Тесты запускаются обычным пользователем, а результаты и служебные данные сохраняются во временные папки, заданные в Compose.
USER 10001:10001
ENV APP_JARS=/apps
ENV HOME=/tmp
ENV MAVEN_OPTS="-Xmx256m -Duser.home=/tmp -Dfile.encoding=UTF-8"
# Запускаем проверки при старте контейнера, чтобы ошибка любого теста завершала его с неуспешным результатом.
ENTRYPOINT ["/opt/maven/bin/mvn", "-B", "-ntp", "-o", "-Dmaven.repo.local=/opt/m2", "verify"]
