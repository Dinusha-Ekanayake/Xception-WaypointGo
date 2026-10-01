# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY backend/pom.xml ./
COPY backend/src ./src
# The Maven cache survives between builds without a go-offline step, which
# failed on plugins resolved only at package time.
RUN --mount=type=cache,target=/root/.m2 mvn -q -B package -DskipTests

FROM eclipse-temurin:17-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /src/target/*-backend-*.jar /app/backend.jar
COPY data /app/data
COPY migrations /app/migrations
COPY frontend/public/assets /app/frontend/public/assets
ENV DATA_DIR=/app/data MIGRATIONS_DIR=/app/migrations PROOF_DIR=/var/lib/waypoint/proofs
# Proof of delivery is evidence. The directory is created owned by the runtime
# user so a named volume mounted over it starts with the same ownership.
RUN useradd --system --uid 10001 --home /app waypoint \
    && mkdir -p /var/lib/waypoint/proofs && chown -R waypoint /var/lib/waypoint
USER waypoint
VOLUME /var/lib/waypoint/proofs
EXPOSE 8080
CMD ["java","-jar","/app/backend.jar"]
