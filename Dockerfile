FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw --batch-mode --no-transfer-progress dependency:go-offline
COPY src src
RUN ./mvnw --batch-mode --no-transfer-progress clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache curl \
    && addgroup -S agent \
    && adduser -S -G agent agent
WORKDIR /opt/wechat-agent-platform
COPY --from=build /workspace/target/wechat-agent-platform-1.0.0-boot.jar app.jar
RUN mkdir -p /var/lib/wechat-agent-platform \
    && chown -R agent:agent /opt/wechat-agent-platform /var/lib/wechat-agent-platform
USER agent
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/opt/wechat-agent-platform/app.jar"]
