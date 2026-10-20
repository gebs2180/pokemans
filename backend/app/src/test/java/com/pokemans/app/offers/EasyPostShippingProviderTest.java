package com.pokemans.app.offers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** The EasyPost adapter against the published shipment and buy response shapes (docs.easypost.com). */
class EasyPostShippingProviderTest {

    private static final ShippingProperties.EasyPost CONFIG = new ShippingProperties.EasyPost(true, "EZTK_test", URI.create("https://ep.test/v2"),
            "USPS", Duration.ofSeconds(5));
    private static final ShippingProvider.Address FROM = new ShippingProvider.Address("Sam", null, "1 Main St", null, "Springfield", "IL",
            "62701", "US", null);
    private static final ShippingProvider.Address TO = new ShippingProvider.Address("Card Shop", "Card Shop LLC", "9 Elm St", "Suite 2",
            "Austin", "TX", "78701", "US", "5125550100");
    private static final ShippingProvider.Parcel PARCEL = new ShippingProvider.Parcel(new BigDecimal("7"), new BigDecimal("5"),
            BigDecimal.ONE, new BigDecimal("3"));

    private static final String CREATED = """
            {"id": "shp_123", "object": "Shipment", "rates": [
              {"id": "rate_ups", "carrier": "UPS", "service": "Ground", "rate": "4.10"},
              {"id": "rate_priority", "carrier": "USPS", "service": "Priority", "rate": "9.35"},
              {"id": "rate_ground", "carrier": "USPS", "service": "GroundAdvantage", "rate": "5.10"}]}""";

    private static final String BOUGHT = """
            {"id": "shp_123", "tracking_code": "9400100000000000000000",
             "selected_rate": {"id": "rate_ground", "carrier": "USPS", "service": "GroundAdvantage", "rate": "5.10"},
             "postage_label": {"label_url": "https://easypost-files.s3.amazonaws.com/files/postage_label/label.pdf"},
             "fees": [{"type": "LabelFee", "amount": "0.00000"}, {"type": "PostageFee", "amount": "5.10000"},
                      {"type": "InsuranceFee", "amount": "2.25000"}]}""";

    @Test
    void buysTheCheapestRateOnThePreferredCarrierInsuredForTheOffer() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String auth = "Basic " + Base64.getEncoder().encodeToString("EZTK_test:".getBytes());
        server.expect(requestTo("https://ep.test/v2/shipments")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", auth))
                .andExpect(jsonPath("$.shipment.from_address.name").value("Sam"))
                .andExpect(jsonPath("$.shipment.to_address.street2").value("Suite 2"))
                .andExpect(jsonPath("$.shipment.parcel.weight").value(3))
                .andExpect(jsonPath("$.shipment.reference").value("listing-1"))
                .andExpect(jsonPath("$.shipment.options.label_format").value("PDF"))
                .andRespond(withSuccess(CREATED, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://ep.test/v2/shipments/shp_123/buy")).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"rate\":{\"id\":\"rate_ground\"},\"insurance\":\"225.50\"}"))
                .andRespond(withSuccess(BOUGHT, MediaType.APPLICATION_JSON));

        ShippingProvider.Label label = new EasyPostShippingProvider(CONFIG, builder)
                .buy(FROM, TO, PARCEL, new BigDecimal("225.50"), "listing-1");

        server.verify();
        assertThat(label.provider()).isEqualTo("EASYPOST");
        assertThat(label.shipmentId()).isEqualTo("shp_123");
        assertThat(label.carrier()).isEqualTo("USPS");
        assertThat(label.service()).isEqualTo("GroundAdvantage");
        assertThat(label.trackingCode()).isEqualTo("9400100000000000000000");
        assertThat(label.labelUrl()).endsWith("label.pdf");
        assertThat(label.postage()).isEqualByComparingTo("5.10");
        assertThat(label.insuranceCost()).isEqualByComparingTo("2.25");
    }

    @Test
    void failsLoudlyWhenThereAreNoRatesOrTheApiRefuses() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://ep.test/v2/shipments"))
                .andRespond(withSuccess("{\"id\":\"shp_1\",\"rates\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://ep.test/v2/shipments")).andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY));
        EasyPostShippingProvider easyPost = new EasyPostShippingProvider(CONFIG, builder);

        assertThatThrownBy(() -> easyPost.buy(FROM, TO, PARCEL, BigDecimal.TEN, "x")).hasMessageContaining("no rates");
        assertThatThrownBy(() -> easyPost.buy(FROM, TO, PARCEL, BigDecimal.TEN, "x")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new EasyPostShippingProvider(new ShippingProperties.EasyPost(true, " ", CONFIG.baseUrl(), "USPS",
                Duration.ofSeconds(1)), RestClient.builder())).hasMessageContaining("api-key");
        assertThat(CONFIG.toString()).doesNotContain("EZTK_test");
    }
}
