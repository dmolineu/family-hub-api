package com.familyhub.demo;

import com.familyhub.demo.cli.PasswordResetCommand;
import com.familyhub.demo.cli.PasswordResetLauncher;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

// TODO: Configure a bounded ThreadPoolTaskExecutor bean for @Async (default SimpleAsyncTaskExecutor
//  creates unbounded threads). Also consider a custom AsyncUncaughtExceptionHandler to log/track
//  unexpected failures in async sync operations.
// Scheduling is switched on in config.SchedulingConfig so one-off commands can leave it off.
@SpringBootApplication
@EnableAsync
public class FamilyHubApplication {

	/**
	 * Starts the server, or runs {@code reset-password <username>} when that is the
	 * first argument.
	 */
	public static void main(String[] args) {
		if (args.length > 0 && PasswordResetCommand.NAME.equals(args[0])) {
			System.exit(PasswordResetLauncher.launch(args));
		}
		SpringApplication.run(FamilyHubApplication.class, args);
	}

}
