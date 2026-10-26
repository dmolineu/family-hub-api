package com.familyhub.demo.cli;

import com.familyhub.demo.FamilyHubApplication;
import com.familyhub.demo.service.AuthService;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;

/**
 * Starts the app without its web server or scheduled jobs to run
 * {@link PasswordResetCommand}, so a reset can be done with the same image, the
 * same database settings and the same password hashing as the server.
 */
public final class PasswordResetLauncher {

    private PasswordResetLauncher() {
    }

    /**
     * The container entry point: {@code reset-password <username>} with the real
     * terminal, quiet logging and no banner.
     *
     * @param args the full program arguments, starting with {@link PasswordResetCommand#NAME}
     * @return the process exit code
     */
    public static int launch(String[] args) {
        SpringApplicationBuilder app = new SpringApplicationBuilder(FamilyHubApplication.class)
                // logback-spring.xml sets com.familyhub to INFO, so it is quieted by name.
                .properties("spring.main.banner-mode=off", "logging.level.root=WARN",
                        "logging.level.com.familyhub=WARN");
        return launch(Arrays.asList(args).subList(1, args.length), PasswordPrompt.system(),
                System.out, System.err, app);
    }

    /**
     * Runs the command against an app built from {@code app}.
     *
     * @param args the arguments after {@link PasswordResetCommand#NAME}
     * @return the process exit code
     */
    public static int launch(List<String> args, PasswordPrompt prompt, PrintStream out, PrintStream err,
                             SpringApplicationBuilder app) {
        PasswordResetCommand command = new PasswordResetCommand((username, newPassword) -> {
            // Booted only after the input has been checked, so a typo costs nothing.
            try (ConfigurableApplicationContext context = app
                    .web(WebApplicationType.NONE)
                    .properties("familyhub.scheduling.enabled=false")
                    .run()) {
                context.getBean(AuthService.class).resetPassword(username, newPassword);
            }
        });
        return command.run(args, prompt, out, err);
    }
}
