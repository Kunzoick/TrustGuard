package com.trustguard.infrastructure.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.lang.reflect.Method;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Rule 3.2: two named thread pools that never share threads, enforcing
 * workload isolation between Category B (checkRisk(), rateLimit(),
 * getSignals() — latency-sensitive) and Category A (signal processing,
 * snapshot computation, feedback recalibration, scheduled jobs). A
 * flood of trackLogin() events saturating the async executor must never
 * starve the scoring path.
 *
 * Code v2 fix (CF-004): both executors set
 * setWaitForTasksToCompleteOnShutdown(true) and
 * setAwaitTerminationSeconds(60), matching Rule 9.9's graceful shutdown
 * requirement exactly.
 *
 * Code v2 addition (CF-004 scope extension, per Agent 4): both queue
 * capacities are finite (100 and 500) and will eventually fill under
 * load — this is not speculative. Rejection behavior is deliberately
 * different per pool:
 *   - scoringExecutor: AbortPolicy (wrapped to log ERROR + increment a
 *     metric before throwing). Scoring tasks must never be silently
 *     swallowed. The RejectedExecutionException this throws is not yet
 *     caught anywhere in this batch — no code calls submit() on this
 *     executor until B-017's RiskOrchestrator exists. That future catch
 *     site is responsible for translating the exception into a
 *     DEGRADED response (Rule 9.1) rather than letting it propagate as
 *     an unhandled 500.
 *   - asyncExecutor: CallerRunsPolicy (wrapped to log WARN before
 *     running). This applies real backpressure to the submitting
 *     thread rather than dropping work — appropriate here since this
 *     pool is not on the latency-sensitive request path.
 */
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    public static final String SCORING_EXECUTOR_BEAN_NAME = "scoringExecutor";
    public static final String ASYNC_EXECUTOR_BEAN_NAME = "asyncExecutor";

    private static final int SCORING_CORE_POOL_SIZE = 20;
    private static final int SCORING_MAX_POOL_SIZE = 50;
    private static final int SCORING_QUEUE_CAPACITY = 100;
    private static final String SCORING_THREAD_PREFIX = "scoring-";

    private static final int ASYNC_CORE_POOL_SIZE = 10;
    private static final int ASYNC_MAX_POOL_SIZE = 25;
    private static final int ASYNC_QUEUE_CAPACITY = 500;
    private static final String ASYNC_THREAD_PREFIX = "async-";

    private static final boolean WAIT_FOR_TASKS_TO_COMPLETE_ON_SHUTDOWN = true;
    private static final int AWAIT_TERMINATION_SECONDS = 60;

    private static final String SCORING_QUEUE_FULL_METRIC = "executor.scoring.queue.full";
    private static final String ASYNC_QUEUE_FULL_METRIC = "executor.async.queue.full";

    private final MeterRegistry meterRegistry;

    public AsyncConfig(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * trustguard.executor.scoring — checkRisk(), rateLimit(), getSignals()
     * only. Never used for signal processing, snapshot computation, or
     * scheduled jobs; those belong on {@link #asyncExecutor()}.
     */
    @Bean(SCORING_EXECUTOR_BEAN_NAME)
    public Executor scoringExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(SCORING_CORE_POOL_SIZE);
        executor.setMaxPoolSize(SCORING_MAX_POOL_SIZE);
        executor.setQueueCapacity(SCORING_QUEUE_CAPACITY);
        executor.setThreadNamePrefix(SCORING_THREAD_PREFIX);
        executor.setWaitForTasksToCompleteOnShutdown(WAIT_FOR_TASKS_TO_COMPLETE_ON_SHUTDOWN);
        executor.setAwaitTerminationSeconds(AWAIT_TERMINATION_SECONDS);
        executor.setRejectedExecutionHandler(loggingAbortPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * trustguard.executor.async — signal processing, snapshot
     * computation, feedback recalibration, scheduled jobs. Never used
     * for checkRisk() or any latency-sensitive Category B call.
     */
    @Bean(ASYNC_EXECUTOR_BEAN_NAME)
    public Executor asyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(ASYNC_CORE_POOL_SIZE);
        executor.setMaxPoolSize(ASYNC_MAX_POOL_SIZE);
        executor.setQueueCapacity(ASYNC_QUEUE_CAPACITY);
        executor.setThreadNamePrefix(ASYNC_THREAD_PREFIX);
        executor.setWaitForTasksToCompleteOnShutdown(WAIT_FOR_TASKS_TO_COMPLETE_ON_SHUTDOWN);
        executor.setAwaitTerminationSeconds(AWAIT_TERMINATION_SECONDS);
        executor.setRejectedExecutionHandler(loggingCallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * Wraps the standard AbortPolicy: logs ERROR and increments a metric
     * before delegating to the real AbortPolicy, which throws
     * RejectedExecutionException. The scoring path must never silently
     * swallow a rejected task — the exception still propagates; this
     * only adds visibility at the moment it happens.
     */
    private RejectedExecutionHandler loggingAbortPolicy() {
        ThreadPoolExecutor.AbortPolicy delegate = new ThreadPoolExecutor.AbortPolicy();
        return (Runnable rejectedTask, ThreadPoolExecutor executor) -> {
            log.error("Scoring executor queue is full (capacity {}). Rejecting task.",
                    SCORING_QUEUE_CAPACITY);
            meterRegistry.counter(SCORING_QUEUE_FULL_METRIC).increment();
            delegate.rejectedExecution(rejectedTask, executor);
        };
    }

    /**
     * Wraps the standard CallerRunsPolicy: logs WARN before delegating
     * to the real CallerRunsPolicy, which runs the rejected task on the
     * calling thread — applying backpressure to the submitter rather
     * than dropping the work.
     */
    private RejectedExecutionHandler loggingCallerRunsPolicy() {
        ThreadPoolExecutor.CallerRunsPolicy delegate = new ThreadPoolExecutor.CallerRunsPolicy();
        return (Runnable rejectedTask, ThreadPoolExecutor executor) -> {
            log.warn("Async executor queue is full (capacity {}). Applying backpressure "
                    + "to the submitting thread.", ASYNC_QUEUE_CAPACITY);
            meterRegistry.counter(ASYNC_QUEUE_FULL_METRIC).increment();
            delegate.rejectedExecution(rejectedTask, executor);
        };
    }

    /**
     * Relies on this class remaining a full @Configuration (default
     * proxyBeanMethods = true / CGLIB-enhanced) so this intra-class call
     * returns the same managed singleton bean rather than constructing a
     * second, unmanaged executor. If proxyBeanMethods = false is ever
     * added to this class for performance reasons, this method must be
     * changed to inject the asyncExecutor bean instead of calling it
     * directly — flagging this dependency explicitly per Principle 0.4.
     */
    @Override
    public Executor getAsyncExecutor() {
        return asyncExecutor();
    }

    /**
     * Rule 17.10: @Async methods returning void silently swallow
     * uncaught exceptions with no log, no alert, no indication anything
     * went wrong. Every uncaught async exception is logged at ERROR and
     * emits a metric instead.
     */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (Throwable exception, Method method, Object... params) -> {
            log.error("Uncaught exception in async method {}: {}",
                    method.getName(), exception.getMessage(), exception);
            meterRegistry.counter("async.uncaught.exception").increment();
        };
    }
}