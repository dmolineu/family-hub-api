package com.familyhub.demo.dto;

import java.util.UUID;

/** An address, beyond the members' own, allowed to send to the inbox. */
public record InboxSenderResponse(UUID id, String address) {
}
