# Production image for the API (docs/hosting.md). Build context: backend/.
# Tests run in CI, not here, so the image builds without Docker-in-Docker for Testcontainers.

FROM eclipse-temurin:25-jdk-noble AS build
WORKDIR /src
# Wrapper and build scripts first, so the Gradle download and dependencies cache across code changes.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY app/build.gradle.kts app/
COPY valuation-core/build.gradle.kts valuation-core/
RUN chmod +x gradlew && ./gradlew --no-daemon --console=plain :app:dependencies > /dev/null
COPY . .
RUN ./gradlew --no-daemon --console=plain :app:bootJar -x test \
    && cp "$(find app/build/libs -name '*.jar' ! -name '*-plain.jar')" /src/app.jar

# glibc base (not Alpine): OpenCV's bundled native library needs it.
FROM eclipse-temurin:25-jre-noble
RUN groupadd --system app && useradd --system --gid app --home-dir /app app
WORKDIR /app
COPY --from=build /src/app.jar app.jar
USER app
EXPOSE 8080
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080 && printf 'GET /actuator/health HTTP/1.0\\r\\n\\r\\n' >&3 && grep -q '\"UP\"' <&3"]
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "/app/app.jar"]
