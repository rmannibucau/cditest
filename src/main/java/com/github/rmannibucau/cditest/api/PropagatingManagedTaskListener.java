package com.github.rmannibucau.cditest.api;

import com.github.rmannibucau.cditest.internal.PropagatingContext;

import javax.enterprise.concurrent.ManagedExecutorService;
import javax.enterprise.concurrent.ManagedTaskListener;
import javax.enterprise.context.spi.Context;
import java.util.concurrent.Future;

import static java.util.Optional.ofNullable;

// use to propagate the context at the moment of the instantiation of this listener in the task
public class PropagatingManagedTaskListener implements ManagedTaskListener {
    private final PropagatingContext.ContextualContext state;
    private final PropagatingContext propagatingContext;
    private final boolean stop;

    private volatile PropagatingContext.ContextualContext old;

    public PropagatingManagedTaskListener(final Context propagatingContext) {
        this(propagatingContext, false);
    }

    public PropagatingManagedTaskListener(final Context propagatingContext, final boolean stop) {
        this.propagatingContext = PropagatingContext.class.cast(propagatingContext);
        this.state = ofNullable(this.propagatingContext).map(PropagatingContext::currentOrNull).orElse(null);
        this.stop = stop || state == null;
    }

    @Override
    public void taskStarting(final Future<?> future, final ManagedExecutorService executor, final Object task) {
        old = propagatingContext.start(state);
    }

    @Override
    public void taskSubmitted(final Future<?> future, final ManagedExecutorService executor, final Object task) {
        // no-op
    }

    @Override
    public void taskAborted(final Future<?> future, final ManagedExecutorService executor, final Object task, final Throwable exception) {
        if (stop) {
            propagatingContext.stop(old);
        }
    }

    @Override
    public void taskDone(final Future<?> future, final ManagedExecutorService executor, final Object task, final Throwable exception) {
        if (stop) {
            propagatingContext.stop(old);
        }
    }
}
