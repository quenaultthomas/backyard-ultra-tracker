# syntax=docker/dockerfile:1

# --- Etape de build : JDK 25 ---
FROM eclipse-temurin:25.0.4.1_1-jdk-noble AS build
WORKDIR /construction

# Dependances d'abord, pour profiter du cache des couches.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline

COPY src src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -DskipTests package \
    && cp target/backyard-ultra-tracker-*.jar application.jar

# --- Etape d'execution : JRE 25, utilisateur non root ---
FROM eclipse-temurin:25.0.4.1_1-jre-noble
RUN groupadd --system --gid 10001 backyard \
    && useradd --system --uid 10001 --gid backyard --no-create-home --shell /usr/sbin/nologin backyard
WORKDIR /application
COPY --from=build --chown=backyard:backyard /construction/application.jar application.jar
USER backyard:backyard
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/application/application.jar"]
