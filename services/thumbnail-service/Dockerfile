FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

# Runtime — needs fontconfig + a real TTF for AWT text rendering
FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends \
        fontconfig fonts-dejavu-core \
    && rm -rf /var/lib/apt/lists/*
# Headless AWT + bounded heap (AWT canvases live on-heap and fit comfortably
# in 768m — code-review 2026-06-10).
ENV JAVA_TOOL_OPTIONS="-Djava.awt.headless=true -Xmx768m"
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8086
ENTRYPOINT ["java","-jar","/app/app.jar"]
