FROM maven:3.9.12-amazoncorretto-21 AS build

WORKDIR /build

COPY pom.xml .

RUN mvn dependency:go-offline
COPY src ./src
RUN mvn package -DskipTests

#Run stage
FROM eclipse-temurin:21-jre-jammy
ARG JAR_FILE=/build/target/*.jar
COPY --from=build $JAR_FILE /opt/spring_ai_service/app.jar
ENTRYPOINT ["java", "-jar", "/opt/spring_ai_service/app.jar"]