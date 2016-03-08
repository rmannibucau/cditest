package com.github.rmannibucau.cditest.api;

import com.github.rmannibucau.cditest.internal.PropagatingContext;
import org.apache.openejb.junit.ApplicationComposer;
import org.apache.openejb.testing.Classes;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import javax.enterprise.concurrent.ManagedExecutorService;
import javax.enterprise.concurrent.ManagedTask;
import javax.enterprise.concurrent.ManagedTaskListener;
import javax.enterprise.context.spi.Context;
import javax.enterprise.inject.spi.BeanManager;
import javax.inject.Inject;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@Classes(cdi = true, innerClassesAsBean = true)
@RunWith(ApplicationComposer.class)
public class PropagatedThreadScopedTest {
    @Inject
    private MyDataHolder data;

    @Resource
    private ManagedExecutorService mes;

    @Inject
    private BeanManager beanManager;

    private PropagatingContext context;

    @Before
    public void findContext() {
        context = PropagatingContext.class.cast(beanManager.getContext(PropagatedThreadScoped.class));
        MyDataHolder.destroyed = 0;
    }

    @Test
    public void propagation() throws Exception {
        // check the behavior
        assertTrue(context.executeWithContext(null, () -> {
            assertEquals(0, data.getCount());
            data.setCount(1);

            final long old = data.id();

            try {
                final int result = mes.submit(new HelpingManagedTask<>(context, () -> {
                    assertEquals(1, data.getCount());
                    data.setCount(2);
                    return data.getCount();
                })).get();
                assertEquals(2, result);
                assertEquals(2, data.getCount());

                // now check a lower level data: the bean id
                assertEquals(old, data.id());
                assertEquals(
                    data.id(),
                    mes.submit(new HelpingManagedTask<>(context, data::id)).get().longValue());
            } catch (final InterruptedException e) {
                Thread.interrupted();
                fail();
            } catch (final ExecutionException e) {
                fail(e.getMessage());
            }

            return true;
        }));

        // check beans have been destroyed and context too
        assertEquals(1, MyDataHolder.destroyed);
        assertNull(context.currentOrNull());
    }

    @Test
    public void isolation() throws ExecutionException, InterruptedException {
        final Future<Long> t1 = mes.submit(new HelpingManagedTask<>(context, data::id));
        final Future<Long> t2 = mes.submit(new HelpingManagedTask<>(context, data::id));
        assertNotEquals(t1.get().longValue(), t2.get().longValue());
        assertEquals(2, MyDataHolder.destroyed);
        assertNull(context.currentOrNull());
    }

    @PropagatedThreadScoped
    public static class MyDataHolder {
        private static volatile int destroyed = 0;

        private volatile int count = 0;

        public int getCount() {
            return count;
        }

        public void setCount(int count) {
            this.count = count;
        }

        public long id() {
            return System.identityHashCode(this);
        }

        @PreDestroy
        private void end() {
            synchronized (MyDataHolder.class) {
                destroyed++;
            }
        }
    }

    private static class HelpingManagedTask<T> implements ManagedTask, Callable<T> {
        private final ManagedTaskListener listener;
        private final Callable<T> delegate;

        private HelpingManagedTask(final Context contextToPropagate, final Callable<T> delegate) {
            this.delegate = delegate;
            this.listener = new PropagatingManagedTaskListener(contextToPropagate);
        }

        @Override
        public T call() throws Exception {
            return delegate.call();
        }

        @Override
        public ManagedTaskListener getManagedTaskListener() {
            return listener;
        }

        @Override
        public Map<String, String> getExecutionProperties() {
            return Collections.emptyMap();
        }
    }
}
