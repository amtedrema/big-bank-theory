package io.github.amtedrema.bigbanktheory.bot;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.amtedrema.bigbanktheory.bot.GameResult.Status;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class GameResultTest {

	@ParameterizedTest
	@CsvSource({"FINISHED, 1001, true", "FINISHED, 1000, false", "TARGET_REACHED, 1500, true", "TARGET_REACHED, 900, false",
			"ABORTED, 5000, false"})
	void passesAboveTheScoreUnlessAborted(Status status, long score, boolean passed) {
		assertThat(new GameResult("g", status, score, 10, "").passed()).isEqualTo(passed);
	}
}
