package com.github.yuyuvu.springhotelsbookingapp.system;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Вспомогательный класс для запуска приложений в отдельных процессах во время интеграционных
 * тестов. Также ожидает их готовности и завершает процессы после проверок.
 */
final class Applications implements AutoCloseable {
  private final List<Process> processes = new ArrayList<>();
  private final Path logs;

  /**
   * Конструктор создаёт отдельную папку журналов для процессов текущего набора интеграционных
   * тестов.
   */
  Applications(String suite) throws Exception {
    logs = Path.of("target", "process-logs", suite);
    Files.createDirectories(logs);
  }

  /**
   * Метод для получения свободного локального порта, чтобы тестовые приложения не зависели от
   * заранее заданных портов.
   */
  static int port() throws Exception {
    try (var socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  /**
   * Запускаем собранное приложение в отдельном процессе Java и ожидаем его готовности за
   * ограниченное время. Если процесс завершается раньше, показываем журнал с причиной ошибки.
   */
  void start(String service, int port, String... options) throws Exception {
    String location = System.getenv("APP_JARS");
    var jar =
        location == null
            ? Path.of("..", service, "target", service + "-1.0.0.jar")
            : Path.of(location, service + ".jar");
    if (!Files.isRegularFile(jar))
      throw new IllegalStateException("Не найден собранный сервис: " + jar);
    var args =
        new ArrayList<>(
            List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xms32m",
                "-Xmx192m",
                "-XX:MaxMetaspaceSize=160m",
                "-Dfile.encoding=UTF-8",
                "-Duser.timezone=UTC",
                "-jar",
                jar.toAbsolutePath().toString(),
                "--server.port=" + port));
    args.addAll(List.of(options));
    var log = logs.resolve(service + ".log");
    var process =
        new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    processes.add(process);
    await(
        () -> {
          if (!process.isAlive())
            throw new AssertionError(service + " завершился: " + Files.readString(log));
          try (var api = new Api(port)) {
            return api.get("/actuator/health/readiness", 200, 503).status() == 200;
          }
        },
        Duration.ofSeconds(90));
  }

  /**
   * Метод для получения текста журнала отдельного тестового приложения, чтобы проверить
   * произошедшие события или выяснить причину сбоя.
   */
  String log(String service) throws Exception {
    return Files.readString(logs.resolve(service + ".log"));
  }

  /**
   * Повторяем проверку условия до успешного результата или истечения времени ожидания. Последняя
   * полученная ошибка сохраняется для сообщения о проваленном ожидании.
   */
  static void await(Condition condition, Duration timeout) throws Exception {
    long deadline = System.nanoTime() + timeout.toNanos();
    Exception last = null;
    while (System.nanoTime() < deadline) {
      try {
        if (condition.test()) return;
      } catch (Exception exception) {
        last = exception;
      }
      Thread.sleep(100);
    }
    throw new AssertionError("Условие не выполнено за " + timeout, last);
  }

  /**
   * Завершаем запущенные приложения в обратном порядке и принудительно останавливаем процесс, если
   * он не завершился за отведённое время.
   */
  @Override
  public void close() throws Exception {
    for (var process : processes.reversed()) {
      process.destroy();
      if (!process.waitFor(10, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        process.waitFor(5, TimeUnit.SECONDS);
      }
    }
  }

  /** Интерфейс для условия, выполнения которого нужно дождаться в тесте за ограниченное время. */
  @FunctionalInterface
  interface Condition {
    /** Метод для проверки того, наступило ли ожидаемое событие в интеграционном тесте. */
    boolean test() throws Exception;
  }
}
