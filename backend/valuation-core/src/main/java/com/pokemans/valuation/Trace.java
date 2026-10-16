package com.pokemans.valuation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Explanation trace: which rule, inputs and parameters produced each number. Reports render it so
 * every value can be traced back to comps and rules.
 */
public final class Trace {

    /** One step: {@code subject} (e.g. "FLV" or "price[4]") got {@code value} by {@code rule}. */
    public record Step(String subject, String rule, String value, String detail) {
        @Override
        public String toString() {
            return subject + " = " + value + " [" + rule + "] " + detail;
        }
    }

    private final List<Step> steps = new ArrayList<>();

    public Trace add(String subject, String rule, Object value, String detail) {
        steps.add(new Step(subject, rule, String.valueOf(value), detail));
        return this;
    }

    public Trace addAll(Trace other) {
        steps.addAll(other.steps);
        return this;
    }

    public List<Step> steps() {
        return Collections.unmodifiableList(steps);
    }

    /** Steps for one subject, in order. */
    public List<Step> stepsFor(String subject) {
        return steps.stream().filter(s -> s.subject().equals(subject)).toList();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (Step step : steps) {
            sb.append(step).append('\n');
        }
        return sb.toString();
    }
}
