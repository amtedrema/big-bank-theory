package io.github.amtedrema.bigbanktheory.strategy;

import java.util.List;

/** The strategy's answer: every ad rated and sorted best first, the chosen move, and why. */
public record Advice(List<RatedAd> ads, Move move, String reason) {
}
