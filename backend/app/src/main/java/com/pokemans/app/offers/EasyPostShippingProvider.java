package com.pokemans.app.offers;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * <a href="https://docs.easypost.com/docs/shipments">EasyPost shipments</a>: create a shipment, then
 * buy its cheapest rate on the preferred carrier with insurance for the declared value. HTTP basic
 * auth with the API key as the user name. Labels are PDFs EasyPost hosts.
 */
@Component
@ConditionalOnBooleanProperty("pokemans.shipping.easypost.enabled")
class EasyPostShippingProvider implements ShippingProvider {

    static final String NAME = "EASYPOST";

    private static final ParameterizedTypeReference<Map<String, Object>> OBJECT = new ParameterizedTypeReference<>() {
    };

    private final RestClient http;
    private final String carrier;

    @Autowired
    EasyPostShippingProvider(ShippingProperties properties) {
        this(properties.easypost(), RestClient.builder().requestFactory(requests(properties.easypost())));
    }

    /** For tests: a builder bound to a mock server. */
    EasyPostShippingProvider(ShippingProperties.EasyPost config, RestClient.Builder builder) {
        if (config.apiKey() == null || config.apiKey().isBlank()) {
            throw new IllegalStateException("pokemans.shipping.easypost.api-key must be set when EasyPost is enabled");
        }
        String auth = Base64.getEncoder().encodeToString((config.apiKey() + ":").getBytes(StandardCharsets.UTF_8));
        this.http = builder.baseUrl(config.baseUrl().toString())
                .defaultHeader("Authorization", "Basic " + auth)
                .build();
        this.carrier = config.carrier();
    }

    /**
     * Buffered, so each body goes with its length. Streamed, it goes chunked, and EasyPost reads a chunked body as none:
     * 422 "shipment cannot be blank" (live check with the test key, 2026-11-02).
     */
    private static ClientHttpRequestFactory requests(ShippingProperties.EasyPost config) {
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(config.timeout()).build());
        requests.setReadTimeout(config.timeout());
        return new BufferingClientHttpRequestFactory(requests);
    }

    @Override
    public String name() {
        return NAME;
    }

    /**
     * Creates a shipment to read its rates and buys none. No reference, so {@link #buy}'s look-up by listing id never finds
     * it. Creating a shipment costs nothing; the unbought one is left on the account.
     */
    @Override
    public Rate quote(Address from, Address to, Parcel parcel) {
        Map<String, Object> created = http.post().uri("/shipments").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("shipment", shipment(from, to, parcel))).retrieve().body(OBJECT);
        Map<String, Object> rate = cheapest(list(created, "rates"));
        return new Rate(string(rate, "carrier"), string(rate, "service"), new BigDecimal(string(rate, "rate")));
    }

    @Override
    public Label buy(Address from, Address to, Parcel parcel, BigDecimal insuredValue, String reference) {
        Map<String, Object> earlier = shipmentBy(reference);
        if (!map(earlier, "postage_label").isEmpty()) {
            return label(earlier);
        }
        Map<String, Object> shipment = shipment(from, to, parcel);
        shipment.put("reference", reference);
        shipment.put("options", Map.of("label_format", "PDF"));
        Map<String, Object> created = http.post().uri("/shipments").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("shipment", shipment)).retrieve().body(OBJECT);
        String shipmentId = string(created, "id");
        Map<String, Object> rate = cheapest(list(created, "rates"));

        Map<String, Object> bought = http.post().uri("/shipments/{id}/buy", shipmentId).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("rate", Map.of("id", string(rate, "id")), "insurance", insuredValue.toPlainString()))
                .retrieve().body(OBJECT);
        return label(bought);
    }

    private static Map<String, Object> shipment(Address from, Address to, Parcel parcel) {
        Map<String, Object> shipment = new LinkedHashMap<>();
        shipment.put("from_address", address(from));
        shipment.put("to_address", address(to));
        shipment.put("parcel", Map.of("length", parcel.length(), "width", parcel.width(), "height", parcel.height(),
                "weight", parcel.weightOz()));
        return shipment;
    }

    /**
     * The shipment an earlier try made under this reference, or null. A purchase whose answer was lost (a timeout, a
     * crash before we recorded it) is found here instead of paid for twice. EasyPost finds a shipment by id or reference;
     * any refusal means none, but a failure to ask is thrown, since we then can't tell whether a label was bought.
     */
    private Map<String, Object> shipmentBy(String reference) {
        try {
            return http.get().uri("/shipments/{reference}", reference).retrieve().body(OBJECT);
        } catch (HttpClientErrorException e) {
            return null;
        }
    }

    private static Label label(Map<String, Object> bought) {
        Map<String, Object> selected = map(bought, "selected_rate");
        Map<String, Object> label = map(bought, "postage_label");
        BigDecimal insurance = list(bought, "fees").stream()
                .filter(f -> "InsuranceFee".equals(f.get("type")))
                .map(f -> new BigDecimal(String.valueOf(f.get("amount"))))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Label(NAME, string(bought, "id"), string(selected, "carrier"), string(selected, "service"), string(bought, "tracking_code"),
                string(label, "label_url"), new BigDecimal(string(selected, "rate")), insurance);
    }

    /** <a href="https://docs.easypost.com/docs/shipments/refunds">Refunds</a>: EasyPost answers with the shipment and its refund status. */
    @Override
    public String refund(String shipmentId) {
        Map<String, Object> shipment = http.post().uri("/shipments/{id}/refund", shipmentId).retrieve().body(OBJECT);
        String status = string(shipment, "refund_status");
        if (status == null) {
            throw new IllegalStateException("EasyPost gave no refund status for " + shipmentId);
        }
        return status;
    }

    /** The cheapest rate on the preferred carrier, or the cheapest of all if it offers none. */
    private Map<String, Object> cheapest(List<Map<String, Object>> rates) {
        Comparator<Map<String, Object>> byPrice = Comparator.comparing(r -> new BigDecimal(string(r, "rate")));
        return rates.stream().filter(r -> carrier.equalsIgnoreCase(string(r, "carrier"))).min(byPrice)
                .or(() -> rates.stream().min(byPrice))
                .orElseThrow(() -> new IllegalStateException("EasyPost returned no rates for this shipment"));
    }

    private static Map<String, Object> address(Address a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", a.name());
        if (a.company() != null) {
            m.put("company", a.company());
        }
        m.put("street1", a.street1());
        if (a.street2() != null) {
            m.put("street2", a.street2());
        }
        m.put("city", a.city());
        m.put("state", a.state());
        m.put("zip", a.zip());
        m.put("country", a.country());
        if (a.phone() != null) {
            m.put("phone", a.phone());
        }
        return m;
    }

    private static String string(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v == null ? null : String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v instanceof Map<?, ?> ? (Map<String, Object>) v : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v instanceof List<?> ? (List<Map<String, Object>>) v : List.of();
    }
}
