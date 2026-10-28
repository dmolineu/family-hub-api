package com.familyhub.demo.inbox;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SenderVerifierTest {
    private final SenderVerifier verifier = new SenderVerifier(List.of("mx.google.com"));

    private static final String GMAIL_PASS = "mx.google.com; dkim=pass header.i=@gmail.com header.s=20230601"
            + " header.b=Ab12; spf=pass (google.com: domain of dad@gmail.com designates 1.2.3.4 as permitted"
            + " sender) smtp.mailfrom=dad@gmail.com; dmarc=pass (p=NONE sp=QUARANTINE dis=NONE)"
            + " header.from=gmail.com";

    @Test
    void gmailsOwnDmarcPassVerifiesTheSender() {
        assertThat(verifier.verified("Dad@Gmail.com", List.of(GMAIL_PASS))).isTrue();
    }

    @Test
    void aFailedOrMissingCheckIsNotVerified() {
        assertThat(verifier.verified("dad@gmail.com", List.of(GMAIL_PASS.replace("dmarc=pass", "dmarc=fail")
                .replace("dkim=pass", "dkim=fail")))).isFalse();
        assertThat(verifier.verified("dad@gmail.com", List.of())).isFalse();
    }

    @Test
    void aPassForAnotherDomainDoesNotVouchForTheFromAddress() {
        String other = "mx.google.com; dkim=pass header.i=@evil.example; dmarc=pass header.from=evil.example";
        assertThat(verifier.verified("dad@gmail.com", List.of(other))).isFalse();
    }

    @Test
    void anAlignedDkimPassIsEnoughWhenThereIsNoDmarcResult() {
        String dkimOnly = "mx.google.com; dkim=pass header.i=@school.example.org; spf=neutral";
        assertThat(verifier.verified("office@school.example.org", List.of(dkimOnly))).isTrue();
        // A subdomain signature vouches for its parent domain (relaxed alignment)...
        String sub = "mx.google.com; dkim=pass header.i=@mail.school.example.org; spf=neutral";
        assertThat(verifier.verified("office@school.example.org", List.of(sub))).isTrue();
        // ...but a look-alike domain does not.
        String lookalike = "mx.google.com; dkim=pass header.i=@notschool.example.org";
        assertThat(verifier.verified("office@school.example.org", List.of(lookalike))).isFalse();
    }

    @Test
    void onlyTheReceivingServersOwnTopmostResultCounts() {
        // Anyone can write an Authentication-Results header into a message they send;
        // only the one the receiving server added (the topmost, with its name) counts.
        String forgedBelow = GMAIL_PASS;
        String realOnTop = GMAIL_PASS.replace("dmarc=pass", "dmarc=fail").replace("dkim=pass", "dkim=none");
        assertThat(verifier.verified("dad@gmail.com", List.of(realOnTop, forgedBelow))).isFalse();
        assertThat(verifier.verified("dad@gmail.com", List.of(GMAIL_PASS.replace("mx.google.com", "evil.example"))))
                .isFalse();
    }
}
