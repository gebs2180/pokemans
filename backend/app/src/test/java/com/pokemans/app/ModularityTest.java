package com.pokemans.app;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/** Module boundaries (identity, storage, intake): no cycles, no reaching into another module's internals. */
class ModularityTest {

    @Test
    void modulesRespectTheirBoundaries() {
        ApplicationModules.of(PokemansApplication.class).verify();
    }
}
