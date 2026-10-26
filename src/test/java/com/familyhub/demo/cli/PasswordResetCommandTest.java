package com.familyhub.demo.cli;

import com.familyhub.demo.exception.BadRequestException;
import com.familyhub.demo.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordResetCommandTest {

    private final List<List<String>> resets = new ArrayList<>();
    private final List<String> labels = new ArrayList<>();
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private PasswordPrompt answers(String... lines) {
        Deque<String> remaining = new ArrayDeque<>(List.of(lines));
        return label -> {
            labels.add(label);
            return Optional.ofNullable(remaining.poll());
        };
    }

    private int run(PasswordResetCommand.Resetter resetter, List<String> args, PasswordPrompt prompt) {
        return new PasswordResetCommand(resetter).run(args, prompt,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private int run(List<String> args, PasswordPrompt prompt) {
        return run((username, password) -> resets.add(List.of(username, password)), args, prompt);
    }

    private String out() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return err.toString(StandardCharsets.UTF_8);
    }

    @Test
    void withoutAUsername_printsUsage_andChangesNothing() {
        int exit = run(List.of(), answers());

        assertThat(exit).isEqualTo(2);
        assertThat(err()).contains("Usage: reset-password <username>");
        assertThat(labels).isEmpty();
        assertThat(resets).isEmpty();
    }

    @Test
    void aPasswordOnTheCommandLine_isRefused_soItNeverLandsInShellHistory() {
        int exit = run(List.of("molineus", "hunter22hunter"), answers());

        assertThat(exit).isEqualTo(2);
        assertThat(err()).contains("Usage: reset-password <username>")
                .contains("asks for the new password");
        assertThat(resets).isEmpty();
    }

    @Test
    void asksTwice_thenResets_andSaysEveryDeviceMustSignInAgain() {
        int exit = run(List.of("molineus"), answers("a-new-password", "a-new-password"));

        assertThat(exit).isZero();
        assertThat(labels).containsExactly("New password", "Same password again");
        assertThat(resets).containsExactly(List.of("molineus", "a-new-password"));
        assertThat(out()).contains("Password changed for molineus.")
                .contains("Every device has to sign in again with the new one.");
    }

    @Test
    void passwordsThatDoNotMatch_changeNothing() {
        int exit = run(List.of("molineus"), answers("a-new-password", "a-new-pasword"));

        assertThat(exit).isEqualTo(1);
        assertThat(err()).contains("The two passwords don't match. Nothing was changed.");
        assertThat(resets).isEmpty();
    }

    @Test
    void inputThatEndsEarly_changesNothing() {
        int exit = run(List.of("molineus"), answers("a-new-password"));

        assertThat(exit).isEqualTo(1);
        assertThat(err()).contains("No password entered. Nothing was changed.");
        assertThat(resets).isEmpty();
    }

    @Test
    void anUnknownFamily_isExplained() {
        int exit = run((username, password) -> {
            throw new ResourceNotFoundException("No family signs in as \"nobody\".");
        }, List.of("nobody"), answers("a-new-password", "a-new-password"));

        assertThat(exit).isEqualTo(1);
        assertThat(err()).contains("No family signs in as \"nobody\".");
        assertThat(out()).doesNotContain("Password changed");
    }

    @Test
    void aRefusedPassword_isExplained() {
        int exit = run((username, password) -> {
            throw new BadRequestException("A password is 8 to 100 characters.");
        }, List.of("molineus"), answers("short", "short"));

        assertThat(exit).isEqualTo(1);
        assertThat(err()).contains("A password is 8 to 100 characters.");
    }
}
