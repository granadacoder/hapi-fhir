package ca.uhn.fhir.jpa.subscription.websocket;

import ca.uhn.fhir.jpa.provider.dstu3.BaseResourceProviderDstu3Test;
import ca.uhn.fhir.jpa.subscription.FhirDstu3Util;
import ca.uhn.fhir.jpa.test.util.SubscriptionTestUtil;
import ca.uhn.fhir.jpa.util.WebsocketSubscriptionClient;
import ca.uhn.fhir.rest.api.MethodOutcome;
import org.hl7.fhir.dstu3.model.CodeableConcept;
import org.hl7.fhir.dstu3.model.Coding;
import org.hl7.fhir.dstu3.model.Observation;
import org.hl7.fhir.dstu3.model.Patient;
import org.hl7.fhir.dstu3.model.Reference;
import org.hl7.fhir.dstu3.model.Subscription;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;

/**
 * Adds a FHIR subscription with criteria through the rest interface. Then creates a websocket with the id of the
 * subscription
 * <p>
 * Note: This test only returns a ping with the subscription id, Check FhirSubscriptionWithSubscriptionIdDstu3Test for
 * a test that returns the xml of the observation
 * <p>
 * To execute the following test, execute it the following way:
 * 0. execute 'clean' test
 * 1. Execute the 'createPatient' test
 * 2. Update the patient id static variable
 * 3. Execute the 'createSubscription' test
 * 4. Update the subscription id static variable
 * 5. Execute the 'attachWebSocket' test
 * 6. Execute the 'sendObservation' test
 * 7. Look in the 'attachWebSocket' terminal execution and wait for your ping with the subscription id
 */
public class WebsocketWithSubscriptionIdDstu3Test extends BaseResourceProviderDstu3Test {

	private static final Logger ourLog = org.slf4j.LoggerFactory.getLogger(WebsocketWithSubscriptionIdDstu3Test.class);
	@RegisterExtension
	private final WebsocketSubscriptionClient myWebsocketClientExtension = new WebsocketSubscriptionClient(() -> myServer, () -> myModelConfig);
	private String myPatientId;
	private String mySubscriptionId;
	@Autowired
	private SubscriptionTestUtil mySubscriptionTestUtil;

	@Override
	@AfterEach
	public void after() throws Exception {
		super.after();

		mySubscriptionTestUtil.unregisterSubscriptionInterceptor();
	}

	@Override
	@BeforeEach
	public void before() throws Exception {
		super.before();

		mySubscriptionTestUtil.registerWebSocketInterceptor();

		/*
		 * Create patient
		 */

		Patient patient = FhirDstu3Util.getPatient();
		MethodOutcome methodOutcome = ourClient.create().resource(patient).execute();
		myPatientId = methodOutcome.getId().getIdPart();

		/*
		 * Create subscription
		 */
		Subscription subscription = new Subscription();
		subscription.setReason("Monitor new neonatal function (note, age will be determined by the monitor)");
		subscription.setStatus(Subscription.SubscriptionStatus.ACTIVE);
		// subscription.setCriteria("Observation?subject=Patient/" + PATIENT_ID);
		subscription.setCriteria("Observation?code=SNOMED-CT|82313006");

		Subscription.SubscriptionChannelComponent channel = new Subscription.SubscriptionChannelComponent();
		channel.setType(Subscription.SubscriptionChannelType.WEBSOCKET);
		channel.setPayload("application/json");
		subscription.setChannel(channel);

		methodOutcome = ourClient.create().resource(subscription).execute();
		mySubscriptionId = methodOutcome.getId().getIdPart();

		myWebsocketClientExtension.bind(mySubscriptionId);
	}

	@Test
	public void createObservation() {
		Observation observation = new Observation();
		CodeableConcept codeableConcept = new CodeableConcept();
		observation.setCode(codeableConcept);
		Coding coding = codeableConcept.addCoding();
		coding.setCode("82313006");
		coding.setSystem("SNOMED-CT");
		Reference reference = new Reference();
		reference.setReference("Patient/" + myPatientId);
		observation.setSubject(reference);
		observation.setStatus(Observation.ObservationStatus.FINAL);

		MethodOutcome methodOutcome2 = ourClient.create().resource(observation).execute();
		String observationId = methodOutcome2.getId().getIdPart();
		observation.setId(observationId);

		ourLog.info("Observation id generated by server is: " + observationId);

		ourLog.info("WS Messages: {}", myWebsocketClientExtension.getMessages());
		waitForSize(2, myWebsocketClientExtension.getMessages());
		assertThat(myWebsocketClientExtension.getMessages(), contains("bound " + mySubscriptionId, "ping " + mySubscriptionId));
	}

	@Test
	public void createObservationThatDoesNotMatch() {
		Observation observation = new Observation();
		CodeableConcept codeableConcept = new CodeableConcept();
		observation.setCode(codeableConcept);
		Coding coding = codeableConcept.addCoding();
		coding.setCode("8231");
		coding.setSystem("SNOMED-CT");
		Reference reference = new Reference();
		reference.setReference("Patient/" + myPatientId);
		observation.setSubject(reference);
		observation.setStatus(Observation.ObservationStatus.FINAL);

		MethodOutcome methodOutcome2 = ourClient.create().resource(observation).execute();
		String observationId = methodOutcome2.getId().getIdPart();
		observation.setId(observationId);

		ourLog.info("Observation id generated by server is: " + observationId);

		ourLog.info("WS Messages: {}", myWebsocketClientExtension.getMessages());
		waitForSize(1, myWebsocketClientExtension.getMessages());
		assertThat(myWebsocketClientExtension.getMessages(), contains("bound " + mySubscriptionId));
	}
}
