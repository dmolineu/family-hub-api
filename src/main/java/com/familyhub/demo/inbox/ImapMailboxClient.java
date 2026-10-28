package com.familyhub.demo.inbox;

import com.familyhub.demo.config.InboxProperties;
import jakarta.mail.Address;
import jakarta.mail.BodyPart;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;

/**
 * The mailbox over IMAP (Gmail's imap.gmail.com with an app password). Filing a message
 * copies it into the label's folder and removes it from the inbox, which in Gmail is
 * archiving it with that label.
 */
@Slf4j
@Component
public class ImapMailboxClient implements MailboxClient {
    /** Larger attachments are left out rather than read into memory. */
    private static final long MAX_ATTACHMENT_BYTES = 25L * 1024 * 1024;

    private final InboxProperties properties;

    public ImapMailboxClient(InboxProperties properties) {
        this.properties = properties;
    }

    @Override
    public void processInbox(int max, Function<ReceivedMail, String> handler) throws MessagingException {
        String protocol = properties.isImapSsl() ? "imaps" : "imap";
        Properties settings = new Properties();
        settings.put("mail." + protocol + ".connectiontimeout", String.valueOf(properties.getConnectTimeout().toMillis()));
        settings.put("mail." + protocol + ".timeout", String.valueOf(properties.getReadTimeout().toMillis()));
        Session session = Session.getInstance(settings);
        try (Store store = session.getStore(protocol)) {
            store.connect(properties.getImapHost(), properties.getImapPort(), properties.getUsername(),
                    properties.getPassword());
            Folder inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_WRITE);
            try {
                Message[] messages = inbox.getMessages();
                Map<String, List<Message>> filed = new LinkedHashMap<>();
                for (Message message : Arrays.copyOf(messages, Math.min(max, messages.length))) {
                    String label = handle(handler, (MimeMessage) message);
                    if (label != null) {
                        filed.computeIfAbsent(label, key -> new ArrayList<>()).add(message);
                    }
                }
                for (Map.Entry<String, List<Message>> entry : filed.entrySet()) {
                    Folder target = store.getFolder(entry.getKey());
                    if (!target.exists()) {
                        target.create(Folder.HOLDS_MESSAGES);
                    }
                    Message[] batch = entry.getValue().toArray(Message[]::new);
                    inbox.copyMessages(batch, target);
                    for (Message message : batch) {
                        message.setFlag(Flags.Flag.DELETED, true);
                    }
                }
            } finally {
                inbox.close(true);
            }
        }
    }

    /** One message; a failure leaves it in the inbox and does not stop the others. */
    private static String handle(Function<ReceivedMail, String> handler, MimeMessage message) {
        try {
            return handler.apply(new ImapReceivedMail(message));
        } catch (RuntimeException e) {
            log.warn("Inbox message left in the inbox after an error: {}", e.toString());
            return null;
        }
    }

    /**
     * The headers are read at once (they are small, and stay usable after the mailbox
     * closes); the body and attachments only when asked for, while it is open.
     */
    private static final class ImapReceivedMail implements ReceivedMail {
        private final MimeMessage message;
        private final String messageId;
        private final String from;
        private final String subject;
        private final String inReplyTo;
        private final List<String> authenticationResults;
        private MailContent content;

        ImapReceivedMail(MimeMessage message) {
            this.message = message;
            this.from = readFrom(message);
            this.subject = readSubject(message);
            this.inReplyTo = header(message, "In-Reply-To");
            this.authenticationResults = readAuthenticationResults(message);
            String id = header(message, "Message-ID");
            // Without one, a stand-in that stays the same for the same message.
            this.messageId = id != null ? id
                    : "<no-id-" + Integer.toHexString((from + "|" + subject + "|" + sent(message)).hashCode()) + ">";
        }

        @Override
        public String messageId() {
            return messageId;
        }

        @Override
        public String from() {
            return from;
        }

        @Override
        public String subject() {
            return subject;
        }

        @Override
        public String inReplyTo() {
            return inReplyTo;
        }

        @Override
        public List<String> authenticationResults() {
            return authenticationResults;
        }

        private static String readFrom(MimeMessage message) {
            try {
                Address[] from = message.getFrom();
                if (from == null || from.length == 0 || !(from[0] instanceof InternetAddress address)) {
                    return "";
                }
                return address.getAddress() == null ? "" : address.getAddress().toLowerCase(Locale.ROOT);
            } catch (MessagingException e) {
                return "";
            }
        }

        private static String readSubject(MimeMessage message) {
            try {
                return message.getSubject() == null ? "" : message.getSubject();
            } catch (MessagingException e) {
                return "";
            }
        }

        private static List<String> readAuthenticationResults(MimeMessage message) {
            try {
                String[] values = message.getHeader("Authentication-Results");
                return values == null ? List.of()
                        : Arrays.stream(values).map(value -> value.replaceAll("\\s+", " ").trim()).toList();
            } catch (MessagingException e) {
                return List.of();
            }
        }

        private static String sent(MimeMessage message) {
            try {
                return String.valueOf(message.getSentDate());
            } catch (MessagingException e) {
                return "";
            }
        }

        private static String header(MimeMessage message, String name) {
            try {
                String value = message.getHeader(name, null);
                return value == null ? null : value.replaceAll("\\s+", " ").trim();
            } catch (MessagingException e) {
                return null;
            }
        }

        @Override
        public MailContent content() {
            if (content == null) {
                StringBuilder text = new StringBuilder();
                StringBuilder html = new StringBuilder();
                List<MailContent.Attachment> attachments = new ArrayList<>();
                try {
                    collect(message, text, html, attachments);
                } catch (MessagingException | IOException e) {
                    throw new IllegalStateException("The message could not be read: " + e.getMessage(), e);
                }
                String body = !text.isEmpty() ? text.toString() : Jsoup.parse(html.toString()).text();
                content = new MailContent(body.strip(), List.copyOf(attachments));
            }
            return content;
        }

        private static void collect(Part part, StringBuilder text, StringBuilder html,
                                    List<MailContent.Attachment> attachments) throws MessagingException, IOException {
            String type = new ContentType(part.getContentType()).getBaseType().toLowerCase(Locale.ROOT);
            String fileName = part.getFileName();
            boolean attached = Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())
                    || (fileName != null && !type.startsWith("multipart/"));
            if (type.startsWith("multipart/")) {
                Multipart multipart = (Multipart) part.getContent();
                for (int i = 0; i < multipart.getCount(); i++) {
                    BodyPart child = multipart.getBodyPart(i);
                    collect(child, text, html, attachments);
                }
            } else if (type.equals("message/rfc822") && part.getContent() instanceof Part forwarded) {
                // A message forwarded as an attachment: its text and files are part of this one.
                collect(forwarded, text, html, attachments);
            } else if (!attached && type.equals("text/plain")) {
                text.append(part.getContent()).append('\n');
            } else if (!attached && type.equals("text/html")) {
                html.append(part.getContent()).append('\n');
            } else if (part.getSize() <= MAX_ATTACHMENT_BYTES) {
                try (InputStream in = part.getInputStream()) {
                    byte[] bytes = in.readNBytes((int) MAX_ATTACHMENT_BYTES + 1);
                    if (bytes.length <= MAX_ATTACHMENT_BYTES) {
                        attachments.add(new MailContent.Attachment(fileName == null ? "attachment" : fileName,
                                type, bytes));
                    }
                }
            }
        }
    }
}
