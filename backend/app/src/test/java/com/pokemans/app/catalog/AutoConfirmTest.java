package com.pokemans.app.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/** The automatic identity rule: each condition's accept and refuse case. */
class AutoConfirmTest {

    private static final UUID TOP = UUID.randomUUID();
    private static final UUID SECOND = UUID.randomUUID();
    private static final UUID READING = UUID.randomUUID();
    private static final BigDecimal THRESHOLD = new BigDecimal("0.90");

    /** The collector confirmed the reader's clear top suggestion, with everything settled: confirmed. */
    private static AutoConfirm.Inputs accepted() {
        return new AutoConfirm.Inputs(false, false, IdentityProposal.Source.SUGGESTION, TOP, READING, READING,
                List.of(new AutoConfirm.Ranked(TOP, new BigDecimal("0.950")), new AutoConfirm.Ranked(SECOND, new BigDecimal("0.550"))),
                Edition.UNLIMITED, Variant.HOLO, false, Edition.UNLIMITED, Variant.HOLO, THRESHOLD);
    }

    private static Optional<AutoConfirm.Refusal> decide(UnaryOperator<AutoConfirm.Inputs> change) {
        return AutoConfirm.decide(change.apply(accepted()));
    }

    private static AutoConfirm.Inputs with(AutoConfirm.Inputs i, boolean lot, boolean confirmed, IdentityProposal.Source source,
                                           UUID chosen, UUID readingOf, List<AutoConfirm.Ranked> ranked, Edition edition,
                                           Variant variant, boolean stampUnsure, Edition readerEdition, Variant readerVariant) {
        return new AutoConfirm.Inputs(lot, confirmed, source, chosen, readingOf, i.latestReadingId(), ranked, edition, variant,
                stampUnsure, readerEdition, readerVariant, i.minConfidence());
    }

    @Test
    void confirmsTheTopSuggestionWithEverythingSettled() {
        assertThat(AutoConfirm.decide(accepted())).isEmpty();
    }

    @Test
    void confirmsAtExactlyTheThreshold() {
        assertThat(decide(i -> with(i, false, false, i.source(), TOP, READING,
                List.of(new AutoConfirm.Ranked(TOP, new BigDecimal("0.90"))), i.edition(), i.variant(), false,
                i.readerEdition(), i.readerVariant()))).isEmpty();
    }

    @Test
    void confirmsWhenTheReaderNamedNoPrint() {
        assertThat(decide(i -> with(i, false, false, i.source(), TOP, READING, i.suggestions(), Edition.FIRST_EDITION,
                Variant.REVERSE_HOLO, false, null, null))).isEmpty();
    }

    @Test
    void refusesALot() {
        assertThat(decide(i -> with(i, true, false, i.source(), TOP, READING, i.suggestions(), i.edition(), i.variant(), false,
                i.readerEdition(), i.readerVariant()))).contains(AutoConfirm.Refusal.LOT);
    }

    @Test
    void refusesACardAlreadyConfirmed() {
        assertThat(decide(i -> with(i, false, true, i.source(), TOP, READING, i.suggestions(), i.edition(), i.variant(), false,
                i.readerEdition(), i.readerVariant()))).contains(AutoConfirm.Refusal.ALREADY_CONFIRMED);
    }

    @Test
    void refusesASearchedPick() {
        assertThat(decide(i -> with(i, false, false, IdentityProposal.Source.SEARCH, null, null, i.suggestions(), i.edition(),
                i.variant(), false, i.readerEdition(), i.readerVariant()))).contains(AutoConfirm.Refusal.NOT_A_SUGGESTION);
        assertThat(decide(i -> with(i, false, false, IdentityProposal.Source.DESCRIBED, null, null, i.suggestions(), null,
                null, false, null, null))).contains(AutoConfirm.Refusal.NOT_A_SUGGESTION);
    }

    @Test
    void refusesASuggestionFromAnOlderReading() {
        assertThat(decide(i -> with(i, false, false, i.source(), TOP, UUID.randomUUID(), i.suggestions(), i.edition(), i.variant(),
                false, i.readerEdition(), i.readerVariant()))).contains(AutoConfirm.Refusal.NOT_LATEST_READING);
    }

    @Test
    void refusesTheSecondSuggestion() {
        assertThat(decide(i -> with(i, false, false, i.source(), SECOND, READING, i.suggestions(), i.edition(), i.variant(),
                false, i.readerEdition(), i.readerVariant()))).contains(AutoConfirm.Refusal.NOT_TOP_SUGGESTION);
    }

    @Test
    void refusesATiedTop() {
        List<AutoConfirm.Ranked> tied = List.of(new AutoConfirm.Ranked(TOP, new BigDecimal("0.95")),
                new AutoConfirm.Ranked(SECOND, new BigDecimal("0.950")));
        assertThat(decide(i -> with(i, false, false, i.source(), TOP, READING, tied, i.edition(), i.variant(), false,
                i.readerEdition(), i.readerVariant()))).contains(AutoConfirm.Refusal.TOP_TIED);
    }

    @Test
    void refusesBelowTheThreshold() {
        List<AutoConfirm.Ranked> low = List.of(new AutoConfirm.Ranked(TOP, new BigDecimal("0.899")));
        assertThat(decide(i -> with(i, false, false, i.source(), TOP, READING, low, i.edition(), i.variant(), false,
                i.readerEdition(), i.readerVariant()))).contains(AutoConfirm.Refusal.BELOW_THRESHOLD);
        List<AutoConfirm.Ranked> none = List.of(new AutoConfirm.Ranked(TOP, null));
        assertThat(decide(i -> with(i, false, false, i.source(), TOP, READING, none, i.edition(), i.variant(), false,
                i.readerEdition(), i.readerVariant()))).contains(AutoConfirm.Refusal.NO_CONFIDENCE);
    }

    @Test
    void refusesAnUnsettledEditionFinishOrStamp() {
        assertThat(decide(i -> with(i, false, false, i.source(), TOP, READING, i.suggestions(), null, i.variant(), false,
                i.readerEdition(), i.readerVariant()))).contains(AutoConfirm.Refusal.EDITION_UNSETTLED);
        assertThat(decide(i -> with(i, false, false, i.source(), TOP, READING, i.suggestions(), i.edition(), null, false,
                i.readerEdition(), i.readerVariant()))).contains(AutoConfirm.Refusal.FINISH_UNSETTLED);
        assertThat(decide(i -> with(i, false, false, i.source(), TOP, READING, i.suggestions(), i.edition(), i.variant(), true,
                i.readerEdition(), i.readerVariant()))).contains(AutoConfirm.Refusal.STAMP_UNSETTLED);
    }

    @Test
    void refusesWhenTheReaderSawAnotherPrint() {
        // The collector says 1st Edition; the reader saw no stamp. Pricing a 1st Edition wrongly is the error to avoid.
        assertThat(decide(i -> with(i, false, false, i.source(), TOP, READING, i.suggestions(), Edition.FIRST_EDITION,
                i.variant(), false, Edition.UNLIMITED, i.readerVariant()))).contains(AutoConfirm.Refusal.READER_EDITION_DIFFERS);
        assertThat(decide(i -> with(i, false, false, i.source(), TOP, READING, i.suggestions(), i.edition(),
                Variant.REVERSE_HOLO, false, i.readerEdition(), Variant.HOLO))).contains(AutoConfirm.Refusal.READER_FINISH_DIFFERS);
    }
}
