package com.pokemans.app;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/** Module boundaries (identity, storage, intake, catalog, condition, outcomes, pricing, provenance): no cycles, no reaching into internals. */
class ModularityTest {

    @Test
    void modulesRespectTheirBoundaries() {
        ApplicationModules.of(PokemansApplication.class).verify();
    }
}
