package io.github.amtedrema.bigbanktheory.mugloar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.ServerSocket;

import io.github.amtedrema.bigbanktheory.mugloar.MugloarException.Kind;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The read timeout, checked without a network: a local socket that accepts but never answers. */
@SpringBootTest(properties = "spring.http.clients.read-timeout=300ms")
class MugloarConfigTest {

	private static final ServerSocket SILENT_SERVER = silentServer();

	@Autowired
	MugloarClient client;

	@DynamicPropertySource
	static void baseUrl(DynamicPropertyRegistry registry) {
		registry.add("mugloar.base-url", () -> "http://localhost:" + SILENT_SERVER.getLocalPort());
	}

	@AfterAll
	static void closeServer() throws IOException {
		SILENT_SERVER.close();
	}

	@Test
	@Timeout(5)
	void readTimeoutFromPropertiesIsAppliedAndMappedToUpstreamUnavailable() {
		assertThatThrownBy(() -> client.start())
				.isInstanceOfSatisfying(MugloarException.class, e -> assertThat(e.kind()).isEqualTo(Kind.UPSTREAM_UNAVAILABLE));
	}

	private static ServerSocket silentServer() {
		try {
			return new ServerSocket(0);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}
}
