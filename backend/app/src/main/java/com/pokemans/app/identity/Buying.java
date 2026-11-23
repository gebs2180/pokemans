package com.pokemans.app.identity;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * One login buys and sells (Zach, 2026-11-22). A person with a seller account turns buying on from the same login:
 * they accept the buyer terms and add a ship-to address, which opens a buyer org they own, and buying waits on the
 * admin's approval until shipping labels are paid at accept. Who asked, who answered and when is kept.
 *
 * <p>Buying gets its own org rather than a role on the seller's org because everything a buyer does is keyed by the
 * buyer org: its ship-to address (one per org, where a seller's row holds the ship-from address and payment handle),
 * the market and its deals, label billing, deal outcomes and the buyer terms. A buyer org the admin made and invited
 * people to has no request and needs none: it counts as approved.
 */
@Service
public class Buying {

    public enum Status {
        /** The person has no buyer org. */
        NOT_STARTED,
        /** They asked; the admin hasn't answered. */
        WAITING,
        APPROVED,
        /** The admin said no; the note says why. They may ask again. */
        DECLINED
    }

    /**
     * Where the current person stands on buying.
     *
     * @param orgId     their buyer org, once they have one
     * @param note      why buying was declined, when it was
     * @param terms     the buyer terms they accept to start, shown before they do
     */
    public record State(Status status, UUID orgId, String orgName, Instant appliedAt, Instant decidedAt, String note,
                        Consents.Terms terms) {
    }

    /** A request to buy, for the admin's queue. {@code sellerOrgs} are the person's other orgs, to see who is asking. */
    public record Request(UUID id, UUID orgId, String orgName, UUID userId, String email, int attempt, Instant appliedAt,
                          List<String> sellerOrgs, Boolean approved, Instant decidedAt, String decidedBy, String note) {
    }

    /**
     * The admin answered a request to buy, published in the transaction that recorded the answer. {@code note} is the
     * reason given for a decline.
     */
    public record Decided(UUID requestId, UUID orgId, UUID userId, boolean approved, String note, Instant at) {
    }

    private static final Comparator<Status> STANDING = Comparator.comparingInt(s -> switch (s) {
        case APPROVED -> 0;
        case WAITING -> 1;
        case DECLINED -> 2;
        case NOT_STARTED -> 3;
    });

    private final BuyerApplicationRepository applications;
    private final BuyerDecisionRepository decisions;
    private final MembershipRepository memberships;
    private final OrgRepository orgs;
    private final AppUserRepository users;
    private final CurrentUser currentUser;
    private final Consents consents;
    private final ApplicationEventPublisher events;

    Buying(BuyerApplicationRepository applications, BuyerDecisionRepository decisions, MembershipRepository memberships,
           OrgRepository orgs, AppUserRepository users, CurrentUser currentUser, Consents consents,
           ApplicationEventPublisher events) {
        this.applications = applications;
        this.decisions = decisions;
        this.memberships = memberships;
        this.orgs = orgs;
        this.users = users;
        this.currentUser = currentUser;
        this.consents = consents;
        this.events = events;
    }

    /** Where the current person stands: their best-placed buyer org, approved before waiting before declined. */
    @Transactional(readOnly = true)
    public State state() {
        Consents.Terms terms = Consents.termsFor(OrgType.BUYER).orElseThrow();
        State none = new State(Status.NOT_STARTED, null, null, null, null, null, terms);
        return memberships.findByUserId(currentUser.id()).stream()
                .map(m -> orgs.findById(m.getOrgId()).orElseThrow())
                .filter(o -> o.getType() == OrgType.BUYER)
                .map(o -> state(o, terms))
                .min(Comparator.comparing(State::status, STANDING))
                .orElse(none);
    }

    private State state(Org org, Consents.Terms terms) {
        Optional<BuyerApplication> last = applications.findFirstByOrgIdOrderByAttemptDesc(org.getId());
        if (last.isEmpty()) {
            return new State(Status.APPROVED, org.getId(), org.getName(), null, null, null, terms);
        }
        BuyerApplication a = last.get();
        Optional<BuyerDecision> d = decisions.findById(a.getId());
        Status status = d.map(x -> x.isApproved() ? Status.APPROVED : Status.DECLINED).orElse(Status.WAITING);
        return new State(status, org.getId(), org.getName(), a.getCreatedAt(), d.map(BuyerDecision::getDecidedAt).orElse(null),
                d.map(BuyerDecision::getNote).orElse(null), terms);
    }

    /** Whether the buyer org may buy: the admin approved its latest request, or it never needed one. No user check. */
    @Transactional(readOnly = true)
    public boolean isApproved(UUID orgId) {
        return status(orgId) == Status.APPROVED;
    }

    private Status status(UUID orgId) {
        return applications.findFirstByOrgIdOrderByAttemptDesc(orgId)
                .map(a -> decisions.findById(a.getId()).map(d -> d.isApproved() ? Status.APPROVED : Status.DECLINED).orElse(Status.WAITING))
                .orElse(Status.APPROVED);
    }

    /** 403 until the admin approves the buyer org. The admin is exempt, as with the terms. */
    @Transactional(readOnly = true)
    public void requireApproved(UUID orgId) {
        if (currentUser.isAdmin()) {
            return;
        }
        switch (status(orgId)) {
            case APPROVED -> {
            }
            case DECLINED -> throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "We couldn't approve buying on this account. Your account page says why.");
            default -> throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Buying opens once we approve your request. Your account page shows where it stands.");
        }
    }

    /**
     * Asks to buy: opens a buyer org the person owns (or, after a decline, asks again on the one they have), records
     * their acceptance of the buyer terms, and puts the request in the admin's queue. The caller records the ship-to
     * address in the same transaction. Only a login that already belongs to an org can ask.
     *
     * @return the buyer org
     */
    @Transactional
    public UUID start(String orgName, String termsVersion) {
        Consents.Terms terms = Consents.termsFor(OrgType.BUYER).orElseThrow();
        if (!terms.version().equals(termsVersion)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The terms have changed; reload and read them again");
        }
        UUID userId = currentUser.id();
        if (memberships.findByUserId(userId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Buying starts from an account you already use here.");
        }
        State now = state();
        UUID orgId = switch (now.status()) {
            case APPROVED -> throw new ResponseStatusException(HttpStatus.CONFLICT, "You can already buy.");
            case WAITING -> throw new ResponseStatusException(HttpStatus.CONFLICT, "We're already looking at your request to buy.");
            case DECLINED -> now.orgId();
            case NOT_STARTED -> {
                Org org = orgs.save(new Org(orgName.strip(), OrgType.BUYER));
                memberships.save(new Membership(org.getId(), userId, Role.BUYER));
                yield org.getId();
            }
        };
        int attempt = applications.findFirstByUserIdOrderByAttemptDesc(userId).map(a -> a.getAttempt() + 1).orElse(1);
        try {
            applications.saveAndFlush(new BuyerApplication(orgId, userId, attempt));
        } catch (DataIntegrityViolationException e) {
            // The same person asking twice at once: the second one loses, and nothing of it is kept.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "We're already looking at your request to buy.");
        }
        consents.accept(orgId, termsVersion);
        return orgId;
    }

    /** The admin's queue: requests waiting for an answer, oldest first, then the last ones answered. Admin-only by path. */
    @Transactional(readOnly = true)
    public List<Request> requests() {
        return java.util.stream.Stream.concat(applications.findUndecided().stream(), applications.findRecentlyDecided().stream())
                .map(this::request)
                .toList();
    }

    /** Approves or declines a request. A decline needs the reason the person will see. Answering twice is a 409. */
    @Transactional
    public Request decide(UUID requestId, boolean approved, String note) {
        BuyerApplication a = applications.findById(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found"));
        String reason = note == null || note.isBlank() ? null : note.strip();
        if (!approved && reason == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Say why, so they know what to change.");
        }
        if (decisions.existsById(requestId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This request has already been answered.");
        }
        try {
            decisions.saveAndFlush(new BuyerDecision(requestId, approved, currentUser.id(), reason));
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This request has already been answered.");
        }
        events.publishEvent(new Decided(requestId, a.getOrgId(), a.getUserId(), approved, reason, Instant.now()));
        return request(a);
    }

    private Request request(BuyerApplication a) {
        Optional<BuyerDecision> d = decisions.findById(a.getId());
        List<String> others = memberships.findByUserId(a.getUserId()).stream()
                .filter(m -> !m.getOrgId().equals(a.getOrgId()))
                .map(m -> orgs.findById(m.getOrgId()).map(Org::getName).orElse(null))
                .toList();
        return new Request(a.getId(), a.getOrgId(), orgs.findById(a.getOrgId()).map(Org::getName).orElse(null), a.getUserId(),
                users.findById(a.getUserId()).map(AppUser::getEmail).orElse(null), a.getAttempt(), a.getCreatedAt(), others,
                d.map(BuyerDecision::isApproved).orElse(null), d.map(BuyerDecision::getDecidedAt).orElse(null),
                d.map(x -> users.findById(x.getDecidedBy()).map(AppUser::getEmail).orElse(null)).orElse(null),
                d.map(BuyerDecision::getNote).orElse(null));
    }
}
