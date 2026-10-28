package com.familyhub.demo.integration;

import com.familyhub.demo.config.TestcontainersConfig;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.InboxSender;
import com.familyhub.demo.repository.FamilyRepository;
import com.familyhub.demo.repository.InboxMessageRepository;
import com.familyhub.demo.repository.InboxSenderRepository;
import com.familyhub.demo.scheduler.InboxPoller;
import com.familyhub.demo.service.HeicDecoder;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.user.GreenMailUser;
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
@Import({TestcontainersConfig.class, FamilyInboxIntegrationTest.SwitchableHeicDecoder.class})
@ActiveProfiles("test")
class FamilyInboxIntegrationTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.IMAP);

    private static final String FAMILY = "inbox_family";
    private static final String PASSWORD = "TestPassword123!";
    private static final String DAD = "dad@example.com";
    private static final String GMAIL_VERIFIED = "mx.google.com; dkim=pass header.i=@example.com;"
            + " dmarc=pass header.from=example.com";

    @DynamicPropertySource
    static void inbox(DynamicPropertyRegistry registry) {
        registry.add("inbox.imap-host", () -> "localhost");
        registry.add("inbox.imap-port", () -> ServerSetupTest.IMAP.getPort());
        registry.add("inbox.imap-ssl", () -> "false");
        registry.add("inbox.username", () -> "hub");
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
    void noDecoder() {
        SwitchableHeicDecoder.AVAILABLE.set(false);
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

    // ------------------------------------------------------------- helpers

    private String signIn() throws Exception {
        if (familyRepository.findByUsername(FAMILY).isEmpty()) {
            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"username": "%s", "password": "%s", "familyName": "Inbox Family",
                                     "members": [{"name": "Dad", "color": "teal", "email": "%s"}],
                                     "timezone": "America/Los_Angeles", "starterRecipes": false}
                                    """.formatted(FAMILY, PASSWORD, DAD)))
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
        MimeMessage message = new KeepsItsId();
        message.setFrom(new InternetAddress(from));
        message.setRecipients(Message.RecipientType.TO, "hub@localhost");
        message.setSubject(subject);
        message.setHeader("Message-ID", messageId);
        if (authenticationResults != null) {
            message.setHeader("Authentication-Results", authenticationResults);
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
