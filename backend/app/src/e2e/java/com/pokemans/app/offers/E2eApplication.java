package com.pokemans.app.offers;

import com.pokemans.app.PokemansApplication;
import org.springframework.boot.SpringApplication;

/**
 * The app as the browser tests in e2e/ run it: unchanged, plus the tests' fake shipping provider, so a taken card gets
 * a label without EasyPost. Never part of the boot jar.
 */
public class E2eApplication {

    public static void main(String[] args) {
        SpringApplication.from(PokemansApplication::main).with(FakeShippingProvider.Config.class).run(args);
    }
}
