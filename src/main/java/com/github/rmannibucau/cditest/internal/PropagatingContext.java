package com.github.rmannibucau.cditest.internal;

import com.github.rmannibucau.cditest.api.PropagatedThreadScoped;

import javax.enterprise.context.spi.AlterableContext;
import javax.enterprise.context.spi.Contextual;
import javax.enterprise.context.spi.CreationalContext;
import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.util.HashSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static java.util.Optional.ofNullable;

public class PropagatingContext implements AlterableContext {
    private final ThreadLocal<ContextualContext> current = new ThreadLocal<>();

    public ContextualContext start(final ContextualContext inherited) {
        final ContextualContext existing = current.get();
        current.set(ofNullable(inherited).orElseGet(ContextualContext::new));
        return existing;
    }

    public void stop(final ContextualContext old) {
        ofNullable(getOrCreate()).ifPresent(ContextualContext::destroy);
        if (old == null) {
            current.remove();
        } else {
            current.set(old);
        }
    }

    // the interceptor friendly version
    public <T> T executeWithContext(final ContextualContext inherited, final Callable<T> call) throws Exception {
        final ContextualContext contextualContext = start(inherited);
        try {
            return call.call();
        } finally {
            stop(contextualContext);
        }
    }

    public ContextualContext currentOrNull() {
        final ContextualContext delegate = current.get();
        if (delegate == null) {
            current.remove();
        }
        return delegate;
    }

    private ContextualContext getOrCreate() {
        ContextualContext contextualContext = current.get();
        if (contextualContext == null) {
            contextualContext = new ContextualContext();
            current.set(contextualContext);
        }
        return contextualContext;
    }

    @Override
    public Class<? extends Annotation> getScope() {
        return PropagatedThreadScoped.class;
    }

    @Override
    public <T> T get(final Contextual<T> component, final CreationalContext<T> creationalContext) {
        return getOrCreate().get(component, creationalContext);
    }

    @Override
    public <T> T get(final Contextual<T> component) {
        return getOrCreate().get(component);
    }

    @Override
    public void destroy(final Contextual<?> contextual) {
        getOrCreate().destroy(contextual);
    }

    @Override
    public boolean isActive() {
        return true;
    }

    public static class ContextualContext {
        private final ConcurrentMap<Contextual<?>, Instance<?>> instances = new ConcurrentHashMap<>();

        private ContextualContext() {
            // no-op
        }

        private <T> T get(final Contextual<T> component, final CreationalContext<T> creationalContext) {
            final Instance bag = instances.computeIfAbsent(component, bean -> new Instance<>(creationalContext));
            return (T) ofNullable(bag.instance).orElseGet(() -> bag.create(component));
        }

        private <T> T get(final Contextual<T> component) {
            return (T) ofNullable(instances.get(component)).map(b -> b.instance).orElse(null);
        }

        private void destroy() {
            new HashSet<>(instances.keySet()).forEach(this::destroy);
            instances.clear();
        }

        private void destroy(final Contextual<?> contextual) {
            ofNullable(instances.remove(contextual)).filter(b -> b.instance != null)
                .ifPresent(bag -> Contextual.class.cast(contextual).destroy(bag.instance, bag.context));
        }
    }

    private static class Instance<T> implements Serializable {
        private final CreationalContext<T> context;
        private final Lock lock = new ReentrantLock();
        private volatile T instance;

        private Instance(final CreationalContext<T> context) {
            this.context = context;
        }

        private T create(final Contextual<T> factory) {
            try {
                lock.lock();
                if (instance == null) {
                    instance = factory.create(context);
                }
            } finally {
                lock.unlock();
            }
            return instance;
        }
    }
}
