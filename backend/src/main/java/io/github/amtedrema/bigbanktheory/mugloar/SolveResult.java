package io.github.amtedrema.bigbanktheory.mugloar;

public record SolveResult(boolean success, int lives, long gold, long score, int turn, String message) {

	public SolveResult {
		MugloarException.required(message, "message");
	}
}
