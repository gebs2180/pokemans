package com.pokemans.app.offers;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

/** The admin routes of the offers module sit together, apart from the seller and buyer ones (review finding 30). */
class OfferRoutesTest {

    @Test
    void theSellerAndBuyerControllerServesNoAdminRoute() {
        assertThat(paths(OfferController.class)).isNotEmpty().noneMatch(p -> p.startsWith("/api/admin/"));
    }

    @Test
    void theAdminControllerServesOnlyAdminRoutes() {
        assertThat(paths(OfferAdminController.class))
                .contains("/api/admin/shipping/charges", "/api/admin/deals/awaiting-label", "/api/admin/deals/{listingId}/label")
                .allMatch(p -> p.startsWith("/api/admin/"));
    }

    private static List<String> paths(Class<?> controller) {
        return Arrays.stream(controller.getDeclaredMethods()).flatMap(OfferRoutesTest::paths).toList();
    }

    private static Stream<String> paths(Method m) {
        GetMapping get = m.getAnnotation(GetMapping.class);
        PostMapping post = m.getAnnotation(PostMapping.class);
        return Stream.concat(get == null ? Stream.empty() : Arrays.stream(get.value()),
                post == null ? Stream.empty() : Arrays.stream(post.value()));
    }
}
