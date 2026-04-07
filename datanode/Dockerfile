# ── Stage 1: build ──────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src

# Copy parent POM + shared-model + datanode sources
COPY pom.xml .
COPY shared-model/pom.xml shared-model/
COPY shared-model/src     shared-model/src
COPY datanode/pom.xml     datanode/
COPY datanode/src         datanode/src

# Build only the datanode (shared-model compiled as dependency)
RUN mvn -pl shared-model,datanode -am package -DskipTests -q

# ── Stage 2: runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=build /src/datanode/target/datanode-1.0.0-SNAPSHOT.jar app.jar

# Data directory — mount a volume here for persistence
VOLUME ["/data"]

EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
