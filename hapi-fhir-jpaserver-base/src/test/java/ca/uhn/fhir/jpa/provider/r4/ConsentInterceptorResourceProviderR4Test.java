package ca.uhn.fhir.jpa.provider.r4;

import ca.uhn.fhir.jpa.config.BaseConfig;
import ca.uhn.fhir.jpa.config.TestR4Config;
import ca.uhn.fhir.jpa.dao.DaoConfig;
import ca.uhn.fhir.rest.api.Constants;
import ca.uhn.fhir.rest.api.PreferReturnEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.client.interceptor.CapturingInterceptor;
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import ca.uhn.fhir.rest.server.interceptor.consent.*;
import ca.uhn.fhir.util.BundleUtil;
import ca.uhn.fhir.util.UrlUtil;
import com.google.common.base.Charsets;
import com.google.common.collect.Lists;
import org.apache.commons.collections4.ListUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.Validate;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.apache.commons.lang3.StringUtils.leftPad;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = {TestR4Config.class})
public class ConsentInterceptorResourceProviderR4Test extends BaseResourceProviderR4Test {

	private static final Logger ourLog = LoggerFactory.getLogger(ConsentInterceptorResourceProviderR4Test.class);
	private List<String> myObservationIds;
	private List<String> myPatientIds;
	private List<String> myObservationIdsOddOnly;
	private List<String> myObservationIdsEvenOnly;
	private List<String> myObservationIdsEvenOnlyBackwards;
	private List<String> myObservationIdsBackwards;
	private ConsentInterceptor myConsentInterceptor;
	@Autowired
	@Qualifier(BaseConfig.GRAPHQL_PROVIDER_NAME)
	private Object myGraphQlProvider;

	@Override
	@After
	public void after() throws Exception {
		super.after();
		Validate.notNull(myConsentInterceptor);
		myDaoConfig.setSearchPreFetchThresholds(new DaoConfig().getSearchPreFetchThresholds());
		ourRestServer.getInterceptorService().unregisterInterceptor(myConsentInterceptor);
		ourRestServer.unregisterProvider(myGraphQlProvider);
	}

	@Override
	@Before
	public void before() throws Exception {
		super.before();
		myDaoConfig.setSearchPreFetchThresholds(Arrays.asList(20, 50, 190));
		ourRestServer.registerProvider(myGraphQlProvider);
	}

	@Test
	public void testSearchAndBlockSomeWithReject() {
		create50Observations();

		IConsentService consentService = new ConsentSvcCantSeeOddNumbered();
		myConsentInterceptor = new ConsentInterceptor(consentService);
		ourRestServer.getInterceptorService().registerInterceptor(myConsentInterceptor);

		// Perform a search
		Bundle result = ourClient
			.search()
			.forResource("Observation")
			.sort()
			.ascending(Observation.SP_IDENTIFIER)
			.returnBundle(Bundle.class)
			.count(15)
			.execute();
		List<IBaseResource> resources = BundleUtil.toListOfResources(myFhirCtx, result);
		List<String> returnedIdValues = toUnqualifiedVersionlessIdValues(resources);
		assertEquals(myObservationIdsEvenOnly.subList(0, 15), returnedIdValues);

		// Fetch the next page
		result = ourClient
			.loadPage()
			.next(result)
			.execute();
		resources = BundleUtil.toListOfResources(myFhirCtx, result);
		returnedIdValues = toUnqualifiedVersionlessIdValues(resources);
		assertEquals(myObservationIdsEvenOnly.subList(15, 25), returnedIdValues);
	}

	/**
	 * Make sure that the query cache doesn't get used at all if the consent
	 * service wants to inspect a request
	 */
	@Test
	public void testSearchAndBlockSome_DontReuseSearches() {
		create50Observations();

		CapturingInterceptor capture = new CapturingInterceptor();
		ourClient.registerInterceptor(capture);

		DelegatingConsentService consentService = new DelegatingConsentService();
		myConsentInterceptor = new ConsentInterceptor(consentService);
		ourRestServer.getInterceptorService().registerInterceptor(myConsentInterceptor);

		// Perform a search and only allow even
		consentService.setTarget(new ConsentSvcCantSeeOddNumbered());
		Bundle result = ourClient
			.search()
			.forResource("Observation")
			.sort()
			.ascending(Observation.SP_IDENTIFIER)
			.returnBundle(Bundle.class)
			.count(15)
			.execute();
		List<IBaseResource> resources = BundleUtil.toListOfResources(myFhirCtx, result);
		List<String> returnedIdValues = toUnqualifiedVersionlessIdValues(resources);
		assertEquals(myObservationIdsEvenOnly.subList(0, 15), returnedIdValues);
		List<String> cacheOutcome = capture.getLastResponse().getHeaders(Constants.HEADER_X_CACHE);
		assertEquals(0, cacheOutcome.size());

		// Perform a search and only allow odd
		consentService.setTarget(new ConsentSvcCantSeeEvenNumbered());
		result = ourClient
			.search()
			.forResource("Observation")
			.sort()
			.ascending(Observation.SP_IDENTIFIER)
			.returnBundle(Bundle.class)
			.count(15)
			.execute();
		resources = BundleUtil.toListOfResources(myFhirCtx, result);
		returnedIdValues = toUnqualifiedVersionlessIdValues(resources);
		assertEquals(myObservationIdsOddOnly.subList(0, 15), returnedIdValues);
		cacheOutcome = capture.getLastResponse().getHeaders(Constants.HEADER_X_CACHE);
		assertEquals(0, cacheOutcome.size());

		// Perform a search and allow all with a PROCEED
		consentService.setTarget(new ConsentSvcNop(ConsentOperationStatusEnum.PROCEED));
		result = ourClient
			.search()
			.forResource("Observation")
			.sort()
			.ascending(Observation.SP_IDENTIFIER)
			.returnBundle(Bundle.class)
			.count(15)
			.execute();
		resources = BundleUtil.toListOfResources(myFhirCtx, result);
		returnedIdValues = toUnqualifiedVersionlessIdValues(resources);
		assertEquals(myObservationIds.subList(0, 15), returnedIdValues);
		cacheOutcome = capture.getLastResponse().getHeaders(Constants.HEADER_X_CACHE);
		assertEquals(0, cacheOutcome.size());

		// Perform a search and allow all with an AUTHORIZED (no further checking)
		consentService.setTarget(new ConsentSvcNop(ConsentOperationStatusEnum.AUTHORIZED));
		result = ourClient
			.search()
			.forResource("Observation")
			.sort()
			.ascending(Observation.SP_IDENTIFIER)
			.returnBundle(Bundle.class)
			.count(15)
			.execute();
		resources = BundleUtil.toListOfResources(myFhirCtx, result);
		returnedIdValues = toUnqualifiedVersionlessIdValues(resources);
		assertEquals(myObservationIds.subList(0, 15), returnedIdValues);
		cacheOutcome = capture.getLastResponse().getHeaders(Constants.HEADER_X_CACHE);
		assertEquals(0, cacheOutcome.size());

		// Perform a second search and allow all with an AUTHORIZED (no further checking)
		// which means we should finally get one from the cache
		consentService.setTarget(new ConsentSvcNop(ConsentOperationStatusEnum.AUTHORIZED));
		result = ourClient
			.search()
			.forResource("Observation")
			.sort()
			.ascending(Observation.SP_IDENTIFIER)
			.returnBundle(Bundle.class)
			.count(15)
			.execute();
		resources = BundleUtil.toListOfResources(myFhirCtx, result);
		returnedIdValues = toUnqualifiedVersionlessIdValues(resources);
		assertEquals(myObservationIds.subList(0, 15), returnedIdValues);
		cacheOutcome = capture.getLastResponse().getHeaders(Constants.HEADER_X_CACHE);
		assertThat(cacheOutcome.get(0), matchesPattern("^HIT from .*"));

		ourClient.unregisterInterceptor(capture);
	}

	@Test
	public void testSearchMaskSubject() {
		create50Observations();

		IConsentService consentService = new ConsentSvcMaskObservationSubjects();
		myConsentInterceptor = new ConsentInterceptor(consentService);
		ourRestServer.getInterceptorService().registerInterceptor(myConsentInterceptor);

		// Perform a search
		Bundle result = ourClient
			.search()
			.forResource("Observation")
			.sort()
			.ascending(Observation.SP_IDENTIFIER)
			.returnBundle(Bundle.class)
			.count(15)
			.execute();
		List<IBaseResource> resources = BundleUtil.toListOfResources(myFhirCtx, result);
		assertEquals(15, resources.size());
		resources.forEach(t -> {
			assertEquals(null, ((Observation) t).getSubject().getReference());
		});

		// Fetch the next page
		result = ourClient
			.loadPage()
			.next(result)
			.execute();
		resources = BundleUtil.toListOfResources(myFhirCtx, result);
		assertEquals(15, resources.size());
		resources.forEach(t -> {
			assertEquals(null, ((Observation) t).getSubject().getReference());
		});
	}

	@Test
	public void testHistoryAndBlockSome() {
		create50Observations();

		IConsentService consentService = new ConsentSvcCantSeeOddNumbered();
		myConsentInterceptor = new ConsentInterceptor(consentService);
		ourRestServer.getInterceptorService().registerInterceptor(myConsentInterceptor);

		// Perform a search
		Bundle result = ourClient
			.history()
			.onServer()
			.returnBundle(Bundle.class)
			.count(10)
			.execute();
		List<IBaseResource> resources = BundleUtil.toListOfResources(myFhirCtx, result);
		List<String> returnedIdValues = toUnqualifiedVersionlessIdValues(resources);
		assertEquals(myObservationIdsEvenOnlyBackwards.subList(0, 5), returnedIdValues);

	}

	@Test
	public void testReadAndBlockSome() {
		create50Observations();

		IConsentService consentService = new ConsentSvcCantSeeOddNumbered();
		myConsentInterceptor = new ConsentInterceptor(consentService);
		ourRestServer.getInterceptorService().registerInterceptor(myConsentInterceptor);

		ourClient.read().resource("Observation").withId(new IdType(myObservationIdsEvenOnly.get(0))).execute();
		ourClient.read().resource("Observation").withId(new IdType(myObservationIdsEvenOnly.get(1))).execute();

		try {
			ourClient.read().resource("Observation").withId(new IdType(myObservationIdsOddOnly.get(0))).execute();
			fail();
		} catch (ResourceNotFoundException e) {
			// good
		}
		try {
			ourClient.read().resource("Observation").withId(new IdType(myObservationIdsOddOnly.get(1))).execute();
			fail();
		} catch (ResourceNotFoundException e) {
			// good
		}

	}

	@Test
	public void testCreateBlockResponse() throws IOException {
		create50Observations();

		DelegatingConsentService consentService = new DelegatingConsentService();
		myConsentInterceptor = new ConsentInterceptor(consentService);
		ourRestServer.getInterceptorService().registerInterceptor(myConsentInterceptor);

		Patient patient = new Patient();
		patient.setActive(true);

		// Reject output
		consentService.setTarget(new ConsentSvcRejectSeeingAnything());
		HttpPost post = new HttpPost(ourServerBase + "/Patient");
		post.addHeader(Constants.HEADER_PREFER, Constants.HEADER_PREFER_RETURN + '=' + Constants.HEADER_PREFER_RETURN_REPRESENTATION);
		post.setEntity(toEntity(patient));
		try (CloseableHttpResponse status = ourHttpClient.execute(post)) {
			String id = status.getFirstHeader(Constants.HEADER_CONTENT_LOCATION).getValue();
			assertThat(id, matchesPattern("^.*/Patient/[0-9]+/_history/[0-9]+$"));
			assertEquals(201, status.getStatusLine().getStatusCode());
			String responseString = IOUtils.toString(status.getEntity().getContent(), Charsets.UTF_8);
			assertThat(responseString, blankOrNullString());
			assertNull(status.getEntity().getContentType());
		}

		// Accept output
		consentService.setTarget(new ConsentSvcNop(ConsentOperationStatusEnum.PROCEED));
		post = new HttpPost(ourServerBase + "/Patient");
		post.addHeader(Constants.HEADER_PREFER, Constants.HEADER_PREFER_RETURN + '=' + Constants.HEADER_PREFER_RETURN_REPRESENTATION);
		post.setEntity(toEntity(patient));
		try (CloseableHttpResponse status = ourHttpClient.execute(post)) {
			String id = status.getFirstHeader(Constants.HEADER_CONTENT_LOCATION).getValue();
			assertThat(id, matchesPattern("^.*/Patient/[0-9]+/_history/[0-9]+$"));
			assertEquals(201, status.getStatusLine().getStatusCode());
			assertNotNull(status.getEntity());
			String responseString = IOUtils.toString(status.getEntity().getContent(), Charsets.UTF_8);
			assertThat(responseString, not(blankOrNullString()));
			assertThat(status.getEntity().getContentType().getValue().toLowerCase(), matchesPattern(".*json.*"));
		}

	}

	@Test
	public void testUpdateBlockResponse() throws IOException {
		create50Observations();

		Patient patient = new Patient();
		patient.setActive(true);
		IIdType id = ourClient.create().resource(patient).prefer(PreferReturnEnum.REPRESENTATION).execute().getId().toUnqualifiedVersionless();

		DelegatingConsentService consentService = new DelegatingConsentService();
		myConsentInterceptor = new ConsentInterceptor(consentService);
		ourRestServer.getInterceptorService().registerInterceptor(myConsentInterceptor);

		// Reject output
		consentService.setTarget(new ConsentSvcRejectSeeingAnything());
		patient = new Patient();
		patient.setId(id);
		patient.setActive(true);
		patient.addIdentifier().setValue("VAL1");
		HttpPut put = new HttpPut(ourServerBase + "/Patient/" + id.getIdPart());
		put.addHeader(Constants.HEADER_PREFER, Constants.HEADER_PREFER_RETURN + '=' + Constants.HEADER_PREFER_RETURN_REPRESENTATION);
		put.setEntity(toEntity(patient));
		try (CloseableHttpResponse status = ourHttpClient.execute(put)) {
			String idVal = status.getFirstHeader(Constants.HEADER_CONTENT_LOCATION).getValue();
			assertThat(idVal, matchesPattern("^.*/Patient/[0-9]+/_history/[0-9]+$"));
			assertEquals(200, status.getStatusLine().getStatusCode());
			String responseString = IOUtils.toString(status.getEntity().getContent(), Charsets.UTF_8);
			assertThat(responseString, blankOrNullString());
			assertNull(status.getEntity().getContentType());
		}

		// Accept output
		consentService.setTarget(new ConsentSvcNop(ConsentOperationStatusEnum.PROCEED));
		patient = new Patient();
		patient.setId(id);
		patient.setActive(true);
		patient.addIdentifier().setValue("VAL2");
		put = new HttpPut(ourServerBase + "/Patient/" + id.getIdPart());
		put.addHeader(Constants.HEADER_PREFER, Constants.HEADER_PREFER_RETURN + '=' + Constants.HEADER_PREFER_RETURN_REPRESENTATION);
		put.setEntity(toEntity(patient));
		try (CloseableHttpResponse status = ourHttpClient.execute(put)) {
			String idVal = status.getFirstHeader(Constants.HEADER_CONTENT_LOCATION).getValue();
			assertThat(idVal, matchesPattern("^.*/Patient/[0-9]+/_history/[0-9]+$"));
			assertEquals(200, status.getStatusLine().getStatusCode());
			assertNotNull(status.getEntity());
			String responseString = IOUtils.toString(status.getEntity().getContent(), Charsets.UTF_8);
			assertThat(responseString, not(blankOrNullString()));
			assertThat(status.getEntity().getContentType().getValue().toLowerCase(), matchesPattern(".*json.*"));
		}

	}

	@Test
	public void testGraphQL_Proceed() throws IOException {
		createPatientAndOrg();

		DelegatingConsentService consentService = new DelegatingConsentService();
		myConsentInterceptor = new ConsentInterceptor(consentService);
		ourRestServer.getInterceptorService().registerInterceptor(myConsentInterceptor);

		// Proceed everything
		consentService.setTarget(new ConsentSvcNop(ConsentOperationStatusEnum.PROCEED));
		String query = "{ name { family, given }, managingOrganization { reference, resource {name} } }";
		String url = ourServerBase + "/" + myPatientIds.get(0) + "/$graphql?query=" + UrlUtil.escapeUrlParam(query);
		ourLog.info("HTTP GET {}", url);
		HttpGet get = new HttpGet(url);
		get.addHeader(Constants.HEADER_ACCEPT, Constants.CT_JSON);
		try (CloseableHttpResponse status = ourHttpClient.execute(get)) {
			String responseString = IOUtils.toString(status.getEntity().getContent(), Charsets.UTF_8);
			ourLog.info("Response: {}", responseString);
			assertEquals(200, status.getStatusLine().getStatusCode());
			assertThat(responseString, containsString("\"family\":\"PATIENT_FAMILY\""));
			assertThat(responseString, containsString("\"given\":[\"PATIENT_GIVEN1\",\"PATIENT_GIVEN2\"]"));
		}

	}

	@Test
	public void testGraphQL_RejectResource() throws IOException {
		createPatientAndOrg();

		DelegatingConsentService consentService = new DelegatingConsentService();
		myConsentInterceptor = new ConsentInterceptor(consentService);
		ourRestServer.getInterceptorService().registerInterceptor(myConsentInterceptor);

		// Proceed everything
		consentService.setTarget(new ConsentSvcNop(ConsentOperationStatusEnum.PROCEED));
		String query = "{ name { family, given } }";
		String url = ourServerBase + "/" + myPatientIds.get(0) + "/$graphql?query=" + UrlUtil.escapeUrlParam(query);
		ourLog.info("HTTP GET {}", url);
		HttpGet get = new HttpGet(url);
		get.addHeader(Constants.HEADER_ACCEPT, Constants.CT_JSON);
		try (CloseableHttpResponse status = ourHttpClient.execute(get)) {
			String responseString = IOUtils.toString(status.getEntity().getContent(), Charsets.UTF_8);
			ourLog.info("Response: {}", responseString);
			assertEquals(200, status.getStatusLine().getStatusCode());
			assertThat(responseString, containsString("\"family\":\"PATIENT_FAMILY\""));
			assertThat(responseString, containsString("\"given\":[\"PATIENT_GIVEN1\",\"PATIENT_GIVEN2\"]"));
		}

	}

	private void createPatientAndOrg() {
		myPatientIds = new ArrayList<>();

		Organization org = new Organization();
		org.setName("ORG_NAME");
		IIdType orgId = myOrganizationDao.create(org).getId().toUnqualifiedVersionless();

		Patient p = new Patient();
		p.setActive(true);
		p.addName().setFamily("PATIENT_FAMILY").addGiven("PATIENT_GIVEN1").addGiven("PATIENT_GIVEN2");
		p.getManagingOrganization().setReference(orgId.getValue());
		String pid = myPatientDao.create(p).getId().toUnqualifiedVersionless().getValue();
		myPatientIds.add(pid);
	}

	private void create50Observations() {
		myPatientIds = new ArrayList<>();
		myObservationIds = new ArrayList<>();

		Patient p = new Patient();
		p.setActive(true);
		String pid = myPatientDao.create(p).getId().toUnqualifiedVersionless().getValue();
		myPatientIds.add(pid);

		for (int i = 0; i < 50; i++) {
			final Observation obs1 = new Observation();
			obs1.setStatus(Observation.ObservationStatus.FINAL);
			obs1.addIdentifier().setSystem("urn:system").setValue("I" + leftPad("" + i, 5, '0'));
			obs1.getSubject().setReference(pid);
			IIdType obs1id = myObservationDao.create(obs1).getId().toUnqualifiedVersionless();
			myObservationIds.add(obs1id.toUnqualifiedVersionless().getValue());
		}

		myObservationIdsEvenOnly =
			myObservationIds
				.stream()
				.filter(t -> Long.parseLong(t.substring(t.indexOf('/') + 1)) % 2 == 0)
				.collect(Collectors.toList());

		myObservationIdsOddOnly = ListUtils.removeAll(myObservationIds, myObservationIdsEvenOnly);
		myObservationIdsBackwards = Lists.reverse(myObservationIds);
		myObservationIdsEvenOnlyBackwards = Lists.reverse(myObservationIdsEvenOnly);
	}

	private HttpEntity toEntity(Patient thePatient) {
		String encoded = myFhirCtx.newJsonParser().encodeResourceToString(thePatient);
		ContentType cs = ContentType.create(Constants.CT_FHIR_JSON, Constants.CHARSET_UTF8);
		return new StringEntity(encoded, cs);
	}

	private class ConsentSvcMaskObservationSubjects implements IConsentService {

		@Override
		public ConsentOutcome startOperation(RequestDetails theRequestDetails) {
			return ConsentOutcome.PROCEED;
		}

		@Override
		public ConsentOutcome canSeeResource(RequestDetails theRequestDetails, IBaseResource theResource) {
			return ConsentOutcome.PROCEED;
		}

		@Override
		public ConsentOutcome seeResource(RequestDetails theRequestDetails, IBaseResource theResource) {
			if (theResource instanceof Observation) {
				((Observation) theResource).getSubject().setReference("");
				((Observation) theResource).getSubject().setResource(null);
				return new ConsentOutcome(ConsentOperationStatusEnum.PROCEED, theResource);
			}
			return ConsentOutcome.PROCEED;
		}

		@Override
		public void completeOperationSuccess(RequestDetails theRequestDetails) {
			// nothing
		}

		@Override
		public void completeOperationFailure(RequestDetails theRequestDetails, BaseServerResponseException theException) {
			// nothing
		}


	}

	private static class ConsentSvcCantSeeOddNumbered implements IConsentService {

		@Override
		public ConsentOutcome startOperation(RequestDetails theRequestDetails) {
			return new ConsentOutcome(ConsentOperationStatusEnum.PROCEED);
		}

		@Override
		public ConsentOutcome canSeeResource(RequestDetails theRequestDetails, IBaseResource theResource) {
			Long resIdLong = theResource.getIdElement().getIdPartAsLong();
			if (resIdLong % 2 == 1) {
				return new ConsentOutcome(ConsentOperationStatusEnum.REJECT);
			}
			return new ConsentOutcome(ConsentOperationStatusEnum.PROCEED);
		}

		@Override
		public ConsentOutcome seeResource(RequestDetails theRequestDetails, IBaseResource theResource) {
			return ConsentOutcome.PROCEED;
		}

		@Override
		public void completeOperationSuccess(RequestDetails theRequestDetails) {
			// nothing
		}

		@Override
		public void completeOperationFailure(RequestDetails theRequestDetails, BaseServerResponseException theException) {
			// nothing
		}


	}

	private static class ConsentSvcCantSeeEvenNumbered implements IConsentService {

		@Override
		public ConsentOutcome startOperation(RequestDetails theRequestDetails) {
			return new ConsentOutcome(ConsentOperationStatusEnum.PROCEED);
		}

		@Override
		public ConsentOutcome canSeeResource(RequestDetails theRequestDetails, IBaseResource theResource) {
			Long resIdLong = theResource.getIdElement().getIdPartAsLong();
			if (resIdLong % 2 == 0) {
				return new ConsentOutcome(ConsentOperationStatusEnum.REJECT);
			}
			return new ConsentOutcome(ConsentOperationStatusEnum.PROCEED);
		}

		@Override
		public ConsentOutcome seeResource(RequestDetails theRequestDetails, IBaseResource theResource) {
			return ConsentOutcome.PROCEED;
		}

		@Override
		public void completeOperationSuccess(RequestDetails theRequestDetails) {
			// nothing
		}

		@Override
		public void completeOperationFailure(RequestDetails theRequestDetails, BaseServerResponseException theException) {
			// nothing
		}


	}

	private static class ConsentSvcNop implements IConsentService {

		private final ConsentOperationStatusEnum myOperationStatus;

		private ConsentSvcNop(ConsentOperationStatusEnum theOperationStatus) {
			myOperationStatus = theOperationStatus;
		}

		@Override
		public ConsentOutcome startOperation(RequestDetails theRequestDetails) {
			return new ConsentOutcome(myOperationStatus);
		}

		@Override
		public ConsentOutcome canSeeResource(RequestDetails theRequestDetails, IBaseResource theResource) {
			return new ConsentOutcome(ConsentOperationStatusEnum.PROCEED);
		}

		@Override
		public ConsentOutcome seeResource(RequestDetails theRequestDetails, IBaseResource theResource) {
			return ConsentOutcome.PROCEED;
		}

		@Override
		public void completeOperationSuccess(RequestDetails theRequestDetails) {
			// nothing
		}

		@Override
		public void completeOperationFailure(RequestDetails theRequestDetails, BaseServerResponseException theException) {
			// nothing
		}


	}

	private static class ConsentSvcRejectSeeingAnything implements IConsentService {

		@Override
		public ConsentOutcome startOperation(RequestDetails theRequestDetails) {
			return ConsentOutcome.PROCEED;
		}

		@Override
		public ConsentOutcome canSeeResource(RequestDetails theRequestDetails, IBaseResource theResource) {
			return ConsentOutcome.REJECT;
		}

		@Override
		public ConsentOutcome seeResource(RequestDetails theRequestDetails, IBaseResource theResource) {
			return ConsentOutcome.PROCEED;
		}

		@Override
		public void completeOperationSuccess(RequestDetails theRequestDetails) {
			// nothing
		}

		@Override
		public void completeOperationFailure(RequestDetails theRequestDetails, BaseServerResponseException theException) {
			// nothing
		}


	}
}
