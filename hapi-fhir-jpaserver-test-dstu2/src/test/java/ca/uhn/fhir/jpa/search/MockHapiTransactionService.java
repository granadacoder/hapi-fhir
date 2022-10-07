package ca.uhn.fhir.jpa.search;

import ca.uhn.fhir.jpa.dao.tx.HapiTransactionService;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.storage.TransactionDetails;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionCallback;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.Callable;

public class MockHapiTransactionService extends HapiTransactionService {


	@Override
	public <T> T execute(@Nullable RequestDetails theRequestDetails, @Nullable TransactionDetails theTransactionDetails, @Nonnull TransactionCallback<T> theCallback, @Nullable Runnable theOnRollback, @Nonnull Propagation thePropagation, @Nonnull Isolation theIsolation) {
		return theCallback.doInTransaction(null);
	}
}
