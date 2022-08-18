package ca.uhn.fhir.jpa.search.builder;

import ca.uhn.fhir.interceptor.api.HookParams;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.jpa.dao.IResultIterator;
import ca.uhn.fhir.jpa.model.search.SearchRuntimeDetails;
import ca.uhn.fhir.jpa.model.search.StorageProcessingMessage;
import ca.uhn.fhir.jpa.search.builder.sql.SearchQueryExecutor;
import ca.uhn.fhir.jpa.util.BaseIterator;
import ca.uhn.fhir.jpa.util.CurrentThreadCaptureQueriesListener;
import ca.uhn.fhir.jpa.util.SqlQueryList;
import ca.uhn.fhir.rest.api.Constants;
import ca.uhn.fhir.rest.api.SortSpec;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.storage.ResourcePersistentId;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import ca.uhn.fhir.rest.server.util.CompositeInterceptorBroadcaster;
import ca.uhn.fhir.util.StopWatch;
import org.apache.commons.lang3.Validate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

public class QueryIterator extends BaseIterator<ResourcePersistentId> implements IResultIterator {

	private final SearchRuntimeDetails mySearchRuntimeDetails;
	private final RequestDetails myRequest;
	private final boolean myHaveRawSqlHooks;
	private final boolean myHavePerfTraceFoundIdHook;
	private final SortSpec mySort;
	private final Integer myOffset;
	private boolean myFirst = true;
	private SearchBuilder.IncludesIterator myIncludesIterator;
	private ResourcePersistentId myNext;
	private ISearchQueryExecutor myResultsIterator;
	private boolean myFetchIncludesForEverythingOperation;
	private int mySkipCount = 0;
	private int myNonSkipCount = 0;
	private List<ISearchQueryExecutor> myQueryList = new ArrayList<>();

	private SearchBuilder mySearchBuilderParent;

	private QueryIterator(SearchRuntimeDetails theSearchRuntimeDetails,
								 RequestDetails theRequest) {
		mySearchRuntimeDetails = theSearchRuntimeDetails;
		mySort = myParams.getSort();
		myOffset = myParams.getOffset();
		myRequest = theRequest;

		// Includes are processed inline for $everything query when we don't have a '_type' specified
		if (myParams.getEverythingMode() != null && !myParams.containsKey(Constants.PARAM_TYPE)) {
			myFetchIncludesForEverythingOperation = true;
		}

		myHavePerfTraceFoundIdHook = CompositeInterceptorBroadcaster.hasHooks(Pointcut.JPA_PERFTRACE_SEARCH_FOUND_ID, myInterceptorBroadcaster, myRequest);
		myHaveRawSqlHooks = CompositeInterceptorBroadcaster.hasHooks(Pointcut.JPA_PERFTRACE_RAW_SQL, myInterceptorBroadcaster, myRequest);

	}

	private void fetchNext() {
		try {
			if (myHaveRawSqlHooks) {
				CurrentThreadCaptureQueriesListener.startCapturing();
			}

			// If we don't have a query yet, create one
			if (myResultsIterator == null) {
				if (myMaxResultsToFetch == null) {
					if (myParams.getLoadSynchronousUpTo() != null) {
						myMaxResultsToFetch = myParams.getLoadSynchronousUpTo();
					} else if (myParams.getOffset() != null && myParams.getCount() != null) {
						myMaxResultsToFetch = myParams.getCount();
					} else {
						myMaxResultsToFetch = myDaoConfig.getFetchSizeDefaultMaximum();
					}
				}

				initializeIteratorQuery(myOffset, myMaxResultsToFetch);

				if (myAlsoIncludePids == null) {
					myAlsoIncludePids = new ArrayList<>();
				}
			}

			if (myNext == null) {


				for (Iterator<ResourcePersistentId> myPreResultsIterator = myAlsoIncludePids.iterator(); myPreResultsIterator.hasNext(); ) {
					ResourcePersistentId next = myPreResultsIterator.next();
					if (next != null)
						if (myPidSet.add(next)) {
							myNext = next;
							break;
						}
				}

				if (myNext == null) {
					while (myResultsIterator.hasNext() || !myQueryList.isEmpty()) {
						// Update iterator with next chunk if necessary.
						if (!myResultsIterator.hasNext()) {
							retrieveNextIteratorQuery();
						}

						Long nextLong = myResultsIterator.next();
						if (myHavePerfTraceFoundIdHook) {
							HookParams params = new HookParams()
								.add(Integer.class, System.identityHashCode(this))
								.add(Object.class, nextLong);
							CompositeInterceptorBroadcaster.doCallHooks(myInterceptorBroadcaster, myRequest, Pointcut.JPA_PERFTRACE_SEARCH_FOUND_ID, params);
						}

						if (nextLong != null) {
							ResourcePersistentId next = new ResourcePersistentId(nextLong);
							if (myPidSet.add(next)) {
								myNext = next;
								myNonSkipCount++;
								break;
							} else {
								mySkipCount++;
							}
						}

						if (!myResultsIterator.hasNext()) {
							if (myMaxResultsToFetch != null && (mySkipCount + myNonSkipCount == myMaxResultsToFetch)) {
								if (mySkipCount > 0 && myNonSkipCount == 0) {

									StorageProcessingMessage message = new StorageProcessingMessage();
									String msg = "Pass completed with no matching results seeking rows " + myPidSet.size() + "-" + mySkipCount + ". This indicates an inefficient query! Retrying with new max count of " + myMaxResultsToFetch;
									ourLog.warn(msg);
									message.setMessage(msg);
									HookParams params = new HookParams()
										.add(RequestDetails.class, myRequest)
										.addIfMatchesType(ServletRequestDetails.class, myRequest)
										.add(StorageProcessingMessage.class, message);
									CompositeInterceptorBroadcaster.doCallHooks(myInterceptorBroadcaster, myRequest, Pointcut.JPA_PERFTRACE_WARNING, params);

									myMaxResultsToFetch += 1000;
									initializeIteratorQuery(myOffset, myMaxResultsToFetch);
								}
							}
						}
					}
				}

				if (myNext == null) {
					if (myFetchIncludesForEverythingOperation) {
						myIncludesIterator = new SearchBuilder.IncludesIterator(myPidSet, myRequest);
						myFetchIncludesForEverythingOperation = false;
					}
					if (myIncludesIterator != null) {
						while (myIncludesIterator.hasNext()) {
							ResourcePersistentId next = myIncludesIterator.next();
							if (next != null)
								if (myPidSet.add(next)) {
									myNext = next;
									break;
								}
						}
						if (myNext == null) {
							myNext = NO_MORE;
						}
					} else {
						myNext = NO_MORE;
					}
				}

			} // if we need to fetch the next result

			mySearchRuntimeDetails.setFoundMatchesCount(myPidSet.size());

		} finally {
			if (myHaveRawSqlHooks) {
				SqlQueryList capturedQueries = CurrentThreadCaptureQueriesListener.getCurrentQueueAndStopCapturing();
				HookParams params = new HookParams()
					.add(RequestDetails.class, myRequest)
					.addIfMatchesType(ServletRequestDetails.class, myRequest)
					.add(SqlQueryList.class, capturedQueries);
				CompositeInterceptorBroadcaster.doCallHooks(myInterceptorBroadcaster, myRequest, Pointcut.JPA_PERFTRACE_RAW_SQL, params);
			}
		}

		if (myFirst) {
			HookParams params = new HookParams()
				.add(RequestDetails.class, myRequest)
				.addIfMatchesType(ServletRequestDetails.class, myRequest)
				.add(SearchRuntimeDetails.class, mySearchRuntimeDetails);
			CompositeInterceptorBroadcaster.doCallHooks(myInterceptorBroadcaster, myRequest, Pointcut.JPA_PERFTRACE_SEARCH_FIRST_RESULT_LOADED, params);
			myFirst = false;
		}

		if (NO_MORE.equals(myNext)) {
			HookParams params = new HookParams()
				.add(RequestDetails.class, myRequest)
				.addIfMatchesType(ServletRequestDetails.class, myRequest)
				.add(SearchRuntimeDetails.class, mySearchRuntimeDetails);
			CompositeInterceptorBroadcaster.doCallHooks(myInterceptorBroadcaster, myRequest, Pointcut.JPA_PERFTRACE_SEARCH_SELECT_COMPLETE, params);
		}

	}

	private void initializeIteratorQuery(Integer theOffset, Integer theMaxResultsToFetch) {
		if (myQueryList.isEmpty()) {
			// wipmb what is this?
			// Capture times for Lucene/Elasticsearch queries as well
			mySearchRuntimeDetails.setQueryStopwatch(new StopWatch());
			myQueryList = createQuery(myParams, mySort, theOffset, theMaxResultsToFetch, false, myRequest, mySearchRuntimeDetails);
		}

		mySearchRuntimeDetails.setQueryStopwatch(new StopWatch());

		retrieveNextIteratorQuery();

		mySkipCount = 0;
		myNonSkipCount = 0;
	}

	private void retrieveNextIteratorQuery() {
		close();
		if (myQueryList != null && myQueryList.size() > 0) {
			myResultsIterator = myQueryList.remove(0);
			myHasNextIteratorQuery = true;
		} else {
			myResultsIterator = SearchQueryExecutor.emptyExecutor();
			myHasNextIteratorQuery = false;
		}

	}

	@Override
	public boolean hasNext() {
		if (myNext == null) {
			fetchNext();
		}
		return !NO_MORE.equals(myNext);
	}

	@Override
	public ResourcePersistentId next() {
		fetchNext();
		ResourcePersistentId retVal = myNext;
		myNext = null;
		Validate.isTrue(!NO_MORE.equals(retVal), "No more elements");
		return retVal;
	}

	@Override
	public int getSkippedCount() {
		return mySkipCount;
	}

	@Override
	public int getNonSkippedCount() {
		return myNonSkipCount;
	}

	@Override
	public Collection<ResourcePersistentId> getNextResultBatch(long theBatchSize) {
		Collection<ResourcePersistentId> batch = new ArrayList<>();
		while (this.hasNext() && batch.size() < theBatchSize) {
			batch.add(this.next());
		}
		return batch;
	}

	@Override
	public void close() {
		if (myResultsIterator != null) {
			myResultsIterator.close();
		}
		myResultsIterator = null;
	}

}
