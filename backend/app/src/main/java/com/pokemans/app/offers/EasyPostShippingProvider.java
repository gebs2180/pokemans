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
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
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

    private static JdkClientHttpRequestFactory requests(ShippingProperties.EasyPost config) {
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(config.timeout()).build());
        requests.setReadTimeout(config.timeout());
        return requests;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Label buy(Address from, Address to, Parcel parcel, BigDecimal insuredValue, String reference) {
        Map<String, Object> shipment = new LinkedHashMap<>();
        shipment.put("from_address", address(from));
        shipment.put("to_address", address(to));
        shipment.put("parcel", Map.of("length", parcel.length(), "width", parcel.width(), "height", parcel.height(),
                "weight", parcel.weightOz()));
        shipment.put("reference", reference);
        shipment.put("options", Map.of("label_format", "PDF"));
        Map<String, Object> created = http.post().uri("/shipments").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("shipment", shipment)).retrieve().body(OBJECT);
        String shipmentId = string(created, "id");
        Map<String, Object> rate = cheapest(list(created, "rates"));

        Map<String, Object> bought = http.post().uri("/shipments/{id}/buy", shipmentId).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("rate", Map.of("id", string(rate, "id")), "insurance", insuredValue.toPlainString()))
                .retrieve().body(OBJECT);
        Map<String, Object> selected = map(bought, "selected_rate");
        Map<String, Object> label = map(bought, "postage_label");
        BigDecimal insurance = list(bought, "fees").stream()
                .filter(f -> "InsuranceFee".equals(f.get("type")))
                .map(f -> new BigDecimal(String.valueOf(f.get("amount"))))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Label(NAME, shipmentId, string(selected, "carrier"), string(selected, "service"), string(bought, "tracking_code"),
                string(label, "label_url"), new BigDecimal(string(selected, "rate")), insurance);
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
