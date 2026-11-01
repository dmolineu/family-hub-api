package com.familyhub.demo.inbox;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** What counts as the parent's own words in a reply, as the common mail apps quote the earlier email. */
class ReplyTextTest {

    @Test
    void gmailsQuoteIsLeftOutEvenWhenItsAttributionLineWraps() {
        assertThat(ReplyText.ownWords("""
                Approved, it starts at 5:30

                On Sun, Nov 1, 2026 at 9:24 AM Family Hub <
                hub@example.com> wrote:
                > Hi from the Family Hub. I read "#event".
                > 1. Parade of Lights: Sun, Dec 13, all day
                """)).isEqualTo("Approved, it starts at 5:30");
        assertThat(ReplyText.ownWords("Yes\n\nOn Sun, Nov 1, 2026 at 9:24 AM <hub@example.com> wrote:\n> Hi"))
                .isEqualTo("Yes");
    }

    @Test
    void iphoneMailsSignatureAndQuoteAreLeftOut() {
        assertThat(ReplyText.ownWords("""
                Only the 13th

                Sent from my iPhone

                > On Nov 1, 2026, at 9:24 AM, hub@example.com wrote:
                >\s
                > Hi from the Family Hub.
                """)).isEqualTo("Only the 13th");
    }

    @Test
    void outlooksOriginalMessageBlockIsLeftOut() {
        assertThat(ReplyText.ownWords("No thanks\r\n\r\n-----Original Message-----\r\nFrom: Family Hub\r\nHi"))
                .isEqualTo("No thanks");
        assertThat(ReplyText.ownWords("Make it 6 PM\n\n________________________________\nFrom: Family Hub\nHi"))
                .isEqualTo("Make it 6 PM");
    }

    @Test
    void aReplyWithNothingQuotedIsKeptWholeAndNothingIsEmpty() {
        assertThat(ReplyText.ownWords("  Approve both.\nThe second one is for Maya.  "))
                .isEqualTo("Approve both.\nThe second one is for Maya.");
        assertThat(ReplyText.ownWords(null)).isEmpty();
        assertThat(ReplyText.ownWords("> only a quote")).isEmpty();
    }
}
