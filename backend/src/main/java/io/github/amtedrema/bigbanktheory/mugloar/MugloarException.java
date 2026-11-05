package io.github.amtedrema.bigbanktheory.mugloar;

/** Every failure of a Mugloar call. The message never contains the upstream response body. */
public class MugloarException extends RuntimeException {

	public enum Kind { AD_UNAVAILABLE, GAME_NOT_FOUND, GAME_OVER, UPSTREAM_UNAVAILABLE, UPSTREAM_INVALID }

	private final Kind kind;

	public MugloarException(Kind kind, String message) {
		super(message);
		this.kind = kind;
	}

	public Kind kind() {
		return kind;
	}

	/** Used by the records: a required field is missing in a parsed response. */
	static <T> T required(T value, String field) {
		if (value == null) {
			throw new MugloarException(Kind.UPSTREAM_INVALID, "Missing field: " + field);
		}
		return value;
	}
}
