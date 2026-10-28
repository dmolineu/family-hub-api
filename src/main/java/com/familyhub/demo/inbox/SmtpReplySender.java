package com.familyhub.demo.inbox;

import com.familyhub.demo.config.InboxProperties;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.springframework.stereotype.Component;

import java.io.UnsupportedEncodingException;
import java.util.Properties;

/**
 * Sends replies from the inbox's own address over SMTP (Gmail's smtp.gmail.com with the
 * same app password it reads with). Each is marked Auto-Submitted, so mail software
 * does not answer it with an automatic reply of its own.
 */
@Component
public class SmtpReplySender implements ReplySender {
    private final InboxProperties properties;

    public SmtpReplySender(InboxProperties properties) {
        this.properties = properties;
    }

    @Override
    public String send(Reply reply) throws MessagingException {
        String protocol = properties.isSmtpSsl() ? "smtps" : "smtp";
        Properties settings = new Properties();
        settings.put("mail." + protocol + ".auth", "true");
        settings.put("mail." + protocol + ".connectiontimeout", String.valueOf(properties.getConnectTimeout().toMillis()));
        settings.put("mail." + protocol + ".timeout", String.valueOf(properties.getReadTimeout().toMillis()));
        Session session = Session.getInstance(settings);

        MimeMessage message = new MimeMessage(session);
        try {
            message.setFrom(new InternetAddress(properties.fromAddress(), "Family Hub"));
        } catch (UnsupportedEncodingException e) {
            message.setFrom(new InternetAddress(properties.fromAddress()));
        }
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(reply.to()));
        for (String cc : reply.cc()) {
            message.addRecipients(Message.RecipientType.CC, InternetAddress.parse(cc));
        }
        message.setSubject(reply.subject(), "UTF-8");
        if (reply.inReplyTo() != null) {
            message.setHeader("In-Reply-To", reply.inReplyTo());
            message.setHeader("References", reply.inReplyTo());
        }
        message.setHeader("Auto-Submitted", "auto-replied");
        message.setText(reply.text(), "UTF-8");
        message.saveChanges();
        String messageId = message.getMessageID();
        try (Transport transport = session.getTransport(protocol)) {
            transport.connect(properties.getSmtpHost(), properties.getSmtpPort(), properties.getUsername(),
                    properties.getPassword());
            transport.sendMessage(message, message.getAllRecipients());
        }
        return messageId;
    }
}
