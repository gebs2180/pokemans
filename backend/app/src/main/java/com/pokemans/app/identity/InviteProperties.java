package com.pokemans.app.identity;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** @param ttl how long an invite link stays valid */
@ConfigurationProperties("pokemans.invites")
public record InviteProperties(@DefaultValue("7d") Duration ttl) {
}
