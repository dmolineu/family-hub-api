package com.familyhub.demo.inbox;

import java.util.List;

/** A plain-text email the inbox sends back. */
public record Reply(String to, List<String> cc, String subject, String inReplyTo, String text) {
}
