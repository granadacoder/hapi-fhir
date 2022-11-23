package ca.uhn.fhir.jpa.mdm.helper;

import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.api.model.DaoMethodOutcome;
import ca.uhn.fhir.jpa.dao.data.IMdmLinkJpaRepository;
import ca.uhn.fhir.jpa.dao.index.IdHelperService;
import ca.uhn.fhir.jpa.entity.MdmLink;
import ca.uhn.fhir.jpa.mdm.dao.MdmLinkDaoSvc;
import ca.uhn.fhir.jpa.mdm.helper.testmodels.MDMLinkResults;
import ca.uhn.fhir.jpa.mdm.helper.testmodels.MDMState;
import ca.uhn.fhir.jpa.partition.SystemRequestDetails;
import ca.uhn.fhir.mdm.api.MdmLinkSourceEnum;
import ca.uhn.fhir.mdm.api.MdmMatchOutcome;
import ca.uhn.fhir.mdm.api.MdmMatchResultEnum;
import ca.uhn.fhir.mdm.dao.IMdmLinkDao;
import ca.uhn.fhir.mdm.model.MdmTransactionContext;
import ca.uhn.fhir.mdm.util.MdmResourceUtil;
import ca.uhn.fhir.model.primitive.IdDt;
import ca.uhn.fhir.rest.api.server.storage.ResourcePersistentId;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Patient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Service
public class MdmLinkHelper {
	private static final Logger ourLog = LoggerFactory.getLogger(MdmLinkHelper.class);

	private enum Side {
		LHS, // left hand side; practically speaking, this is the GoldenResource of the link
		RHS // right hand side; practically speaking, this is the SourceResource of the link
	}

	@Autowired
   private IMdmLinkJpaRepository myMdmLinkRepo;
	@Autowired
	private IFhirResourceDao<Patient> myPatientDao;
	@Autowired
	private MdmLinkDaoSvc myMdmLinkDaoSvc;
	@SuppressWarnings("rawtypes")
	@Autowired
	private IMdmLinkDao myMdmLinkDao;
	@Autowired
	private IdHelperService myIdHelperService;

	@Transactional
	public void logMdmLinks() {
		List<MdmLink> links = myMdmLinkRepo.findAll();
		ourLog.info("All MDM Links:");
		for (MdmLink link : links) {
			IdDt goldenResourceId = link.getGoldenResource().getIdDt().toVersionless();
			IdDt targetId = link.getSource().getIdDt().toVersionless();
			ourLog.info("{}: {}, {}, {}, {}", link.getId(), goldenResourceId, targetId, link.getMatchResult(), link.getLinkSource());
		}
	}

	private MdmTransactionContext createContextForCreate(String theResourceType) {
		MdmTransactionContext ctx = new MdmTransactionContext();
		ctx.setRestOperation(MdmTransactionContext.OperationType.CREATE_RESOURCE);
		ctx.setResourceType(theResourceType);
		ctx.setTransactionLogMessages(null);
		return ctx;
	}

	/**
	 * Creates all the initial links specified in the state object.
	 *
	 * These links will be returned in an MDMLinkResults object, in case
	 * they are needed.
	 */
	public MDMLinkResults setup(MDMState<Patient> theState) {
		MDMLinkResults results = new MDMLinkResults();

		String[] inputs = theState.getParsedInputState();

		// create all patients if needed
		for (String inputState : inputs) {
			String[] params = MDMState.parseState(inputState);
			createIfNeeded(theState, params[0]);
			createIfNeeded(theState, params[3]);
		}

		// create all the links
		for (String inputState : theState.getParsedInputState()) {
			ourLog.info(inputState);
			String[] params = MDMState.parseState(inputState);

			Patient goldenResource = theState.getParameter(params[0]);
			Patient targetResource = theState.getParameter(params[3]);

			MdmLinkSourceEnum matchSourceType = MdmLinkSourceEnum.valueOf(params[1]);
			MdmMatchResultEnum matchResultType = MdmMatchResultEnum.valueOf(params[2]);

			MdmMatchOutcome matchOutcome = new MdmMatchOutcome(
				null,
				null
			);
			matchOutcome.setMatchResultEnum(matchResultType);

			MdmLink link = (MdmLink) myMdmLinkDaoSvc.createOrUpdateLinkEntity(
				goldenResource, // golden
				targetResource, // source
				matchOutcome, // match outcome
				matchSourceType, // link source
				createContextForCreate("Patient") // context
			);

			results.addResult(link);
		}

		return results;
	}

	private void createIfNeeded(MDMState<Patient> theState, String thePatientId) {
		Patient patient = theState.getParameter(thePatientId);
		if (patient == null) {
			// if it doesn't exist, create it
			patient = createPatientAndTags(thePatientId, theState);
			theState.addParameter(thePatientId, patient);
		}
	}

	private Patient createPatientAndTags(String theId, MDMState<Patient> theState) {
		Patient patient = new Patient();
		patient.setActive(true); // all mdm patients must be active

		// we add an identifier and use a forced id
		// to make test debugging a little simpler
		patient.addIdentifier(new Identifier().setValue(theId));
		patient.setId(theId);

		// Golden patients will be "PG#"
		if (theId.length() >= 2 && theId.charAt(1) == 'G') {
			// golden resource
			MdmResourceUtil.setGoldenResource(patient);
		}
		MdmResourceUtil.setMdmManaged(patient);

		DaoMethodOutcome outcome = myPatientDao.update(patient,
			SystemRequestDetails.forAllPartitions());
		Patient outputPatient = (Patient) outcome.getResource();
		theState.addPID(theId, outcome.getPersistentId());
		return outputPatient;
	}

	public void validateResults(MDMState<Patient> theState) {
		String[] expectedOutputState = theState.getParsedOutputState();

		StringBuilder outputStateSB = new StringBuilder();

		// for every parameter, we'll get all links
		for (Map.Entry<String, Patient> entrySet : theState.getParameterToValue().entrySet()) {
			Patient patient = entrySet.getValue();
			List<MdmLink> links = getAllMdmLinks(patient);
			for (MdmLink link : links) {
				if (!outputStateSB.isEmpty()) {
					outputStateSB.append("\n");
				}
				outputStateSB.append(createStateFromLink(link, theState));
				theState.addLinksForResource(patient, link);
			}
		}

		String actualOutputState = outputStateSB.toString();
		ourLog.info("Expected: \n" + theState.getOutputState());
		ourLog.info("Actual: \n" + actualOutputState);

		int totalExpectedLinks = expectedOutputState.length;
		int totalActualLinks = 0;
		for (Patient p : theState.getActualOutcomeLinks().keys()) {
			totalActualLinks += theState.getActualOutcomeLinks().get(p).size();
		}
		assertEquals(totalExpectedLinks, totalActualLinks,
			String.format("Invalid number of links. Expected %d, Actual %d.",
				totalExpectedLinks, totalActualLinks)
		);

		for (String state : expectedOutputState) {
			ourLog.info(state);
			String[] params = MDMState.parseState(state);

			Patient leftSideResource = theState.getParameter(params[0]);
			Collection<MdmLink> links = theState.getActualOutcomeLinks().get(leftSideResource);
			assertFalse(links.isEmpty(), String.format("No links found, but expected state: %s", state));

			MdmLinkSourceEnum matchSourceType = MdmLinkSourceEnum.valueOf(params[1]);
			MdmMatchResultEnum matchResultType = MdmMatchResultEnum.valueOf(params[2]);

			Patient rightSideResource = theState.getParameter(params[3]);

			boolean foundLink = false;
			for (MdmLink link : links) {
				if (isResourcePartOfLink(link, leftSideResource, Side.LHS, theState)
					&& isResourcePartOfLink(link, rightSideResource, Side.RHS, theState)
					&& link.getMatchResult() == matchResultType
					&& link.getLinkSource() == matchSourceType
				) {
					foundLink = true;
					break;
				}
			}

			assertTrue(foundLink, String.format("State: %s - not found", state));
		}
	}

	public List<MdmLink> getAllMdmLinks(Patient theGoldenPatient) {
		return myMdmLinkDaoSvc.findMdmLinksByGoldenResource(theGoldenPatient).stream()
			.map( link -> (MdmLink) link)
			.collect(Collectors.toList());
	}

	private boolean isResourcePartOfLink(
		MdmLink theLink,
		Patient theResource,
		Side theSide,
		MDMState<Patient> theState
	) {
		ResourcePersistentId resourcePid = theState.getPID(theResource.getIdElement().getIdPart());

		long linkPid;
		if (theSide == Side.LHS) {
			// LHS
			linkPid = theLink.getGoldenResourcePid();
		} else {
			// RHS
			linkPid = theLink.getSourcePid();
		}

		return linkPid == resourcePid.getIdAsLong();
	}

	private String createStateFromLink(MdmLink theLink, MDMState<Patient> theState) {
		String LHS = "";
		String RHS = "";
		for (Map.Entry<String, Patient> set : theState.getParameterToValue().entrySet()) {
			Patient patient = set.getValue();
			if (isResourcePartOfLink(theLink, patient, Side.LHS, theState)) {
				LHS = set.getKey();
			}
			if (isResourcePartOfLink(theLink, patient, Side.RHS, theState)) {
				RHS = set.getKey();
			}

			if (isNotBlank(LHS) && isNotBlank(RHS)) {
				boolean selfReferential = LHS.equals(RHS);

				String link = LHS + ", "
					+ theLink.getLinkSource().name() + ", "
					+ theLink.getMatchResult().name() + ", "
					+ RHS;
				if (selfReferential) {
					link += " <- Invalid Self Referencing link!";
				}
				return link;
			}
		}

		return "INVALID LINK: " + theLink.getId().toString();
	}
}
