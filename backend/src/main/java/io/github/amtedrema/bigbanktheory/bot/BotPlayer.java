package io.github.amtedrema.bigbanktheory.bot;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongConsumer;

import io.github.amtedrema.bigbanktheory.bot.GameResult.Status;
import io.github.amtedrema.bigbanktheory.mugloar.GameState;
import io.github.amtedrema.bigbanktheory.mugloar.MugloarClient;
import io.github.amtedrema.bigbanktheory.mugloar.MugloarException;
import io.github.amtedrema.bigbanktheory.mugloar.MugloarException.Kind;
import io.github.amtedrema.bigbanktheory.mugloar.ShopItem;
import io.github.amtedrema.bigbanktheory.strategy.Advice;
import io.github.amtedrema.bigbanktheory.strategy.AdviceService;
import io.github.amtedrema.bigbanktheory.strategy.Move;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Plays one game: advice, move, new state, until the lives run out or the score reaches the target. */
@Component
public class BotPlayer {

	private static final Logger log = LoggerFactory.getLogger(BotPlayer.class);

	/** Steps in a row without the turn moving before the game is given up. */
	private static final int MAX_STUCK_STEPS = 2;

	/** Unavailable answers in a row (429, 5xx, network) before the game is given up, after about 6 minutes of waiting. */
	private static final int MAX_UPSTREAM_FAILURES = 10;

	private static final long FIRST_WAIT_MS = 5_000;
	private static final long LONGEST_WAIT_MS = 60_000;

	private final MugloarClient client;
	private final AdviceService adviceService;
	private final LongConsumer sleep;

	@Autowired
	public BotPlayer(MugloarClient client, AdviceService adviceService) {
		this(client, adviceService, BotPlayer::sleep);
	}

	BotPlayer(MugloarClient client, AdviceService adviceService, LongConsumer sleep) {
		this.client = client;
		this.adviceService = adviceService;
		this.sleep = sleep;
	}

	public GameResult play(long targetScore) {
		GameState state;
		try {
			state = client.start();
		} catch (MugloarException e) {
			log.warn("Game could not be started: {} ({})", e.kind(), e.getMessage());
			return new GameResult("-", Status.ABORTED, 0, 0, e.kind().name());
		}
		return loop(state, targetScore);
	}

	private GameResult loop(GameState first, long targetScore) {
		GameState state = first;
		// the list and its prices have been the same in every game, so one read saves a request per step
		List<ShopItem> shop = null;
		// ads the server rejected at the current turn
		Set<String> rejected = new HashSet<>();
		int stuckSteps = 0;
		int upstreamFailures = 0;
		while (true) {
			if (state.lives() <= 0) {
				return result(state, Status.FINISHED, "");
			}
			if (state.score() >= targetScore) {
				return result(state, Status.TARGET_REACHED, "target " + targetScore);
			}
			GameState next = state;
			try {
				if (shop == null) {
					shop = client.shop(state.gameId());
				}
				Advice advice = adviceService.advise(state, shop, rejected);
				Move move = advice.move();
				if (move.type() == Move.Type.NONE) {
					return result(state, Status.ABORTED, "no playable ad");
				}
				try {
					next = move.type() == Move.Type.SOLVE
							? state.with(client.solve(state.gameId(), move.id()))
							: state.with(client.buy(state.gameId(), move.id()));
					logStep(next, move, advice.reason());
				} catch (MugloarException e) {
					if (e.kind() != Kind.AD_UNAVAILABLE) {
						throw e;
					}
					// not a failure: reload and leave this ad out for the rest of the turn
					rejected.add(move.id());
					log.info("Game {} turn {}: ad {} no longer available, reloading", state.gameId(),
							state.turn(), move.id());
				}
			} catch (MugloarException e) {
				// the server says the game is over: a normal end, not an error
				if (e.kind() == Kind.GAME_OVER) {
					return result(state, Status.FINISHED, "game over reported by the server");
				}
				if (e.kind() == Kind.UPSTREAM_UNAVAILABLE && ++upstreamFailures < MAX_UPSTREAM_FAILURES) {
					// A long game should outlast a short outage. The move is decided anew, not repeated: a lost
					// answer can hide a move that did happen, and the next answer brings the state back in line.
					long waitMs = Math.min(LONGEST_WAIT_MS, FIRST_WAIT_MS << (upstreamFailures - 1));
					log.warn("Game {} turn {}: server unavailable ({}), waiting {} s", state.gameId(), state.turn(),
							e.getMessage(), waitMs / 1000);
					sleep.accept(waitMs);
					continue;
				}
				log.warn("Game {} aborted: {} ({})", state.gameId(), e.kind(), e.getMessage());
				return result(state, Status.ABORTED, e.kind().name());
			}
			upstreamFailures = 0;
			if (next.turn() > state.turn()) {
				stuckSteps = 0;
				rejected.clear();
			} else if (++stuckSteps >= MAX_STUCK_STEPS) {
				return result(next, Status.ABORTED, "turn did not advance");
			}
			state = next;
		}
	}

	private static GameResult result(GameState state, Status status, String note) {
		return new GameResult(state.gameId(), status, state.score(), state.turn(), note);
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static void logStep(GameState state, Move move, String reason) {
		log.info("Game {} turn {} level {} lives {} gold {} score {} | {} {} | {}", state.gameId(), state.turn(),
				state.level(), state.lives(), state.gold(), state.score(), move.type(), move.id(), reason);
	}
}
