package com.familyhub.demo.cli;

import com.familyhub.demo.exception.BadRequestException;
import com.familyhub.demo.exception.ResourceNotFoundException;

import java.io.PrintStream;
import java.util.List;
import java.util.Optional;

/**
 * {@code reset-password <username>}: sets a family's sign-in password from the
 * server when nobody remembers it. Asks for the new password twice and signs out
 * every device. The app has no forgotten-password screen on purpose; whoever runs
 * the server is the way back in.
 */
public class PasswordResetCommand {

    /** The first program argument that selects this command instead of the server. */
    public static final String NAME = "reset-password";

    /** Applies a reset; in production this boots the app's database layer. */
    @FunctionalInterface
    public interface Resetter {
        /** Sets {@code username}'s password, or throws if the family or password is refused. */
        void reset(String username, String newPassword);
    }

    private final Resetter resetter;

    /** @param resetter applies the reset once the input has been checked */
    public PasswordResetCommand(Resetter resetter) {
        this.resetter = resetter;
    }

    /**
     * Runs the command.
     *
     * @param args the arguments after {@link #NAME}
     * @return the process exit code: 0 done, 1 refused, 2 wrong usage
     */
    public int run(List<String> args, PasswordPrompt prompt, PrintStream out, PrintStream err) {
        // Exactly one argument: a password given on the command line would end up in
        // shell history and in the process list, so it is always asked for instead.
        if (args.size() != 1 || args.getFirst().isBlank()) {
            err.println("Usage: reset-password <username>");
            err.println("It asks for the new password twice; never put it on the command line.");
            return 2;
        }
        String username = args.getFirst();

        Optional<String> first = prompt.read("New password");
        Optional<String> second = first.isPresent() ? prompt.read("Same password again") : Optional.empty();
        if (first.isEmpty() || second.isEmpty()) {
            err.println("No password entered. Nothing was changed.");
            return 1;
        }
        if (!first.get().equals(second.get())) {
            err.println("The two passwords don't match. Nothing was changed.");
            return 1;
        }

        try {
            resetter.reset(username, first.get());
        } catch (ResourceNotFoundException | BadRequestException e) {
            err.println(e.getMessage());
            return 1;
        }
        out.println("Password changed for " + username + ". Every device has to sign in again with the new one.");
        return 0;
    }
}
