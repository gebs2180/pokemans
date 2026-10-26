package com.pokemans.app.offers;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** A shipping provider for tests: records what it was asked to buy and refund, and fails while {@link #failing} is set. */
class FakeShippingProvider implements ShippingProvider {

    record Bought(Address from, Address to, BigDecimal insuredValue, String reference) {
    }

    final List<Bought> bought = new CopyOnWriteArrayList<>();
    final List<String> refunded = new CopyOnWriteArrayList<>();
    volatile boolean failing;
    /** While set, a purchase waits here: a test can hold one in flight and see what a second request does. */
    volatile CountDownLatch gate;
    final AtomicInteger inFlight = new AtomicInteger();

    @Override
    public String name() {
        return "FAKE";
    }

    @Override
    public Label buy(Address from, Address to, Parcel parcel, BigDecimal insuredValue, String reference) {
        if (failing) {
            throw new IllegalStateException("carrier is down");
        }
        inFlight.incrementAndGet();
        try {
            CountDownLatch g = gate;
            if (g != null && !g.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the test never opened the gate");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } finally {
            inFlight.decrementAndGet();
        }
        bought.add(new Bought(from, to, insuredValue, reference));
        String tracking = "9400" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        return new Label(name(), "shp_" + tracking, "USPS", "GroundAdvantage", tracking, "https://labels.example/" + tracking + ".pdf",
                new BigDecimal("5.10"), insuredValue.multiply(new BigDecimal("0.01")).setScale(2, java.math.RoundingMode.HALF_UP));
    }

    @Override
    public String refund(String shipmentId) {
        if (failing) {
            throw new IllegalStateException("carrier is down");
        }
        refunded.add(shipmentId);
        return "submitted";
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean
        FakeShippingProvider fakeShippingProvider() {
            return new FakeShippingProvider();
        }
    }
}
