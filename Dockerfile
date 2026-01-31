FROM maven:3.9.12-amazoncorretto-21 AS build
COPY pom.xml /build/
WORKDIR /build
#RUN mvn dependency:go-offline
COPY src /build/src
RUN mvn package -DskipTests

#Run stage
FROM eclipse-temurin:21-jdk-jammy
ARG JAR_FILE=/build/target/*.jar
COPY --from=build $JAR_FILE /opt/spring_ai_service/app.jar
ENTRYPOINT ["java", "-jar", "/opt/spring_ai_service/app.jar"]