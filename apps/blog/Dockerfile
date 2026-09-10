# syntax=docker/dockerfile:1

ARG BLOG_SOURCE_HASH=unversioned

FROM eclipse-temurin:21-jre-jammy AS runtime-base
ARG BLOG_SOURCE_HASH
RUN groupadd --gid 10001 rurublog \
    && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin rurublog \
    && install -d -o 10001 -g 10001 -m 0750 /data \
    && install -d -m 0755 /app
WORKDIR /app
COPY --chmod=755 container/entrypoint.sh container/healthcheck.sh /app/
ENV BLOG_DATA_DIR=/data \
    SERVER_PORT=8080 \
    TZ=Asia/Shanghai \
    JAVA_TOOL_OPTIONS="-Xms48m -Xmx224m -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=32m -XX:MaxDirectMemorySize=32m -Xss512k -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -XX:TieredStopAtLevel=1 -XX:+ExitOnOutOfMemoryError"
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=10s --start-period=90s --retries=3 \
    CMD ["/app/healthcheck.sh"]
ENTRYPOINT ["/app/entrypoint.sh"]
LABEL org.opencontainers.image.title="rurublog" \
      org.opencontainers.image.description="小茹茹博客 - 轻量级、local-first 的 AI 个人博客" \
      beer.xiaoruru.blog.source-hash="${BLOG_SOURCE_HASH}"

# Low-memory deployment: build the JAR locally, then choose --target prebuilt.
FROM runtime-base AS prebuilt
COPY --chmod=644 target/rurublog.jar /app/rurublog.jar
RUN java -Djarmode=tools -jar /app/rurublog.jar list-layers >/dev/null

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
ENV MAVEN_OPTS="-Xmx384m -XX:MaxMetaspaceSize=192m"
COPY pom.xml ./
COPY src ./src
ARG BLOG_BUILD_TESTS=false
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    if [ "$BLOG_BUILD_TESTS" = "true" ]; then \
        mvn -B -ntp verify; \
    else \
        mvn -B -ntp -Dmaven.test.skip=true package; \
    fi

FROM runtime-base AS runtime
COPY --from=build --chmod=644 /build/target/rurublog.jar /app/rurublog.jar
RUN java -Djarmode=tools -jar /app/rurublog.jar list-layers >/dev/null
