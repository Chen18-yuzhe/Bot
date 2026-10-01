FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY vendor vendor
RUN ./mvnw --batch-mode --no-transfer-progress dependency:go-offline
COPY src src
RUN ./mvnw --batch-mode --no-transfer-progress clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache curl nodejs npm \
    && addgroup -S agent \
    && adduser -S -G agent agent \
    && mkdir -p /opt/wechat-agent-platform /var/lib/wechat-agent-platform
COPY --from=build /workspace/target/wechat-agent-platform-1.0.0-boot.jar /opt/wechat-agent-platform/app.jar
RUN chown -R agent:agent /opt/wechat-agent-platform /var/lib/wechat-agent-platform
WORKDIR /var/lib/wechat-agent-platform
USER agent
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/opt/wechat-agent-platform/app.jar"]
