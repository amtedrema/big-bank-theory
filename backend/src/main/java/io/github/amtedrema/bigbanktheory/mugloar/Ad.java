package io.github.amtedrema.bigbanktheory.mugloar;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.function.UnaryOperator;

/** An ad as the real API returns it. {@code probability} and {@code encrypted} are undocumented and may be null. */
public record Ad(String adId, String message, long reward, int expiresIn, String probability, String encrypted) {

	public Ad {
		MugloarException.required(adId, "adId");
		MugloarException.required(message, "message");
	}

	/**
	 * The plain ad, with {@code encrypted} null: 1 is Base64, 2 is ROT13, for adId, message and probability.
	 * An unknown value or text that does not decode returns the ad unchanged, so {@code encrypted} stays
	 * set and the ad cannot be solved (its adId is not usable).
	 */
	public Ad decoded() {
		UnaryOperator<String> decode;
		if (encrypted == null) {
			return this;
		} else if (encrypted.equals("1")) {
			decode = Ad::fromBase64;
		} else if (encrypted.equals("2")) {
			decode = Ad::rot13;
		} else {
			return this;
		}
		try {
			return new Ad(decode.apply(adId), decode.apply(message), reward, expiresIn,
					probability == null ? null : decode.apply(probability), null);
		} catch (IllegalArgumentException e) {
			return this;
		}
	}

	private static String fromBase64(String text) {
		return new String(Base64.getDecoder().decode(text), StandardCharsets.UTF_8);
	}

	/** Letters shifted by 13, digits and other characters unchanged. */
	private static String rot13(String text) {
		StringBuilder out = new StringBuilder();
		for (char c : text.toCharArray()) {
			if (c >= 'a' && c <= 'z') {
				out.append((char) ('a' + (c - 'a' + 13) % 26));
			} else if (c >= 'A' && c <= 'Z') {
				out.append((char) ('A' + (c - 'A' + 13) % 26));
			} else {
				out.append(c);
			}
		}
		return out.toString();
	}
}
