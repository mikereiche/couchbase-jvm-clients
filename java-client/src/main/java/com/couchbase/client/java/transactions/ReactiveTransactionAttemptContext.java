/*
 * Copyright 2022 Couchbase, Inc.
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

package com.couchbase.client.java.transactions;

import com.couchbase.client.core.annotation.Stability;
import com.couchbase.client.core.deps.com.fasterxml.jackson.databind.node.ObjectNode;
import com.couchbase.client.core.error.EncodingFailureException;
import com.couchbase.client.core.json.Mapper;
import com.couchbase.client.core.msg.query.QueryRequest;
import com.couchbase.client.core.transaction.CoreTransactionAttemptContext;
import com.couchbase.client.core.transaction.log.CoreTransactionLogger;
import com.couchbase.client.java.ReactiveCollection;
import com.couchbase.client.java.ReactiveScope;
import com.couchbase.client.java.codec.JsonSerializer;
import com.couchbase.client.java.json.JsonObject;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.Objects;

import static com.couchbase.client.java.transactions.internal.ConverterUtil.makeCollectionIdentifier;

/**
 * Provides methods to allow an application's transaction logic to read, mutate, insert and delete documents, as well
 * as commit or rollback the transaction.
 * <p>
 * Thread-safety: This class is thread-safe for specific workloads, namely doing batch mutations in a reactive way.
 */
public class ReactiveTransactionAttemptContext {
    private final CoreTransactionAttemptContext internal;
    private final JsonSerializer serializer;

    ReactiveTransactionAttemptContext(CoreTransactionAttemptContext internal, JsonSerializer serializer) {
        this.internal = Objects.requireNonNull(internal);
        this.serializer = Objects.requireNonNull(serializer);
    }

    /**
     * Gets a document with the specified <code>id</code> and from the specified Couchbase <code>bucket</code>.
     * <p>
     * If the document does not exist it will throw a DocumentNotFoundException.
     *
     * @param collection the Couchbase collection the document exists on
     * @param id         the document's ID
     * @return a <code>TransactionGetResult</code> containing the document
     */
    public Mono<TransactionGetResult> get(ReactiveCollection collection, String id) {
        return internal.get(makeCollectionIdentifier(collection.async()), id)
                .map(result -> new TransactionGetResult(result, serializer()));
    }

    /**
     * Inserts a new document into the specified Couchbase <code>collection</code>.
     *
     * @param collection the Couchbase collection in which to insert the doc
     * @param id         the document's unique ID
     * @param content    the content to insert
     * @return the doc, updated with its new CAS value and ID, and converted to a <code>TransactionGetResult</code>
     */
    public Mono<TransactionGetResult> insert(ReactiveCollection collection, String id, Object content) {
        byte[] encoded = serializer().serialize(content);
        return internal.insert(makeCollectionIdentifier(collection.async()), id, encoded)
                .map(result -> new TransactionGetResult(result, serializer()));
    }
    
    private JsonSerializer serializer() {
        return serializer;
    }

    /**
     * Mutates the specified <code>doc</code> with new content.
     *
     * @param doc     the doc to be mutated
     * @param content the content to replace the doc with
     * @return the doc, updated with its new CAS value.  For performance a copy is not created and the original doc
     * object is modified.
     */
    public Mono<TransactionGetResult> replace(TransactionGetResult doc, Object content) {
        byte[] encoded = serializer().serialize(content);
        return internal.replace(doc.internal(), encoded)
                .map(result -> new TransactionGetResult(result, serializer()));
    }

    /**
     * Removes the specified <code>doc</code>.
     * <p>
     * @param doc - the doc to be removed
     */
    public Mono<Void> remove(TransactionGetResult doc) {
        return internal.remove(doc.internal());
    }

    @SuppressWarnings("unused")
    @Stability.Internal
    CoreTransactionLogger logger() {
        return internal.logger();
    }

    /**
     * Calls query() with default options.
     */
    public Mono<TransactionQueryResult> query(final String statement) {
        return query(statement, null);
    }


    /**
     * Runs a N1QL query and returns the result.
     * <p>
     * All rows are buffered in-memory.
     * <p>
     * Raises CouchbaseException or an error derived from it on failure.  The application can choose to catch and ignore this error, and the
     * transaction attempt is allowed to continue.  This differs from Key-Value operations, whose failure will
     * cause the attempt to fail.
     */
    public Mono<TransactionQueryResult> query(final String statement,
                                              final TransactionQueryOptions options) {
        return query(null, statement, options);
    }

    /**
     * Runs a N1QL query and returns the result, with default parameters.
     * <p>
     * All rows are buffered in-memory.
     * <p>
     * This overload performs a 'scope-level query': that is, one in which a collection may be referenced by name in the
     * query statement, without needing to specify the full bucket.scope.collection syntax.
     * <p>
     * Raises CouchbaseException or an error derived from it on failure.  The application can choose to catch and ignore this error, and the
     * transaction attempt is allowed to continue.  This differs from Key-Value operations, whose failure will
     * cause the attempt to fail.
     */
    public Mono<TransactionQueryResult> query(final ReactiveScope scope,
                                              final String statement) {
        return query(scope, statement, null);
    }

    /**
     * Runs a N1QL query and returns the result.
     * <p>
     * All rows are buffered in-memory.
     * <p>
     * This overload performs a 'scope-level query': that is, one in which a collection may be referenced by name in the
     * query statement, without needing to specify the full bucket.scope.collection syntax.
     * <p>
     * Raises CouchbaseException or an error derived from it on failure.  The application can choose to catch and ignore this error, and the
     * transaction attempt is allowed to continue.  This differs from Key-Value operations, whose failure will
     * cause the attempt to fail.
     */
    public Mono<TransactionQueryResult> query(final ReactiveScope scope,
                                              final String statement,
                                              final TransactionQueryOptions options) {
        JsonObject json = JsonObject.create()
                .put("statement", statement);
        if (scope != null) {
            json.put("query_context", QueryRequest.queryContext(scope.bucketName(), scope.name()));
        }
        if (options != null) {
            options.builder().build().injectParams(json);
        }
        try {
            ObjectNode opts = Mapper.reader().readValue(json.toBytes(), ObjectNode.class);
            return internal.queryBlocking(statement,
                            scope == null ? null : scope.bucketName(),
                            scope == null ? null : scope.name(),
                            opts,
                            false)
                    .map(response -> new TransactionQueryResult(response.header, response.rows, response.trailer, serializer()));
        } catch (IOException e) {
            throw new EncodingFailureException(e);
        }
    }
}