package io.github.amtedrema.bigbanktheory.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import io.github.amtedrema.bigbanktheory.bot.GameResult.Status;
import io.github.amtedrema.bigbanktheory.mugloar.BuyResult;
import io.github.amtedrema.bigbanktheory.mugloar.GameState;
import io.github.amtedrema.bigbanktheory.mugloar.MugloarClient;
import io.github.amtedrema.bigbanktheory.mugloar.MugloarException;
import io.github.amtedrema.bigbanktheory.mugloar.MugloarException.Kind;
import io.github.amtedrema.bigbanktheory.mugloar.ShopItem;
import io.github.amtedrema.bigbanktheory.mugloar.SolveResult;
import io.github.amtedrema.bigbanktheory.strategy.Advice;
import io.github.amtedrema.bigbanktheory.strategy.AdviceService;
import io.github.amtedrema.bigbanktheory.strategy.Move;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The timeout runs in a separate thread because the mocked game loop never blocks: only then does
 * a loop that never ends (a broken stop rule) become a failing test instead of a hang.
 */
@Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class BotPlayerTest {

	private static final GameState START = new GameState("g1", 3, 0, 0, 0, 0);

	private final MugloarClient client = mock(MugloarClient.class);
	private final AdviceService adviceService = mock(AdviceService.class);
	private final List<Long> waits = new ArrayList<>();
	private final BotPlayer player = new BotPlayer(client, adviceService, waits::add);

	private static Advice advice(Move move) {
		return new Advice(List.of(), move, "test");
	}

	private static SolveResult solved(int lives, long gold, long score, int turn) {
		return new SolveResult(true, lives, gold, score, turn, "ok");
	}

	@Test
	void solveKeepsTheLevelAndBuyKeepsTheScore() {
		GameState start = new GameState("g1", 3, 100, 2, 500, 5);
		when(client.start()).thenReturn(start);
		when(adviceService.advise(any(), any(), any()))
				.thenReturn(advice(Move.solve("a")), advice(Move.buy("cs")), advice(Move.solve("b")));
		when(client.solve("g1", "a")).thenReturn(solved(3, 150, 550, 6));
		when(client.buy("g1", "cs")).thenReturn(new BuyResult(true, 50, 3, 3, 7));
		when(client.solve("g1", "b")).thenReturn(solved(0, 50, 550, 8));

		GameResult result = player.play(BotRunner.NO_TARGET);

		assertThat(result.status()).isEqualTo(Status.FINISHED);
		// buy gave level 3 and score stayed 550; the last solve (no level in the response) kept level 3
		verify(adviceService).advise(new GameState("g1", 3, 150, 2, 550, 6), List.of(), Set.of());
		verify(adviceService).advise(new GameState("g1", 3, 50, 3, 550, 7), List.of(), Set.of());
		assertThat(result.score()).isEqualTo(550);
		assertThat(result.turns()).isEqualTo(8);
	}

	@Test
	void stopsWhenLivesReachZeroAndMakesNoFurtherCalls() {
		when(client.start()).thenReturn(START);
		when(adviceService.advise(any(), any(), any())).thenReturn(advice(Move.solve("a")));
		when(client.solve("g1", "a")).thenReturn(new SolveResult(false, 0, 0, 40, 1, "lost"));

		GameResult result = player.play(BotRunner.NO_TARGET);

		assertThat(result).isEqualTo(new GameResult("g1", Status.FINISHED, 40, 1, ""));
		verify(adviceService, times(1)).advise(any(), any(), any());
		verify(client, times(1)).solve(anyString(), anyString());
		verifyNoMoreInteractions(adviceService);
	}

	@Test
	void rejectedAdIsExcludedForTheTurnAndTheGameContinues() {
		when(client.start()).thenReturn(START);
		when(adviceService.advise(START, List.of(), Set.of())).thenReturn(advice(Move.solve("a")));
		when(adviceService.advise(START, List.of(), Set.of("a"))).thenReturn(advice(Move.solve("b")));
		when(client.solve("g1", "a")).thenThrow(new MugloarException(Kind.AD_UNAVAILABLE, "gone"));
		when(client.solve("g1", "b")).thenReturn(solved(0, 0, 10, 1));

		GameResult result = player.play(BotRunner.NO_TARGET);

		assertThat(result.status()).isEqualTo(Status.FINISHED);
		verify(client, times(1)).solve("g1", "a");
		verify(client, times(1)).solve("g1", "b");
	}

	@Test
	void exclusionsAreClearedWhenTheTurnMoves() {
		GameState turn1 = new GameState("g1", 3, 10, 0, 10, 1);
		when(client.start()).thenReturn(START);
		when(adviceService.advise(START, List.of(), Set.of())).thenReturn(advice(Move.solve("a")));
		when(client.solve("g1", "a")).thenThrow(new MugloarException(Kind.AD_UNAVAILABLE, "gone"));
		when(adviceService.advise(START, List.of(), Set.of("a"))).thenReturn(advice(Move.solve("b")));
		when(client.solve("g1", "b")).thenReturn(solved(3, 10, 10, 1));
		when(adviceService.advise(turn1, List.of(), Set.of())).thenReturn(advice(Move.solve("c")));
		when(client.solve("g1", "c")).thenReturn(solved(0, 10, 10, 2));

		assertThat(player.play(BotRunner.NO_TARGET).status()).isEqualTo(Status.FINISHED);
		verify(adviceService).advise(turn1, List.of(), Set.of());
	}

	@Test
	void abortsWhenTheTurnDoesNotAdvanceOverTwoSteps() {
		when(client.start()).thenReturn(START);
		when(adviceService.advise(any(), any(), any())).thenReturn(advice(Move.solve("a")), advice(Move.solve("b")),
				advice(Move.solve("c")));
		when(client.solve(anyString(), anyString())).thenThrow(new MugloarException(Kind.AD_UNAVAILABLE, "gone"));

		GameResult result = player.play(BotRunner.NO_TARGET);

		assertThat(result.status()).isEqualTo(Status.ABORTED);
		assertThat(result.note()).isEqualTo("turn did not advance");
		verify(client, times(2)).solve(anyString(), anyString());
	}

	@Test
	void stopsExactlyWhenTheScoreReachesTheTarget() {
		when(client.start()).thenReturn(START);
		when(adviceService.advise(any(), any(), any())).thenReturn(advice(Move.solve("a")));
		when(client.solve("g1", "a")).thenReturn(solved(3, 10, 999, 1), solved(3, 10, 1000, 2));

		GameResult result = player.play(1000);

		assertThat(result).isEqualTo(new GameResult("g1", Status.TARGET_REACHED, 1000, 2, "target 1000"));
		verify(client, times(2)).solve(anyString(), anyString());
	}

	@Test
	void losingTheLastLifeOnTheTargetScoreIsFinished() {
		when(client.start()).thenReturn(START);
		when(adviceService.advise(any(), any(), any())).thenReturn(advice(Move.solve("a")));
		when(client.solve("g1", "a")).thenReturn(solved(0, 10, 50, 2));

		assertThat(player.play(50).status()).isEqualTo(Status.FINISHED);
	}

	@Test
	void hasNoTurnLimit() {
		when(client.start()).thenReturn(START);
		when(adviceService.advise(any(), any(), any())).thenReturn(advice(Move.solve("a")));
		int[] turn = {0};
		when(client.solve("g1", "a")).thenAnswer(call -> ++turn[0] < 1000 ? solved(3, 0, 0, turn[0]) : solved(0, 0, 0, turn[0]));

		GameResult result = player.play(BotRunner.NO_TARGET);

		assertThat(result.status()).isEqualTo(Status.FINISHED);
		assertThat(result.turns()).isEqualTo(1000);
	}

	@Test
	void readsTheShopOncePerGame() {
		List<ShopItem> shop = List.of(new ShopItem("hpot", "Healing potion", 50));
		when(client.start()).thenReturn(START);
		when(client.shop("g1")).thenReturn(shop);
		when(adviceService.advise(any(), any(), any())).thenReturn(advice(Move.solve("a")));
		when(client.solve("g1", "a")).thenReturn(solved(3, 0, 0, 1), solved(3, 0, 0, 2), solved(0, 0, 0, 3));

		player.play(BotRunner.NO_TARGET);

		verify(client, times(1)).shop("g1");
		verify(adviceService, times(3)).advise(any(), eq(shop), any());
	}

	@Test
	void manyRejectionsInOneGameDoNotAbortWhenTheTurnKeepsMoving() {
		when(client.start()).thenReturn(START);
		when(adviceService.advise(any(), any(), any())).thenReturn(advice(Move.solve("a")), advice(Move.solve("b")),
				advice(Move.solve("c")), advice(Move.solve("d")), advice(Move.solve("e")));
		when(client.solve("g1", "a")).thenThrow(new MugloarException(Kind.AD_UNAVAILABLE, "gone"));
		when(client.solve("g1", "b")).thenReturn(solved(3, 10, 10, 1));
		when(client.solve("g1", "c")).thenThrow(new MugloarException(Kind.AD_UNAVAILABLE, "gone"));
		when(client.solve("g1", "d")).thenReturn(solved(3, 20, 20, 2));
		when(client.solve("g1", "e")).thenReturn(solved(0, 20, 20, 3));

		GameResult result = player.play(BotRunner.NO_TARGET);

		assertThat(result.status()).isEqualTo(Status.FINISHED);
		assertThat(result.turns()).isEqualTo(3);
	}

	@Test
	void abortsWhenASolveReturnsTheSameTurnTwice() {
		when(client.start()).thenReturn(START);
		when(adviceService.advise(any(), any(), any())).thenReturn(advice(Move.solve("a")));
		when(client.solve("g1", "a")).thenReturn(solved(3, 0, 0, 0));

		GameResult result = player.play(BotRunner.NO_TARGET);

		assertThat(result.status()).isEqualTo(Status.ABORTED);
		assertThat(result.note()).isEqualTo("turn did not advance");
		verify(client, times(2)).solve(anyString(), anyString());
	}

	@Test
	void noMoveAborts() {
		when(client.start()).thenReturn(START);
		when(adviceService.advise(any(), any(), any())).thenReturn(advice(Move.none()));

		GameResult result = player.play(BotRunner.NO_TARGET);

		assertThat(result.status()).isEqualTo(Status.ABORTED);
		assertThat(result.note()).isEqualTo("no playable ad");
		verify(client, never()).solve(anyString(), anyString());
	}

	@Test
	void anUnavailableServerIsWaitedOutAndTheMoveDecidedAnew() {
		when(client.start()).thenReturn(START);
		when(adviceService.advise(any(), any(), any())).thenReturn(advice(Move.solve("a")), advice(Move.solve("b")));
		when(client.solve("g1", "a")).thenThrow(new MugloarException(Kind.UPSTREAM_UNAVAILABLE, "429"));
		when(client.solve("g1", "b")).thenReturn(solved(0, 0, 70, 1));

		GameResult result = player.play(BotRunner.NO_TARGET);

		assertThat(result.status()).isEqualTo(Status.FINISHED);
		assertThat(result.score()).isEqualTo(70);
		assertThat(waits).containsExactly(5_000L);
	}

	@Test
	void anUnavailableShopIsWaitedOutToo() {
		when(client.start()).thenReturn(START);
		when(client.shop("g1")).thenThrow(new MugloarException(Kind.UPSTREAM_UNAVAILABLE, "429")).thenReturn(List.of());
		when(adviceService.advise(any(), any(), any())).thenReturn(advice(Move.solve("a")));
		when(client.solve("g1", "a")).thenReturn(solved(0, 0, 70, 1));

		assertThat(player.play(BotRunner.NO_TARGET).status()).isEqualTo(Status.FINISHED);
		assertThat(waits).containsExactly(5_000L);
	}

	@Test
	void anUnavailableServerAbortsAfterTenAnswersInARowWithLongerWaits() {
		when(client.start()).thenReturn(START);
		when(adviceService.advise(any(), any(), any())).thenThrow(new MugloarException(Kind.UPSTREAM_UNAVAILABLE, "429"));

		GameResult result = player.play(BotRunner.NO_TARGET);

		assertThat(result.status()).isEqualTo(Status.ABORTED);
		assertThat(result.note()).isEqualTo("UPSTREAM_UNAVAILABLE");
		assertThat(waits).containsExactly(5_000L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L, 60_000L, 60_000L, 60_000L);
	}

	@Test
	void aMoveThatWorksResetsTheCountOfUnavailableAnswers() {
		MugloarException down = new MugloarException(Kind.UPSTREAM_UNAVAILABLE, "429");
		when(client.start()).thenReturn(START);
		when(adviceService.advise(any(), any(), any()))
				.thenThrow(down, down).thenReturn(advice(Move.solve("a")))
				.thenThrow(down).thenReturn(advice(Move.solve("b")));
		when(client.solve("g1", "a")).thenReturn(solved(3, 0, 0, 1));
		when(client.solve("g1", "b")).thenReturn(solved(0, 0, 0, 2));

		assertThat(player.play(BotRunner.NO_TARGET).status()).isEqualTo(Status.FINISHED);
		assertThat(waits).containsExactly(5_000L, 10_000L, 5_000L);
	}

	@Test
	void otherServerProblemsStillAbortAtOnce() {
		when(client.start()).thenReturn(START);
		when(adviceService.advise(any(), any(), any())).thenThrow(new MugloarException(Kind.UPSTREAM_INVALID, "bad"));

		GameResult result = player.play(BotRunner.NO_TARGET);

		assertThat(result.status()).isEqualTo(Status.ABORTED);
		assertThat(result.note()).isEqualTo("UPSTREAM_INVALID");
		assertThat(waits).isEmpty();
	}

	@Test
	void gameOverFromTheServerIsANormalEnd() {
		when(client.start()).thenReturn(START);
		when(adviceService.advise(any(), any(), any())).thenReturn(advice(Move.solve("a")));
		when(client.solve("g1", "a")).thenThrow(new MugloarException(Kind.GAME_OVER, "over"));

		GameResult result = player.play(BotRunner.NO_TARGET);

		assertThat(result.status()).isEqualTo(Status.FINISHED);
	}

	@Test
	void startFailureAborts() {
		when(client.start()).thenThrow(new MugloarException(Kind.UPSTREAM_UNAVAILABLE, "down"));

		GameResult result = player.play(BotRunner.NO_TARGET);

		assertThat(result.status()).isEqualTo(Status.ABORTED);
		assertThat(result.note()).isEqualTo("UPSTREAM_UNAVAILABLE");
	}
}
