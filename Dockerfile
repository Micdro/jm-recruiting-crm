# One image runs the whole CRM: Spring Boot serves the API and the built React app.

# 1. Build the React app
FROM node:22-alpine AS frontend
WORKDIR /app/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# 2. Build the Spring Boot jar with the React app inside
FROM eclipse-temurin:21-jdk AS backend
WORKDIR /app/backend
COPY backend/.mvn .mvn
COPY backend/mvnw backend/pom.xml ./
COPY backend/src src
COPY --from=frontend /app/frontend/dist src/main/resources/static
RUN chmod +x mvnw \
    && ./mvnw -B -q -DskipTests package \
    && cp target/jm-recruiting-crm-*.jar app.jar

# 3. Run it
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --create-home crm
COPY --from=backend /app/backend/app.jar app.jar
USER crm
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
