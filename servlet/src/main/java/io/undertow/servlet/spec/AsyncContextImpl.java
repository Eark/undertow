/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.undertow.servlet.spec;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.DispatcherType;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import io.undertow.UndertowLogger;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpHandlers;
import io.undertow.server.HttpServerExchange;
import io.undertow.servlet.UndertowServletLogger;
import io.undertow.servlet.UndertowServletMessages;
import io.undertow.servlet.api.Deployment;
import io.undertow.servlet.api.InstanceFactory;
import io.undertow.servlet.api.ServletDispatcher;
import io.undertow.servlet.api.ThreadSetupAction;
import io.undertow.servlet.core.CompositeThreadSetupAction;
import io.undertow.servlet.handlers.ServletPathMatch;
import io.undertow.servlet.handlers.ServletRequestContext;
import io.undertow.util.AttachmentKey;
import io.undertow.util.SameThreadExecutor;
import org.xnio.XnioExecutor;

/**
 * @author Stuart Douglas
 */
public class AsyncContextImpl implements AsyncContext {

    public static final AttachmentKey<Boolean> ASYNC_SUPPORTED = AttachmentKey.create(Boolean.class);
    public static final AttachmentKey<Executor> ASYNC_EXECUTOR = AttachmentKey.create(Executor.class);

    private final List<BoundAsyncListener> asyncListeners = new CopyOnWriteArrayList<BoundAsyncListener>();

    private final HttpServerExchange exchange;
    private final ServletRequest servletRequest;
    private final ServletResponse servletResponse;
    private final TimeoutTask timeoutTask = new TimeoutTask();
    private final ServletRequestContext servletRequestContext;

    private AsyncContextImpl previousAsyncContext; //the previous async context


    //todo: make default configurable
    private volatile long timeout = 120000;

    private volatile XnioExecutor.Key timeoutKey;

    private boolean dispatched;
    private boolean initialRequestDone;
    private Thread initiatingThread;

    private final Deque<Runnable> asyncTaskQueue = new ArrayDeque<>();
    private boolean processingAsyncTask = false;

    public AsyncContextImpl(final HttpServerExchange exchange, final ServletRequest servletRequest, final ServletResponse servletResponse, final ServletRequestContext servletRequestContext, final AsyncContextImpl previousAsyncContext) {
        this.exchange = exchange;
        this.servletRequest = servletRequest;
        this.servletResponse = servletResponse;
        this.servletRequestContext = servletRequestContext;
        this.previousAsyncContext = previousAsyncContext;
        initiatingThread = Thread.currentThread();
        exchange.dispatch(SameThreadExecutor.INSTANCE, new Runnable() {
            @Override
            public void run() {
                exchange.setDispatchExecutor(null);
                initialRequestDone();
            }
        });
    }

    public void updateTimeout() {
        XnioExecutor.Key key = this.timeoutKey;
        if (key != null) {
            if (!key.remove()) {
                return;
            }
        }
        if (timeout > 0) {
            this.timeoutKey = exchange.getIoThread().executeAfter(timeoutTask, timeout, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public ServletRequest getRequest() {
        return servletRequest;
    }

    @Override
    public ServletResponse getResponse() {
        return servletResponse;
    }

    @Override
    public boolean hasOriginalRequestAndResponse() {
        return servletRequest instanceof HttpServletRequestImpl &&
                servletResponse instanceof HttpServletResponseImpl;
    }

    @Override
    public void dispatch() {
        final HttpServletRequestImpl requestImpl = this.servletRequestContext.getOriginalRequest();
        final ServletPathMatch handler;
        Deployment deployment = requestImpl.getServletContext().getDeployment();
        if (servletRequest instanceof HttpServletRequest) {
            handler = deployment.getServletPaths().getServletHandlerByPath(((HttpServletRequest) servletRequest).getServletPath());
        } else {
            handler = deployment.getServletPaths().getServletHandlerByPath(exchange.getRelativePath());
        }

        final HttpServerExchange exchange = requestImpl.getExchange();
        final ServletRequestContext servletRequestContext = exchange.getAttachment(ServletRequestContext.ATTACHMENT_KEY);

        servletRequestContext.setDispatcherType(DispatcherType.ASYNC);

        servletRequestContext.setServletRequest(servletRequest);
        servletRequestContext.setServletResponse(servletResponse);

        dispatchAsyncRequest(deployment.getServletDispatcher(), handler, exchange);
    }

    private void dispatchAsyncRequest(final ServletDispatcher servletDispatcher, final ServletPathMatch pathInfo, final HttpServerExchange exchange) {
        doDispatch(new Runnable() {
            @Override
            public void run() {
                HttpHandlers.executeRootHandler(new HttpHandler() {
                    @Override
                    public void handleRequest(final HttpServerExchange exchange) throws Exception {
                        servletDispatcher.dispatchToPath(exchange, pathInfo, DispatcherType.ASYNC);
                    }
                }, exchange, false);
            }
        });
    }

    @Override
    public void dispatch(final String path) {
        dispatch(servletRequest.getServletContext(), path);
    }

    @Override
    public void dispatch(final ServletContext context, final String path) {

        HttpServletRequestImpl requestImpl = servletRequestContext.getOriginalRequest();
        HttpServletResponseImpl responseImpl = servletRequestContext.getOriginalResponse();
        final HttpServerExchange exchange = requestImpl.getExchange();

        exchange.getAttachment(ServletRequestContext.ATTACHMENT_KEY).setDispatcherType(DispatcherType.ASYNC);

        requestImpl.setAttribute(ASYNC_REQUEST_URI, requestImpl.getRequestURI());
        requestImpl.setAttribute(ASYNC_CONTEXT_PATH, requestImpl.getContextPath());
        requestImpl.setAttribute(ASYNC_SERVLET_PATH, requestImpl.getServletPath());
        requestImpl.setAttribute(ASYNC_QUERY_STRING, requestImpl.getQueryString());

        String newQueryString = "";
        int qsPos = path.indexOf("?");
        String newServletPath = path;
        if (qsPos != -1) {
            newQueryString = newServletPath.substring(qsPos + 1);
            newServletPath = newServletPath.substring(0, qsPos);
        }
        String newRequestUri = context.getContextPath() + newServletPath;

        //todo: a more efficent impl
        Map<String, Deque<String>> newQueryParameters = new HashMap<String, Deque<String>>();
        for (String part : newQueryString.split("&")) {
            String name = part;
            String value = "";
            int equals = part.indexOf('=');
            if (equals != -1) {
                name = part.substring(0, equals);
                value = part.substring(equals + 1);
            }
            Deque<String> queue = newQueryParameters.get(name);
            if (queue == null) {
                newQueryParameters.put(name, queue = new ArrayDeque<String>(1));
            }
            queue.add(value);
        }
        requestImpl.setQueryParameters(newQueryParameters);

        requestImpl.getExchange().setRelativePath(newServletPath);
        requestImpl.getExchange().setQueryString(newQueryString);
        requestImpl.getExchange().setRequestPath(newRequestUri);
        requestImpl.getExchange().setRequestURI(newRequestUri);
        requestImpl.setServletContext((ServletContextImpl) context);
        responseImpl.setServletContext((ServletContextImpl) context);

        Deployment deployment = requestImpl.getServletContext().getDeployment();
        ServletPathMatch info = deployment.getServletPaths().getServletHandlerByPath(newServletPath);
        requestImpl.getExchange().getAttachment(ServletRequestContext.ATTACHMENT_KEY).setServletPathMatch(info);

        dispatchAsyncRequest(deployment.getServletDispatcher(), info, exchange);
    }

    @Override
    public synchronized void complete() {
        onAsyncComplete();
        completeInternal();
    }

    public synchronized void completeInternal() {

        if (!initialRequestDone && Thread.currentThread() == initiatingThread) {
            //the context was stopped in the same request context it was started, we don't do anything
            if (dispatched) {
                throw UndertowServletMessages.MESSAGES.asyncRequestAlreadyDispatched();
            }
            exchange.unDispatch();
            dispatched = true;
            HttpServletRequestImpl request = servletRequestContext.getOriginalRequest();
            initialRequestDone();
            request.asyncRequestDispatched();
        } else {
            doDispatch(new Runnable() {
                @Override
                public void run() {
                    //we do not run the ServletRequestListeners here, as the request does not come into the scope
                    //of a web application, as defined by the javadoc on ServletRequestListener
                    HttpServletResponseImpl response = servletRequestContext.getOriginalResponse();
                    response.responseDone();
                }
            });
        }
    }

    @Override
    public void start(final Runnable run) {
        Executor executor = asyncExecutor();
        final CompositeThreadSetupAction setup = servletRequestContext.getDeployment().getThreadSetupAction();
        executor.execute(new Runnable() {
            @Override
            public void run() {
                ThreadSetupAction.Handle handle = setup.setup(null);
                try {
                    run.run();
                } finally {
                    handle.tearDown();
                }
            }
        });

    }

    private Executor asyncExecutor() {
        Executor executor = exchange.getAttachment(ASYNC_EXECUTOR);
        if (executor == null) {
            executor = exchange.getDispatchExecutor();
        }
        if (executor == null) {
            executor = exchange.getConnection().getWorker();
        }
        return executor;
    }


    @Override
    public void addListener(final AsyncListener listener) {
        asyncListeners.add(new BoundAsyncListener(listener, servletRequest, servletResponse));
    }

    @Override
    public void addListener(final AsyncListener listener, final ServletRequest servletRequest, final ServletResponse servletResponse) {
        asyncListeners.add(new BoundAsyncListener(listener, servletRequest, servletResponse));
    }

    public boolean isDispatched() {
        return dispatched;
    }

    @Override
    public <T extends AsyncListener> T createListener(final Class<T> clazz) throws ServletException {
        try {
            InstanceFactory<T> factory = ((ServletContextImpl) this.servletRequest.getServletContext()).getDeployment().getDeploymentInfo().getClassIntrospecter().createInstanceFactory(clazz);
            return factory.createInstance().getInstance();
        } catch (NoSuchMethodException e) {
            throw new ServletException(e);
        } catch (InstantiationException e) {
            throw new ServletException(e);
        }
    }

    @Override
    public void setTimeout(final long timeout) {
        this.timeout = timeout;
    }

    @Override
    public long getTimeout() {
        return timeout;
    }

    public void handleError(final Throwable error) {
        dispatched = false; //we reset the dispatched state
        onAsyncError(error);
        if (!dispatched) {
            servletRequest.setAttribute(RequestDispatcher.ERROR_EXCEPTION, error);
            try {
                ((HttpServletResponse) servletResponse).sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            } catch (IOException e) {
                UndertowLogger.REQUEST_IO_LOGGER.ioException(e);
            }
            if (!dispatched) {
                complete();
            }
        }
    }

    /**
     * Called by the container when the initial request is finished.
     * If this request has a dispatch or complete call pending then
     * this will be started.
     */
    public synchronized void initialRequestDone() {
        initialRequestDone = true;
        if (previousAsyncContext != null) {
            previousAsyncContext.onAsyncStart(this);
            previousAsyncContext = null;
        }
        if (!processingAsyncTask) {
            processAsyncTask();
        }
        initiatingThread = null;
    }


    private synchronized void doDispatch(final Runnable runnable) {
        if (dispatched) {
            throw UndertowServletMessages.MESSAGES.asyncRequestAlreadyDispatched();
        }
        dispatched = true;
        final HttpServletRequestImpl request = servletRequestContext.getOriginalRequest();
        addAsyncTask(new Runnable() {
            @Override
            public void run() {
                request.asyncRequestDispatched();
                runnable.run();
            }
        });
        if (timeoutKey != null) {
            timeoutKey.remove();
        }
    }


    private final class TimeoutTask implements Runnable {

        @Override
        public void run() {
            synchronized (AsyncContextImpl.this) {
                if (!dispatched) {
                    UndertowServletLogger.REQUEST_LOGGER.debug("Async request timed out");
                    onAsyncTimeout();
                    if (!dispatched) {
                        //servlet
                        try {
                            ((HttpServletResponse) servletResponse).sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                        } catch (IOException e) {
                            UndertowLogger.REQUEST_IO_LOGGER.ioException(e);
                        }
                        if (!dispatched) {
                            complete();
                        }
                    }
                }
            }
        }
    }

    private synchronized void processAsyncTask() {
        if (!initialRequestDone) {
            return;
        }
        updateTimeout();
        final Runnable task = asyncTaskQueue.poll();
        if (task != null) {
            processingAsyncTask = true;
            asyncExecutor().execute(new TaskDispatchRunnable(task));
        } else {
            processingAsyncTask = false;
        }
    }

    /**
     * Adds a task to be run to the async context. These tasks are run one at a time,
     * after the initial request is finished. If the request is dispatched before the initial
     * request is complete then these tasks will not be run
     * <p/>
     * <p/>
     * This method is intended to be used to queue read and write tasks for async streams,
     * to make sure that multiple threads do not end up working on the same exchange at once
     *
     * @param runnable The runnable
     */
    public synchronized void addAsyncTask(final Runnable runnable) {
        asyncTaskQueue.add(runnable);
        if (!processingAsyncTask) {
            processAsyncTask();
        }
    }

    private class TaskDispatchRunnable implements Runnable {

        private final Runnable task;

        private TaskDispatchRunnable(final Runnable task) {
            this.task = task;
        }

        @Override
        public void run() {
            try {
                task.run();
            } finally {
                processAsyncTask();
            }
        }
    }


    private void onAsyncComplete() {
        final ThreadSetupAction.Handle handle = servletRequestContext.getDeployment().getThreadSetupAction().setup(exchange);
        try {
            for (final BoundAsyncListener listener : asyncListeners) {
                AsyncEvent event = new AsyncEvent(this, listener.servletRequest, listener.servletResponse);
                try {
                    listener.asyncListener.onComplete(event);
                } catch (IOException e) {
                    UndertowServletLogger.REQUEST_LOGGER.ioExceptionDispatchingAsyncEvent(e);
                }
            }
        } finally {
            handle.tearDown();
        }
    }

    private void onAsyncTimeout() {
        final ThreadSetupAction.Handle handle = servletRequestContext.getDeployment().getThreadSetupAction().setup(exchange);
        try {
            for (final BoundAsyncListener listener : asyncListeners) {
                AsyncEvent event = new AsyncEvent(this, listener.servletRequest, listener.servletResponse);
                try {
                    listener.asyncListener.onTimeout(event);
                } catch (IOException e) {
                    UndertowServletLogger.REQUEST_LOGGER.ioExceptionDispatchingAsyncEvent(e);
                }
            }
        } finally {
            handle.tearDown();
        }
    }

    private void onAsyncStart(AsyncContext newAsyncContext) {
        final ThreadSetupAction.Handle handle = servletRequestContext.getDeployment().getThreadSetupAction().setup(exchange);
        try {
            for (final BoundAsyncListener listener : asyncListeners) {
                //make sure we use the new async context
                AsyncEvent event = new AsyncEvent(newAsyncContext, listener.servletRequest, listener.servletResponse);
                try {
                    listener.asyncListener.onStartAsync(event);
                } catch (IOException e) {
                    UndertowServletLogger.REQUEST_LOGGER.ioExceptionDispatchingAsyncEvent(e);
                }
            }
        } finally {
            handle.tearDown();
        }
    }

    private void onAsyncError(Throwable t) {
        final ThreadSetupAction.Handle handle = servletRequestContext.getDeployment().getThreadSetupAction().setup(exchange);
        try {
            for (final BoundAsyncListener listener : asyncListeners) {
                AsyncEvent event = new AsyncEvent(this, listener.servletRequest, listener.servletResponse, t);
                try {
                    listener.asyncListener.onError(event);
                } catch (IOException e) {
                    UndertowServletLogger.REQUEST_LOGGER.ioExceptionDispatchingAsyncEvent(e);
                }
            }
        } finally {
            handle.tearDown();
        }
    }

    private final class BoundAsyncListener {
        final AsyncListener asyncListener;
        final ServletRequest servletRequest;
        final ServletResponse servletResponse;

        private BoundAsyncListener(final AsyncListener asyncListener, final ServletRequest servletRequest, final ServletResponse servletResponse) {
            this.asyncListener = asyncListener;
            this.servletRequest = servletRequest;
            this.servletResponse = servletResponse;
        }
    }
}
