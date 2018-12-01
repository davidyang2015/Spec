package com.specmate.persistency.cdo.internal;

import java.util.List;
import java.util.Map;

import org.eclipse.emf.cdo.CDOObject;
import org.eclipse.emf.cdo.common.commit.CDOChangeSetData;
import org.eclipse.emf.cdo.common.id.CDOID;
import org.eclipse.emf.cdo.common.id.CDOIDUtil;
import org.eclipse.emf.cdo.common.revision.CDOIDAndVersion;
import org.eclipse.emf.cdo.transaction.CDOTransaction;
import org.eclipse.emf.cdo.util.CommitException;
import org.eclipse.emf.cdo.view.CDOQuery;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.net4j.util.concurrent.TimeoutRuntimeException;
import org.osgi.service.log.LogService;

import com.specmate.administration.api.IStatusService;
import com.specmate.common.SpecmateException;
import com.specmate.common.SpecmateValidationException;
import com.specmate.model.base.INamed;
import com.specmate.model.base.ISpecmateModelObject;
import com.specmate.model.support.util.SpecmateEcoreUtil;
import com.specmate.persistency.IChange;
import com.specmate.persistency.IChangeListener;
import com.specmate.persistency.ITransaction;
import com.specmate.persistency.event.EChangeKind;
import com.specmate.rest.RestResult;

/**
 * Implements ITransaction with CDO in the back
 *
 */
public class TransactionImpl extends ViewImpl implements ITransaction {
	/* The CDO transaction */
	private CDOTransaction transaction;

	/* The log service */
	private LogService logService;

	/* Listeners that are notified on commits */
	private List<IChangeListener> changeListeners;
	private IStatusService statusService;

	public TransactionImpl(CDOPersistencyService persistency, CDOTransaction transaction, String resourceName,
			LogService logService, IStatusService statusService, List<IChangeListener> listeners) {
		super(persistency, transaction, resourceName, logService);
		this.transaction = transaction;
		this.logService = logService;
		this.statusService = statusService;
		this.changeListeners = listeners;

	}

	@Override
	public void close() {
		if (transaction != null) {
			transaction.close();
			persistency.closedTransaction(this);
		}
		logService.log(LogService.LOG_DEBUG, "Transaction closed: " + transaction.getViewID());
	}

	private <T> void commit(T object) throws SpecmateException {
		if (!isActive()) {
			throw new SpecmateException("Attempt to commit but transaction is not active");
		}
		if (!isDirty()) {
			return;
		}
		if (statusService != null && statusService.getCurrentStatus().isReadOnly()) {
			throw new SpecmateException("Attempt to commit when in read-only mode");
		}
		try {
			List<CDOIDAndVersion> detachedObjects;
			try {
				notifyListeners();
				detachedObjects = transaction.getChangeSetData().getDetachedObjects();
				for (CDOIDAndVersion id : detachedObjects) {
					SpecmateEcoreUtil.unsetAllReferences(transaction.getObject(id.getID()));
				}
			} catch (SpecmateException s) {
				transaction.rollback();
				throw (new SpecmateException("Error while preparing commit, transaction rolled back", s));
			}
			setMetadata(object, detachedObjects);
			transaction.commit();
		} catch (CommitException e) {
			transaction.rollback();
			String message = "Error during commit, transaction rolled back";
			logService.log(LogService.LOG_ERROR, message);
			throw new SpecmateException(message, e);
		} catch (TimeoutRuntimeException e) {
			String message = "Timeout occured while comitting, probably too high load. Try setting up the timeout or reduce the load.";
			logService.log(LogService.LOG_ERROR, message);
			throw new SpecmateException(message);
		}
	}

	@Override
	public <T> T doAndCommit(IChange<T> change) throws SpecmateException, SpecmateValidationException {
		int maxAttempts = 10;
		boolean success = false;
		int attempts = 1;
		T result = null;

		SpecmateException lastException = null;

		while (!success && attempts <= maxAttempts) {

			result = change.doChange();

			try {
				commit(result);
			} catch (SpecmateException e) {
				lastException = e;
				try {
					Thread.sleep(attempts * 50);
				} catch (InterruptedException ie) {
					throw new SpecmateException("Interrupted during commit.", ie);
				}
				attempts += 1;
				continue;
			}
			success = true;
		}
		if (!success) {
			throw new SpecmateException("Could not commit after " + maxAttempts + " attempts.", lastException);
		}
		return result;
	}

	@Override
	public boolean isDirty() {
		return transaction.isDirty();
	}

	private <T> void setMetadata(T object, List<CDOIDAndVersion> detachedObjects) {
		StringBuilder comment = new StringBuilder();

		String userName = extractUserName(object);
		if (userName != null) {
			comment.append(userName);
			comment.append(extractDeletedObjects(detachedObjects));
		}

		transaction.setCommitComment(comment.toString());
	}

	private <T> String extractUserName(T object) {
		String userName = null;

		if (object instanceof RestResult<?>) {
			userName = ((RestResult<?>) object).getUserName();
		}

		return userName;
	}

	private String extractDeletedObjects(List<CDOIDAndVersion> detachedObjects) {
		StringBuilder names = new StringBuilder();

		if (detachedObjects.size() > 0) {
			names.append(COMMENT_RECORD_SEPARATOR);
			boolean addDataSeparator = false;
			for (CDOIDAndVersion cdoidv : detachedObjects) {
				CDOObject obj = transaction.getObject(cdoidv.getID());
				if (obj instanceof ISpecmateModelObject || obj instanceof com.specmate.model.processes.Process) {
					INamed named = (INamed) obj;
					if (addDataSeparator) {
						names.append(COMMENT_FIELD_SEPARATOR);
					}

					names.append(named.getName());
					names.append(COMMENT_DATA_SEPARATOR);
					names.append(named.eClass().getName());
					addDataSeparator = true;
				}
			}
		}

		names.append(COMMENT_RECORD_SEPARATOR);
		return names.toString();
	}

	private void notifyListeners() throws SpecmateException {
		CDOChangeSetData data = transaction.getChangeSetData();
		DeltaProcessor processor = new DeltaProcessor(data) {

			@Override
			protected void newObject(CDOID id, String className, Map<EStructuralFeature, Object> featureMap) {
				StringBuilder builder = new StringBuilder();
				CDOIDUtil.write(builder, id);
				String idAsString = builder.toString();
				for (IChangeListener listener : changeListeners) {
					listener.newObject(idAsString, className, featureMap);
				}
			}

			@Override
			protected void detachedObject(CDOID id, int version) {
				for (IChangeListener listener : changeListeners) {
					listener.removedObject(transaction.getObject(id));
				}
			}

			@Override
			public void changedObject(CDOID id, EStructuralFeature feature, EChangeKind changeKind, Object oldValue,
					Object newValue, int index, String objectClassName) {
				for (IChangeListener listener : changeListeners) {
					if (newValue instanceof CDOID) {
						newValue = transaction.getObject((CDOID) newValue);
					}
					listener.changedObject(transaction.getObject(id), feature, changeKind, oldValue, newValue,
							objectClassName);
				}
			}

		};

		processor.process();

	}

	public void addListener(IChangeListener listener) {
		changeListeners.add(listener);
	}

	public CDOTransaction getInternalTransaction() {
		return transaction;
	}

	@Override
	public void rollback() {
		transaction.rollback();

	}

	@Override
	public List<Object> query(String queryString, Object context) {
		CDOQuery cdoQuery = this.transaction.createQuery("ocl", queryString, context);
		return cdoQuery.getResult();
	}

	@Override
	public boolean isActive() {
		return persistency.isActive();
	}

	public void update(CDOTransaction transaction) {
		super.update(transaction);
		this.transaction = transaction;
	}
}
