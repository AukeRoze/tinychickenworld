FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
# Bound the heap explicitly: without -Xmx the JVM sizes itself off the HOST's
# RAM, and with 8 sibling JVMs on one machine that adds up under pressure.
ENV JAVA_TOOL_OPTIONS="-Xmx1g"
ENTRYPOINT ["java","-jar","/app/app.jar"]
