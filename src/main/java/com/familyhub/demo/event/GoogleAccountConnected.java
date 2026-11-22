package com.familyhub.demo.event;

import java.util.UUID;

/**
 * A member connected Google, or connected it again (Phase 4). After the commit the hub
 * reads that account's calendar list and syncs the family's calendars.
 *
 * @param tokenId the signed-in account's id
 */
public record GoogleAccountConnected(UUID tokenId) {
}
