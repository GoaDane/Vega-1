package com.subgraph.vega.internal.http.proxy;

import java.util.ArrayList;

import org.apache.http.HttpResponse;

import com.subgraph.vega.api.events.IEvent;
import com.subgraph.vega.api.events.IEventHandler;
import com.subgraph.vega.api.http.proxy.HttpInterceptorLevel;
import com.subgraph.vega.api.http.proxy.IHttpInterceptor;
import com.subgraph.vega.api.http.proxy.IHttpInterceptorEventHandler;
import com.subgraph.vega.api.http.proxy.IProxyTransaction;
import com.subgraph.vega.api.http.proxy.IProxyTransaction.TransactionDirection;
import com.subgraph.vega.api.model.IModel;
import com.subgraph.vega.api.model.IWorkspace;
import com.subgraph.vega.api.model.WorkspaceCloseEvent;
import com.subgraph.vega.api.model.WorkspaceOpenEvent;
import com.subgraph.vega.api.model.WorkspaceResetEvent;
import com.subgraph.vega.api.model.conditions.ConditionSetChanged;
import com.subgraph.vega.api.model.conditions.IHttpConditionManager;
import com.subgraph.vega.api.model.conditions.IHttpConditionSet;

import com.subgraph.vega.internal.http.proxy.ProxyTransaction;

public class HttpInterceptor implements IHttpInterceptor {
	private static final String propertyInterceptorLevelRequest = "vega.preferences.proxy.interceptor.level.request"; 
	private static final String propertyInterceptorLevelResponse = "vega.preferences.proxy.interceptor.level.response"; 
	private final Object interceptorLock = new Object(); /**< Lock for contents of object */
	private IHttpInterceptorEventHandler eventHandler;
	private HttpInterceptorLevel interceptorLevelRequest = HttpInterceptorLevel.DISABLED;
	private HttpInterceptorLevel interceptorLevelResponse = HttpInterceptorLevel.DISABLED; 
	private IHttpConditionSet breakpointSetRequest; 
	private IHttpConditionSet breakpointSetResponse; 
	private final ArrayList<ProxyTransaction> transactionQueue = new ArrayList<ProxyTransaction>(); /**< Queue of intercepted transactions pending processing */
	private IWorkspace currentWorkspace;
	
	HttpInterceptor(IModel model) {
		currentWorkspace = model.addWorkspaceListener(new IEventHandler() {
			@Override
			public void handleEvent(IEvent event) {
				if(event instanceof WorkspaceOpenEvent) {
					handleWorkspaceOpen((WorkspaceOpenEvent) event);
				} else if(event instanceof WorkspaceCloseEvent) {
					handleWorkspaceClose((WorkspaceCloseEvent) event);
				} else if (event instanceof WorkspaceResetEvent) {
					handleWorkspaceReset((WorkspaceResetEvent) event);
				}
			}
		});
		loadInterceptorLevelRequest();
		loadInterceptorLevelResponse();
		breakpointSetRequest = createConditionSet(model, true);
		breakpointSetResponse = createConditionSet(model, false);
		
	}

	private void handleWorkspaceOpen(WorkspaceOpenEvent event) {
		currentWorkspace = event.getWorkspace();
	}

	private void handleWorkspaceClose(WorkspaceCloseEvent event) {
		currentWorkspace = null;
	}

	private void handleWorkspaceReset(WorkspaceResetEvent event) {
		currentWorkspace = event.getWorkspace();
		loadInterceptorLevelRequest();		
		loadInterceptorLevelResponse();
	}

	private void loadInterceptorLevelRequest() {
		interceptorLevelRequest = HttpInterceptorLevel.fromValue(currentWorkspace.getIntegerProperty(propertyInterceptorLevelRequest));
		if (interceptorLevelRequest == null) {
			interceptorLevelRequest = HttpInterceptorLevel.DISABLED;
		}
	}

	private void loadInterceptorLevelResponse() {
		interceptorLevelResponse = HttpInterceptorLevel.fromValue(currentWorkspace.getIntegerProperty(propertyInterceptorLevelResponse));
		if (interceptorLevelResponse == null) {
			interceptorLevelResponse = HttpInterceptorLevel.DISABLED;
		}
	}
	
	private IHttpConditionSet createConditionSet(IModel model, boolean isRequestSet) {
		final String name = (isRequestSet) ? (IHttpConditionManager.CONDITION_SET_BREAKPOINTS_REQUEST) : (IHttpConditionManager.CONDITION_SET_BREAKPOINTS_RESPONSE);
		return model.addConditionSetTracker(name, createConditionSetChangedHandler(isRequestSet));
	}

	private IEventHandler createConditionSetChangedHandler(final boolean isRequestSet) {
		return new IEventHandler() {
			@Override
			public void handleEvent(IEvent event) {
				if(event instanceof ConditionSetChanged) 
					onConditionSetChanged((ConditionSetChanged) event, isRequestSet);
			}
		};
	}

	private void onConditionSetChanged(ConditionSetChanged event, boolean isRequestSet) {
		synchronized(interceptorLock) {
			final TransactionDirection direction;
			if(isRequestSet) {
				breakpointSetRequest = event.getConditionSet();
				direction = TransactionDirection.DIRECTION_REQUEST;
			} else {
				breakpointSetResponse = event.getConditionSet();
				direction = TransactionDirection.DIRECTION_RESPONSE;
			}
			releaseOnChange(direction);
		}
	}

	private boolean intercept(ProxyTransaction transaction) {
		if (transaction.hasResponse() == false) 
			return interceptByLevelAndBreakpointSet(transaction, interceptorLevelRequest, breakpointSetRequest);
		else
			return interceptByLevelAndBreakpointSet(transaction, interceptorLevelResponse, breakpointSetResponse);				
	}
	
	private boolean interceptByLevelAndBreakpointSet(ProxyTransaction transaction, HttpInterceptorLevel level, IHttpConditionSet breakpointSet) {
		switch(level) {
		case ENABLED_ALL:
			return true;
		case DISABLED:
			return false;
		case ENABLED_BREAKPOINTS:
			return interceptOnBreakpointSet(breakpointSet, transaction);
		}
		return false;
	}

	private boolean interceptOnBreakpointSet(IHttpConditionSet breakpointSet, ProxyTransaction transaction) {
		final HttpResponse response = (transaction.hasResponse()) ? (transaction.getResponse().getRawResponse()) : (null);
		synchronized(interceptorLock) {
			return (breakpointSet == null) ? (false) : (breakpointSet.matches(transaction.getRequest(), response));
		}
	}

	/**
	 * @return True if the transaction was intercepted and added to the queue for processing, false if it can immediately
	 * be handled.
	 */
	public boolean handleTransaction(ProxyTransaction transaction) {
		synchronized(interceptorLock) {
			if (eventHandler != null && intercept(transaction) != false) {
				transaction.setPending(this);
				transactionQueue.add(transaction);
				eventHandler.notifyQueue(transaction);
				return true;
			}
			return false;
		}
	}

	@Override
	public void setEventHandler(IHttpInterceptorEventHandler eventHandler) {
		synchronized(interceptorLock) {
			this.eventHandler = eventHandler;
		}
	}

	@Override
	public void setInterceptLevel(TransactionDirection direction, HttpInterceptorLevel level) {
		synchronized(interceptorLock) {
			if (direction == TransactionDirection.DIRECTION_REQUEST) {
				interceptorLevelRequest = level;
				currentWorkspace.setIntegerProperty(propertyInterceptorLevelRequest, level.getSerializeValue());
			} else {
				interceptorLevelResponse = level;
				currentWorkspace.setIntegerProperty(propertyInterceptorLevelResponse, level.getSerializeValue());
			}
			releaseOnChange(direction);
		}
	}

	@Override
	public HttpInterceptorLevel getInterceptLevel( TransactionDirection direction) {
		synchronized(interceptorLock) {
			if (direction == TransactionDirection.DIRECTION_REQUEST) {
				return interceptorLevelRequest;
			} else {
				return interceptorLevelResponse;
			}
		}
	}

	@Override
	public int transactionQueueSize() {
		synchronized(interceptorLock) {
			return transactionQueue.size();
		}
	}

	@Override
	public IProxyTransaction transactionQueueGet(int idx) {
		synchronized(interceptorLock) {
			if (transactionQueue.size() <= idx) {
				return null;
			}
			return transactionQueue.get(idx);
		}
	}

	/**
	 * Notification that a proxy transaction queued by this interceptor was handled. The transaction is removed from the
	 * transaction queue. 
	 * 
	 * @param transaction Transaction
	 */
	public void notifyHandled(ProxyTransaction transaction) {
		synchronized(interceptorLock) {
			transactionQueue.remove(transactionQueue.indexOf(transaction));
		}
	}

	private IHttpConditionSet getBreakpointSet(TransactionDirection direction) {
		synchronized(interceptorLock) {
			if (direction == TransactionDirection.DIRECTION_REQUEST) {
				return breakpointSetRequest;
			} else {
				return breakpointSetResponse;
			}
		}
	}

	private void releaseOnChange(TransactionDirection direction) {
		final HttpInterceptorLevel level = getInterceptLevel(direction);
		if (level != HttpInterceptorLevel.ENABLED_ALL) {
			if (level != HttpInterceptorLevel.DISABLED) {
				final IHttpConditionSet breakpointSet = getBreakpointSet(direction);
				for (int idx = 0; idx < transactionQueue.size(); idx++) {
					ProxyTransaction transaction = transactionQueue.get(idx);
					if (transaction.hasResponse() == (direction == TransactionDirection.DIRECTION_RESPONSE)) {
						if (interceptOnBreakpointSet(breakpointSet, transaction) == false) {
							transaction.doForward();
							idx--;
						}
					}
				}
			} else {
				for (int idx = 0; idx < transactionQueue.size(); idx++) {
					ProxyTransaction transaction = transactionQueue.get(idx);
					if (transaction.hasResponse() == (direction == TransactionDirection.DIRECTION_RESPONSE)) {
						transaction.doForward();
						idx--;
					}
				}
			}
		}
	}
}
