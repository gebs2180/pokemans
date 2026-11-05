package com.pokemans.app;

import com.pokemans.valuation.ParameterSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The one valuation parameter set (review finding 10): price snapshots, valuation runs and offers all
 * use {@code pokemans.parameter-set}, loaded once at startup, so a snapshot can't be priced under one
 * version and valued under another, and a missing or broken set stops the app from starting.
 */
@Configuration(proxyBeanMethods = false)
class ParameterSetConfig {

    @Bean
    ParameterSet parameterSet(@Value("${pokemans.parameter-set}") String version) {
        return ParameterSet.load(version);
    }
}
