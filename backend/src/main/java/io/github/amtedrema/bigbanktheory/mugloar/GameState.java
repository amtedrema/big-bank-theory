package io.github.amtedrema.bigbanktheory.mugloar;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

/** What we know about a game. The start call returns all of it; later calls return parts of it. */
public record GameState(@NotBlank String gameId, @PositiveOrZero int lives, @PositiveOrZero long gold,
		@PositiveOrZero int level, @PositiveOrZero long score, @PositiveOrZero int turn) {

	public GameState {
		MugloarException.required(gameId, "gameId");
	}

	/** Solve responses carry no level, so the level is kept. */
	public GameState with(SolveResult result) {
		return new GameState(gameId, result.lives(), result.gold(), level, result.score(), result.turn());
	}

	/** Buy responses carry no score, so the score is kept. */
	public GameState with(BuyResult result) {
		return new GameState(gameId, result.lives(), result.gold(), result.level(), score, result.turn());
	}
}
