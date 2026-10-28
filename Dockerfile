# Stage 1: Build the JAR
FROM eclipse-temurin:21 AS build
WORKDIR /app

# Copy mvn wrapper from repo (one source of truth)
COPY .mvn .mvn

# Copy pom.xml to cache dependency downloads.
COPY mvnw pom.xml ./

# Docker caches each layer — if pom.xml hasn't changed,
# Maven won't re-download dependencies on subsequent builds.
RUN ./mvnw dependency:go-offline -B

# Now copy source and build
COPY src ./src
RUN ./mvnw clean package -DskipTests -B

# Stage 2: Run (JRE only, no Maven or source code)
FROM eclipse-temurin:21-jre
WORKDIR /app
# create a system user with no shell
RUN useradd -r -s /bin/false appuser
# Photos are files under /photos. Created here and owned by the app user so a named
# volume mounted there starts writable; a NAS share mounted there must allow it too.
RUN mkdir -p /photos/library && chown -R appuser /photos
# from pom.xml :: <finalName>app</finalName>
COPY --from=build /app/target/app.jar app.jar
USER appuser
EXPOSE 8080
# Production is the only profile that makes sense in a container: the default `dev`
# profile uses an in-memory H2 database and loses everything on restart.
ENV SPRING_PROFILES_ACTIVE=prod
# JVM flags are overridable per host (a Pi wants -XX:+UseSerialGC and a bigger heap
# than a 512 MB VPS). The shell wrapper is what lets JAVA_OPTS expand; "$@" passes
# container arguments through, so `docker compose run --rm api reset-password <username>`
# runs that command instead of the server.
ENV JAVA_OPTS="-Xmx256m -Xms128m"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar \"$@\"", "--"]
