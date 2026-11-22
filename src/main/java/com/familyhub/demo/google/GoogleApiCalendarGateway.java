package com.familyhub.demo.google;

import com.familyhub.demo.config.GoogleCalendarProperties;
import com.familyhub.demo.google.GoogleApiExceptionTranslator.Operation;
import com.familyhub.demo.model.GoogleOAuthToken;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.CalendarList;
import com.google.api.services.calendar.model.CalendarListEntry;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;
import com.google.api.services.calendar.model.Events;
import com.google.auth.http.HttpCredentialsAdapter;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

/**
 * The gateway that talks to Google Calendar's API (Phase 4). Each call builds a client
 * for one account from {@link GoogleCredentialsProvider}, with the configured connect and
 * read timeouts; every failure leaves as a {@link GoogleCalendarException}.
 */
public class GoogleApiCalendarGateway implements GoogleCalendarGateway {

    // Google's maximum page size; fewer round trips for a full sync.
    private static final int MAX_RESULTS = 2500;
    // Requests name instants in UTC, so they read the same whatever zone the host runs in.
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    @FunctionalInterface
    private interface GoogleCall<T> {
        T run(Calendar client) throws Exception;
    }

    private final GoogleCredentialsProvider credentials;
    private final HttpTransport transport;
    private final GoogleCalendarProperties properties;
    private final GoogleApiExceptionTranslator translator;

    /**
     * Creates the gateway.
     *
     * @param credentials credentials per signed-in account
     * @param transport   the HTTP transport (a mock one in tests)
     * @param properties  timeouts
     * @param translator  turns failures into {@link GoogleCalendarException}s
     */
    public GoogleApiCalendarGateway(GoogleCredentialsProvider credentials, HttpTransport transport,
                                    GoogleCalendarProperties properties, GoogleApiExceptionTranslator translator) {
        this.credentials = credentials;
        this.transport = transport;
        this.properties = properties;
        this.translator = translator;
    }

    @Override
    public EventPage listEvents(GoogleOAuthToken account, String calendarId, ListRequest request) {
        return call(account, Operation.LIST_EVENTS, client -> {
            Calendar.Events.List list = client.events().list(calendarId)
                    .setSingleEvents(false)
                    .setMaxResults(MAX_RESULTS)
                    .setPageToken(request.pageToken());
            if (request.sharedExtendedProperty() != null) {
                list.setSharedExtendedProperty(List.of(request.sharedExtendedProperty()));
            } else {
                // Cancelled occurrences are what hide one date of a series; a sync needs them.
                list.setShowDeleted(true);
            }
            if (request.syncToken() != null) {
                list.setSyncToken(request.syncToken());
            } else if (request.timeMin() != null) {
                list.setTimeMin(new DateTime(Date.from(request.timeMin()), UTC));
            }
            Events events = list.execute();
            List<Event> items = events.getItems() == null ? List.of() : events.getItems();
            return new EventPage(items, events.getNextPageToken(), events.getNextSyncToken());
        });
    }

    @Override
    public Event getEvent(GoogleOAuthToken account, String calendarId, String eventId) {
        return call(account, Operation.EVENT, client -> client.events().get(calendarId, eventId).execute());
    }

    @Override
    public Event insertEvent(GoogleOAuthToken account, String calendarId, Event event, boolean notifyGuests) {
        return call(account, Operation.EVENT, client -> client.events().insert(calendarId, event)
                .setSendUpdates(sendUpdates(notifyGuests))
                .execute());
    }

    @Override
    public Event updateEvent(GoogleOAuthToken account, String calendarId, String eventId, Event event,
                             String etag, boolean notifyGuests) {
        return call(account, Operation.EVENT, client -> {
            Calendar.Events.Update update = client.events().update(calendarId, eventId, event)
                    .setSendUpdates(sendUpdates(notifyGuests));
            if (etag != null) {
                update.getRequestHeaders().setIfMatch(etag);
            }
            return update.execute();
        });
    }

    @Override
    public void deleteEvent(GoogleOAuthToken account, String calendarId, String eventId, String etag) {
        call(account, Operation.EVENT, client -> {
            Calendar.Events.Delete delete = client.events().delete(calendarId, eventId)
                    .setSendUpdates(sendUpdates(false));
            if (etag != null) {
                delete.getRequestHeaders().setIfMatch(etag);
            }
            delete.execute();
            return null;
        });
    }

    @Override
    public Event getInstance(GoogleOAuthToken account, String calendarId, String masterEventId,
                             EventDateTime originalStart) {
        return call(account, Operation.EVENT, client -> {
            DateTime start = originalStart.getDateTime() != null ? originalStart.getDateTime() : originalStart.getDate();
            Events instances = client.events().instances(calendarId, masterEventId)
                    .setOriginalStart(start.toStringRfc3339())
                    .setMaxResults(1)
                    .execute();
            if (instances.getItems() == null || instances.getItems().isEmpty()) {
                throw new GoogleCalendarException(GoogleCalendarException.Kind.GONE,
                        "No occurrence of " + masterEventId + " starts at " + start, null, null);
            }
            return instances.getItems().getFirst();
        });
    }

    @Override
    public Event moveEvent(GoogleOAuthToken account, String calendarId, String eventId,
                           String destinationCalendarId) {
        return call(account, Operation.EVENT, client -> client.events()
                .move(calendarId, eventId, destinationCalendarId)
                .setSendUpdates(sendUpdates(false))
                .execute());
    }

    @Override
    public List<CalendarListEntry> listCalendars(GoogleOAuthToken account) {
        return call(account, Operation.CALENDAR_LIST, client -> {
            List<CalendarListEntry> entries = new ArrayList<>();
            String pageToken = null;
            do {
                // Hidden calendars too: the family may want one on the wall that is hidden on a phone.
                CalendarList page = client.calendarList().list()
                        .setShowHidden(true)
                        .setPageToken(pageToken)
                        .execute();
                if (page.getItems() != null) {
                    entries.addAll(page.getItems());
                }
                pageToken = page.getNextPageToken();
            } while (pageToken != null);
            return entries;
        });
    }

    private static String sendUpdates(boolean notifyGuests) {
        return notifyGuests ? "all" : "none";
    }

    private <T> T call(GoogleOAuthToken account, Operation operation, GoogleCall<T> work) {
        try {
            return work.run(client(account));
        } catch (Exception e) {
            throw translator.translate(e, operation);
        }
    }

    private Calendar client(GoogleOAuthToken account) {
        HttpCredentialsAdapter authorization = new HttpCredentialsAdapter(credentials.forAccount(account));
        int connect = Math.toIntExact(properties.getConnectTimeout().toMillis());
        int read = Math.toIntExact(properties.getReadTimeout().toMillis());
        HttpRequestInitializer initializer = request -> {
            authorization.initialize(request);
            request.setConnectTimeout(connect);
            request.setReadTimeout(read);
        };
        return new Calendar.Builder(transport, GsonFactory.getDefaultInstance(), initializer)
                .setApplicationName("FamilyHub")
                .build();
    }
}
