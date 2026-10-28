package com.familyhub.demo.dto;

import java.time.Instant;
import java.util.List;

/**
 * The family inbox at a glance: its address and whether it is being read, who may send
 * to it, how many proposals wait for a parent, and the latest imports.
 */
public record InboxStatusResponse(boolean configured, String address, boolean readerAvailable, Instant lastReadAt,
                                  String lastError, List<String> memberAddresses,
                                  List<InboxSenderResponse> extraSenders, long waiting,
                                  List<InboxImportResponse> imports) {
}
