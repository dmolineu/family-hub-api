package com.familyhub.demo.cli;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Where the reset command reads a new password from. A terminal hides what is
 * typed; piped input (for scripts) is read one line per prompt.
 */
@FunctionalInterface
public interface PasswordPrompt {

    /**
     * Asks for one password.
     *
     * @return what was entered, or empty when the input ended first
     */
    Optional<String> read(String label);

    /** The real terminal when there is one, otherwise standard input. */
    static PasswordPrompt system() {
        Console console = System.console();
        if (console != null) {
            return label -> Optional.ofNullable(console.readPassword("%s: ", label)).map(String::new);
        }
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        return label -> {
            try {
                return Optional.ofNullable(in.readLine());
            } catch (IOException e) {
                throw new UncheckedIOException("Could not read the password from standard input", e);
            }
        };
    }
}
