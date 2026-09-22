package com.apimarketplace.common.web;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.metrics.task.TaskExecutorMetricsAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The MVC streaming executor added 2026-08-31.
 *
 * <p>Regression cover for a defect that produced no exception and no log line: every servlet
 * service inherited Spring Boot's default {@code applicationTaskExecutor} for
 * {@code StreamingResponseBody} return values (core 8, unbounded queue), which capped
 * storage-service at 8 concurrent file downloads platform-wide and queued the rest without bound.
 * Prometheus recorded 8 active with 10 queued in ordinary traffic in the week to 2026-08-31.
 *
 * <p>Every assertion here fails against the pre-fix code: without
 * {@code MvcStreamingAsyncConfiguration} the bean does not exist and nothing overrides Spring
 * Boot's choice of async executor.
 */
class CommonAsyncConfigMvcStreamingTest {

    private static final String EXECUTOR =
            CommonAsyncConfig.MvcStreamingAsyncConfiguration.EXECUTOR_BEAN_NAME;

    private final WebApplicationContextRunner servletRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TaskExecutionAutoConfiguration.class,
                    WebMvcAutoConfiguration.class,
                    CommonAsyncConfig.class));

    @Test
    @DisplayName("streaming executor is a FIXED pool, so its advertised size is the concurrency it can actually reach")
    void streamingExecutorIsAFixedPool() {
        servletRunner.run(context -> {
            ThreadPoolTaskExecutor executor = context.getBean(EXECUTOR, ThreadPoolTaskExecutor.class);

            // core == max is the whole point. A ThreadPoolTaskExecutor only grows past core once
            // the queue is FULL, so the Spring Boot default this replaces (core 8, max MAX_VALUE,
            // unbounded queue) could never exceed 8.
            assertThat(executor.getCorePoolSize()).isEqualTo(32);
            assertThat(executor.getMaxPoolSize()).isEqualTo(32);
        });
    }

    @Test
    @DisplayName("streaming queue is bounded and small, so a download cannot be parked behind a long backlog")
    void streamingQueueIsBounded() {
        servletRunner.run(context -> {
            ThreadPoolTaskExecutor executor = context.getBean(EXECUTOR, ThreadPoolTaskExecutor.class);

            // Pre-fix this was Integer.MAX_VALUE. It is also deliberately SMALL relative to the
            // pool: a large finite queue reproduces most of the defect, since a queued download
            // is one that has been accepted and is sending nothing.
            assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(16);
        });
    }

    @Test
    @DisplayName("Spring MVC uses the streaming executor, not applicationTaskExecutor")
    void mvcAsyncSupportUsesTheStreamingExecutor() {
        servletRunner.run(context -> {
            ThreadPoolTaskExecutor streamingExecutor = context.getBean(EXECUTOR, ThreadPoolTaskExecutor.class);

            // Replay what DelegatingWebMvcConfiguration does: run every configurer in order and
            // read back which executor is left installed. Spring Boot's own configurer sets
            // applicationTaskExecutor here at order 0, so this asserts the ordering, not merely
            // that the bean exists.
            List<WebMvcConfigurer> configurers =
                    context.getBeanProvider(WebMvcConfigurer.class).orderedStream().toList();
            AsyncSupportConfigurer configurer = new AsyncSupportConfigurer();
            configurers.forEach(c -> c.configureAsyncSupport(configurer));

            assertThat(readField(configurer, "taskExecutor")).isSameAs(streamingExecutor);
        });
    }

    @Test
    @DisplayName("the configured async request timeout survives this library's configurer")
    void asyncRequestTimeoutSurvivesTheStreamingConfigurer() {
        // storage-service, agent-service and the CE monolith each set
        // spring.mvc.async.request-timeout because the container default of 30s truncates
        // a StreamingResponseBody mid-transfer. Each of those is pinned by a test that
        // reads its own YAML, and all three would stay green if THIS configurer ever
        // called setDefaultTimeout: it is @Order(LOWEST_PRECEDENCE), so it runs after
        // Boot's property-driven one and would win. That is the gap this closes.
        servletRunner
                .withPropertyValues("spring.mvc.async.request-timeout=600000")
                .run(context -> {
                    List<WebMvcConfigurer> configurers =
                            context.getBeanProvider(WebMvcConfigurer.class).orderedStream().toList();
                    AsyncSupportConfigurer configurer = new AsyncSupportConfigurer();
                    configurers.forEach(c -> c.configureAsyncSupport(configurer));

                    assertThat(readField(configurer, "timeout"))
                            .as("the property must be what is left installed after every configurer has run")
                            .isEqualTo(600_000L);
                });
    }

    @Test
    @DisplayName("overflow on the SHIPPED bean runs the stream inline rather than failing the download")
    void overflowFallsBackToCallerRuns() {
        // Drives the real bean, shrunk through its own properties. An earlier version of this
        // test built a fresh executor and set CallerRunsPolicy on it by hand, which asserted a
        // JDK invariant: switching the production bean to AbortPolicy left it green, so the
        // rejection policy the javadoc spends a paragraph defending was pinned by nothing.
        servletRunner
                .withPropertyValues(
                        "livecontext.web.async.core-size=1",
                        "livecontext.web.async.max-size=1",
                        "livecontext.web.async.queue-capacity=1")
                .run(context -> {
                    ThreadPoolTaskExecutor executor = context.getBean(EXECUTOR, ThreadPoolTaskExecutor.class);
                    CountDownLatch release = new CountDownLatch(1);
                    CountDownLatch occupied = new CountDownLatch(1);
                    executor.execute(() -> {
                        occupied.countDown();
                        try {
                            release.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
                    assertThat(occupied.await(5, TimeUnit.SECONDS)).isTrue();
                    executor.execute(() -> { });   // fills the 1-deep queue

                    // The third task has nowhere to go. AbortPolicy would throw here and the user
                    // would get an error on a file download; caller-runs serves it.
                    AtomicReference<Thread> ranOn = new AtomicReference<>();
                    executor.execute(() -> ranOn.set(Thread.currentThread()));

                    assertThat(ranOn.get()).isSameAs(Thread.currentThread());
                    release.countDown();

                    // Caller-runs and the shipped policy are indistinguishable on this branch,
                    // so assert the identity too: stock CallerRunsPolicy would pass everything
                    // above while silently reintroducing the shutdown hang.
                    assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                            .isInstanceOf(CommonAsyncConfig.MvcStreamingAsyncConfiguration
                                    .RunInCallerUnlessShuttingDown.class);
                });
    }

    @Test
    @DisplayName("a stream rejected during shutdown throws instead of vanishing, so the request fails rather than hanging")
    void rejectionDuringShutdownThrows() {
        // Stock CallerRunsPolicy returns without running and WITHOUT throwing once the executor is
        // shut down. On the MVC async path WebAsyncManager has already called startAsync() by then
        // and catches only RejectedExecutionException, so silence means the response hangs to the
        // async timeout. Rolling deploys make that window routine, not exotic.
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.setRejectedExecutionHandler(
                new CommonAsyncConfig.MvcStreamingAsyncConfiguration.RunInCallerUnlessShuttingDown());
        executor.initialize();
        ThreadPoolExecutor pool = executor.getThreadPoolExecutor();
        pool.shutdown();

        AtomicReference<Thread> ranOn = new AtomicReference<>();
        assertThatThrownBy(() -> pool.getRejectedExecutionHandler()
                .rejectedExecution(() -> ranOn.set(Thread.currentThread()), pool))
                .isInstanceOf(RejectedExecutionException.class);
        assertThat(ranOn.get()).as("the task must not be silently run either").isNull();
    }

    @Test
    @DisplayName("max-size below core-size is clamped, so following the alert's advice cannot crashloop the pod")
    void maxSizeBelowCoreSizeIsClampedRatherThanFatal() {
        // ThreadPoolExecutor throws IllegalArgumentException on maximumPoolSize < corePoolSize,
        // from inside initialize(), which fails context refresh. The StreamingPool* alerts
        // tell the operator to raise the pool, and raising core-size alone is the natural move.
        servletRunner
                .withPropertyValues(
                        "livecontext.web.async.core-size=64",
                        "livecontext.web.async.max-size=32")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ThreadPoolTaskExecutor executor = context.getBean(EXECUTOR, ThreadPoolTaskExecutor.class);
                    assertThat(executor.getCorePoolSize()).isEqualTo(64);
                    assertThat(executor.getMaxPoolSize()).isEqualTo(64);
                });
    }

    @Test
    @DisplayName("max-size above core-size is left alone, so the clamp cannot quietly become a fixed pool")
    void maxSizeAboveCoreSizeIsPreserved() {
        // The other half of the clamp. Without this, `maxSize = coreSize` unconditionally passes
        // both maxSizeBelowCoreSizeIsClampedRatherThanFatal and sizingIsConfigurable, which use
        // equal values.
        servletRunner
                .withPropertyValues(
                        "livecontext.web.async.core-size=8",
                        "livecontext.web.async.max-size=24")
                .run(context -> {
                    ThreadPoolTaskExecutor executor = context.getBean(EXECUTOR, ThreadPoolTaskExecutor.class);
                    assertThat(executor.getCorePoolSize()).isEqualTo(8);
                    assertThat(executor.getMaxPoolSize()).isEqualTo(24);
                });
    }

    @Test
    @DisplayName("a core-size of zero is raised to one rather than crashlooping the pod")
    void nonPositiveCoreSizeIsRaised() {
        // ThreadPoolExecutor rejects maximumPoolSize <= 0 from inside initialize(), which fails
        // context refresh. The clamp for max < core did not cover this, so core-size=0 reached
        // exactly the outcome the clamp exists to prevent.
        servletRunner
                .withPropertyValues("livecontext.web.async.core-size=0")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(EXECUTOR, ThreadPoolTaskExecutor.class).getCorePoolSize())
                            .isGreaterThanOrEqualTo(1);
                });
    }

    @Test
    @DisplayName("the deployment's ENV VAR spellings bind to this pool, not just the dotted property names")
    void deploymentEnvironmentVariableNamesBind() {
        // The only place these sizes are ever set in production is a Helm env map, as
        // LIVECONTEXT_WEB_ASYNC_CORE_SIZE / _MAX_SIZE / _QUEUE_CAPACITY. Every other test here
        // uses the dotted form, which cannot catch a wrong env-var name: such a name renders
        // perfectly, deploys perfectly, binds nothing, and silently leaves the pool at its
        // default while the operator believes it was raised. Relaxed binding of the
        // SCREAMING_SNAKE form only happens for a property source named systemEnvironment,
        // hence SystemEnvironmentPropertySource rather than withPropertyValues.
        //
        // What counts as "wrong" is narrower than it looks, and worth knowing before anyone
        // rewrites these strings: the binder compares names with separators removed, so
        // LIVECONTEXT_WEB_ASYNC_CORESIZE binds to core-size just as well (verified). The names
        // that break are the ones that change a PREFIX segment, e.g. LIVECONTEXT_WEBASYNC_*
        // (-> livecontext.webasync.*), which is what this test was proved against.
        servletRunner
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource(
                                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                Map.<String, Object>of(
                                        "LIVECONTEXT_WEB_ASYNC_CORE_SIZE", "64",
                                        "LIVECONTEXT_WEB_ASYNC_MAX_SIZE", "64",
                                        "LIVECONTEXT_WEB_ASYNC_QUEUE_CAPACITY", "24"))))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ThreadPoolTaskExecutor executor = context.getBean(EXECUTOR, ThreadPoolTaskExecutor.class);
                    assertThat(executor.getCorePoolSize()).isEqualTo(64);
                    assertThat(executor.getMaxPoolSize()).isEqualTo(64);
                    // Queue capacity has no getter; an empty bounded queue's remaining capacity is it.
                    assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity())
                            .isEqualTo(24);
                });
    }

    @Test
    @DisplayName("idle stream threads are reclaimed, which is what makes active >= core a saturation signal")
    void coreThreadsTimeOut() {
        // Load-bearing twice over: it is why a service that never streams pays nothing for the
        // pool, and it is the premise of StreamingPoolSaturated, which reads
        // active >= core. Without it the gauge would sit at core forever and the alert would fire
        // permanently.
        servletRunner.run(context -> {
            ThreadPoolTaskExecutor executor = context.getBean(EXECUTOR, ThreadPoolTaskExecutor.class);
            assertThat(executor.getThreadPoolExecutor().allowsCoreThreadTimeOut()).isTrue();
            assertThat(executor.getThreadPoolExecutor().getKeepAliveTime(TimeUnit.SECONDS)).isEqualTo(60);
        });
    }

    @Test
    @DisplayName("Micrometer tags the pool with the bean name the storage alerts select on")
    void micrometerNameTagMatchesTheAlertSelector() {
        // StreamingPoolSaturated and StreamingPoolQueueing filter on
        // name="mvcStreamingTaskExecutor". Spring Boot derives that tag from the BEAN NAME, so
        // this is the seam where a rename disarms both alerts silently.
        servletRunner
                .withConfiguration(AutoConfigurations.of(TaskExecutorMetricsAutoConfiguration.class))
                .withBean(SimpleMeterRegistry.class)
                .run(context -> {
                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    assertThat(registry.find("executor.active").tag("name", EXECUTOR).gauge())
                            .as("no executor.active gauge tagged name=%s", EXECUTOR)
                            .isNotNull();
                });
    }

    @Test
    @DisplayName("a service can resize the pool without replacing the bean")
    void sizingIsConfigurable() {
        servletRunner
                .withPropertyValues(
                        "livecontext.web.async.core-size=4",
                        "livecontext.web.async.max-size=4",
                        "livecontext.web.async.queue-capacity=7")
                .run(context -> {
                    ThreadPoolTaskExecutor executor = context.getBean(EXECUTOR, ThreadPoolTaskExecutor.class);
                    assertThat(executor.getCorePoolSize()).isEqualTo(4);
                    assertThat(executor.getMaxPoolSize()).isEqualTo(4);
                    assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(7);
                });
    }

    @Test
    @DisplayName("a service can replace the bean outright")
    void aServiceCanSupplyItsOwnExecutor() {
        // @ConditionalOnMissingBean(name = ...) is the escape hatch for a service whose streaming
        // profile differs. Untested, it is indistinguishable from an unconditional bean that
        // would clash on startup.
        ThreadPoolTaskExecutor replacement = new ThreadPoolTaskExecutor();
        replacement.setCorePoolSize(3);
        servletRunner
                .withBean(EXECUTOR, ThreadPoolTaskExecutor.class, () -> replacement)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(EXECUTOR)).isSameAs(replacement);
                });
    }

    @Test
    @DisplayName("a reactive service (the gateway) gets no streaming executor")
    void reactiveApplicationsAreUntouched() {
        // The gateway is WebFlux. AsyncSupportConfigurer does not apply there, and a 32-thread
        // pool it can never use would be pure waste.
        new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CommonAsyncConfig.class))
                .run(context -> assertThat(context).doesNotHaveBean(EXECUTOR));
    }

    @Test
    @DisplayName("MDC decorator is installed on the streaming executor, so a request id survives into the bytes-writing thread")
    void streamingExecutorCarriesTheMdcDecorator() {
        servletRunner.run(context -> {
            ThreadPoolTaskExecutor executor = context.getBean(EXECUTOR, ThreadPoolTaskExecutor.class);
            assertThat(readField(executor, "taskDecorator")).isInstanceOf(MdcTaskDecorator.class);
        });
    }

    @Test
    @DisplayName("threads are named for the stream pool, so a thread dump attributes a stalled download")
    void streamThreadsAreNamed() {
        servletRunner.run(context -> {
            ThreadPoolTaskExecutor executor = context.getBean(EXECUTOR, ThreadPoolTaskExecutor.class);
            AtomicReference<String> threadName = new AtomicReference<>();
            CountDownLatch ran = new CountDownLatch(1);
            executor.execute(() -> {
                threadName.set(Thread.currentThread().getName());
                ran.countDown();
            });
            assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(threadName.get()).startsWith("mvc-stream-");
        });
    }

    @Test
    @DisplayName("applicationTaskExecutor survives, so @Async and spring.task.execution.* keep working")
    void springBootsOwnExecutorIsNotSuppressed() {
        // Spring Boot only auto-configures applicationTaskExecutor when the context has no
        // Executor of its own (TaskExecutorConfigurations.OnExecutorCondition). Registering a
        // second ThreadPoolTaskExecutor from an auto-configuration is therefore a real risk of
        // silently REPLACING it, which would repoint every @Async method and quietly drop
        // spring.task.execution.* config.
        servletRunner.run(context -> {
            assertThat(context).hasBean(EXECUTOR);
            assertThat(context).hasBean("applicationTaskExecutor");
            assertThat(context.getBean(EXECUTOR))
                    .isNotSameAs(context.getBean("applicationTaskExecutor"));
        });
    }

    @Test
    @DisplayName("an Executor registered before Boot's task auto-configuration DOES suppress applicationTaskExecutor")
    void anEarlierExecutorSuppressesBootsOwn() {
        // The hazard this configuration's @AutoConfigureAfter guards against, demonstrated rather
        // than asserted in prose. Boot registers applicationTaskExecutor only when the context has
        // no Executor of its own, and the suppression takes the AsyncConfigurer with it: every
        // @Async call would fall back to a thread-per-invocation SimpleAsyncTaskExecutor and
        // spring.task.execution.* would go inert, with no exception and no log.
        new WebApplicationContextRunner()
                .withUserConfiguration(ExecutorRegisteredFirst.class)
                .withConfiguration(AutoConfigurations.of(TaskExecutionAutoConfiguration.class))
                .run(context -> assertThat(context)
                        .as("if this ever starts passing, Boot changed its condition and "
                                + "@AutoConfigureAfter on CommonAsyncConfig may be revisited")
                        .doesNotHaveBean("applicationTaskExecutor"));
    }

    @Test
    @DisplayName("@AutoConfigureAfter, not luck, is what keeps applicationTaskExecutor alive")
    void orderingAgainstTaskExecutionAutoConfigurationIsDeclared() {
        // The behavioural form, and it kills the mutant: drop @AutoConfigureAfter from
        // CommonAsyncConfig and this fails while every other test stays green.
        //
        // The runner re-sorts with the real AutoConfigurationSorter, which is exactly what makes
        // the observation possible: with the annotation the sorter puts TaskExecutionAutoConfiguration
        // first and Boot registers applicationTaskExecutor; without it, CommonAsyncConfig sorts
        // first, its Executor trips OnExecutorCondition, and Boot registers neither
        // applicationTaskExecutor nor its AsyncConfigurer.
        //
        // Listing ONLY these two is deliberate. Adding WebMvcAutoConfiguration (as
        // springBootsOwnExecutorIsNotSuppressed does, for a different purpose) drags the
        // com.apimarketplace class to the end of the sort regardless of annotations, which makes
        // the test pass for the mutant too.
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        CommonAsyncConfig.class,
                        TaskExecutionAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasBean(EXECUTOR);
                    assertThat(context)
                            .as("Boot backed off: every @Async call would silently fall back to a "
                                    + "thread-per-invocation SimpleAsyncTaskExecutor")
                            .hasBean("applicationTaskExecutor");
                });
    }

    @Test
    @DisplayName("the gateway is protected by @ConditionalOnClass, not only by being reactive")
    void servletOnlyGuardHoldsWithoutSpringWebMvcOnTheClasspath() {
        // reactiveApplicationsAreUntouched runs in common-lib, where spring-webmvc IS present, so
        // it only exercises @ConditionalOnWebApplication(SERVLET). The gateway has no spring-webmvc
        // at all, so what actually protects it is @ConditionalOnClass(WebMvcConfigurer.class)
        // against a class whose @Bean return type and anonymous inner class both name webmvc types.
        // Hoisting one such reference to the outer class would crashloop the gateway with
        // NoClassDefFoundError and leave every other test green.
        new WebApplicationContextRunner()
                .withClassLoader(new FilteredClassLoader(WebMvcConfigurer.class))
                .withConfiguration(AutoConfigurations.of(CommonAsyncConfig.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(EXECUTOR);
                });
    }

    /** A streaming-executor-shaped bean registered before any auto-configuration runs. */
    @Configuration(proxyBeanMethods = false)
    static class ExecutorRegisteredFirst {
        @Bean(name = "mvcStreamingTaskExecutor")
        ThreadPoolTaskExecutor mvcStreamingTaskExecutor() {
            return new ThreadPoolTaskExecutor();
        }
    }

    private static Object readField(Object target, String name) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
