/*
 * Copyright (c) 2020 Couchbase, Inc.
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
package com.couchbase.twoway;

import com.couchbase.InternalPerformerFailure;
import com.couchbase.client.core.cnc.EventSubscription;
import com.couchbase.client.core.deps.com.fasterxml.jackson.databind.JsonNode;
import com.couchbase.client.core.error.DecodingFailureException;
import com.couchbase.client.core.error.DocumentExistsException;
import com.couchbase.client.core.error.DocumentNotFoundException;
import com.couchbase.client.core.json.Mapper;
import com.couchbase.client.core.cnc.events.transaction.IllegalDocumentStateEvent;
import com.couchbase.client.core.cnc.events.transaction.TransactionLogEvent;
import com.couchbase.client.core.cnc.events.transaction.TransactionEvent;
import com.couchbase.client.core.transaction.log.CoreTransactionLogger;
import com.couchbase.client.core.transaction.util.MonoBridge;
import com.couchbase.client.java.codec.JsonValueSerializerWrapper;
import com.couchbase.client.java.json.JsonObject;
import com.couchbase.client.java.transactions.TransactionGetResult;
import com.couchbase.client.java.transactions.error.TransactionFailedException;
import com.couchbase.grpc.protocol.BroadcastToOtherConcurrentTransactionsRequest;
import com.couchbase.grpc.protocol.CommandBatch;
import com.couchbase.grpc.protocol.CommandGet;
import com.couchbase.grpc.protocol.CommandGetOptional;
import com.couchbase.grpc.protocol.CommandSetLatch;
import com.couchbase.grpc.protocol.CommandWaitOnLatch;
import com.couchbase.grpc.protocol.DocStatus;
import com.couchbase.grpc.protocol.ErrorWrapper;
import com.couchbase.grpc.protocol.Event;
import com.couchbase.grpc.protocol.EventType;
import com.couchbase.grpc.protocol.ExpectedCause;
import com.couchbase.grpc.protocol.ExpectedResult;
import com.couchbase.grpc.protocol.ExternalException;
import com.couchbase.grpc.protocol.TransactionCommand;
import com.couchbase.grpc.protocol.TransactionCreateRequest;
import com.couchbase.grpc.protocol.TransactionStreamPerformerToDriver;
import com.couchbase.utils.ClusterConnection;
import com.couchbase.utils.ResultsUtil;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.couchbase.utils.ResultValidation.anythingAllowed;
import static com.couchbase.utils.ResultValidation.dbg;

/**
 * Runs a transaction.
 *
 * TwoWayTransaction are similar to (now removed) BlockTransactions,
 * with these improvements:
 *
 * 1. They are not blocking, they happen in a thread (this is handled
 *    by TwoWayTransactionMarshaller, they can be run blocking on the
 *    app's thread if desired too).
 * 2. They can both send and receive messages to/from the driver.
 * 3. They support 'latches', e.g. on the Java client this will be a
 *    CountdownLatch.  They allow multiple TwoWayTransaction to gate
 *    each other's progress and hence allow very granular and precise
 *    checking of concurrent transactions, even across performers and
 *    languages.
 */
public abstract class TwoWayTransactionShared {
    protected Logger logger;
    protected final ConcurrentHashMap<String, CountDownLatch> latches = new ConcurrentHashMap<>();
    protected String name;
    protected String bp;
    // The result of the last get call will automatically be stashed here for use with subsequent mutation commands.
    // We may need something more complex later with multiple stashed variables, but this will likely be enough for 90%
    // of cases.
    protected final AtomicReference<TransactionGetResult> stashedGet = new AtomicReference<>();
    protected final static ExpectedResult EXPECT_SUCCESS = ExpectedResult.newBuilder().setSuccess(true).build();

    //Class names for the QueryOptions and TransactionQueryOptions
    public final static String CLASSNAME_QUERY_OPTIONS ="com.couchbase.client.java.query.QueryOptions";
    public final static String CLASSNAME_TRANSACTION_QUERY_OPTIONS ="com.couchbase.transactions.TransactionQueryOptions";
    public final static String CLASSNAME_SINGLE_QUERY_TRANSACTION_CONFIG_BUILDER = "com.couchbase.transactions.config.SingleQueryTransactionConfigBuilder";
    public final static String CLASSNAME_SINGLE_QUERY_TRANSACTION_CONFIG = "com.couchbase.transactions.config.SingleQueryTransactionConfig";
    public final static String CLASSNAME_SINGLE_QUERY_TRANSACTION_RESULT = "com.couchbase.transactions.SingleQueryTransactionResult";
    public final static String CLASSNAME_SCOPE = "com.couchbase.client.java.Scope";



    /**
     * There are a handful of cases where a InternalDriverFailure won't be propagated through into the final
     * TransactionFailed, e.g. if it happens while the transaction is expired.  So stash it here.
     */
    protected final AtomicReference<RuntimeException> testFailure = new AtomicReference<>();

    protected void dump(CoreTransactionLogger ctxLogger) {
        logger.warn("Dumping logs so far for debugging:");
        ctxLogger.logs().forEach(l ->
            logger.info("    " + l.toString()));
    }

    abstract protected com.couchbase.client.java.transactions.TransactionResult runInternal(
            ClusterConnection connection,
            TransactionCreateRequest req,
            @Nullable StreamObserver<TransactionStreamPerformerToDriver> toTest
    );

    public com.couchbase.grpc.protocol.TransactionResult run(
            ClusterConnection connection,
            TransactionCreateRequest req,
            @Nullable StreamObserver<TransactionStreamPerformerToDriver> toTest
    ) {
        this.name = req.getName();
        this.bp = this.name + ": ";
        logger = LoggerFactory.getLogger(this.name);

        final ConcurrentLinkedQueue<TransactionEvent> transactionEvents = new ConcurrentLinkedQueue<>();
        EventSubscription eventBusSubscription = null;

        if (req.getExpectedEventsCount() != 0) {
            eventBusSubscription = connection.cluster().environment().eventBus().subscribe(event -> {
                if (event instanceof TransactionEvent) {
                    transactionEvents.add((TransactionEvent) event);
                }
            });
        }

        try {
            com.couchbase.client.java.transactions.TransactionResult result = runInternal(connection, req, toTest);

            Optional<Exception> e = Optional.empty();
            if (testFailure.get() != null) {
                logger.warn("Test actually failed with {}", testFailure.get().getMessage());
                e = Optional.of(testFailure.get());

                // Going back-and-forwards on whether to throw this.  The test definitely fails regardless of whether
                // it validates the result object, which we want.  But, it doesn't allow sending the full log back to
                // the driver for easy debugging.
                throw testFailure.get();
            }

            assertExpectedEvents(req, transactionEvents);

            Optional<Integer> cleanupQueueLength = cleanupQueueLength(connection);

            return ResultsUtil.createResult(e,
                    result,
                    cleanupQueueLength);
        } catch (TransactionFailedException err) {
            Optional<Exception> e = Optional.of(err);
            if (testFailure.get() != null) {
                logger.warn("Test really failed with {}", testFailure.get().toString());
                e = Optional.of(testFailure.get());

                throw testFailure.get();
            }

            logger.info("Error while executing an operation: {}", err.toString());
            assertExpectedEvents(req, transactionEvents);

            Optional<Integer> cleanupQueueLength = cleanupQueueLength(connection);

            return ResultsUtil.createResult(e,
                    err.logs(),
                    err.transactionId(),
                    false,
                    cleanupQueueLength);
        }
        finally {
            if (eventBusSubscription != null) {
                eventBusSubscription.unsubscribe();
            }
        }
    }

    private Optional<Integer>
    cleanupQueueLength(ClusterConnection connection) {
        return connection.cluster().core().transactionsCleanup().cleanupQueueLength();
    }

    /**
     * Starts a two-way transaction.
     */
    public void create(TransactionCreateRequest req) {
        this.name = req.getName();
        this.bp = this.name + ": ";
        logger = LoggerFactory.getLogger(this.name);

        req.getLatchesList().forEach(latch -> {
            logger.info(bp, "Adding new latch '{}' count={}", latch.getName(), latch.getInitialCount());

            CountDownLatch l = new CountDownLatch(latch.getInitialCount());
            latches.put(latch.getName(), l);
        });
    }

    /**
     * transactionEvents is being populated asynchronously, need to wait for the expected events to arrive.
     */
    public void assertExpectedEvents(TransactionCreateRequest req, ConcurrentLinkedQueue<TransactionEvent> transactionEvents) {
        logger.info("Waiting for all expected events");

        for (Event e : req.getExpectedEventsList()) {
            waitForEvent(transactionEvents, e);
        }
    }

    private static void waitForEvent(ConcurrentLinkedQueue<TransactionEvent> transactionEvents, Event e) {
        boolean found = false;
        long start = System.nanoTime();

        while (!found) {
            for (TransactionEvent t : transactionEvents) {
                if (e.getType() == EventType.EventIllegalDocumentState) {
                    if (t instanceof IllegalDocumentStateEvent) {
                        found = true;
                    }
                } else {
                    throw new InternalPerformerFailure(
                            new IllegalArgumentException("Don't know how to handle event " + e.getType()));
                }
            }

            try {
                Thread.sleep(20);
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            }

            long now = System.nanoTime();
            if (TimeUnit.NANOSECONDS.toSeconds(now - start) > 5) {
                throw new TestFailure("Expected event " + e.getType() + " to be raised, but it wasn't");

            }
        }
    }

    public void handleRequest(CommandSetLatch request) {
        final String latchName = request.getLatchName();
        CountDownLatch latch = latches.get(latchName);
        logger.info("Counting down latch '{}', currently is {}", latchName, latch.getCount());
        latch.countDown();
    }

    protected void handleGetOptionalResult(CommandGet request,
                                           CommandGetOptional req,
                                           Optional<TransactionGetResult> out,
                                           ClusterConnection cc) {
        if (!out.isPresent()) {
            if (req.getExpectDocPresent()) {
                logger.info("Doc is missing but is supposed to exist");
                throw new TestFailure(new DocumentNotFoundException(null));
            } else {
                logger.info("Document missing, as expected. Allowing txn to continue.");
            }
        } else {
            if (!req.getExpectDocPresent()) {
                logger.info("Document exists but isn't supported too");
                throw new TestFailure(new DocumentExistsException(null));
            } else {
                handleGetResult(request, out.get(), cc);
            }
        }

    }

    protected void handleGetResult(CommandGet request, TransactionGetResult getResult, ClusterConnection cc) {
        stashedGet.set(getResult);

        if (!request.getExpectedContentJson().isEmpty()) {
            JsonObject expected = JsonObject.fromJson(request.getExpectedContentJson());
            // Due to JsonValueSerializerWrapper, can't use contentAsObject() as that uses Jackson directly rather
            // than the wrapped CustomSerializer.  CustomSerializer always returns a JsonObject, so we request a String
            // and cast to that.  Unfortunately we can't always do this, as with the default Json serializer contentAsObject()
            // throws if we ask for String.class.
            JsonObject actual;
            if (cc.cluster().environment().jsonSerializer() instanceof JsonValueSerializerWrapper) {
                actual = (JsonObject) (Object) getResult.contentAs(String.class);
            }
            else {
                actual = getResult.contentAsObject();
            }

            if (!expected.equals(actual)) {
                logger.warn("Expected content {}, got content {}", expected, actual);
                throw new TestFailure(new IllegalStateException("Did not get expected content"));
            }
        }

        if (request.getExpectedStatus() != DocStatus.DO_NOT_CHECK) {
            logger.warn("Ignoring request to check doc status");
        }
    }

    protected void handleWaitOnLatch(CommandWaitOnLatch request, CoreTransactionLogger txnLogger) {
        String latchName = request.getLatchName();
        CountDownLatch latch = latches.get(request.getLatchName());

        logger.info("Blocking on latch '{}', current count is {}", latchName, latch.getCount());
        txnLogger.info("", "Blocking on latch '%s', current count is %d", latchName, latch.getCount());

        try {
            latch.await();
        } catch (InterruptedException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }

        logger.info("Finished blocking on latch '{}'", latchName);
        txnLogger.info("", "Finished blocking on latch '%s'", latchName);
    }

    protected void handleSetLatch(CommandSetLatch request, StreamObserver<TransactionStreamPerformerToDriver> toTest, CoreTransactionLogger txnLogger) {
        String latchName = request.getLatchName();
        CountDownLatch latch = latches.get(request.getLatchName());

        logger.info("Setting own copy of latch '{}', current count is {}", latchName, latch.getCount());
        txnLogger.info("", "Setting own copy of latch '%s', current count is %d", latchName, latch.getCount());

        latch.countDown();

        logger.info("Telling other workers, via the driver, to set latch '{}'", latchName);
        txnLogger.info("", "Telling other performers, via the driver, to set latch '%s'", latchName);

        toTest.onNext(TransactionStreamPerformerToDriver.newBuilder()
                .setBroadcast(BroadcastToOtherConcurrentTransactionsRequest.newBuilder()
                        .setLatchSet(request))
                .build());
    }

    protected boolean hasResult(List<ExpectedResult> expectedResults, ExpectedResult er) {
        return expectedResults.contains(er);
    }

    // GRPC has a limit
    public static String logs(CoreTransactionLogger logger) {
        var logs = logger.logs().stream().map(TransactionLogEvent::toString).collect(Collectors.joining("\n"));
        return logs.length() < 6000 ? logs : "too-big";
    }

    protected void handleIfResultSucceededWhenShouldNot(String opDebug,
                                                        Supplier<CoreTransactionLogger> logger,
                                                        List<ExpectedResult> expectedResults) {
        if (!hasResult(expectedResults, EXPECT_SUCCESS) && !anythingAllowed(expectedResults)) {
            var l = logger.get();
            String fmt = String.format("Operation '%s' succeeded but was expecting %s.  Logs: %s",
                    opDebug, dbg(expectedResults), logs(l));
            this.logger.warn(fmt);
            dump(l);
            RuntimeException e = new TestFailure(fmt);
            // Make absolutely certain the test fails
            testFailure.set(e);
            throw e;
        }
    }



    protected void handleOperationError(String opDebug,
                                  Runnable dump,
                                  List<ExpectedResult> expectedResults,
                                  boolean doNotPropagateError,
                                  RuntimeException err) {
        if (err instanceof TestFailure
            || err instanceof InternalPerformerFailure) {
            // Make absolutely certain the test fails
            testFailure.set(err);
        }
        else if (anythingAllowed(expectedResults)) {
            logger.info("Operation {} failed with {}, fine as test told us to EXPECT_ANYTHING_ALLOWED",
                    opDebug, err.getMessage());
        }
        else if (err instanceof com.couchbase.client.core.error.transaction.TransactionOperationFailedException) {
            com.couchbase.client.core.error.transaction.TransactionOperationFailedException e =
                    (com.couchbase.client.core.error.transaction.TransactionOperationFailedException) err;

            ErrorWrapper.Builder tofRaisedFromSDK = ErrorWrapper.newBuilder()
                    .setAutoRollbackAttempt(e.autoRollbackAttempt())
                    .setRetryTransaction(e.retryTransaction())
                    .setToRaise(ResultsUtil.mapToRaise(e.toRaise()));
            ExternalException causeFromTOF = ResultsUtil.mapCause(e.getCause());
            boolean ok = false;

            for (ExpectedResult er : expectedResults) {
                if (er.hasError()) {
                    ErrorWrapper anExpectedError = er.getError();
                    // Note we no longer compare error classes
                    if (anExpectedError.getAutoRollbackAttempt() == tofRaisedFromSDK.getAutoRollbackAttempt()
                        && anExpectedError.getRetryTransaction() == tofRaisedFromSDK.getRetryTransaction()
                        && anExpectedError.getToRaise() == tofRaisedFromSDK.getToRaise()) {
                        ExpectedCause c = anExpectedError.getCause();

                        if (c.getDoNotCheck()
                                || (c.hasException() && c.getException().equals(causeFromTOF))) {
                            ok = true;
                        }
                    }
                }
            }

            if (!ok) {
                String fmt = String.format("Operation '%s' failed unexpectedly, was expecting %s but got %s: %s",
                        opDebug, dbg(expectedResults), tofRaisedFromSDK, err.getMessage());
                logger.warn(fmt);
                dump.run();
                RuntimeException error = new TestFailure(fmt, err);
                // Make absolutely certain the test fails
                testFailure.set(error);
                throw error;
            } else {
                logger.info("Operation '{}' failed with {} as expected: {}",
                        opDebug, tofRaisedFromSDK.build(), err.getMessage());
            }
        }
        else {
            boolean ok = false;

            for (ExpectedResult er : expectedResults) {
                ExternalException ee = ResultsUtil.mapCause(err);
                ExternalException expected = er.getException();
                if (expected != ExternalException.Unknown && ee.equals(expected)) {
                    logger.info("Operation '{}' failed with {} as expected", opDebug, ee);
                    ok = true;
                    break;
                }
            }

            if (!ok) {
                // A stage can only raise an TransactionOperationFailedException
                // Update: As of ExtQuery and ExtSDKIntegration, that is no longer the case.
                String msg = String.format("Command %s raised error '%s', but require TransactionOperationFailedException",
                        opDebug, err);
                logger.warn(msg);
                dump.run();
                RuntimeException error = new InternalPerformerFailure(
                        new IllegalStateException(msg));
                // Make absolutely certain the test fails
                testFailure.set(error);
                throw error;
            }
        }

        if (doNotPropagateError) {
            String msg = "Not propagating the error, as requested by test";
            logger.info(msg);
        }
        else {
            throw err;
        }
    }

    // To support CustomSerializer tests. We can't use JsonObject as CustomSerializer gets wrapped by a
    // JsonValueSerializerWrapper, so JsonObject bypasses CustomSerializer.
    protected static JsonNode readJson(String in) {
        try {
            return Mapper.reader().readValue(in, JsonNode.class);
        } catch (IOException e) {
            throw new InternalPerformerFailure(new DecodingFailureException(e));
        }
    }

    protected Mono<Void> performCommandBatchBlocking(CommandBatch request, Function<TransactionCommand, Mono<Void>> call) {
        CountDownLatch latch = new CountDownLatch(request.getCommandsCount());

        return Flux.fromIterable(request.getCommandsList())
                .doOnSubscribe(s -> logger.info("Running {} operations in parallel, concurrency={}",
                        request.getCommandsCount(), request.getParallelism()))
                .parallel(request.getParallelism())
                .runOn(Schedulers.elastic())
                .concatMap(parallelOp -> new MonoBridge<>(call.apply(parallelOp), "not-used", this, null).external()
                        .doOnTerminate(() -> latch.countDown())
                .doOnNext(v -> logger.info("A parallel op {} has finished", parallelOp.getCommandCase()))
                .doOnCancel(() -> logger.info("A parallel op {} has been cancelled", parallelOp.getCommandCase()))
                .doOnError(err -> logger.info("A parallel op {} has errored with {}", parallelOp.getCommandCase(), err)))
                .sequential()
                .then(Mono.fromRunnable(() -> {
                    logger.info("Reached end of operations with nothing throwing, waiting for all {} ops (which should be 0)", latch.getCount());
                    try {
                        latch.await();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    logger.info("All ops finished");
                }))
                .onErrorResume(e -> {
                    logger.info("An op threw {}, waiting for {} remaining ops", e.getClass().getSimpleName(), latch.getCount());
                    try {
                        latch.await();
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                    logger.info("All ops finished, rethrowing {}", e.toString());
                    return Mono.error(e);
                })
                .then()
                .doOnNext(v -> logger.info("All parallel ops have finished"))
                .doOnCancel(() -> logger.info("Parallel ops have been cancelled"))
                .doOnError(v -> logger.info("Parallel ops have errored"));
    }

    protected Mono<Void> performCommandBatch(CommandBatch request, Function<TransactionCommand, Mono<Void>> call) {
        return Flux.fromIterable(request.getCommandsList())
                .doOnSubscribe(s -> logger.info("Running {} operations in parallel, concurrency={}",
                        request.getCommandsCount(), request.getParallelism()))
                .parallel(request.getParallelism())
                .runOn(Schedulers.elastic())
                .concatMap(parallelOp -> new MonoBridge<>(call.apply(parallelOp), "not-used", this, null).external()
                        .doOnNext(v -> logger.info("A parallel op {} has finished", parallelOp.getCommandCase()))
                        .doOnCancel(() -> logger.info("A parallel op {} has been cancelled", parallelOp.getCommandCase()))
                        .doOnError(err -> logger.info("A parallel op {} has errored with {}", parallelOp.getCommandCase(), err)))
                .sequential()
                .then(Mono.fromRunnable(() -> {
                    logger.info("Reached end of operations with nothing throwing");
                }))
                .doOnError(e -> logger.info("An op threw {}", e.toString()))
                .then()
                .doOnNext(v -> logger.info("All parallel ops have finished"))
                .doOnCancel(() -> logger.info("Parallel ops have been cancelled"))
                .doOnError(v -> logger.info("Parallel ops have errored"));
    }
}