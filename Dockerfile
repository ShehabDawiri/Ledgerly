# ---- build ----------------------------------------------------------------
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

# Copy the build definition first so dependency resolution is cached independently
# of source changes.
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN chmod +x gradlew

COPY src src
# Tests run in CI, not in the image build.
RUN ./gradlew --no-daemon clean bootJar -x test

# ---- runtime --------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Run as an unprivileged user.
RUN addgroup -S ledgerly && adduser -S ledgerly -G ledgerly
COPY --from=build /workspace/build/libs/*.jar app.jar
RUN chown -R ledgerly:ledgerly /app
USER ledgerly

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=5 \
  CMD wget -qO- http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
