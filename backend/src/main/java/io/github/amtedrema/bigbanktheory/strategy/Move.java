package io.github.amtedrema.bigbanktheory.strategy;

/** What to do next: solve an ad, buy a shop item, or nothing (nothing playable). */
public record Move(Type type, String id) {

	public enum Type { SOLVE, BUY, NONE }

	public static Move solve(String adId) {
		return new Move(Type.SOLVE, adId);
	}

	public static Move buy(String itemId) {
		return new Move(Type.BUY, itemId);
	}

	public static Move none() {
		return new Move(Type.NONE, null);
	}
}
