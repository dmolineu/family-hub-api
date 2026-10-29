package com.familyhub.demo.cli;

import com.familyhub.demo.FamilyHubApplication;
import com.familyhub.demo.config.TestcontainersConfig;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.config.TaskManagementConfigUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Boots the reset command the way the container does, against the same test
 * database the server context uses, and checks the server sees the change.
 *
 * <p>The command's context gets the database address as plain properties and skips
 * every {@code @TestConfiguration}: if it held the shared container as a bean,
 * Spring Boot would stop the container when the command's context closed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class PasswordResetLauncherIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PostgreSQLContainer postgres;

    /** What {@code @SpringBootTest} does for test contexts, done by hand for this plain app. */
    private static final class SkipTestConfigurations extends TypeExcludeFilter {
        @Override
        public boolean match(MetadataReader reader, MetadataReaderFactory factory) {
            return reader.getAnnotationMetadata().hasAnnotation(TestConfiguration.class.getName());
        }
    }

    @Test
    void resetsThePasswordWithoutAWebServerOrScheduledJobs_andSignsOutEveryDevice() throws Exception {
        // Not System.nanoTime(): it counts from boot, and on a Mac awake for weeks its 16 digits
        // push the username past the 20-character limit.
        String username = "reset" + UUID.randomUUID().toString().substring(0, 8);
        String body = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "username": "%s",
                                    "password": "password123",
                                    "familyName": "Reset Family",
                                    "starterRecipes": false,
                                    "members": [ { "name": "Mom", "color": "coral" } ]
                                }
                                """.formatted(username)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String oldToken = JsonPath.read(body, "$.data.token");

        Deque<String> typed = new ArrayDeque<>(List.of("a-new-password", "a-new-password"));
        AtomicReference<ConfigurableApplicationContext> booted = new AtomicReference<>();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        // First in line so it beats application.yml's "dev", as SPRING_PROFILES_ACTIVE
        // does in the container.
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("reset-command-test", Map.of(
                "spring.profiles.active", "test",
                "spring.datasource.url", postgres.getJdbcUrl(),
                "spring.datasource.username", postgres.getUsername(),
                "spring.datasource.password", postgres.getPassword())));
        SpringApplicationBuilder app = new SpringApplicationBuilder(FamilyHubApplication.class)
                .environment(environment)
                .initializers(context -> context.getBeanFactory()
                        .registerSingleton("skipTestConfigurations", new SkipTestConfigurations()))
                .listeners((ApplicationListener<ApplicationPreparedEvent>) event ->
                        booted.set(event.getApplicationContext()));

        int exit = PasswordResetLauncher.launch(List.of(username), label -> Optional.ofNullable(typed.poll()),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), app);

        assertThat(exit).as(err.toString(StandardCharsets.UTF_8)).isZero();
        assertThat(booted.get()).isNotInstanceOf(WebServerApplicationContext.class);
        assertThat(booted.get().containsBeanDefinition(
                TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME)).isFalse();

        mockMvc.perform(get("/api/family").header("Authorization", "Bearer " + oldToken))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "username": "%s", "password": "a-new-password" }
                                """.formatted(username)))
                .andExpect(status().isOk());
    }
}
