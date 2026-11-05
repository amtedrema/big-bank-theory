package io.github.amtedrema.bigbanktheory.mugloar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.function.Consumer;

import io.github.amtedrema.bigbanktheory.mugloar.MugloarException.Kind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.test.autoconfigure.RestClientTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;

@RestClientTest(value = MugloarClient.class, properties = "mugloar.base-url=https://dragonsofmugloar.com/api/v2")
@ExtendWith(OutputCaptureExtension.class)
class MugloarClientTest {

	private static final String BASE = "https://dragonsofmugloar.com/api/v2";
	private static final String HTML = "<html><body>Not Found</body></html>";

	@Autowired
	MugloarClient client;

	@Autowired
	MockRestServiceServer server;

	@BeforeEach
	void reset() {
		server.reset();
	}

	private void expect(HttpMethod method, String path, ResponseCreator response) {
		server.expect(requestTo(BASE + path)).andExpect(method(method)).andRespond(response);
	}

	private static ResponseCreator json(String body) {
		return withSuccess(body, MediaType.APPLICATION_JSON);
	}

	private static ResponseCreator html(HttpStatus status) {
		return withStatus(status).contentType(MediaType.TEXT_HTML).body(HTML);
	}

	private static Consumer<Throwable> kind(Kind expected) {
		return e -> assertThat(((MugloarException) e).kind()).isEqualTo(expected);
	}

	// Response shapes

	@Test
	void messagesBareArrayWithNumericRewardAndNullEncrypted() {
		expect(HttpMethod.GET, "/g1/messages", json("""
				[{"adId":"a1","message":"Help X","reward":57,"expiresIn":5,"probability":"Piece of cake","encrypted":null}]
				"""));

		List<Ad> ads = client.messages("g1");

		assertThat(ads).containsExactly(new Ad("a1", "Help X", 57, 5, "Piece of cake", null));
	}

	@Test
	void adWithoutProbabilityIsAllowed() {
		expect(HttpMethod.GET, "/g1/messages", json("""
				[{"adId":"a1","message":"m","reward":1,"expiresIn":5,"encrypted":"abc"}]
				"""));

		assertThat(client.messages("g1").get(0).probability()).isNull();
	}

	@Test
	void shopBareArray() {
		expect(HttpMethod.GET, "/g1/shop", json("""
				[{"id":"hpot","name":"Healing potion","cost":50},{"id":"cs","name":"Claw Sharpening","cost":100}]
				"""));

		assertThat(client.shop("g1")).containsExactly(
				new ShopItem("hpot", "Healing potion", 50), new ShopItem("cs", "Claw Sharpening", 100));
	}

	@Test
	void startIgnoresHighScoreAndSendsNoBody() {
		server.expect(requestTo(BASE + "/game/start")).andExpect(method(HttpMethod.POST))
				.andExpect(content().string(""))
				.andRespond(json("""
						{"gameId":"g1","lives":3,"gold":0,"level":0,"score":0,"highScore":0,"turn":0}
						"""));

		assertThat(client.start()).isEqualTo(new GameState("g1", 3, 0, 0, 0, 0));
		server.verify();
	}

	@Test
	void buyResponseHasBooleanSuccess() {
		expect(HttpMethod.POST, "/g1/shop/buy/hpot", json("""
				{"shoppingSuccess":true,"gold":10,"lives":4,"level":1,"turn":7}
				"""));

		assertThat(client.buy("g1", "hpot")).isEqualTo(new BuyResult(true, 10, 4, 1, 7));
	}

	@Test
	void gameStateMergeKeepsLevelOnSolveAndScoreOnBuy() {
		GameState state = new GameState("g1", 3, 100, 2, 500, 10);

		GameState afterSolve = state.with(new SolveResult(true, 2, 120, 550, 11, "ok"));
		GameState afterBuy = state.with(new BuyResult(true, 50, 4, 3, 11));

		assertThat(afterSolve).isEqualTo(new GameState("g1", 2, 120, 2, 550, 11));
		assertThat(afterBuy).isEqualTo(new GameState("g1", 4, 50, 3, 500, 11));
	}

	// Error mapping

	@Test
	void solve400HtmlIsAdUnavailableWithoutHtmlInMessage() {
		expect(HttpMethod.POST, "/g1/solve/a1", html(HttpStatus.BAD_REQUEST));

		assertThatThrownBy(() -> client.solve("g1", "a1"))
				.satisfies(kind(Kind.AD_UNAVAILABLE))
				.message().doesNotContain("<html");
	}

	@Test
	void buy400IsUpstreamInvalid() {
		expect(HttpMethod.POST, "/g1/shop/buy/x", html(HttpStatus.BAD_REQUEST));

		assertThatThrownBy(() -> client.buy("g1", "x")).satisfies(kind(Kind.UPSTREAM_INVALID));
	}

	@Test
	void notFoundIsGameNotFoundOnEveryCall() {
		expect(HttpMethod.POST, "/game/start", html(HttpStatus.NOT_FOUND));
		expect(HttpMethod.GET, "/g1/messages", html(HttpStatus.NOT_FOUND));
		expect(HttpMethod.POST, "/g1/solve/a1", html(HttpStatus.NOT_FOUND));
		expect(HttpMethod.GET, "/g1/shop", html(HttpStatus.NOT_FOUND));
		expect(HttpMethod.POST, "/g1/shop/buy/hpot", html(HttpStatus.NOT_FOUND));

		assertThatThrownBy(() -> client.start()).satisfies(kind(Kind.GAME_NOT_FOUND));
		assertThatThrownBy(() -> client.messages("g1")).satisfies(kind(Kind.GAME_NOT_FOUND));
		assertThatThrownBy(() -> client.solve("g1", "a1")).satisfies(kind(Kind.GAME_NOT_FOUND));
		assertThatThrownBy(() -> client.shop("g1")).satisfies(kind(Kind.GAME_NOT_FOUND));
		assertThatThrownBy(() -> client.buy("g1", "hpot")).satisfies(kind(Kind.GAME_NOT_FOUND));
	}

	@ParameterizedTest
	@ValueSource(ints = {429, 500, 503})
	void rateLimitAndServerErrorsAreUpstreamUnavailable(int status) {
		expect(HttpMethod.POST, "/g1/solve/a1", html(HttpStatus.valueOf(status)));

		assertThatThrownBy(() -> client.solve("g1", "a1")).satisfies(kind(Kind.UPSTREAM_UNAVAILABLE));
	}

	@Test
	void aRateLimitAnswerIsLoggedAsAWarning(CapturedOutput output) {
		expect(HttpMethod.POST, "/g1/solve/a1", html(HttpStatus.TOO_MANY_REQUESTS));

		assertThatThrownBy(() -> client.solve("g1", "a1")).satisfies(kind(Kind.UPSTREAM_UNAVAILABLE));

		assertThat(output.getOut()).contains("WARN").contains("Upstream answered 429: rate limited");
	}

	@Test
	void otherClientErrorIsUpstreamInvalid() {
		expect(HttpMethod.POST, "/g1/solve/a1", html(HttpStatus.FORBIDDEN));

		assertThatThrownBy(() -> client.solve("g1", "a1")).satisfies(kind(Kind.UPSTREAM_INVALID));
	}

	@ParameterizedTest
	@ValueSource(strings = {"application/json", "text/html"})
	void htmlBodyWith200IsUpstreamInvalid(String contentType) {
		expect(HttpMethod.POST, "/g1/solve/a1", withSuccess(HTML, MediaType.parseMediaType(contentType)));

		assertThatThrownBy(() -> client.solve("g1", "a1"))
				.satisfies(kind(Kind.UPSTREAM_INVALID))
				.message().doesNotContain("<html");
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"{\"success\":true,\"gold\":5,\"score\":6,\"turn\":9,\"message\":\"ok\"}",
			"{\"success\":true,\"lives\":null,\"gold\":5,\"score\":6,\"turn\":9,\"message\":\"ok\"}"})
	void missingOrNullPrimitiveIsUpstreamInvalid(String body) {
		expect(HttpMethod.POST, "/g1/solve/a1", json(body));

		assertThatThrownBy(() -> client.solve("g1", "a1")).satisfies(kind(Kind.UPSTREAM_INVALID));
	}

	@Test
	void missingStringIsUpstreamInvalid() {
		expect(HttpMethod.GET, "/g1/messages", json("""
				[{"message":"m","reward":1,"expiresIn":5,"probability":"p"}]
				"""));

		assertThatThrownBy(() -> client.messages("g1")).satisfies(kind(Kind.UPSTREAM_INVALID));
	}

	@Test
	void emptyBodyIsUpstreamInvalid() {
		expect(HttpMethod.POST, "/g1/solve/a1", withSuccess());

		assertThatThrownBy(() -> client.solve("g1", "a1")).satisfies(kind(Kind.UPSTREAM_INVALID));
	}

	@Test
	void connectionErrorIsUpstreamUnavailable() {
		expect(HttpMethod.POST, "/g1/solve/a1", request -> {
			throw new IOException("Connection refused");
		});

		assertThatThrownBy(() -> client.solve("g1", "a1")).satisfies(kind(Kind.UPSTREAM_UNAVAILABLE));
	}

	// Retry

	@Test
	void connectionDropWhileReadingBodyIsUpstreamUnavailable() {
		server.expect(times(2), requestTo(BASE + "/g1/messages")).andRespond(request -> {
			var response = new MockClientHttpResponse(new InputStream() {
				@Override
				public int read() throws IOException {
					throw new IOException("reset");
				}
			}, HttpStatus.OK);
			response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
			return response;
		});

		assertThatThrownBy(() -> client.messages("g1")).satisfies(kind(Kind.UPSTREAM_UNAVAILABLE));
		server.verify();
	}

	@Test
	void getIsRetriedOnceAndSucceeds() {
		server.expect(requestTo(BASE + "/g1/messages")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
		server.expect(requestTo(BASE + "/g1/messages")).andRespond(json("[]"));

		assertThat(client.messages("g1")).isEmpty();
		server.verify();
	}

	@Test
	void getFailingTwiceIsUpstreamUnavailableAfterExactlyTwoRequests() {
		server.expect(times(2), requestTo(BASE + "/g1/shop")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

		assertThatThrownBy(() -> client.shop("g1")).satisfies(kind(Kind.UPSTREAM_UNAVAILABLE));
		server.verify();
	}

	@Test
	void getIsNotRetriedOnGameNotFound() {
		server.expect(requestTo(BASE + "/g1/shop")).andRespond(html(HttpStatus.NOT_FOUND));

		assertThatThrownBy(() -> client.shop("g1")).satisfies(kind(Kind.GAME_NOT_FOUND));
		server.verify();
	}

	@Test
	void postIsNeverRetried() {
		server.expect(times(1), requestTo(BASE + "/g1/solve/a1"))
				.andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

		assertThatThrownBy(() -> client.solve("g1", "a1")).satisfies(kind(Kind.UPSTREAM_UNAVAILABLE));
		server.verify();
	}

	// Encrypted ads (real samples)

	@Test
	void base64AdIsDecodedAtTheBoundary() {
		expect(HttpMethod.GET, "/g1/messages", json("""
				[{"adId":"Rkh3UEhFNmU=","message":"SW52ZXN0aWdhdGUgQWJlbG9uZSBBbGZyZWRzb24gYW5kIGZpbmQgb3V0IHRoZWlyIHJlbGF0aW9uIHRvIHRoZSBtYWdpYyBwb3RhdG9lcy4=","reward":144,"expiresIn":2,"encrypted":1,"probability":"R2FtYmxl"}]
				"""));

		assertThat(client.messages("g1")).containsExactly(new Ad("FHwPHE6e",
				"Investigate Abelone Alfredson and find out their relation to the magic potatoes.", 144, 2, "Gamble", null));
	}

	@Test
	void rot13AdIsDecodedAtTheBoundary() {
		expect(HttpMethod.GET, "/g1/messages", json("""
				[{"adId":"lYjlmdKJ","message":"Xvyy Pynerggn Onqpbpx jvgu orre zht naq znxr Oevnan Óynsffba sebz cnynpr va Funecjngre gb gnxr gur oynzr","reward":184,"expiresIn":2,"encrypted":2,"probability":"Vzcbffvoyr"}]
				"""));

		assertThat(client.messages("g1")).containsExactly(new Ad("yLwyzqXW",
				"Kill Claretta Badcock with beer mug and make Briana Ólafsson from palace in Sharpwater to take the blame",
				184, 2, "Impossible", null));
	}

	@Test
	void unknownEncryptionAndBrokenBase64StayEncrypted() {
		expect(HttpMethod.GET, "/g1/messages", json("""
				[{"adId":"abcd1234","message":"m","reward":1,"expiresIn":5,"probability":"p","encrypted":3},
				 {"adId":"!!","message":"m","reward":1,"expiresIn":5,"probability":"p","encrypted":1}]
				"""));

		assertThat(client.messages("g1")).extracting(Ad::encrypted).containsExactly("3", "1");
		server.verify();
	}

	// Finished game

	@Test
	void gone410IsGameOverOnEveryCall() {
		ResponseCreator over = withStatus(HttpStatus.GONE).contentType(MediaType.APPLICATION_JSON)
				.body("{\"status\":\"Game Over\"}");
		expect(HttpMethod.GET, "/g1/messages", over);
		expect(HttpMethod.GET, "/g1/shop", over);
		expect(HttpMethod.POST, "/g1/solve/a1", over);
		expect(HttpMethod.POST, "/g1/shop/buy/hpot", over);

		assertThatThrownBy(() -> client.messages("g1")).satisfies(kind(Kind.GAME_OVER));
		assertThatThrownBy(() -> client.shop("g1")).satisfies(kind(Kind.GAME_OVER));
		assertThatThrownBy(() -> client.solve("g1", "a1")).satisfies(kind(Kind.GAME_OVER));
		assertThatThrownBy(() -> client.buy("g1", "hpot")).satisfies(kind(Kind.GAME_OVER));
		server.verify();
	}

}
