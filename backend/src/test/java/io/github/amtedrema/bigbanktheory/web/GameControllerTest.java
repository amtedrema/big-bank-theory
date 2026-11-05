package io.github.amtedrema.bigbanktheory.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import io.github.amtedrema.bigbanktheory.mugloar.Ad;
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
import io.github.amtedrema.bigbanktheory.strategy.RatedAd;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@WebMvcTest(GameController.class)
class GameControllerTest {

	private static final String GAME = "abCD1234";
	private static final String STATE_JSON = """
			{"gameId":"abCD1234","lives":3,"gold":0,"level":0,"score":0,"turn":0}""";

	private static final Map<Kind, Integer> STATUS = new EnumMap<>(Map.of(
			Kind.AD_UNAVAILABLE, 409, Kind.GAME_NOT_FOUND, 404, Kind.GAME_OVER, 410,
			Kind.UPSTREAM_UNAVAILABLE, 503, Kind.UPSTREAM_INVALID, 502));

	@Autowired
	MockMvc mvc;

	@MockitoBean
	MugloarClient client;

	@MockitoBean
	AdviceService adviceService;

	// happy paths

	@Test
	void startReturnsTheNewGame() throws Exception {
		when(client.start()).thenReturn(new GameState(GAME, 3, 0, 0, 0, 0));

		mvc.perform(post("/api/games")).andExpect(status().isOk())
				.andExpect(jsonPath("$.gameId").value(GAME)).andExpect(jsonPath("$.lives").value(3));
	}

	@Test
	void adviceReturnsTheRatedAdsAndTheMove() throws Exception {
		Ad ad = new Ad("Zz9Yy8Xx", "Help", 40, 5, "Walk in the park", null);
		Advice advice = new Advice(List.of(new RatedAd(ad, "Walk in the park", 0.9, 36, "good")),
				Move.solve("Zz9Yy8Xx"), "best value");
		when(adviceService.advise(new GameState(GAME, 3, 0, 0, 0, 0))).thenReturn(advice);

		postJson("/api/games/" + GAME + "/advice", STATE_JSON).andExpect(status().isOk())
				.andExpect(jsonPath("$.move.type").value("SOLVE")).andExpect(jsonPath("$.move.id").value("Zz9Yy8Xx"))
				.andExpect(jsonPath("$.reason").value("best value"))
				.andExpect(jsonPath("$.ads[0].ad.adId").value("Zz9Yy8Xx"))
				.andExpect(jsonPath("$.ads[0].label").value("Walk in the park"));
	}

	@Test
	void solveReturnsTheResult() throws Exception {
		when(client.solve(GAME, "Zz9Yy8Xx")).thenReturn(new SolveResult(true, 3, 40, 40, 1, "Done"));

		mvc.perform(post("/api/games/" + GAME + "/ads/Zz9Yy8Xx/solve")).andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.gold").value(40))
				.andExpect(jsonPath("$.turn").value(1));
	}

	@Test
	void shopReturnsTheItems() throws Exception {
		when(client.shop(GAME)).thenReturn(List.of(new ShopItem("hpot", "Healing potion", 50)));

		mvc.perform(get("/api/games/" + GAME + "/shop")).andExpect(status().isOk())
				.andExpect(jsonPath("$[0].id").value("hpot")).andExpect(jsonPath("$[0].cost").value(50));
	}

	@Test
	void buyReturnsTheResult() throws Exception {
		when(client.buy(GAME, "hpot")).thenReturn(new BuyResult(true, 0, 4, 0, 1));

		mvc.perform(post("/api/games/" + GAME + "/shop/hpot/buy")).andExpect(status().isOk())
				.andExpect(jsonPath("$.shoppingSuccess").value(true)).andExpect(jsonPath("$.lives").value(4));
	}

	// input checks

	@ParameterizedTest
	@ValueSource(strings = { "not-valid", "short", "abCD12345", "abCD123%20" })
	void badGameIdIsRejectedWithoutCallingMugloar(String gameId) throws Exception {
		mvc.perform(get("/api/games/" + gameId + "/shop")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"));
		mvc.perform(post("/api/games/" + gameId + "/ads/Zz9Yy8Xx/solve")).andExpect(status().isBadRequest());
		mvc.perform(post("/api/games/" + gameId + "/shop/hpot/buy")).andExpect(status().isBadRequest());

		verifyNoInteractions(client);
	}

	@ParameterizedTest
	@ValueSource(strings = { "bad", "Zz9Yy8X=", "Zz9Yy8Xx9", "..%2e..%2e" })
	void badAdIdIsRejectedWithoutCallingMugloar(String adId) throws Exception {
		mvc.perform(post("/api/games/" + GAME + "/ads/" + adId + "/solve")).andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(400)).andExpect(jsonPath("$.code").value("INVALID_INPUT"));

		verifyNoInteractions(client);
	}

	@ParameterizedTest
	@ValueSource(strings = { "HPOT", "hp0t", "waytoolongitemidentifier" })
	void badItemIdIsRejectedWithoutCallingMugloar(String itemId) throws Exception {
		mvc.perform(post("/api/games/" + GAME + "/shop/" + itemId + "/buy")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"));

		verifyNoInteractions(client);
	}

	/** Body id equals path id, so only the pattern on the path can reject this request. */
	@Test
	void adviceWithABadGameIdInPathAndBodyIsRejectedByThePattern() throws Exception {
		postJson("/api/games/not-valid/advice",
				"{\"gameId\":\"not-valid\",\"lives\":3,\"gold\":0,\"level\":0,\"score\":0,\"turn\":0}")
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));

		verifyNoInteractions(adviceService);
		verifyNoInteractions(client);
	}

	@Test
	void adviceWithDifferentGameIdInPathAndBodyIsRejected() throws Exception {
		postJson("/api/games/Other123/advice", STATE_JSON).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"));

		verifyNoInteractions(adviceService);
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"{\"gameId\":\"abCD1234\",\"lives\":-1,\"gold\":0,\"level\":0,\"score\":0,\"turn\":0}",
			"{\"gameId\":\"abCD1234\",\"lives\":3,\"gold\":-5,\"level\":0,\"score\":0,\"turn\":0}",
			"{\"gameId\":\"abCD1234\",\"lives\":3,\"gold\":0,\"level\":-1,\"score\":0,\"turn\":0}",
			"{\"gameId\":\"abCD1234\",\"lives\":3,\"gold\":0,\"level\":0,\"score\":-1,\"turn\":0}",
			"{\"gameId\":\"abCD1234\",\"lives\":3,\"gold\":0,\"level\":0,\"score\":0,\"turn\":-1}",
			"{\"gameId\":\"  \",\"lives\":3,\"gold\":0,\"level\":0,\"score\":0,\"turn\":0}",
			"{\"lives\":3,\"gold\":0,\"level\":0,\"score\":0,\"turn\":0}",
			"{\"gameId\":\"abCD1234\",\"lives\":3,\"gold\":0,\"level\":0,\"score\":0}",
			"{\"gameId\":\"abCD1234\",\"lives\":3,\"gold\":0,\"level\":0,\"score\":0,\"turn\":null}",
			"not json", "" })
	void badAdviceBodyIsRejected(String body) throws Exception {
		ResultActions result = postJson("/api/games/" + GAME + "/advice", body);

		result.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
		assertSafeBody(result);
		verifyNoInteractions(adviceService);
	}

	@Test
	void wrongContentTypeOrMethodIsAProblemDetail() throws Exception {
		mvc.perform(post("/api/games/" + GAME + "/advice").contentType(MediaType.TEXT_PLAIN).content("x"))
				.andExpect(status().isUnsupportedMediaType())
				.andExpect(jsonPath("$.code").value("REQUEST_REJECTED"));
		mvc.perform(get("/api/games/" + GAME + "/advice")).andExpect(status().isMethodNotAllowed())
				.andExpect(jsonPath("$.code").value("REQUEST_REJECTED"));
	}

	// error mapping

	@ParameterizedTest
	@EnumSource(Kind.class)
	void everyKindIsMappedToAStatusAndACode(Kind kind) throws Exception {
		when(client.shop(GAME)).thenThrow(new MugloarException(kind, "Upstream answered 500 <html>secret</html>"));

		ResultActions result = mvc.perform(get("/api/games/" + GAME + "/shop"));

		result.andExpect(status().is(STATUS.get(kind))).andExpect(jsonPath("$.code").value(kind.name()))
				.andExpect(jsonPath("$.detail").isNotEmpty())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
		assertSafeBody(result);
		assertThat(result.andReturn().getResponse().getContentAsString()).doesNotContain("secret");
	}

	@Test
	void serverErrorsFromSpringGetTheInternalErrorCode() throws Exception {
		when(client.start()).thenThrow(new HttpMessageNotWritableException("x"));

		mvc.perform(post("/api/games")).andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
	}

	@Test
	void anUnexpectedExceptionIsAnInternalErrorWithoutDetails() throws Exception {
		when(client.start()).thenThrow(new IllegalStateException("boom at bigbanktheory.Secret"));

		ResultActions result = mvc.perform(post("/api/games"));

		result.andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
		assertSafeBody(result);
		assertThat(result.andReturn().getResponse().getContentAsString()).doesNotContain("boom");
	}

	private ResultActions postJson(String url, String body) throws Exception {
		return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	/** No exception class, package, stack trace or HTML in any response body. */
	private static void assertSafeBody(ResultActions result) throws Exception {
		assertThat(result.andReturn().getResponse().getContentAsString()).doesNotContain("Exception", "bigbanktheory",
				"<html", "<!DOCTYPE", "\tat ", "stackTrace", "jackson", "springframework");
	}
}
