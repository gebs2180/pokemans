package com.pokemans.app.catalog;

import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Whether identifying a card waits on its collector: choosing one of the reader's suggestions, or searching. */
@Component
public class IdentifyWaits {

    private final IdentifyService identify;

    IdentifyWaits(IdentifyService identify) {
        this.identify = identify;
    }

    /** True when the card's page asks the collector which card it is. No access check. */
    @Transactional(readOnly = true)
    public boolean onCollector(UUID cardId) {
        IdentifyPolicy.Step step = identify.step(cardId);
        return step == IdentifyPolicy.Step.SUGGEST || step == IdentifyPolicy.Step.PICK;
    }
}
