package com.pokemans.app.offers;

import static org.assertj.core.api.Assertions.assertThat;

import com.pokemans.app.TestApi;
import com.pokemans.app.TestMarket;
import com.pokemans.app.TestMarket.Posted;
import com.pokemans.app.TestMarket.Seller;
import com.pokemans.app.TestcontainersConfiguration;
import com.pokemans.app.identity.SecurityProperties;
import com.pokemans.app.offers.ListingStateMachine.Status;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * A listing's status has one definition, {@link ListingStateMachine#status}; the market query, the
 * {@code v_offer_outcomes} view and the {@code listing_event_closed_once} index each restate part of it
 * where Java can't reach. This holds them to it over the same histories (architecture review #26).
 */
@SpringBootTest(properties = "pokemans.condition.worker.enabled=false")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FakeShippingProvider.Config.class})
@Testcontainers(disabledWithoutDocker = true)
class ListingStatusParityTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SecurityProperties security;

    @Autowired
    private SaleListingRepository listings;

    @Autowired
    private ListingEventRepository events;

    @Autowired
    private JdbcTemplate jdbc;

    /** A history, as the steps appended after posting. */
    private interface Step {
        ListingEvent on(UUID listingId, UUID orgId, UUID userId);
    }

    @Test
    void theMarketTheViewAndTheIndexAgreeWithTheStateMachine() throws Exception {
        TestMarket market = new TestMarket(new TestApi(mvc, security));
        Seller seller = market.seller();
        UUID orgId = UUID.fromString(seller.orgId());
        UUID userId = jdbc.queryForObject("select id from app_user limit 1", UUID.class);

        Step accepted = ListingEvent::accepted;
        Step withdrawn = ListingEvent::withdrawn;
        Step voided = (l, o, u) -> ListingEvent.voided(l, u, "test");
        Step cancelled = (l, o, u) -> ListingEvent.cancelled(l, u, "test");
        Step shipped = ListingEvent::shipped;
        Step received = (l, o, u) -> ListingEvent.received(l, o, u, null);
        Step returned = (l, o, u) -> ListingEvent.returned(l, o, u, ListingEvent.DeclinedReason.NOT_THE_LISTED_CARD, null);
        Step cardBack = ListingEvent::cardBack;

        Map<String, List<Step>> histories = new LinkedHashMap<>();
        histories.put("posted", List.of());
        histories.put("withdrawn", List.of(withdrawn));
        histories.put("voided", List.of(voided));
        histories.put("taken", List.of(accepted));
        histories.put("taken, arrived", List.of(accepted, shipped, received));
        histories.put("taken, cancelled", List.of(accepted, cancelled));
        histories.put("taken, sent back", List.of(accepted, shipped, returned));
        histories.put("taken, back with the seller", List.of(accepted, shipped, returned, cardBack));

        Map<String, UUID> ids = new LinkedHashMap<>();
        for (Map.Entry<String, List<Step>> h : histories.entrySet()) {
            Posted posted = market.posted(seller, "Parity " + h.getKey());
            UUID listingId = UUID.fromString(posted.listingId());
            for (Step step : h.getValue()) {
                events.saveAndFlush(step.on(listingId, orgId, userId));
            }
            ids.put(h.getKey(), listingId);
        }

        Instant now = Instant.now();
        Set<UUID> open = listings.findOpen(now).stream().map(SaleListing::getId).collect(Collectors.toSet());
        Map<UUID, String> view = new LinkedHashMap<>();
        jdbc.query("select listing_id, status from v_offer_outcomes",
                rs -> { view.put(rs.getObject(1, UUID.class), rs.getString(2)); });

        Set<Status> seen = EnumSet.noneOf(Status.class);
        for (Map.Entry<String, UUID> e : ids.entrySet()) {
            SaleListing listing = listings.findById(e.getValue()).orElseThrow();
            List<ListingEvent> history = events.findByListingIdInOrderByCreatedAtAsc(List.of(listing.getId()));
            Status status = ListingStateMachine.status(listing, history, now);
            seen.add(status);
            assertThat(view.get(listing.getId())).as("view, " + e.getKey()).isEqualTo(status.name());
            assertThat(open.contains(listing.getId())).as("market, " + e.getKey()).isEqualTo(status == Status.OPEN);
            // Past its expiry nothing is open, and what was open is EXPIRED.
            Instant later = listing.getExpiresAt().plusSeconds(1);
            Status thenStatus = ListingStateMachine.status(listing, history, later);
            assertThat(listings.findOpen(later)).as("market later, " + e.getKey()).extracting(SaleListing::getId).doesNotContain(listing.getId());
            assertThat(thenStatus).as("later, " + e.getKey()).isEqualTo(status == Status.OPEN ? Status.EXPIRED : status);
        }
        assertThat(seen).containsExactlyInAnyOrder(Status.OPEN, Status.WITHDRAWN, Status.VOIDED, Status.ACCEPTED, Status.CANCELLED, Status.BACK);
    }

    @Test
    void theClosedOnceIndexCoversExactlyTheEventsThatCloseAListing() {
        String definition = jdbc.queryForObject("select pg_get_indexdef('listing_event_closed_once'::regclass)", String.class);
        Matcher quoted = Pattern.compile("'([A-Z_]+)'").matcher(definition);
        List<String> types = new ArrayList<>();
        while (quoted.find()) {
            types.add(quoted.group(1));
        }
        assertThat(types).containsExactlyInAnyOrderElementsOf(ListingStateMachine.CLOSES.stream().map(Enum::name).toList());
        // Every event that ends an open listing decides its status.
        assertThat(ListingStateMachine.DECIDED_BY.keySet()).containsAll(ListingStateMachine.CLOSES);
    }
}
