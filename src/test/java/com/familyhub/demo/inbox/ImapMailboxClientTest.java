package com.familyhub.demo.inbox;

import com.familyhub.demo.config.InboxProperties;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.activation.DataHandler;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class ImapMailboxClientTest {
    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.IMAP);

    private GreenMailUser hub;
    private ImapMailboxClient client;

    @BeforeEach
    void setUp() {
        hub = greenMail.setUser("hub@localhost", "hub", "secret");
        InboxProperties properties = new InboxProperties();
        properties.setImapHost("localhost");
        properties.setImapPort(ServerSetupTest.IMAP.getPort());
        properties.setImapSsl(false);
        properties.setUsername("hub");
        properties.setPassword("secret");
        client = new ImapMailboxClient(properties);
    }

    private MimeMessage flyer() throws Exception {
        MimeMessage message = new KeepsItsId();
        message.setFrom(new InternetAddress("Dad <dad@example.com>"));
        message.setRecipients(Message.RecipientType.TO, "hub@localhost");
        message.setSubject("Soccer schedule #event");
        message.setHeader("Message-ID", "<flyer-1@example.com>");
        message.setHeader("In-Reply-To", "<earlier@hub>");
        message.addHeader("Authentication-Results", "mx.google.com; dmarc=pass header.from=example.com");
        message.addHeader("Authentication-Results", "other.example; dmarc=fail");
        MimeBodyPart text = new MimeBodyPart();
        text.setText("Games every Saturday at 9am.", "UTF-8");
        MimeBodyPart pdf = new MimeBodyPart();
        pdf.setDataHandler(new DataHandler(new ByteArrayDataSource(new byte[] {'%', 'P', 'D', 'F'}, "application/pdf")));
        pdf.setFileName("schedule.pdf");
        message.setContent(new MimeMultipart(text, pdf));
        return message;
    }

    @Test
    void handsOverEachMessageWithItsHeadersTextAndAttachmentsThenFilesItUnderTheLabel() throws Exception {
        hub.deliver(flyer());
        List<ReceivedMail> seen = new ArrayList<>();
        List<MailContent> contents = new ArrayList<>();

        client.processInbox(10, mail -> {
            seen.add(mail);
            contents.add(mail.content());
            return "FamilyHub-Processed";
        });

        assertThat(seen).hasSize(1);
        ReceivedMail mail = seen.getFirst();
        assertThat(mail.messageId()).isEqualTo("<flyer-1@example.com>");
        assertThat(mail.from()).isEqualTo("dad@example.com");
        assertThat(mail.subject()).isEqualTo("Soccer schedule #event");
        assertThat(mail.inReplyTo()).isEqualTo("<earlier@hub>");
        assertThat(mail.authenticationResults()).containsExactly(
                "mx.google.com; dmarc=pass header.from=example.com", "other.example; dmarc=fail");
        MailContent content = contents.getFirst();
        assertThat(content.text()).contains("Games every Saturday at 9am.");
        assertThat(content.attachments()).singleElement().satisfies(attachment -> {
            assertThat(attachment.fileName()).isEqualTo("schedule.pdf");
            assertThat(attachment.contentType()).isEqualTo("application/pdf");
            assertThat(attachment.bytes()).containsExactly('%', 'P', 'D', 'F');
        });

        // Filed: out of the inbox, into the label.
        assertThat(count("INBOX")).isZero();
        assertThat(count("FamilyHub-Processed")).isEqualTo(1);
    }

    @Test
    void aMessageWithoutALabelStaysInTheInbox() throws Exception {
        hub.deliver(flyer());

        client.processInbox(10, mail -> null);

        assertThat(count("INBOX")).isEqualTo(1);
    }

    @Test
    void takesAtMostTheGivenNumberOfMessagesAtATime() throws Exception {
        for (int i = 0; i < 3; i++) {
            MimeMessage message = flyer();
            message.setHeader("Message-ID", "<flyer-" + i + "@example.com>");
            hub.deliver(message);
        }
        List<String> ids = new ArrayList<>();

        client.processInbox(2, mail -> {
            ids.add(mail.messageId());
            return "FamilyHub-Processed";
        });

        assertThat(ids).hasSize(2);
        assertThat(count("INBOX")).isEqualTo(1);
    }

    private int count(String folderName) throws Exception {
        Properties props = new Properties();
        Session session = Session.getInstance(props);
        try (Store store = session.getStore("imap")) {
            store.connect("localhost", ServerSetupTest.IMAP.getPort(), "hub", "secret");
            Folder folder = store.getFolder(folderName);
            if (!folder.exists()) {
                return 0;
            }
            folder.open(Folder.READ_ONLY);
            try {
                return folder.getMessageCount();
            } finally {
                folder.close(false);
            }
        }
    }

    /** GreenMail's deliver() saves the message, which would replace its Message-ID. */
    private static final class KeepsItsId extends MimeMessage {
        KeepsItsId() {
            super(Session.getInstance(new Properties()));
        }

        @Override
        protected void updateMessageID() {
            // Keep the Message-ID the test set, as a real sender's message keeps its own.
        }
    }
}
