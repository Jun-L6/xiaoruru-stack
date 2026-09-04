# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jre-jammy AS runtime-base
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl tzdata \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 rurublog \
    && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin rurublog \
    && install -d -o 10001 -g 10001 -m 0750 /data \
    && install -d -m 0755 /app
WORKDIR /app
COPY --chmod=755 container/entrypoint.sh container/healthcheck.sh /app/
ENV BLOG_DATA_DIR=/data \
    SERVER_PORT=8080 \
    TZ=Asia/Shanghai \
    JAVA_TOOL_OPTIONS="-Xms64m -Xmx320m -XX:MaxMetaspaceSize=160m -XX:ReservedCodeCacheSize=64m -XX:MaxDirectMemorySize=64m -Xss512k -XX:+UseSerialGC -XX:ActiveProcessorCount=2 -XX:+ExitOnOutOfMemoryError"
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=10s --start-period=90s --retries=3 \
    CMD ["/app/healthcheck.sh"]
ENTRYPOINT ["/app/entrypoint.sh"]
LABEL org.opencontainers.image.title="rurublog" \
      org.opencontainers.image.description="小茹茹博客 - 轻量级 AI 个人技术博客"

# Low-memory deployment: build the JAR locally, then choose --target prebuilt.
FROM runtime-base AS prebuilt
COPY --chmod=644 target/rurublog.jar /app/rurublog.jar

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
ENV MAVEN_OPTS="-Xmx512m -XX:MaxMetaspaceSize=256m"
COPY pom.xml ./
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp verify

FROM runtime-base AS runtime
COPY --from=build --chmod=644 /build/target/rurublog.jar /app/rurublog.jar
