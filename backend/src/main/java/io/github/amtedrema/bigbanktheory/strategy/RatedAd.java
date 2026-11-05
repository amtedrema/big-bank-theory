package io.github.amtedrema.bigbanktheory.strategy;

import io.github.amtedrema.bigbanktheory.mugloar.Ad;

/** An ad with the chance and value the strategy gave it. {@code label} is UNKNOWN when it has no estimate. */
public record RatedAd(Ad ad, String label, double probability, double expectedValue, String reason) {
}
