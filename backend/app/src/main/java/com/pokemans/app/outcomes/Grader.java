package com.pokemans.app.outcomes;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Grading companies whose results we record, with their public cert lookup page. */
enum Grader {
    PSA("https://www.psacard.com/cert/%s"),
    BGS("https://www.beckett.com/grading/card-lookup?item_type=BGS&item_id=%s"),
    CGC("https://www.cgccards.com/certlookup/%s/"),
    SGC("https://gosgc.com/cert-code-lookup/%s"),
    TAG("https://my.taggrading.com/card/%s");

    private final String lookupUrl;

    Grader(String lookupUrl) {
        this.lookupUrl = lookupUrl;
    }

    /** Where a person can check this cert by hand. */
    String lookupUrl(String certNumber) {
        return lookupUrl.formatted(URLEncoder.encode(certNumber, StandardCharsets.UTF_8));
    }
}
