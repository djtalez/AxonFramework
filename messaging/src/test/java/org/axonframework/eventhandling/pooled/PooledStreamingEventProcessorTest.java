/*
 * Copyright (c) 2010-2021. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.axonframework.eventhandling.pooled;

import org.axonframework.common.AxonConfigurationException;
import org.axonframework.common.stream.BlockingStream;
import org.axonframework.common.transaction.NoTransactionManager;
import org.axonframework.eventhandling.EventHandlerInvoker;
import org.axonframework.eventhandling.EventMessage;
import org.axonframework.eventhandling.GenericEventMessage;
import org.axonframework.eventhandling.GenericTrackedEventMessage;
import org.axonframework.eventhandling.GlobalSequenceTrackingToken;
import org.axonframework.eventhandling.PropagatingErrorHandler;
import org.axonframework.eventhandling.ReplayToken;
import org.axonframework.eventhandling.Segment;
import org.axonframework.eventhandling.TrackedEventMessage;
import org.axonframework.eventhandling.TrackingToken;
import org.axonframework.eventhandling.tokenstore.TokenStore;
import org.axonframework.eventhandling.tokenstore.inmemory.InMemoryTokenStore;
import org.axonframework.messaging.StreamableMessageSource;
import org.axonframework.messaging.unitofwork.RollbackConfigurationType;
import org.axonframework.utils.MockException;
import org.junit.jupiter.api.*;
import org.mockito.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.axonframework.utils.AssertUtils.assertWithin;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Test class validating the {@link PooledStreamingEventProcessor}.
 *
 * @author Allard Buijze
 * @author Steven van Beelen
 */
class PooledStreamingEventProcessorTest {

    private static final String PROCESSOR_NAME = "test";

    private PooledStreamingEventProcessor testSubject;
    private EventHandlerInvoker stubEventHandler;
    private InMemoryMessageSource stubMessageSource;
    private InMemoryTokenStore tokenStore;
    private ScheduledExecutorService coordinatorExecutor;
    private ScheduledExecutorService workerExecutor;

    @BeforeEach
    void setUp() {
        stubMessageSource = new InMemoryMessageSource();
        stubEventHandler = mock(EventHandlerInvoker.class);
        tokenStore = new InMemoryTokenStore();
        coordinatorExecutor = Executors.newScheduledThreadPool(2);
        workerExecutor = Executors.newScheduledThreadPool(8);

        setTestSubject(createTestSubject());

        when(stubEventHandler.canHandleType(any())).thenReturn(true);
        when(stubEventHandler.canHandle(any(), any())).thenReturn(true);
    }

    private void setTestSubject(PooledStreamingEventProcessor testSubject) {
        this.testSubject = testSubject;
    }

    private PooledStreamingEventProcessor createTestSubject() {
        return createTestSubject(builder -> builder);
    }

    private PooledStreamingEventProcessor createTestSubject(
            UnaryOperator<PooledStreamingEventProcessor.Builder> customization
    ) {
        PooledStreamingEventProcessor.Builder processorBuilder =
                PooledStreamingEventProcessor.builder()
                                             .name(PROCESSOR_NAME)
                                             .eventHandlerInvoker(stubEventHandler)
                                             .rollbackConfiguration(RollbackConfigurationType.ANY_THROWABLE)
                                             .errorHandler(PropagatingErrorHandler.instance())
                                             .messageSource(stubMessageSource)
                                             .tokenStore(tokenStore)
                                             .transactionManager(NoTransactionManager.instance())
                                             .coordinatorExecutor(coordinatorExecutor)
                                             .workerExecutor(workerExecutor)
                                             .initialSegmentCount(8)
                                             .claimExtensionThreshold(1000);
        return customization.apply(processorBuilder).build();
    }

    @AfterEach
    void tearDown() {
        testSubject.shutDown();
        coordinatorExecutor.shutdown();
        workerExecutor.shutdown();
    }

    @Test
    void testStartContinuesWhenTokenInitializationFails() {
        InMemoryTokenStore spy = spy(tokenStore);
        setTestSubject(createTestSubject(b -> b.tokenStore(spy)));

        doThrow(new RuntimeException("Simulated failure")).doCallRealMethod()
                                                          .when(spy)
                                                          .initializeTokenSegments(any(), anyInt(), any());

        testSubject.start();

        assertTrue(testSubject.isRunning());
    }

    @Test
    void testStartShutsDownImmediatelyIfCoordinatorExecutorThrowsAnException() {
        ScheduledExecutorService spiedCoordinatorExecutor = spy(coordinatorExecutor);
        doThrow(new IllegalArgumentException("Some exception")).when(spiedCoordinatorExecutor)
                                                               .submit(any(Runnable.class));

        setTestSubject(createTestSubject(builder -> builder.coordinatorExecutor(spiedCoordinatorExecutor)));

        assertThrows(IllegalArgumentException.class, testSubject::start);
        assertFalse(testSubject.isRunning());
    }

    @Test
    void testSecondStartInvocationIsIgnored() {
        ScheduledExecutorService spiedCoordinatorExecutor = spy(coordinatorExecutor);

        setTestSubject(createTestSubject(builder -> builder.coordinatorExecutor(spiedCoordinatorExecutor)));

        testSubject.start();
        // The second invocation does not cause the Coordinator to schedule another CoordinationTask.
        testSubject.start();
        verify(spiedCoordinatorExecutor, times(1)).submit(any(Runnable.class));
    }

    @Test
    void testStartingProcessorClaimsAllAvailableTokens() {
        List<EventMessage<Integer>> events = IntStream.range(0, 100)
                                                      .mapToObj(GenericEventMessage::new)
                                                      .collect(Collectors.toList());
        events.forEach(stubMessageSource::publishMessage);
        mockEventHandlerInvoker();

        testSubject.start();

        assertWithin(1, TimeUnit.SECONDS, () -> assertEquals(8, testSubject.processingStatus().size()));
        assertWithin(2, TimeUnit.SECONDS, () -> {
            long nonNullTokens = IntStream.range(0, 8)
                                          .mapToObj(i -> tokenStore.fetchToken(PROCESSOR_NAME, i))
                                          .filter(Objects::nonNull)
                                          .count();
            assertEquals(8, nonNullTokens);
        });
        assertEquals(8, testSubject.processingStatus().size());
    }

    @Test
    void testStartingAfterShutdownLetsProcessorProceed() {
        when(stubEventHandler.supportsReset()).thenReturn(true);

        testSubject.start();
        testSubject.shutDown();

        List<EventMessage<Integer>> events = IntStream.range(0, 100)
                                                      .mapToObj(GenericEventMessage::new)
                                                      .collect(Collectors.toList());
        events.forEach(stubMessageSource::publishMessage);

        testSubject.start();

        assertWithin(
                1, TimeUnit.SECONDS,
                () -> assertEquals(8, testSubject.processingStatus().size())
        );
        assertWithin(2, TimeUnit.SECONDS, () -> {
            long nonNullTokens = IntStream.range(0, 8)
                                          .mapToObj(i -> tokenStore.fetchToken(PROCESSOR_NAME, i))
                                          .filter(Objects::nonNull)
                                          .count();
            assertEquals(8, nonNullTokens);
        });
        assertEquals(8, testSubject.processingStatus().size());
    }

    @Test
    void testAllTokensUpdatedToLatestValue() {
        List<EventMessage<Integer>> events = IntStream.range(0, 100)
                                                      .mapToObj(GenericEventMessage::new)
                                                      .collect(Collectors.toList());
        events.forEach(stubMessageSource::publishMessage);
        mockEventHandlerInvoker();

        testSubject.start();

        assertWithin(1, TimeUnit.SECONDS, () -> assertEquals(8, testSubject.processingStatus().size()));
        assertWithin(6, TimeUnit.SECONDS, () -> {
            long lowestToken = IntStream.range(0, 8)
                                        .mapToObj(i -> tokenStore.fetchToken(testSubject.getName(), i))
                                        .mapToLong(this::tokenPosition)
                                        .min()
                                        .orElse(-1);
            assertEquals(100, lowestToken);
        });
    }

    private long tokenPosition(TrackingToken token) {
        return token == null ? 0 : token.position().orElseThrow(IllegalArgumentException::new);
    }

    @Test
    void testExceptionWhileHandlingEventAbortsWorker() throws Exception {
        List<EventMessage<Integer>> events = Stream.of(1, 2, 2, 4, 5)
                                                   .map(GenericEventMessage::new)
                                                   .collect(Collectors.toList());
        mockEventHandlerInvoker();
        doThrow(new RuntimeException("Simulating worker failure"))
                .doNothing()
                .when(stubEventHandler)
                .handle(argThat(em -> em.getIdentifier().equals(events.get(2).getIdentifier())), any());

        testSubject.start();

        assertWithin(1, TimeUnit.SECONDS, () -> assertEquals(8, testSubject.processingStatus().size()));
        assertEquals(8, tokenStore.fetchSegments(PROCESSOR_NAME).length);

        verify(stubEventHandler, never()).canHandle(any(), any());

        events.forEach(e -> stubMessageSource.publishMessage(e));

        assertWithin(1, TimeUnit.SECONDS, () -> {
            try {
                verify(stubEventHandler).handle(
                        argThat(em -> em.getIdentifier().equals(events.get(2).getIdentifier())),
                        argThat(s -> s.getSegmentId() == events.get(2).getPayload())
                );
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertWithin(1, TimeUnit.SECONDS, () -> {
            assertEquals(7, testSubject.processingStatus().size());
            assertFalse(testSubject.processingStatus().containsKey(2));
        });
    }

    @Test
    void testWorkPackageIsAbortedWhenExtendingClaimFails() {
        InMemoryTokenStore spy = spy(tokenStore);
        setTestSubject(createTestSubject(b -> b.tokenStore(spy)
                                               .messageSource(new InMemoryMessageSource(true))
                                               .claimExtensionThreshold(10)));

        doThrow(new MockException("Simulated failure")).when(spy)
                                                       .extendClaim(any(), anyInt());

        testSubject.start();
        assertWithin(
                250, TimeUnit.MILLISECONDS,
                () -> verify(spy, atLeastOnce()).extendClaim(testSubject.getName(), 0)
        );
        assertWithin(100, TimeUnit.MILLISECONDS, () -> assertTrue(testSubject.processingStatus().isEmpty()));
    }

    @Test
    void testHandlingUnknownMessageTypeWillAdvanceToken() {
        setTestSubject(createTestSubject(builder -> builder.initialSegmentCount(1)));

        when(stubEventHandler.canHandle(any(), any())).thenReturn(false);
        when(stubEventHandler.canHandleType(Integer.class)).thenReturn(false);

        EventMessage<Integer> eventToIgnoreOne = GenericEventMessage.asEventMessage(1337);
        stubMessageSource.publishMessage(eventToIgnoreOne);

        testSubject.start();
        assertWithin(1, TimeUnit.SECONDS, () -> assertEquals(1, testSubject.processingStatus().size()));
        assertWithin(
                100, TimeUnit.MILLISECONDS,
                () -> assertEquals(1, testSubject.processingStatus().get(0).getCurrentPosition().orElse(0))
        );

        assertEquals(1, stubMessageSource.getIgnoredEvents().size());
    }

    @Test
    void testEventsWhichMustBeIgnoredAreNotHandledOnlyValidated() throws Exception {
        setTestSubject(createTestSubject(builder -> builder.initialSegmentCount(1)));

        // The custom ArgumentMatcher, for some reason, first runs the assertion with null, failing the current check.
        // Hence a null check is added to the matcher.
        when(stubEventHandler.canHandle(
                argThat(argument -> argument != null && Integer.class.equals(argument.getPayloadType())), any()
        )).thenReturn(false);
        when(stubEventHandler.canHandle(
                argThat(argument -> argument != null && String.class.equals(argument.getPayloadType())), any()
        )).thenReturn(true);
        when(stubEventHandler.canHandleType(Integer.class)).thenReturn(false);
        when(stubEventHandler.canHandleType(String.class)).thenReturn(true);

        EventMessage<Integer> eventToIgnoreOne = GenericEventMessage.asEventMessage(1337);
        EventMessage<Integer> eventToIgnoreTwo = GenericEventMessage.asEventMessage(42);
        EventMessage<Integer> eventToIgnoreThree = GenericEventMessage.asEventMessage(9001);
        List<Integer> eventsToIgnore = new ArrayList<>();
        eventsToIgnore.add(eventToIgnoreOne.getPayload());
        eventsToIgnore.add(eventToIgnoreTwo.getPayload());
        eventsToIgnore.add(eventToIgnoreThree.getPayload());

        EventMessage<String> eventToHandleOne = GenericEventMessage.asEventMessage("some-text");
        EventMessage<String> eventToHandleTwo = GenericEventMessage.asEventMessage("some-other-text");
        List<String> eventsToHandle = new ArrayList<>();
        eventsToHandle.add(eventToHandleOne.getPayload());
        eventsToHandle.add(eventToHandleTwo.getPayload());

        List<Object> eventsToValidate = new ArrayList<>();
        eventsToValidate.add(eventToIgnoreOne.getPayload());
        eventsToValidate.add(eventToIgnoreTwo.getPayload());
        eventsToValidate.add(eventToIgnoreThree.getPayload());
        eventsToValidate.add(eventToHandleOne.getPayload());
        eventsToValidate.add(eventToHandleTwo.getPayload());

        stubMessageSource.publishMessage(eventToIgnoreOne);
        stubMessageSource.publishMessage(eventToIgnoreTwo);
        stubMessageSource.publishMessage(eventToIgnoreThree);
        stubMessageSource.publishMessage(eventToHandleOne);
        stubMessageSource.publishMessage(eventToHandleTwo);

        testSubject.start();

        assertWithin(1, TimeUnit.SECONDS, () -> assertEquals(1, testSubject.processingStatus().size()));
        // noinspection unchecked
        ArgumentCaptor<EventMessage<?>> validatedEventCaptor = ArgumentCaptor.forClass(EventMessage.class);
        verify(stubEventHandler, timeout(500).times(5)).canHandle(validatedEventCaptor.capture(), any());

        List<EventMessage<?>> validatedEvents = validatedEventCaptor.getAllValues();
        assertEquals(5, validatedEvents.size());
        for (EventMessage<?> validatedEvent : validatedEvents) {
            assertTrue(eventsToValidate.contains(validatedEvent.getPayload()));
        }

        //noinspection unchecked
        ArgumentCaptor<EventMessage<?>> handledEventsCaptor = ArgumentCaptor.forClass(EventMessage.class);
        verify(stubEventHandler, timeout(500).times(2)).handle(handledEventsCaptor.capture(), any());
        List<EventMessage<?>> handledEvents = handledEventsCaptor.getAllValues();
        assertEquals(2, handledEvents.size());
        for (EventMessage<?> validatedEvent : handledEvents) {
            //noinspection SuspiciousMethodCalls
            assertTrue(eventsToHandle.contains(validatedEvent.getPayload()));
        }

        List<TrackedEventMessage<?>> ignoredEvents = stubMessageSource.getIgnoredEvents();
        assertEquals(3, ignoredEvents.size());
        for (TrackedEventMessage<?> ignoredMessage : ignoredEvents) {
            //noinspection SuspiciousMethodCalls
            assertTrue(eventsToIgnore.contains(ignoredMessage.getPayload()));
        }
    }

    @Test
    void testCoordinationIsTriggeredThroughEventAvailabilityCallback() {
        boolean streamCallbackSupported = true;
        //noinspection ConstantConditions
        InMemoryMessageSource testMessageSource = new InMemoryMessageSource(streamCallbackSupported);
        setTestSubject(createTestSubject(builder -> builder.messageSource(testMessageSource)));
        mockEventHandlerInvoker();

        Stream.of(0, 1, 2, 3)
              .map(GenericEventMessage::new)
              .forEach(testMessageSource::publishMessage);

        testSubject.start();

        assertWithin(1, TimeUnit.SECONDS, () -> assertEquals(8, testSubject.processingStatus().size()));
        assertWithin(1, TimeUnit.SECONDS, () -> {
            long lowestToken = testSubject.processingStatus().values().stream()
                                          .map(status -> status.getCurrentPosition().orElse(-1))
                                          .min(Long::compareTo)
                                          .orElse(-1L);

            assertEquals(4, lowestToken);
        });

        Stream.of(4, 5, 6, 7)
              .map(GenericEventMessage::new)
              .forEach(testMessageSource::publishMessage);
        testMessageSource.runOnAvailableCallback();

        assertWithin(1, TimeUnit.SECONDS, () -> {
            long lowestToken = testSubject.processingStatus().values().stream()
                                          .map(status -> status.getCurrentPosition().orElse(-1))
                                          .min(Long::compareTo)
                                          .orElse(-1L);

            assertEquals(8, lowestToken);
        });
    }

    @Test
    void testShutdownCompletesAfterAbortingWorkPackages()
            throws InterruptedException, ExecutionException, TimeoutException {
        testSubject.start();
        Stream.of(1, 2, 2, 4, 5).map(GenericEventMessage::new).forEach(stubMessageSource::publishMessage);

        assertWithin(1, TimeUnit.SECONDS, () -> assertFalse(testSubject.processingStatus().isEmpty()));

        testSubject.shutdownAsync().get(1, TimeUnit.SECONDS);
        assertWithin(1, TimeUnit.SECONDS, () -> assertEquals(0, testSubject.processingStatus().size()));

        assertFalse(coordinatorExecutor.isShutdown());
        assertFalse(workerExecutor.isShutdown());
    }

    @Test
    void testShutdownProcessorWhichHasNotStartedYetReturnsCompletedFuture() {
        assertTrue(testSubject.shutdownAsync().isDone());
    }

    @Test
    void testShutdownProcessorAsyncTwiceReturnsSameFuture() {
        testSubject.start();

        CompletableFuture<Void> resultOne = testSubject.shutdownAsync();
        CompletableFuture<Void> resultTwo = testSubject.shutdownAsync();

        assertSame(resultOne, resultTwo);
    }

    @Test
    void testStartFailsWhenShutdownIsInProgress() throws Exception {
        when(stubEventHandler.canHandle(any(), any())).thenReturn(true);
        // Use CountDownLatch to block worker threads from actually doing work, and thus shutting down successfully.
        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(i -> latch.await(10, TimeUnit.MILLISECONDS)).when(stubEventHandler)
                                                             .handle(any(), any());

        testSubject.start();

        Stream.of(1, 2, 2, 4, 5)
              .map(GenericEventMessage::new)
              .forEach(stubMessageSource::publishMessage);

        assertWithin(1, TimeUnit.SECONDS, () -> assertFalse(testSubject.processingStatus().isEmpty()));

        CompletableFuture<Void> shutdownComplete = testSubject.shutdownAsync();
        assertThrows(IllegalStateException.class, () -> testSubject.start());
        // Unblock the Worker threads
        latch.countDown();
        shutdownComplete.get(1, TimeUnit.SECONDS);

        // This is allowed
        assertDoesNotThrow(() -> testSubject.start());
    }

    @Test
    void testIsRunningOnlyReturnsTrueForStartedProcessor() {
        assertFalse(testSubject.isRunning());

        testSubject.start();

        assertTrue(testSubject.isRunning());
    }

    @Test
    void testIsErrorForFailingMessageSourceOperation() {
        assertFalse(testSubject.isError());

        testSubject.start();

        assertFalse(testSubject.isError());

        stubMessageSource.publishMessage(InMemoryMessageSource.FAIL_EVENT);

        assertWithin(500, TimeUnit.MILLISECONDS, () -> assertTrue(testSubject.isError()));

        // After one exception the Coordinator#errorWaitBackOff is 1 second. After this, the Coordinator should proceed.
        Stream.of(1, 2, 2, 4, 5)
              .map(GenericEventMessage::new)
              .forEach(stubMessageSource::publishMessage);
        assertWithin(1500, TimeUnit.MILLISECONDS, () -> assertFalse(testSubject.isError()));
    }

    @Test
    void testIsErrorWhenOpeningTheStreamFails() {
        StreamableMessageSource<TrackedEventMessage<?>> spiedMessageSource = spy(new InMemoryMessageSource());
        when(spiedMessageSource.openStream(any())).thenThrow(new IllegalStateException("Failed to open the stream"))
                                                  .thenCallRealMethod();
        setTestSubject(createTestSubject(builder -> builder.messageSource(spiedMessageSource)));

        assertFalse(testSubject.isError());

        testSubject.start();

        assertWithin(500, TimeUnit.MILLISECONDS, () -> assertTrue(testSubject.isError()));

        // After one exception the Coordinator#errorWaitBackOff is 1 second. After this, the Coordinator should proceed.
        Stream.of(1, 2, 2, 4, 5)
              .map(GenericEventMessage::new)
              .forEach(stubMessageSource::publishMessage);
        assertWithin(1500, TimeUnit.MILLISECONDS, () -> assertFalse(testSubject.isError()));
    }

    @Test
    void testGetTokenStoreIdentifier() {
        String expectedIdentifier = "some-identifier";

        TokenStore tokenStore = mock(TokenStore.class);
        when(tokenStore.retrieveStorageIdentifier()).thenReturn(Optional.of(expectedIdentifier));
        setTestSubject(createTestSubject(builder -> builder.tokenStore(tokenStore)));

        assertEquals(expectedIdentifier, testSubject.getTokenStoreIdentifier());
    }

    @Test
    void testReleaseSegmentMakesTheTokenUnclaimedForTwiceTheTokenClaimInterval() {
        // Given...
        int testSegmentId = 0;
        int testTokenClaimInterval = 500;

        setTestSubject(createTestSubject(builder -> builder.initialSegmentCount(1)
                                                           .tokenClaimInterval(testTokenClaimInterval)));
        testSubject.start();
        // Assert the single WorkPackage is in progress prior to invoking the release.
        assertWithin(
                testTokenClaimInterval, TimeUnit.MILLISECONDS,
                () -> assertNotNull(testSubject.processingStatus().get(testSegmentId))
        );

        // When...
        testSubject.releaseSegment(testSegmentId);

        assertWithin(
                testTokenClaimInterval + 50, TimeUnit.MILLISECONDS,
                () -> assertNull(testSubject.processingStatus().get(testSegmentId))
        );
        // Assert that within twice the tokenClaimInterval, the WorkPackage is in progress again.
        assertWithin(
                (testTokenClaimInterval * 2) + 50, TimeUnit.MILLISECONDS,
                () -> assertNotNull(testSubject.processingStatus().get(testSegmentId))
        );
    }

    @Test
    void testSplitSegmentIsNotSupported() {
        TokenStore tokenStoreWhichCannotSplitSegments = mock(TokenStore.class);
        when(tokenStoreWhichCannotSplitSegments.requiresExplicitSegmentInitialization()).thenReturn(false);
        setTestSubject(createTestSubject(builder -> builder.tokenStore(tokenStoreWhichCannotSplitSegments)));

        CompletableFuture<Boolean> result = testSubject.splitSegment(0);

        assertTrue(result.isDone());
        assertTrue(result.isCompletedExceptionally());
        result.exceptionally(exception -> {
            assertTrue(exception.getClass().isAssignableFrom(UnsupportedOperationException.class));
            return null;
        });
    }

    @Test
    void testSplitSegment() {
        // Given...
        int testSegmentId = 0;
        int testTokenClaimInterval = 500;

        setTestSubject(createTestSubject(builder -> builder.initialSegmentCount(1)
                                                           .tokenClaimInterval(testTokenClaimInterval)));
        testSubject.start();
        // Assert the single WorkPackage is in progress prior to invoking the split.
        assertWithin(
                500, TimeUnit.MILLISECONDS,
                () -> assertNotNull(testSubject.processingStatus().get(testSegmentId))
        );

        // When...
        CompletableFuture<Boolean> result = testSubject.splitSegment(testSegmentId);

        // Assert the SplitTask is done and completed successfully.
        assertWithin(testTokenClaimInterval * 2, TimeUnit.MILLISECONDS, () -> assertTrue(result.isDone()));
        assertFalse(result.isCompletedExceptionally());
        // Assert the Coordinator has set two WorkPackages on the segments.
        assertWithin(
                testTokenClaimInterval, TimeUnit.MILLISECONDS,
                () -> assertNotNull(testSubject.processingStatus().get(testSegmentId))
        );
        assertWithin(
                testTokenClaimInterval, TimeUnit.MILLISECONDS,
                () -> assertNotNull(testSubject.processingStatus().get(1))
        );
    }

    @Test
    void testMergeSegmentIsNotSupported() {
        TokenStore tokenStoreWhichCannotMergeSegments = mock(TokenStore.class);
        when(tokenStoreWhichCannotMergeSegments.requiresExplicitSegmentInitialization()).thenReturn(false);
        setTestSubject(createTestSubject(builder -> builder.tokenStore(tokenStoreWhichCannotMergeSegments)));

        CompletableFuture<Boolean> result = testSubject.mergeSegment(0);

        assertTrue(result.isDone());
        assertTrue(result.isCompletedExceptionally());
        result.exceptionally(exception -> {
            assertTrue(exception.getClass().isAssignableFrom(UnsupportedOperationException.class));
            return null;
        });
    }

    @Test
    void testMergeSegment() {
        // Given...
        int testSegmentId = 0;
        int testSegmentIdToMerge = 1;
        int testTokenClaimInterval = 500;

        setTestSubject(createTestSubject(builder -> builder.initialSegmentCount(2)
                                                           .tokenClaimInterval(testTokenClaimInterval)));
        testSubject.start();
        // Assert the single WorkPackage is in progress prior to invoking the merge.
        assertWithin(
                testTokenClaimInterval, TimeUnit.MILLISECONDS,
                () -> {
                    assertNotNull(testSubject.processingStatus().get(testSegmentId));
                    assertNotNull(testSubject.processingStatus().get(testSegmentIdToMerge));
                }
        );

        // When...
        CompletableFuture<Boolean> result = testSubject.mergeSegment(testSegmentId);

        // Assert the MergeTask is done and completed successfully.
        assertWithin(testTokenClaimInterval * 2, TimeUnit.MILLISECONDS, () -> assertTrue(result.isDone()));
        assertFalse(result.isCompletedExceptionally());
        // Assert the Coordinator has only one WorkPackage at work now.
        assertWithin(
                testTokenClaimInterval, TimeUnit.MILLISECONDS,
                () -> assertNotNull(testSubject.processingStatus().get(testSegmentId))
        );
        assertWithin(
                testTokenClaimInterval, TimeUnit.MILLISECONDS,
                () -> assertNull(testSubject.processingStatus().get(testSegmentIdToMerge))
        );
    }

    @Test
    void testSupportReset() {
        when(stubEventHandler.supportsReset()).thenReturn(true);

        assertTrue(testSubject.supportsReset());

        when(stubEventHandler.supportsReset()).thenReturn(false);

        assertFalse(testSubject.supportsReset());
    }

    @Test
    void testResetTokensFailsIfTheProcessorIsStillRunning() {
        testSubject.start();

        assertThrows(IllegalStateException.class, () -> testSubject.resetTokens());
    }

    @Test
    void testResetTokens() {
        int expectedSegmentCount = 2;
        TrackingToken expectedToken = new GlobalSequenceTrackingToken(42);

        when(stubEventHandler.supportsReset()).thenReturn(true);
        setTestSubject(createTestSubject(builder -> builder.initialSegmentCount(expectedSegmentCount)
                                                           .initialToken(source -> expectedToken)));

        // Start and stop the processor to initialize the tracking tokens
        testSubject.start();
        testSubject.shutDown();

        testSubject.resetTokens();

        verify(stubEventHandler).performReset(null);

        int[] segments = tokenStore.fetchSegments(PROCESSOR_NAME);
        assertEquals(expectedSegmentCount, segments.length);
        // The token stays the same, as the original and token after reset are identical.
        assertEquals(expectedToken, tokenStore.fetchToken(PROCESSOR_NAME, segments[0]));
        assertEquals(expectedToken, tokenStore.fetchToken(PROCESSOR_NAME, segments[1]));
    }

    @Test
    void testResetTokensWithContext() {
        int expectedSegmentCount = 2;
        TrackingToken expectedToken = new GlobalSequenceTrackingToken(42);
        String expectedContext = "my-context";

        when(stubEventHandler.supportsReset()).thenReturn(true);
        setTestSubject(createTestSubject(builder -> builder.initialSegmentCount(expectedSegmentCount)
                                                           .initialToken(source -> expectedToken)));

        // Start and stop the processor to initialize the tracking tokens
        testSubject.start();
        testSubject.shutDown();

        testSubject.resetTokens(expectedContext);

        verify(stubEventHandler).performReset(expectedContext);

        int[] segments = tokenStore.fetchSegments(PROCESSOR_NAME);
        assertEquals(expectedSegmentCount, segments.length);
        // The token stays the same, as the original and token after reset are identical.
        assertEquals(expectedToken, tokenStore.fetchToken(PROCESSOR_NAME, segments[0]));
        assertEquals(expectedToken, tokenStore.fetchToken(PROCESSOR_NAME, segments[1]));
    }

    @Test
    void testResetTokensFromDefinedPosition() {
        TrackingToken testToken = new GlobalSequenceTrackingToken(42);

        int expectedSegmentCount = 2;
        TrackingToken expectedToken = ReplayToken.createReplayToken(testToken, null);

        when(stubEventHandler.supportsReset()).thenReturn(true);
        setTestSubject(createTestSubject(builder -> builder.initialSegmentCount(expectedSegmentCount)
                                                           .initialToken(source -> testToken)));

        // Start and stop the processor to initialize the tracking tokens
        testSubject.start();
        testSubject.shutDown();

        testSubject.resetTokens(StreamableMessageSource::createTailToken);

        verify(stubEventHandler).performReset(null);

        int[] segments = tokenStore.fetchSegments(PROCESSOR_NAME);
        assertEquals(expectedSegmentCount, segments.length);
        assertEquals(expectedToken, tokenStore.fetchToken(PROCESSOR_NAME, segments[0]));
        assertEquals(expectedToken, tokenStore.fetchToken(PROCESSOR_NAME, segments[1]));
    }

    @Test
    void testResetTokensFromDefinedPositionAndWithResetContext() {
        TrackingToken testToken = new GlobalSequenceTrackingToken(42);

        int expectedSegmentCount = 2;
        TrackingToken expectedToken = ReplayToken.createReplayToken(testToken, null);
        String expectedContext = "my-context";

        when(stubEventHandler.supportsReset()).thenReturn(true);
        setTestSubject(createTestSubject(builder -> builder.initialSegmentCount(expectedSegmentCount)
                                                           .initialToken(source -> testToken)));

        // Start and stop the processor to initialize the tracking tokens
        testSubject.start();
        testSubject.shutDown();

        testSubject.resetTokens(StreamableMessageSource::createTailToken, expectedContext);

        verify(stubEventHandler).performReset(expectedContext);

        int[] segments = tokenStore.fetchSegments(PROCESSOR_NAME);
        assertEquals(expectedSegmentCount, segments.length);
        assertEquals(expectedToken, tokenStore.fetchToken(PROCESSOR_NAME, segments[0]));
        assertEquals(expectedToken, tokenStore.fetchToken(PROCESSOR_NAME, segments[1]));
    }

    @Test
    void testMaxCapacityDefaultsToShortMax() {
        assertEquals(Short.MAX_VALUE, testSubject.maxCapacity());
    }

    @Test
    void testMaxCapacityReturnsConfiguredCapacity() {
        int expectedMaxCapacity = 500;
        setTestSubject(createTestSubject(builder -> builder.maxClaimedSegments(expectedMaxCapacity)));

        assertEquals(expectedMaxCapacity, testSubject.maxCapacity());
    }

    @Test
    void testProcessingStatusIsUpdatedWithTrackingToken() {
        testSubject.start();
        mockEventHandlerInvoker();

        Stream.of(1, 2, 2, 4, 5)
              .map(GenericEventMessage::new)
              .forEach(stubMessageSource::publishMessage);

        assertWithin(
                1, TimeUnit.SECONDS,
                () -> testSubject.processingStatus().values().forEach(
                        status -> assertEquals(5, status.getCurrentPosition().orElse(0))
                )
        );
    }

    private void mockEventHandlerInvoker() {
        when(stubEventHandler.canHandleType(any())).thenReturn(true);
        when(stubEventHandler.canHandle(any(), any())).thenAnswer(
                answer -> answer.getArgument(0, EventMessage.class)
                                .getPayload()
                                .equals(answer.getArgument(1, Segment.class).getSegmentId())
        );
    }

    @Test
    void testBuildWithNullMessageSourceThrowsAxonConfigurationException() {
        PooledStreamingEventProcessor.Builder builderTestSubject = PooledStreamingEventProcessor.builder();

        assertThrows(AxonConfigurationException.class, () -> builderTestSubject.messageSource(null));
    }

    @Test
    void testBuildWithoutMessageSourceThrowsAxonConfigurationException() {
        PooledStreamingEventProcessor.Builder builderTestSubject =
                PooledStreamingEventProcessor.builder()
                                             .tokenStore(new InMemoryTokenStore())
                                             .transactionManager(NoTransactionManager.INSTANCE);

        assertThrows(AxonConfigurationException.class, builderTestSubject::build);
    }

    @Test
    void testBuildWithNullTokenStoreThrowsAxonConfigurationException() {
        PooledStreamingEventProcessor.Builder builderTestSubject = PooledStreamingEventProcessor.builder();

        assertThrows(AxonConfigurationException.class, () -> builderTestSubject.tokenStore(null));
    }

    @Test
    void testBuildWithoutTokenStoreThrowsAxonConfigurationException() {
        PooledStreamingEventProcessor.Builder builderTestSubject =
                PooledStreamingEventProcessor.builder()
                                             .name(PROCESSOR_NAME)
                                             .eventHandlerInvoker(stubEventHandler)
                                             .messageSource(stubMessageSource)
                                             .transactionManager(NoTransactionManager.INSTANCE);

        assertThrows(AxonConfigurationException.class, builderTestSubject::build);
    }

    @Test
    void testBuildWithNullTransactionManagerThrowsAxonConfigurationException() {
        PooledStreamingEventProcessor.Builder builderTestSubject = PooledStreamingEventProcessor.builder();

        assertThrows(AxonConfigurationException.class, () -> builderTestSubject.transactionManager(null));
    }

    @Test
    void testBuildWithoutTransactionManagerThrowsAxonConfigurationException() {
        PooledStreamingEventProcessor.Builder builderTestSubject =
                PooledStreamingEventProcessor.builder()
                                             .name(PROCESSOR_NAME)
                                             .eventHandlerInvoker(stubEventHandler)
                                             .messageSource(stubMessageSource)
                                             .tokenStore(new InMemoryTokenStore());

        assertThrows(AxonConfigurationException.class, builderTestSubject::build);
    }

    @Test
    void testBuildWithNullCoordinatorExecutorThrowsAxonConfigurationException() {
        PooledStreamingEventProcessor.Builder builderTestSubject = PooledStreamingEventProcessor.builder();

        assertThrows(
                AxonConfigurationException.class,
                () -> builderTestSubject.coordinatorExecutor((ScheduledExecutorService) null)
        );
    }

    @Test
    void testBuildWithNullCoordinatorExecutorBuilderThrowsAxonConfigurationException() {
        PooledStreamingEventProcessor.Builder builderTestSubject = PooledStreamingEventProcessor.builder();

        assertThrows(
                AxonConfigurationException.class,
                () -> builderTestSubject.coordinatorExecutor((Function<String, ScheduledExecutorService>) null)
        );
    }

    @Test
    void testBuildWithoutCoordinatorExecutorThrowsAxonConfigurationException() {
        PooledStreamingEventProcessor.Builder builderTestSubject =
                PooledStreamingEventProcessor.builder()
                                             .name(PROCESSOR_NAME)
                                             .eventHandlerInvoker(stubEventHandler)
                                             .messageSource(stubMessageSource)
                                             .tokenStore(new InMemoryTokenStore())
                                             .transactionManager(NoTransactionManager.instance());

        assertThrows(AxonConfigurationException.class, builderTestSubject::build);
    }

    @Test
    void testBuildWithNullWorkerExecutorThrowsAxonConfigurationException() {
        PooledStreamingEventProcessor.Builder builderTestSubject = PooledStreamingEventProcessor.builder();

        assertThrows(
                AxonConfigurationException.class,
                () -> builderTestSubject.workerExecutor((ScheduledExecutorService) null)
        );
    }

    @Test
    void testBuildWithNullWorkerExecutorBuilderThrowsAxonConfigurationException() {
        PooledStreamingEventProcessor.Builder builderTestSubject = PooledStreamingEventProcessor.builder();

        assertThrows(
                AxonConfigurationException.class,
                () -> builderTestSubject.workerExecutor((Function<String, ScheduledExecutorService>) null)
        );
    }

    @Test
    void testBuildWithoutWorkerExecutorThrowsAxonConfigurationException() {
        PooledStreamingEventProcessor.Builder builderTestSubject =
                PooledStreamingEventProcessor.builder()
                                             .name(PROCESSOR_NAME)
                                             .eventHandlerInvoker(stubEventHandler)
                                             .messageSource(stubMessageSource)
                                             .tokenStore(new InMemoryTokenStore())
                                             .transactionManager(NoTransactionManager.instance())
                                             .coordinatorExecutor(coordinatorExecutor);

        assertThrows(AxonConfigurationException.class, builderTestSubject::build);
    }

    @Test
    void testBuildWithZeroOrNegativeInitialSegmentCountThrowsAxonConfigurationException() {
        PooledStreamingEventProcessor.Builder builderTestSubject = PooledStreamingEventProcessor.builder();

        assertThrows(AxonConfigurationException.class, () -> builderTestSubject.initialSegmentCount(0));
        assertThrows(AxonConfigurationException.class, () -> builderTestSubject.initialSegmentCount(-1));
    }

    @Test
    void testBuildWithNullInitialTokenThrowsAxonConfigurationException() {
        PooledStreamingEventProcessor.Builder builderTestSubject = PooledStreamingEventProcessor.builder();

        assertThrows(AxonConfigurationException.class, () -> builderTestSubject.initialToken(null));
    }

    @Test
    void testBuildWithZeroOrNegativeTokenClaimIntervalThrowsAxonConfigurationException() {
        PooledStreamingEventProcessor.Builder builderTestSubject = PooledStreamingEventProcessor.builder();

        assertThrows(AxonConfigurationException.class, () -> builderTestSubject.tokenClaimInterval(0));
        assertThrows(AxonConfigurationException.class, () -> builderTestSubject.tokenClaimInterval(-1));
    }

    @Test
    void testBuildWithZeroOrNegativeMaxCapacityThrowsAxonConfigurationException() {
        PooledStreamingEventProcessor.Builder builderTestSubject = PooledStreamingEventProcessor.builder();

        assertThrows(AxonConfigurationException.class, () -> builderTestSubject.maxClaimedSegments(0));
        assertThrows(AxonConfigurationException.class, () -> builderTestSubject.maxClaimedSegments(-1));
    }

    @Test
    void testBuildWithZeroOrNegativeClaimExtensionThresholdThrowsAxonConfigurationException() {
        PooledStreamingEventProcessor.Builder builderTestSubject = PooledStreamingEventProcessor.builder();

        assertThrows(AxonConfigurationException.class, () -> builderTestSubject.claimExtensionThreshold(0));
        assertThrows(AxonConfigurationException.class, () -> builderTestSubject.claimExtensionThreshold(-1));
    }

    @Test
    void testBuildWithZeroOrNegativeBatchSizeThrowsAxonConfigurationException() {
        PooledStreamingEventProcessor.Builder builderTestSubject = PooledStreamingEventProcessor.builder();

        assertThrows(AxonConfigurationException.class, () -> builderTestSubject.batchSize(0));
        assertThrows(AxonConfigurationException.class, () -> builderTestSubject.batchSize(-1));
    }

    @Test
    void testIsReplaying() {
        mockEventHandlerInvoker();
        when(stubEventHandler.supportsReset()).thenReturn(true);

        setTestSubject(createTestSubject(builder -> builder.initialSegmentCount(1)));

        List<EventMessage<Integer>> events = IntStream.range(0, 100)
                                                      .mapToObj(GenericEventMessage::new)
                                                      .collect(Collectors.toList());
        events.forEach(stubMessageSource::publishMessage);

        testSubject.start();

        assertWithin(
                1, TimeUnit.SECONDS,
                () -> {
                    assertEquals(1, testSubject.processingStatus().size());
                    assertTrue(testSubject.processingStatus().get(0).isCaughtUp());
                    assertFalse(testSubject.processingStatus().get(0).isReplaying());
                    assertFalse(testSubject.isReplaying());
                }
        );

        testSubject.shutDown();
        testSubject.resetTokens(StreamableMessageSource::createTailToken);
        testSubject.start();

        assertWithin(
                1, TimeUnit.SECONDS, () -> {
                    assertEquals(1, testSubject.processingStatus().size());
                    assertTrue(testSubject.processingStatus().get(0).isCaughtUp());
                    assertTrue(testSubject.processingStatus().get(0).isReplaying());
                    assertFalse(testSubject.isReplaying());
                }
        );
    }


    private static class InMemoryMessageSource implements StreamableMessageSource<TrackedEventMessage<?>> {

        private static final String FAIL_PAYLOAD = "FAIL";
        private static final EventMessage<String> FAIL_EVENT = GenericEventMessage.asEventMessage(FAIL_PAYLOAD);

        private List<TrackedEventMessage<?>> messages = new CopyOnWriteArrayList<>();
        private final boolean streamCallbackSupported;
        private final List<TrackedEventMessage<?>> ignoredEvents = new CopyOnWriteArrayList<>();
        private Runnable onAvailableCallback = null;

        private InMemoryMessageSource() {
            this(false);
        }

        private InMemoryMessageSource(boolean streamCallbackSupported) {
            this.streamCallbackSupported = streamCallbackSupported;
        }

        @Override
        public BlockingStream<TrackedEventMessage<?>> openStream(TrackingToken trackingToken) {
            return new BlockingStream<TrackedEventMessage<?>>() {

                private int lastToken;

                @Override
                public Optional<TrackedEventMessage<?>> peek() {
                    if (messages.size() > lastToken) {
                        return Optional.of(messages.get(lastToken));
                    }
                    return Optional.empty();
                }

                @Override
                public boolean hasNextAvailable(int timeout, TimeUnit unit) {
                    return peek().isPresent();
                }

                @Override
                public TrackedEventMessage<?> nextAvailable() {
                    TrackedEventMessage<?> next = peek().orElseThrow(
                            () -> new RuntimeException("The processor should never perform a blocking call")
                    );
                    this.lastToken = (int) next.trackingToken()
                                               .position()
                                               .orElseThrow(() -> new UnsupportedOperationException("Not supported"));

                    if (next.getPayload().equals(FAIL_PAYLOAD)) {
                        throw new IllegalStateException("Cannot retrieve event at position [" + lastToken + "].");
                    }

                    return next;
                }

                @Override
                public void close() {
                    clearAllMessages();
                }

                @Override
                public void skipMessagesWithPayloadTypeOf(TrackedEventMessage<?> ignoredEvent) {
                    ignoredEvents.add(ignoredEvent);
                }

                @Override
                public boolean setOnAvailableCallback(Runnable callback) {
                    onAvailableCallback = callback;
                    return streamCallbackSupported;
                }
            };
        }

        @Override
        public TrackingToken createTailToken() {
            return null;
        }

        @Override
        public TrackingToken createHeadToken() {
            if (messages.isEmpty()) {
                return null;
            }
            return messages.get(messages.size() - 1).trackingToken();
        }

        @Override
        public TrackingToken createTokenAt(Instant dateTime) {
            throw new UnsupportedOperationException("Not supported for InMemoryMessageSource");
        }

        @Override
        public TrackingToken createTokenSince(Duration duration) {
            throw new UnsupportedOperationException("Not supported for InMemoryMessageSource");
        }

        private synchronized void publishMessage(EventMessage<?> message) {
            int nextToken = messages.size();
            messages.add(new GenericTrackedEventMessage<>(new GlobalSequenceTrackingToken(nextToken + 1), message));
        }

        private synchronized void clearAllMessages() {
            messages = new CopyOnWriteArrayList<>();
        }

        public List<TrackedEventMessage<?>> getIgnoredEvents() {
            return ignoredEvents;
        }

        private void runOnAvailableCallback() {
            onAvailableCallback.run();
        }
    }
}
