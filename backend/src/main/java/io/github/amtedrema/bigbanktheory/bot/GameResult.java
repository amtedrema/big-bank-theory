package io.github.amtedrema.bigbanktheory.bot;

/** How one bot game ended. FINISHED: lives reached 0. TARGET_REACHED: the score reached the requested target. ABORTED: an error. */
public record GameResult(String gameId, Status status, long score, int turns, String note) {

	public enum Status { FINISHED, TARGET_REACHED, ABORTED }

	static final long PASS_SCORE = 1000;

	/** The score never falls, so a game stopped at its target can pass. An aborted game cannot. */
	boolean passed() {
		return status != Status.ABORTED && score > PASS_SCORE;
	}
}
