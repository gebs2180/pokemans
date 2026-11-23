package com.pokemans.app.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.pokemans.app.identity.Directory;
import com.pokemans.app.identity.OrgType;
import com.pokemans.app.offers.OfferReadiness;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Review finding 1: one email per card when its offer is ready, to a collector only. */
class OfferReadyEmailsTest {

    private final OfferReadiness readiness = mock(OfferReadiness.class);
    private final Directory directory = mock(Directory.class);
    private final Notifier notifier = mock(Notifier.class);
    private final EmailProperties properties = new EmailProperties(true, "k", "Rawmark <notify@rawmark.app>", URI.create("https://api.resend.com"),
            URI.create("https://beta.rawmark.app"), Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ofSeconds(20), 20, 8, "0 0 14 * * *");
    private final OfferReadyEmails emails = new OfferReadyEmails(readiness, directory, notifier, properties);

    @Test
    void emailsTheCollectorWhoAddedEachReadyCardOnce() {
        Instant now = OfferReadyEmails.FROM.plus(Duration.ofDays(30));
        UUID collector = UUID.randomUUID();
        UUID contributor = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID mine = UUID.randomUUID();
        UUID theirs = UUID.randomUUID();
        when(readiness.photographedBetween(now.minus(OfferReadyEmails.LOOKBACK), now)).thenReturn(List.of(
                new OfferReadiness.Ready(mine, collector, user, "Umbreon VMAX"),
                new OfferReadiness.Ready(theirs, contributor, user, "Pikachu")));
        when(directory.orgsOfType(OrgType.COLLECTOR)).thenReturn(List.of(collector));
        when(notifier.queue(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(true);

        assertThat(emails.run(now)).isEqualTo(1);
        verify(notifier).queue(eq("OFFER_READY:" + mine), eq(EmailOutbox.Template.OFFER_READY), eq(user), eq(collector), isNull(),
                any(), eq(now), eq(now));
        verify(notifier, never()).queue(eq(OfferReadyEmails.key(theirs)), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void cardsPhotographedBeforeItShippedGetNoEmail() {
        assertThat(emails.run(OfferReadyEmails.FROM.minusSeconds(1))).isZero();
        verifyNoInteractions(readiness, notifier);

        Instant justAfter = OfferReadyEmails.FROM.plus(Duration.ofDays(1));
        when(readiness.photographedBetween(OfferReadyEmails.FROM, justAfter)).thenReturn(List.of());
        assertThat(emails.run(justAfter)).isZero();
        verify(readiness).photographedBetween(OfferReadyEmails.FROM, justAfter);
    }
}
