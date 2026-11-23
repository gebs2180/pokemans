package com.pokemans.app.offers;

import com.pokemans.app.identity.Buying;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Start buying" on a login that sells: the buyer org, the terms acceptance, the request in the admin's queue and the
 * ship-to address go in together or not at all, so nobody waits for approval with no address on file.
 */
@Service
class BuyerSignup {

    private final Buying buying;
    private final Shipping shipping;

    BuyerSignup(Buying buying, Shipping shipping) {
        this.buying = buying;
        this.shipping = shipping;
    }

    @Transactional
    Buying.State start(String orgName, String termsVersion, ShippingProvider.Address shipTo) {
        shipping.record(buying.start(orgName, termsVersion), shipTo, null);
        return buying.state();
    }
}
