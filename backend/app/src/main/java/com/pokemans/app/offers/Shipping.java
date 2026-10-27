package com.pokemans.app.offers;

import com.pokemans.app.identity.CurrentUser;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Addresses and labels. A taken card gets a prepaid, insured label from the seller's address to the
 * buyer's, bought on our account and billed to the buyer monthly (Zach, 2026-10-19). No access checks:
 * the controllers have done them.
 */
@Service
@EnableConfigurationProperties(ShippingProperties.class)
class Shipping {

    private static final Logger log = LoggerFactory.getLogger(Shipping.class);

    private final ShipAddressRepository addresses;
    private final ShippingLabelRepository labels;
    private final LabelRefundRepository refunds;
    private final Optional<ShippingProvider> provider;
    private final ShippingProperties properties;
    private final CurrentUser currentUser;

    Shipping(ShipAddressRepository addresses, ShippingLabelRepository labels, LabelRefundRepository refunds,
             Optional<ShippingProvider> provider, ShippingProperties properties, CurrentUser currentUser) {
        this.addresses = addresses;
        this.labels = labels;
        this.refunds = refunds;
        this.provider = provider;
        this.properties = properties;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    Optional<ShipAddress> address(UUID orgId) {
        return addresses.findFirstByOrgIdOrderByCreatedAtDesc(orgId);
    }

    @Transactional
    ShipAddress record(UUID orgId, ShippingProvider.Address a) {
        return addresses.save(new ShipAddress(orgId, a, currentUser.id()));
    }

    @Transactional(readOnly = true)
    Optional<ShippingLabel> label(UUID listingId) {
        return labels.findByListingId(listingId);
    }

    /**
     * Buys the label for a taken card, once. Runs in its own transaction after the accept has
     * committed, so a provider failure never undoes the sale: the label can be bought again.
     *
     * @throws ResponseStatusException 503 with no provider set up, 502 when the provider fails
     */
    @Transactional
    ShippingLabel buy(UUID listingId, UUID sellerOrgId, UUID buyerOrgId, BigDecimal insuredValue) {
        Optional<ShippingLabel> existing = labels.findByListingId(listingId);
        if (existing.isPresent()) {
            return existing.get();
        }
        ShippingProvider shipper = provider.orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "No shipping account is set up yet."));
        ShipAddress from = address(sellerOrgId).orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "The seller has no address"));
        ShipAddress to = address(buyerOrgId).orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "The buyer has no address"));
        ShippingProperties.Parcel p = properties.parcel();
        ShippingProvider.Label bought;
        try {
            bought = shipper.buy(from.toAddress(), to.toAddress(), new ShippingProvider.Parcel(p.length(), p.width(), p.height(), p.weightOz()),
                    insuredValue, listingId.toString());
        } catch (RuntimeException e) {
            // The provider's answer can quote either address, so it goes to the log (and Sentry), not to the caller.
            log.error("Couldn't buy the label for listing {}", listingId, e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "We couldn't buy the shipping label just now. Try again later; we've been told.", e);
        }
        return labels.save(new ShippingLabel(listingId, buyerOrgId, from.getId(), to.getId(), bought, insuredValue));
    }

    /**
     * Asks the provider that sold the label for its postage back, and records the request with its
     * answer, or why it couldn't be asked. Once answered, a label isn't asked about again.
     */
    @Transactional
    LabelRefund refund(ShippingLabel label, String reason) {
        List<LabelRefund> earlier = refunds.findByLabelIdInOrderByCreatedAtAsc(List.of(label.getId()));
        Optional<LabelRefund> answered = earlier.stream().filter(r -> r.getProviderStatus() != null).findFirst();
        if (answered.isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A refund for this label was already asked for: " + answered.get().getProviderStatus() + ".");
        }
        String status = null;
        String error = null;
        Optional<ShippingProvider> seller = provider.filter(p -> p.name().equals(label.getProvider()));
        if (seller.isEmpty()) {
            error = "The label's provider (" + label.getProvider() + ") isn't set up here, so ask it for the refund by hand.";
        } else if (label.getProviderShipmentId() == null) {
            error = "The label has no shipment id to refund, so ask " + label.getProvider() + " by hand.";
        } else {
            try {
                status = seller.get().refund(label.getProviderShipmentId());
            } catch (RuntimeException e) {
                error = "Couldn't ask for the refund: " + e.getMessage();
            }
        }
        try {
            return refunds.saveAndFlush(new LabelRefund(label.getId(), label.getListingId(), reason, currentUser.id(),
                    seller.map(ShippingProvider::name).orElse(null), status, error));
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A refund for this label was just asked for.");
        }
    }

    /** Refund requests for these labels, the latest for each. */
    @Transactional(readOnly = true)
    Map<UUID, LabelRefund> refunds(Collection<UUID> labelIds) {
        Map<UUID, LabelRefund> latest = new HashMap<>();
        if (!labelIds.isEmpty()) {
            refunds.findByLabelIdInOrderByCreatedAtAsc(labelIds).forEach(r -> latest.put(r.getLabelId(), r));
        }
        return latest;
    }

    /** Labels bought in a month (UTC), oldest first: what each buyer is billed. */
    @Transactional(readOnly = true)
    List<ShippingLabel> labelsIn(YearMonth month) {
        Instant from = month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return labels.findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(from, to);
    }
}

/** Where an org ships from or to. The latest row is current. Append-only. */
@Entity
@Table(name = "ship_address")
class ShipAddress {

    @Id
    private UUID id;
    private UUID orgId;
    private String name;
    private String company;
    private String street1;
    private String street2;
    private String city;
    private String state;
    private String zip;
    private String country;
    private String phone;
    private UUID recordedBy;
    private Instant createdAt;

    protected ShipAddress() {
    }

    ShipAddress(UUID orgId, ShippingProvider.Address a, UUID recordedBy) {
        this.id = UUID.randomUUID();
        this.orgId = orgId;
        this.name = a.name();
        this.company = a.company();
        this.street1 = a.street1();
        this.street2 = a.street2();
        this.city = a.city();
        this.state = a.state();
        this.zip = a.zip();
        this.country = a.country();
        this.phone = a.phone();
        this.recordedBy = recordedBy;
        this.createdAt = Instant.now();
    }

    ShippingProvider.Address toAddress() {
        return new ShippingProvider.Address(name, company, street1, street2, city, state, zip, country, phone);
    }

    UUID getId() {
        return id;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}

/** A label bought for a taken card. Append-only. */
@Entity
@Table(name = "shipping_label")
class ShippingLabel {

    @Id
    private UUID id;
    private UUID listingId;
    private UUID buyerOrgId;
    private UUID fromAddressId;
    private UUID toAddressId;
    private String provider;
    private String providerShipmentId;
    private String carrier;
    private String service;
    private String trackingCode;
    private String labelUrl;
    private BigDecimal postage;
    private BigDecimal insuredValue;
    private BigDecimal insuranceCost;
    private String currency;
    private Instant createdAt;

    protected ShippingLabel() {
    }

    ShippingLabel(UUID listingId, UUID buyerOrgId, UUID fromAddressId, UUID toAddressId, ShippingProvider.Label label, BigDecimal insuredValue) {
        this.id = UUID.randomUUID();
        this.listingId = listingId;
        this.buyerOrgId = buyerOrgId;
        this.fromAddressId = fromAddressId;
        this.toAddressId = toAddressId;
        this.provider = label.provider();
        this.providerShipmentId = label.shipmentId();
        this.carrier = label.carrier();
        this.service = label.service();
        this.trackingCode = label.trackingCode();
        this.labelUrl = label.labelUrl();
        this.postage = label.postage();
        this.insuredValue = insuredValue;
        this.insuranceCost = label.insuranceCost();
        this.currency = "USD";
        this.createdAt = Instant.now();
    }

    UUID getId() {
        return id;
    }

    UUID getListingId() {
        return listingId;
    }

    String getProvider() {
        return provider;
    }

    String getProviderShipmentId() {
        return providerShipmentId;
    }

    UUID getBuyerOrgId() {
        return buyerOrgId;
    }

    String getCarrier() {
        return carrier;
    }

    String getService() {
        return service;
    }

    String getTrackingCode() {
        return trackingCode;
    }

    String getLabelUrl() {
        return labelUrl;
    }

    BigDecimal getPostage() {
        return postage;
    }

    BigDecimal getInsuredValue() {
        return insuredValue;
    }

    BigDecimal getInsuranceCost() {
        return insuranceCost;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}

interface ShipAddressRepository extends JpaRepository<ShipAddress, UUID> {

    Optional<ShipAddress> findFirstByOrgIdOrderByCreatedAtDesc(UUID orgId);
}

interface ShippingLabelRepository extends JpaRepository<ShippingLabel, UUID> {

    Optional<ShippingLabel> findByListingId(UUID listingId);

    List<ShippingLabel> findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(Instant from, Instant to);
}
