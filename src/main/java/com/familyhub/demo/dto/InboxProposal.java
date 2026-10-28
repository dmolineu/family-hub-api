package com.familyhub.demo.dto;

import java.util.List;

/** An event the inbox read but did not add: what a parent reviews before approving. */
public record InboxProposal(String title, String date, String endDate, boolean allDay, String startTime,
                            String endTime, String location, List<String> people, String uncertainty) {
}
