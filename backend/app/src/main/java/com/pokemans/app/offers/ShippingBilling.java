package com.pokemans.app.offers;

import com.pokemans.app.identity.Directory;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What to bill each buyer for the labels bought on their deals, off-platform (review finding 30: this
 * was in the controller). Reads only.
 */
@Service
class ShippingBilling {

    /**
     * One buyer's labels in a month: what to bill them. A cancelled deal's label is listed but not billed:
     * we undid the deal, so its postage is ours, refunded or not.
     */
    record BuyerCharges(UUID buyerOrgId, String buyerName, int labels, BigDecimal postage, BigDecimal insurance, BigDecimal total,
                        List<ChargeLine> lines) {
    }

    /**
     * @param refund null, or the refund's state: "requested" until its provider answers, then the answer
     * @param billed false once the deal was cancelled
     */
    record ChargeLine(UUID listingId, Instant boughtAt, String carrier, String service, String trackingCode, BigDecimal postage,
                      BigDecimal insurance, String refund, boolean billed) {
    }

    private final Shipping shipping;
    private final DealService deals;
    private final Directory directory;

    ShippingBilling(Shipping shipping, DealService deals, Directory directory) {
        this.shipping = shipping;
        this.deals = deals;
        this.directory = directory;
    }

    /** Labels bought in the month (UTC), per buyer, in the order the labels were bought. */
    @Transactional(readOnly = true)
    List<BuyerCharges> charges(YearMonth month) {
        Map<UUID, List<ShippingLabel>> byBuyer = new LinkedHashMap<>();
        for (ShippingLabel l : shipping.labelsIn(month)) {
            byBuyer.computeIfAbsent(l.getBuyerOrgId(), k -> new ArrayList<>()).add(l);
        }
        List<ShippingLabel> all = byBuyer.values().stream().flatMap(List::stream).toList();
        Map<UUID, LabelRefund> refunds = shipping.refunds(all.stream().map(ShippingLabel::getId).toList());
        Set<UUID> cancelled = deals.cancelledAmong(all.stream().map(ShippingLabel::getListingId).toList());
        List<BuyerCharges> charges = new ArrayList<>();
        byBuyer.forEach((buyer, ls) -> {
            List<ChargeLine> lines = ls.stream().map(l -> {
                LabelRefund r = refunds.get(l.getId());
                String refund = r == null ? null : r.getProviderStatus() == null ? "requested" : r.getProviderStatus();
                return new ChargeLine(l.getListingId(), l.getCreatedAt(), l.getCarrier(), l.getService(), l.getTrackingCode(),
                        l.getPostage(), l.getInsuranceCost(), refund, !cancelled.contains(l.getListingId()));
            }).toList();
            List<ChargeLine> billed = lines.stream().filter(ChargeLine::billed).toList();
            BigDecimal postage = billed.stream().map(ChargeLine::postage).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal insurance = billed.stream().map(ChargeLine::insurance).reduce(BigDecimal.ZERO, BigDecimal::add);
            charges.add(new BuyerCharges(buyer, directory.orgName(buyer), billed.size(), postage, insurance, postage.add(insurance), lines));
        });
        return charges;
    }
}
