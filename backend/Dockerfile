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
# Version de l'image, exposée par /actuator/info (info.app.version) ; `dev` en développement local.
ARG VERSION=dev
ENV APP_VERSION=${VERSION}
COPY --from=build --chown=backyard:backyard /construction/application.jar application.jar
USER backyard:backyard
EXPOSE 8080
# Tas plafonné à 60 % de la mémoire du conteneur (mem_limit en production) : le reste couvre la mémoire hors tas
# (métaspace, piles des threads, tampons) sans dépasser le plafond. Sortie immédiate sur OutOfMemoryError :
# Docker relance api (restart: unless-stopped) au lieu de laisser une JVM dégradée.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-XX:+ExitOnOutOfMemoryError", "-jar", "/application/application.jar"]
