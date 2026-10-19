package com.pokemans.app.pricing;

import com.pokemans.app.catalog.Catalog;
import com.pokemans.app.identity.CurrentUser;
import com.pokemans.valuation.Confidence;
import com.pokemans.valuation.Grade;
import com.pokemans.valuation.GradePrice;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Admin-only (by path): sold comps entered by hand, and a card's per-grader price tables. Manual
 * entry is the pilot route until a price source is licensed: the reviewer looks up a sold listing
 * and records what it shows. Asking prices are never comps, so a sold date is required.
 */
@RestController
@RequestMapping("/api/admin/catalog-cards/{catalogCardId}")
class PricingController {

    private static final Pattern EBAY_HOST = Pattern.compile("(^|\\.)ebay\\.(com|ca|co\\.uk|com\\.au|de|fr|it|es)$");
    private static final Pattern EBAY_ITEM = Pattern.compile("^/itm/(?:[^/]+/)?(\\d{9,15})/?$");

    /**
     * Graded sales give a grading company and grade (half grades as sold, e.g. 8.5); raw sales give
     * a raw condition instead.
     *
     * @param marketplace where it sold, e.g. eBay, for the reviewer and the report
     */
    record CompRequest(PriceSource.GradingCompany gradingCompany,
                       @DecimalMin("1") @DecimalMax("10") BigDecimal grade,
                       PriceSource.RawCondition rawCondition,
                       @NotNull @Positive @Digits(integer = 10, fraction = 2) BigDecimal price,
                       @NotNull LocalDate soldOn,
                       @NotNull PriceSource.ListingType listingType,
                       @NotBlank @Size(max = 100) String marketplace,
                       URI url,
                       @Size(max = 1000) String note) {
    }

    record CompView(UUID id, String source, PriceSource.GradingCompany gradingCompany, BigDecimal grade,
                    PriceSource.RawCondition rawCondition, BigDecimal price, String currency, LocalDate soldOn,
                    PriceSource.ListingType listingType, String url, String licence, Map<String, Object> details,
                    Instant createdAt) {
    }

    /**
     * One grade of a price table; gross, before grading costs.
     *
     * @param confidence       today's, lowered when the table is stale
     * @param pricedConfidence when the table was priced
     */
    record GradeRow(String grade, BigDecimal price, GradePrice.Method method, Confidence confidence, Confidence pricedConfidence,
                    int n, List<UUID> compIds, Integer windowDays, List<String> basis, BigDecimal stepDownRatio) {
    }

    /**
     * @param grades      U, then 1 to 10
     * @param staleLevels confidence levels lost because no refresh has replaced this table
     */
    record TableView(String gradingCompany, UUID snapshotSetId, LocalDate asOf, String parameterSet, List<GradeRow> grades,
                     BigDecimal sigma, int sigmaChanges, BigDecimal salesPerMonth, long daysOld, int staleLevels) {
    }

    /**
     * @param tables  the newest price table per grading company
     * @param skipped why the last refresh left this card, or one of its graders, unpriced
     */
    record PricesView(UUID catalogCardId, List<CompView> comps, List<TableView> tables,
                      List<String> skipped) {
    }

    private final CompIngestionService ingestion;
    private final PriceSnapshotService snapshots;
    private final SoldCompRepository comps;
    private final Prices prices;
    private final Catalog catalog;
    private final CurrentUser currentUser;
    private final PricingProperties properties;

    PricingController(CompIngestionService ingestion, PriceSnapshotService snapshots, SoldCompRepository comps, Prices prices,
                      Catalog catalog, CurrentUser currentUser, PricingProperties properties) {
        this.ingestion = ingestion;
        this.snapshots = snapshots;
        this.comps = comps;
        this.prices = prices;
        this.catalog = catalog;
        this.currentUser = currentUser;
        this.properties = properties;
    }

    @GetMapping("/prices")
    PricesView prices(@PathVariable UUID catalogCardId) {
        requireCard(catalogCardId);
        return view(catalogCardId, List.of());
    }

    @PostMapping("/comps")
    @ResponseStatus(HttpStatus.CREATED)
    CompView addComp(@PathVariable UUID catalogCardId, @Valid @RequestBody CompRequest request) {
        requireCard(catalogCardId);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("marketplace", request.marketplace().trim());
        details.put("enteredBy", currentUser.id().toString());
        if (request.note() != null && !request.note().isBlank()) {
            details.put("note", request.note().trim());
        }
        if (request.url() != null && !listingUrl(request.url())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The listing URL must be an http or https link");
        }
        // A listing entered twice is the same sale: key it by URL. Without a URL each entry is its own sale.
        String externalId = request.url() == null ? "manual:" + UUID.randomUUID() : saleKey(request.url());
        PriceSource.Sale sale;
        try {
            sale = new PriceSource.Sale(CompIngestionService.MANUAL, externalId, catalogCardId, request.gradingCompany(),
                    request.grade(), request.rawCondition(), request.price(), properties.currency(), request.soldOn(),
                    request.url(), request.listingType(), details);
            return view(ingestion.recordManual(sale));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (DataIntegrityViolationException e) {
            // Two reviewers entering the same listing at once: the unique key catches the second.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This sale is already recorded");
        }
    }

    /**
     * Re-prices every card from the stored comps now, instead of waiting for the nightly job, and
     * returns this card's prices. Step-down ratios come from all cards, so all are re-priced.
     */
    @PostMapping("/prices/refresh")
    PricesView refresh(@PathVariable UUID catalogCardId) {
        requireCard(catalogCardId);
        PriceSnapshotService.Result result = snapshots.snapshotAll(LocalDate.now(ZoneOffset.UTC));
        return view(catalogCardId, result.skipped().getOrDefault(catalogCardId, List.of()));
    }

    private void requireCard(UUID catalogCardId) {
        if (catalog.cards(List.of(catalogCardId)).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No catalog card " + catalogCardId);
        }
    }

    private PricesView view(UUID catalogCardId, List<String> skipped) {
        List<CompView> compViews = comps.findByCatalogCardIdOrderBySoldOnDescCreatedAtDesc(catalogCardId).stream()
                .map(PricingController::view).toList();
        List<TableView> tables = prices.latestByCompany(catalogCardId).values().stream().map(PricingController::view).toList();
        return new PricesView(catalogCardId, compViews, tables, skipped);
    }

    private static TableView view(Prices.CardPrices p) {
        List<GradeRow> rows = p.grades().values().stream()
                .sorted(Comparator.comparingInt(g -> g.grade().number()))
                .map(g -> new GradeRow(g.grade().toString(), g.price(), g.method(), g.confidence(), g.pricedConfidence(), g.n(), g.compIds(),
                        g.windowDays(), g.basis().stream().map(Grade::toString).toList(), g.stepDownRatio()))
                .toList();
        return new TableView(p.gradingCompany(), p.snapshotSetId(), p.asOf(), p.parameterSet(), rows, p.sigma(),
                p.sigmaChanges(), p.salesPerMonth(), p.daysOld(), p.staleLevels());
    }

    private static CompView view(SoldComp c) {
        return new CompView(c.getId(), c.getSource(), c.getGradingCompany(), c.getGrade(), c.getRawCondition(), c.getPrice(),
                c.getCurrency(), c.getSoldOn(), c.getListingType(), c.getUrl(), c.getLicence(), c.getRaw(), c.getCreatedAt());
    }

    static boolean listingUrl(URI url) {
        String scheme = url.getScheme();
        return url.isAbsolute() && url.getHost() != null
                && ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme));
    }

    /**
     * The key that makes one listing one sale. An eBay item is keyed by its item number, so the same
     * sale pasted with different tracking parameters isn't counted twice; other links drop the fragment.
     */
    static String saleKey(URI url) {
        String host = url.getHost().toLowerCase(Locale.ROOT);
        Matcher item = EBAY_ITEM.matcher(url.getRawPath() == null ? "" : url.getRawPath());
        if (EBAY_HOST.matcher(host).find() && item.matches()) {
            return "ebay:" + item.group(1);
        }
        return "url:" + url.getScheme().toLowerCase(Locale.ROOT) + "://" + url.getRawAuthority().toLowerCase(Locale.ROOT)
                + (url.getRawPath() == null ? "" : url.getRawPath()) + (url.getRawQuery() == null ? "" : "?" + url.getRawQuery());
    }
}
