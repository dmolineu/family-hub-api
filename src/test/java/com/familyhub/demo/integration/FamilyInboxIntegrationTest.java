package com.familyhub.demo.integration;

import com.familyhub.demo.config.TestcontainersConfig;
import com.familyhub.demo.inbox.MailReader;
import com.familyhub.demo.inbox.MailReaderException;
import com.familyhub.demo.inbox.MailReading;
import com.familyhub.demo.inbox.ReadingRequest;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.InboxSender;
import com.familyhub.demo.repository.FamilyRepository;
import com.familyhub.demo.repository.InboxMessageRepository;
import com.familyhub.demo.repository.InboxSenderRepository;
import com.familyhub.demo.scheduler.InboxPoller;
import com.familyhub.demo.service.HeicDecoder;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.jayway.jsonpath.JsonPath;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The family inbox, against a real IMAP server: who may send, what an emailed photo
 * becomes, and where each message is filed afterwards.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, FamilyInboxIntegrationTest.SwitchableHeicDecoder.class,
        FamilyInboxIntegrationTest.ScriptedReader.class})
@ActiveProfiles("test")
class FamilyInboxIntegrationTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP_IMAP);

    private static final String FAMILY = "inbox_family";
    private static final String PASSWORD = "TestPassword123!";
    private static final String DAD = "dad@example.com";
    private static final String MOM = "mom@example.com";
    private static final String GMAIL_VERIFIED = "mx.google.com; dkim=pass header.i=@example.com;"
            + " dmarc=pass header.from=example.com";

    @DynamicPropertySource
    static void inbox(DynamicPropertyRegistry registry) {
        registry.add("inbox.imap-host", () -> "localhost");
        registry.add("inbox.imap-port", () -> ServerSetupTest.IMAP.getPort());
        registry.add("inbox.imap-ssl", () -> "false");
        registry.add("inbox.username", () -> "hub");
        registry.add("inbox.address", () -> "hub@localhost");
        registry.add("inbox.smtp-host", () -> "localhost");
        registry.add("inbox.smtp-port", () -> ServerSetupTest.SMTP.getPort());
        registry.add("inbox.smtp-ssl", () -> "false");
        registry.add("inbox.password", () -> "secret");
        registry.add("inbox.family-username", () -> FAMILY);
        // Readings happen when a test asks, never on their own.
        registry.add("inbox.initial-delay", () -> "PT1H");
        registry.add("inbox.poll-interval", () -> "PT1H");
    }

    /** Stands in for heif-dec: switched on, any HEIC "decodes" to a JPEG. */
    @TestConfiguration
    static class SwitchableHeicDecoder {
        static final AtomicBoolean AVAILABLE = new AtomicBoolean(false);

        @Bean
        @Primary
        HeicDecoder switchableHeicDecoder() {
            return new HeicDecoder() {
                @Override
                public boolean available() {
                    return AVAILABLE.get();
                }

                @Override
                public byte[] toJpeg(Path heic) throws IOException {
                    try {
                        return jpeg(4032, 3024);
                    } catch (Exception e) {
                        throw new IOException(e);
                    }
                }
            };
        }
    }

    /** Stands in for Claude: answers with whatever the test scripted, and counts its calls. */
    @TestConfiguration
    static class ScriptedReader {
        static final AtomicBoolean AVAILABLE = new AtomicBoolean(true);
        static final AtomicReference<MailReading> NEXT = new AtomicReference<>();
        static final AtomicReference<MailReaderException> FAIL_ONCE = new AtomicReference<>();
        static final AtomicInteger CALLS = new AtomicInteger();

        @Bean
        @Primary
        MailReader scriptedMailReader() {
            return new MailReader() {
                @Override
                public boolean available() {
                    return AVAILABLE.get();
                }

                @Override
                public MailReading read(ReadingRequest request) throws MailReaderException {
                    CALLS.incrementAndGet();
                    MailReaderException failure = FAIL_ONCE.getAndSet(null);
                    if (failure != null) {
                        throw failure;
                    }
                    return NEXT.get();
                }
            };
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InboxPoller poller;

    @Autowired
    private FamilyRepository familyRepository;

    @Autowired
    private InboxSenderRepository senderRepository;

    @Autowired
    private InboxMessageRepository messageRepository;

    private GreenMailUser hub;
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        hub = greenMail.setUser("hub@localhost", "hub", "secret");
        token = signIn();
    }

    @AfterEach
    void reset() {
        SwitchableHeicDecoder.AVAILABLE.set(false);
        ScriptedReader.AVAILABLE.set(true);
        ScriptedReader.NEXT.set(null);
        ScriptedReader.FAIL_ONCE.set(null);
        ScriptedReader.CALLS.set(0);
    }

    @Test
    void aParentsEmailedPhotoJoinsTheLibraryAndTheMessageIsFiled() throws Exception {
        int before = photoCount();
        // iPhone Mail signs every message; a signature alone is not a message to read.
        hub.deliver(mail(DAD, GMAIL_VERIFIED, "<beach-1@example.com>", "Beach day",
                "Sent from my iPhone", jpegPart("IMG_0001.JPG", jpeg(3000, 2000))));

        poller.pollNow();

        assertThat(photoCount()).isEqualTo(before + 1);
        assertThat(count("INBOX")).isZero();
        assertThat(count("FamilyHub-Processed")).isEqualTo(1);

        // The same message delivered again (a resend, a second copy) is not imported twice.
        hub.deliver(mail(DAD, GMAIL_VERIFIED, "<beach-1@example.com>", "Beach day", "",
                jpegPart("IMG_0001.JPG", jpeg(3000, 2000))));
        poller.pollNow();
        assertThat(photoCount()).isEqualTo(before + 1);
        // ...and it is filed like the first, not left to be tried again every reading.
        assertThat(count("INBOX")).isZero();
        assertThat(count("FamilyHub-Processed")).isEqualTo(2);
    }

    @Test
    void anIphoneHeicPhotoIsConvertedWhenTheServerCanDecodeIt() throws Exception {
        SwitchableHeicDecoder.AVAILABLE.set(true);
        int before = photoCount();
        MimeBodyPart heic = new MimeBodyPart();
        heic.setDataHandler(new DataHandler(new ByteArrayDataSource(new byte[] {0, 0, 0, 24, 'f', 't', 'y', 'p'},
                "image/heic")));
        heic.setFileName("IMG_0002.HEIC");
        hub.deliver(mail(DAD, GMAIL_VERIFIED, "<heic-1@example.com>", "", "", heic));

        poller.pollNow();

        assertThat(photoCount()).isEqualTo(before + 1);
        assertThat(count("FamilyHub-Processed")).isEqualTo(1);
    }

    @Test
    void aStrangersMessageIsLabelledUnrecognisedAndNotRead() throws Exception {
        int before = photoCount();
        hub.deliver(mail("stranger@example.net", "mx.google.com; dmarc=pass header.from=example.net",
                "<spam-1@example.net>", "Free photos", "", jpegPart("x.jpg", jpeg(800, 600))));

        poller.pollNow();

        assertThat(photoCount()).isEqualTo(before);
        assertThat(count("FamilyHub-Unrecognised")).isEqualTo(1);
    }

    @Test
    void aFamilyAddressWithoutGmailsVerificationIsTreatedAsAStranger() throws Exception {
        int before = photoCount();
        hub.deliver(mail(DAD, null, "<forged-1@example.com>", "Photo", "",
                jpegPart("x.jpg", jpeg(800, 600))));

        poller.pollNow();

        assertThat(photoCount()).isEqualTo(before);
        assertThat(count("FamilyHub-Unrecognised")).isEqualTo(1);
    }

    @Test
    void anAddressTheFamilyAddedMaySendToo() throws Exception {
        Family family = familyRepository.findByUsername(FAMILY).orElseThrow();
        if (senderRepository.findByFamilyAndAddress(family, "grandma@example.org").isEmpty()) {
            InboxSender grandma = new InboxSender();
            grandma.setFamily(family);
            grandma.setAddress("grandma@example.org");
            grandma.setCreatedAt(Instant.now());
            senderRepository.save(grandma);
        }
        int before = photoCount();
        hub.deliver(mail("Grandma@Example.org", "mx.google.com; dmarc=pass header.from=example.org",
                "<grandma-1@example.org>", "The kids", "", jpegPart("kids.jpg", jpeg(1200, 900))));

        poller.pollNow();

        assertThat(photoCount()).isEqualTo(before + 1);
        assertThat(messageRepository.findByFamilyAndMessageId(family, "<grandma-1@example.org>")).isPresent();
    }

    @Test
    void aFlyersClearDatesGoOnTheCalendarAndTheSenderAndBothParentsAreTold() throws Exception {
        ScriptedReader.NEXT.set(events(
                timed("Soccer practice", "2027-03-06", "9:00 AM", "10:30 AM", MailReading.Confidence.HIGH, "Mom"),
                allDay("Picture day", "2027-03-11", MailReading.Confidence.LOW, "The flyer gives no year")));
        hub.deliver(mail(DAD, GMAIL_VERIFIED, "<flyer-2@example.com>", "Spring schedule", "See the attached flyer",
                pdfPart()));

        poller.pollNow();

        assertThat(eventTitles("2027-03-01", "2027-03-31")).contains("Soccer practice").doesNotContain("Picture day");
        assertThat(eventMember("2027-03-01", "2027-03-31", "Soccer practice")).isEqualTo(memberId("Mom"));
        assertThat(count("FamilyHub-Processed")).isEqualTo(1);

        MimeMessage reply = latestReplyTo(DAD);
        assertThat(reply.getHeader("In-Reply-To", null)).isEqualTo("<flyer-2@example.com>");
        assertThat(reply.getRecipients(Message.RecipientType.CC)).extracting(Object::toString).contains(MOM);
        String body = GreenMailUtil.getBody(reply);
        assertThat(body).contains("Soccer practice").contains("Picture day").contains("UNDO");
    }

    @Test
    void replyingUndoTakesTheAddedEventsOffTheCalendar() throws Exception {
        ScriptedReader.NEXT.set(events(
                timed("Swim meet", "2027-04-10", "8:00 AM", "12:00 PM", MailReading.Confidence.HIGH, "")));
        hub.deliver(mail(DAD, GMAIL_VERIFIED, "<flyer-3@example.com>", "Swim meet", "Saturday!", pdfPart()));
        poller.pollNow();
        assertThat(eventTitles("2027-04-01", "2027-04-30")).contains("Swim meet");
        String confirmation = latestReplyTo(DAD).getMessageID();
        int readerCalls = ScriptedReader.CALLS.get();

        hub.deliver(replyingTo(confirmation, DAD, GMAIL_VERIFIED, "<undo-1@example.com>", "Re: Swim meet",
                "Undo\n\nOn Tue, the Family Hub wrote:\n> Added to the calendar: Swim meet"));
        poller.pollNow();

        assertThat(eventTitles("2027-04-01", "2027-04-30")).doesNotContain("Swim meet");
        // The undo is understood without reading the message again.
        assertThat(ScriptedReader.CALLS.get()).isEqualTo(readerCalls);
        assertThat(GreenMailUtil.getBody(latestReplyTo(DAD))).contains("Taken off").contains("Swim meet");
    }

    @Test
    void aMessageWithNothingToAddIsSetAsideAndTheSenderTold() throws Exception {
        ScriptedReader.NEXT.set(new MailReading(MailReading.Kind.NOTHING, List.of(), emptyRecipe(),
                "A thank-you note"));
        hub.deliver(mail(DAD, GMAIL_VERIFIED, "<note-1@example.com>", "Thanks", "Thanks for dinner!"));

        poller.pollNow();

        assertThat(count("FamilyHub-Unprocessed")).isEqualTo(1);
        assertThat(GreenMailUtil.getBody(latestReplyTo(DAD))).containsIgnoringCase("nothing to add");
    }

    @Test
    void withoutAReaderAMessageWithWordsIsSetAsideUnread() throws Exception {
        ScriptedReader.AVAILABLE.set(false);
        hub.deliver(mail(DAD, GMAIL_VERIFIED, "<flyer-4@example.com>", "Field trip", "Permission slip"));

        poller.pollNow();

        assertThat(ScriptedReader.CALLS.get()).isZero();
        assertThat(count("FamilyHub-Unprocessed")).isEqualTo(1);
    }

    @Test
    void aBusyReaderLeavesTheMessageForTheNextReading() throws Exception {
        ScriptedReader.FAIL_ONCE.set(new MailReaderException("overloaded", true, null));
        ScriptedReader.NEXT.set(events(
                timed("Recital", "2027-05-02", "3:00 PM", "4:00 PM", MailReading.Confidence.HIGH, "")));
        hub.deliver(mail(DAD, GMAIL_VERIFIED, "<flyer-5@example.com>", "Recital", "Sunday", pdfPart()));

        poller.pollNow();
        assertThat(count("INBOX")).isEqualTo(1);
        assertThat(eventTitles("2027-05-01", "2027-05-31")).doesNotContain("Recital");

        poller.pollNow();
        assertThat(count("INBOX")).isZero();
        assertThat(eventTitles("2027-05-01", "2027-05-31")).contains("Recital");
    }

    // ------------------------------------------------------------- helpers

    private static MailReading events(MailReading.ReadEvent... events) {
        return new MailReading(MailReading.Kind.EVENTS, List.of(events), emptyRecipe(), "A flyer");
    }

    private static MailReading.ReadRecipe emptyRecipe() {
        return new MailReading.ReadRecipe("", "", List.of(), List.of(), List.of(), "");
    }

    private static MailReading.ReadEvent timed(String title, String date, String start, String end,
                                               MailReading.Confidence confidence, String person) {
        return new MailReading.ReadEvent(title, date, "", false, start, end, "",
                person.isEmpty() ? List.of() : List.of(person), confidence, "");
    }

    private static MailReading.ReadEvent allDay(String title, String date, MailReading.Confidence confidence,
                                                String uncertainty) {
        return new MailReading.ReadEvent(title, date, "", true, "", "", "", List.of(), confidence, uncertainty);
    }

    private List<String> eventTitles(String from, String to) throws Exception {
        return JsonPath.read(mockMvc.perform(get("/api/calendar/events").param("startDate", from)
                        .param("endDate", to).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.data[*].title");
    }

    private String eventMember(String from, String to, String title) throws Exception {
        List<String> ids = JsonPath.read(mockMvc.perform(get("/api/calendar/events").param("startDate", from)
                        .param("endDate", to).header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString(), "$.data[?(@.title == '" + title + "')].memberId");
        return ids.getFirst();
    }

    private String memberId(String name) throws Exception {
        List<String> ids = JsonPath.read(mockMvc.perform(get("/api/family").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString(), "$.data.members[?(@.name == '" + name + "')].id");
        return ids.getFirst();
    }

    /** The newest message the inbox sent to this address. */
    private MimeMessage latestReplyTo(String address) throws Exception {
        greenMail.waitForIncomingEmail(5000, 1);
        MimeMessage latest = null;
        for (MimeMessage message : greenMail.getReceivedMessagesForDomain(address)) {
            if (message.getFrom()[0].toString().contains("hub@localhost")) {
                latest = message;
            }
        }
        assertThat(latest).as("a reply to " + address).isNotNull();
        return latest;
    }

    private static MimeBodyPart pdfPart() throws Exception {
        MimeBodyPart part = new MimeBodyPart();
        part.setDataHandler(new DataHandler(new ByteArrayDataSource("%PDF-1.4 flyer".getBytes(), "application/pdf")));
        part.setFileName("flyer.pdf");
        return part;
    }

    private String signIn() throws Exception {
        if (familyRepository.findByUsername(FAMILY).isEmpty()) {
            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"username": "%s", "password": "%s", "familyName": "Inbox Family",
                                     "members": [{"name": "Dad", "color": "teal", "email": "%s"},
                                                 {"name": "Mom", "color": "coral", "email": "%s"}],
                                     "timezone": "America/Los_Angeles", "starterRecipes": false}
                                    """.formatted(FAMILY, PASSWORD, DAD, MOM)))
                    .andExpect(status().isOk());
        }
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\": \"" + FAMILY + "\", \"password\": \"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.token");
    }

    private int photoCount() throws Exception {
        List<?> photos = JsonPath.read(mockMvc.perform(get("/api/photos").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.data");
        return photos.size();
    }

    private static MimeMessage mail(String from, String authenticationResults, String messageId, String subject,
                                    String text, MimeBodyPart... attachments) throws Exception {
        return replyingTo(null, from, authenticationResults, messageId, subject, text, attachments);
    }

    private static MimeMessage replyingTo(String inReplyTo, String from, String authenticationResults,
                                          String messageId, String subject, String text,
                                          MimeBodyPart... attachments) throws Exception {
        MimeMessage message = new KeepsItsId();
        message.setFrom(new InternetAddress(from));
        message.setRecipients(Message.RecipientType.TO, "hub@localhost");
        message.setSubject(subject);
        message.setHeader("Message-ID", messageId);
        if (authenticationResults != null) {
            message.setHeader("Authentication-Results", authenticationResults);
        }
        if (inReplyTo != null) {
            message.setHeader("In-Reply-To", inReplyTo);
        }
        List<MimeBodyPart> parts = new ArrayList<>();
        MimeBodyPart body = new MimeBodyPart();
        body.setText(text, "UTF-8");
        parts.add(body);
        parts.addAll(List.of(attachments));
        message.setContent(new MimeMultipart(parts.toArray(MimeBodyPart[]::new)));
        return message;
    }

    private static MimeBodyPart jpegPart(String name, byte[] bytes) throws Exception {
        MimeBodyPart part = new MimeBodyPart();
        part.setDataHandler(new DataHandler(new ByteArrayDataSource(bytes, "image/jpeg")));
        part.setFileName(name);
        return part;
    }

    private static byte[] jpeg(int width, int height) throws Exception {
        BufferedImage picture = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = picture.createGraphics();
        g.setColor(new Color(0x2f, 0x82, 0x7f));
        g.fillRect(0, 0, width, height);
        g.setColor(new Color(0xff, 0xd2, 0x3f));
        g.fillOval(width / 4, height / 4, width / 2, height / 2);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(picture, "jpg", out);
        return out.toByteArray();
    }

    private int count(String folderName) throws Exception {
        Session session = Session.getInstance(new Properties());
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
