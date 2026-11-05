package io.github.amtedrema.bigbanktheory.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.amtedrema.bigbanktheory.bot.GameResult.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class BotRunnerTest {

	private final BotPlayer player = mock(BotPlayer.class);

	@Test
	void playsTheRequestedGamesAndLogsTheSummary(CapturedOutput output) {
		when(player.play(50)).thenReturn(new GameResult("g1", Status.FINISHED, 1200, 80, ""),
				new GameResult("g2", Status.ABORTED, 300, 12, "turn did not advance"),
				new GameResult("g3", Status.TARGET_REACHED, 1500, 50, "target 50"));

		new BotRunner(player).run(new DefaultApplicationArguments("--games=3", "--target-score=50"));

		verify(player, times(3)).play(50);
		assertThat(output.getOut()).contains("Game 1/3: g1 FINISHED score 1200 turns 80")
				.contains("Game 2/3: g2 ABORTED score 300 turns 12 turn did not advance")
				.contains("Played 3: passed 2 (score above 1000, not aborted), reached target 1, aborted 1;"
						+ " score lowest 300, highest 1500");
	}

	@Test
	void stopsTheRunAfterThreeUpstreamFailuresInARow(CapturedOutput output) {
		GameResult down = new GameResult("-", Status.ABORTED, 0, 0, "UPSTREAM_UNAVAILABLE");
		when(player.play(50)).thenReturn(down);

		new BotRunner(player).run(new DefaultApplicationArguments("--games=10", "--target-score=50"));

		verify(player, times(3)).play(50);
		assertThat(output.getOut()).contains("RUN STOPPED after 3 of 10 games").contains("aborted 3");
	}

	@Test
	void threeAbortsForAnotherReasonDoNotStopTheRun() {
		GameResult invalid = new GameResult("g", Status.ABORTED, 0, 3, "UPSTREAM_INVALID");
		when(player.play(50)).thenReturn(invalid);

		new BotRunner(player).run(new DefaultApplicationArguments("--games=5", "--target-score=50"));

		verify(player, times(5)).play(50);
	}

	@Test
	void anUpstreamFailureFollowedByAGoodGameDoesNotStopTheRun() {
		GameResult down = new GameResult("-", Status.ABORTED, 0, 0, "UPSTREAM_UNAVAILABLE");
		GameResult good = new GameResult("g", Status.FINISHED, 1200, 80, "");
		when(player.play(50)).thenReturn(down, down, good, down, down, good);

		new BotRunner(player).run(new DefaultApplicationArguments("--games=6", "--target-score=50"));

		verify(player, times(6)).play(50);
	}

	@Test
	void defaultsToOneGameAndNoTarget() {
		when(player.play(BotRunner.NO_TARGET)).thenReturn(new GameResult("g1", Status.FINISHED, 1, 1, ""));

		new BotRunner(player).run(new DefaultApplicationArguments());

		verify(player, times(1)).play(BotRunner.NO_TARGET);
	}

	@Test
	void isCreatedOnlyWithTheBotProfile() {
		ApplicationContextRunner context = new ApplicationContextRunner()
				.withBean(BotPlayer.class, () -> player).withUserConfiguration(BotRunner.class);

		context.run(c -> assertThat(c).doesNotHaveBean(BotRunner.class));
		context.withPropertyValues("spring.profiles.active=bot")
				.run(c -> assertThat(c).hasSingleBean(BotRunner.class));
	}

	@ParameterizedTest
	@ValueSource(strings = {"--games=0", "--games=-1", "--games=abc", "--target-score=0"})
	void rejectsInvalidOptions(String option) {
		assertThatThrownBy(() -> new BotRunner(player).run(new DefaultApplicationArguments(option)))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
