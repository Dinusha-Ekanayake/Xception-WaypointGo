FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY backend/pom.xml ./
RUN mvn -q dependency:go-offline
COPY backend/src ./src
RUN mvn -q package -DskipTests

FROM eclipse-temurin:17-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /src/target/*-backend-*.jar /app/backend.jar
COPY data /app/data
COPY migrations /app/migrations
COPY frontend/public/assets /app/frontend/public/assets
ENV DATA_DIR=/app/data MIGRATIONS_DIR=/app/migrations
EXPOSE 8080
CMD ["java","-jar","/app/backend.jar"]
