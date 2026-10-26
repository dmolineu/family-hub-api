package com.familyhub.demo.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on the scheduled jobs (Google Calendar sync, OAuth state cleanup). One-off
 * commands such as {@code reset-password} set {@code familyhub.scheduling.enabled=false}
 * so they do not start a calendar sync alongside the running server.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "familyhub.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
