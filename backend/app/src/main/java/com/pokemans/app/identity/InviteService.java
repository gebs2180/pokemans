package com.pokemans.app.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Creates invite links (admin) and turns them into memberships (invitee). */
@Service
class InviteService {

    record CreatedInvite(UUID inviteId, String token, Instant expiresAt) {
    }

    record InvitePreview(String orgName, OrgType orgType, Role role, Instant expiresAt, boolean usable) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final InviteRepository invites;
    private final OrgRepository orgs;
    private final MembershipRepository memberships;
    private final CurrentUser currentUser;
    private final InviteProperties properties;

    InviteService(InviteRepository invites, OrgRepository orgs, MembershipRepository memberships,
                  CurrentUser currentUser, InviteProperties properties) {
        this.invites = invites;
        this.orgs = orgs;
        this.memberships = memberships;
        this.currentUser = currentUser;
        this.properties = properties;
    }

    @Transactional
    CreatedInvite create(UUID orgId, String email) {
        Org org = orgs.findById(orgId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Org not found"));
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiresAt = Instant.now().plus(properties.ttl());
        Invite invite = invites.save(new Invite(org.getId(), org.getType().role(), hash(token), email, currentUser.id(), expiresAt));
        return new CreatedInvite(invite.getId(), token, expiresAt);
    }

    @Transactional(readOnly = true)
    InvitePreview preview(String token) {
        Invite invite = find(token);
        Org org = orgs.findById(invite.getOrgId()).orElseThrow();
        return new InvitePreview(org.getName(), org.getType(), invite.getRole(), invite.getExpiresAt(), invite.isUsable(Instant.now()));
    }

    /** Accepting twice as the same user is harmless; a used or expired invite is gone. */
    @Transactional
    Membership accept(String token) {
        Invite invite = invites.findLockedByTokenHash(hash(token))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invite not found"));
        UUID userId = currentUser.id();
        var existing = memberships.findByOrgIdAndUserId(invite.getOrgId(), userId);
        if (existing.isPresent()) {
            return existing.get();
        }
        Instant now = Instant.now();
        if (!invite.isUsable(now)) {
            throw new ResponseStatusException(HttpStatus.GONE, "Invite expired or already used");
        }
        invite.accept(userId, now);
        return memberships.save(new Membership(invite.getOrgId(), userId, invite.getRole()));
    }

    private Invite find(String token) {
        return invites.findByTokenHash(hash(token))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invite not found"));
    }

    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
