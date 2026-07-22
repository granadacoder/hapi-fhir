package sample.fhir.server.jersey;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
public class SampleJerseyRestfulServerApplicationTest {

	@Autowired
	private WebTestClient webTestClient;

	@Test
	public void metadata() {
		webTestClient.get()
			.uri("/fhir/metadata")
			.exchange()
			.expectStatus().isOk()
			.expectBody(String.class)
			.consumeWith(response -> assertThat(response.getResponseBody()).contains("\"status\": \"active\""));
	}

	@Test
	public void patientResource() {
		webTestClient.get()
			.uri("/fhir/Patient/1")
			.exchange()
			.expectStatus().isOk()
			.expectBody(String.class)
			.consumeWith(response -> assertThat(response.getResponseBody()).contains("\"family\": \"Van Houte\""));
	}

}
