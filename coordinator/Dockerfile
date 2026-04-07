# ── Stage 1: build ──────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src

# Copy parent POM + shared-model + coordinator sources
COPY pom.xml .
COPY shared-model/pom.xml shared-model/
COPY shared-model/src     shared-model/src
COPY coordinator/pom.xml  coordinator/
COPY coordinator/src      coordinator/src

# Build only the coordinator (shared-model compiled as dependency)
RUN mvn -pl shared-model,coordinator -am package -DskipTests -q

# ── Stage 2: runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=build /src/coordinator/target/coordinator-1.0.0-SNAPSHOT.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
