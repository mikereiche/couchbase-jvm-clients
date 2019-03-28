/*
 * Copyright (c) 2018 Couchbase, Inc.
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

package com.couchbase.client.java;

import com.couchbase.client.core.Core;
import com.couchbase.client.core.annotation.Stability;
import com.couchbase.client.core.env.Credentials;
import com.couchbase.client.core.env.OwnedSupplier;
import com.couchbase.client.core.msg.analytics.AnalyticsRequest;
import com.couchbase.client.core.retry.RetryStrategy;
import com.couchbase.client.java.analytics.AnalyticsAccessor;
import com.couchbase.client.java.analytics.AnalyticsOptions;
import com.couchbase.client.java.analytics.AsyncAnalyticsResult;
import com.couchbase.client.java.env.ClusterEnvironment;
import com.couchbase.client.java.json.JsonObject;
import com.couchbase.client.java.query.AsyncQueryResult;
import com.couchbase.client.java.query.QueryAccessor;
import com.couchbase.client.java.query.QueryOptions;
import com.couchbase.client.java.query.SimpleQuery;
import com.couchbase.client.java.query.prepared.LFUCache;
import com.couchbase.client.java.query.prepared.PreparedQuery;
import com.couchbase.client.java.query.prepared.PreparedQueryAccessor;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.couchbase.client.core.util.Validators.notNull;
import static com.couchbase.client.core.util.Validators.notNullOrEmpty;

/**
 * The {@link AsyncCluster} is the main entry point when connecting to a Couchbase cluster.
 *
 * <p>Note that most of the time you want to use the blocking {@link Cluster} or the powerful
 * reactive {@link ReactiveCluster} API instead. Use this API if you know what you are doing and
 * you want to build low-level, even faster APIs on top.</p>
 */
public class AsyncCluster {

  /**
   * Holds the supplied environment that gets used throughout the lifetime.
   */
  private final Supplier<ClusterEnvironment> environment;

  /**
   * Holds the internal core reference.
   */
  private final Core core;

  /**
   * The size of the prepared statement cache.
   *
   * TODO: Allow environment configuration
   */
  private final int PREPARED_CACHE_SIZE = 3000;

  /**
   * The prepared query cache itself.
   */
  private final LFUCache<String, PreparedQuery> preparedQueryCache =
    new LFUCache<>(PREPARED_CACHE_SIZE);

  /**
   * Connect to a Couchbase cluster with a username and a password as credentials.
   *
   * @param connectionString connection string used to locate the Couchbase cluster.
   * @param username the name of the user with appropriate permissions on the cluster.
   * @param password the password of the user with appropriate permissions on the cluster.
   * @return if properly connected, returns a {@link AsyncCluster}.
   */
  public static AsyncCluster connect(final String connectionString, final String username,
                                     final String password) {
    return new AsyncCluster(new OwnedSupplier<>(
      ClusterEnvironment.create(connectionString, username, password)
    ));
  }

  /**
   * Connect to a Couchbase cluster with custom {@link Credentials}.
   *
   * @param connectionString connection string used to locate the Couchbase cluster.
   * @param credentials custom credentials used when connecting to the cluster.
   * @return if properly connected, returns a {@link AsyncCluster}.
   */
  public static AsyncCluster connect(final String connectionString, final Credentials credentials) {
    return new AsyncCluster(new OwnedSupplier<>(
      ClusterEnvironment.create(connectionString, credentials)
    ));
  }

  /**
   * Connect to a Couchbase cluster with a custom {@link ClusterEnvironment}.
   *
   * @param environment the custom environment with its properties used to connect to the cluster.
   * @return if properly connected, returns a {@link AsyncCluster}.
   */
  public static AsyncCluster connect(final ClusterEnvironment environment) {
    return new AsyncCluster(() -> environment);
  }

  /**
   * Creates a new cluster from a {@link ClusterEnvironment}.
   *
   * @param environment the environment to use for this cluster.
   */
  AsyncCluster(final Supplier<ClusterEnvironment> environment) {
    this.environment = environment;
    this.core = Core.create(environment.get());
  }

  /**
   * Provides access to the configured {@link ClusterEnvironment} for this cluster.
   */
  public ClusterEnvironment environment() {
    return environment.get();
  }

  /**
   * Provides access to the underlying {@link Core}.
   *
   * <p>This is advanced API, use with care!</p>
   */
  @Stability.Uncommitted
  public Core core() {
    return core;
  }

  /**
   * Performs a N1QL query with default {@link QueryOptions}.
   *
   * @param statement the N1QL query statement as a raw string.
   * @return the {@link AsyncQueryResult} once the response arrives successfully.
   */
  public CompletableFuture<AsyncQueryResult> query(final String statement) {
    return query(statement, QueryOptions.DEFAULT);
  }

  /**
   * Performs a N1QL query with custom {@link QueryOptions}.
   *
   * @param statement the N1QL query statement as a raw string.
   * @param options the custom options for this query.
   * @return the {@link AsyncQueryResult} once the response arrives successfully.
   */
  public CompletableFuture<AsyncQueryResult> query(final String statement,
                                                   final QueryOptions options) {
    notNullOrEmpty(statement, "Statement");
    notNull(options, "QueryOptions");

    QueryOptions.BuiltQueryOptions builtOptions = options.build();
    if (builtOptions.isPrepared()) {
      return PreparedQueryAccessor.queryAsync(
        core,
        SimpleQuery.createPrepared(statement),
        builtOptions,
        environment(),
        preparedQueryCache
      );
    } else {
      return QueryAccessor.queryAsync(
        core,
        SimpleQuery.create(statement),
        builtOptions,
        environment()
      );
    }
  }

  /**
   * Performs an Analytics query with default {@link AnalyticsOptions}.
   *
   * @param statement the Analytics query statement as a raw string.
   * @return the {@link AsyncAnalyticsResult} once the response arrives successfully.
   */
  public CompletableFuture<AsyncAnalyticsResult> analyticsQuery(final String statement) {
    return analyticsQuery(statement, AnalyticsOptions.DEFAULT);
  }


  /**
   * Performs an Analytics query with custom {@link AnalyticsOptions}.
   *
   * @param statement the Analytics query statement as a raw string.
   * @param options the custom options for this analytics query.
   * @return the {@link AsyncAnalyticsResult} once the response arrives successfully.
   */
  public CompletableFuture<AsyncAnalyticsResult> analyticsQuery(final String statement,
                                                                final AnalyticsOptions options) {
    notNullOrEmpty(statement, "Statement");
    notNull(options, "AnalyticsOptions");

    AnalyticsOptions.BuiltQueryOptions opts = options.build();
    Duration timeout = opts.timeout().orElse(environment.get().timeoutConfig().queryTimeout());
    RetryStrategy retryStrategy = opts.retryStrategy().orElse(environment.get().retryStrategy());

    // TODO: improve with options
    JsonObject query = JsonObject.empty();
    query.put("statement", statement);

    AnalyticsRequest request = new AnalyticsRequest(timeout, core.context(), retryStrategy,
      environment.get().credentials(), query.toString().getBytes(StandardCharsets.UTF_8), opts.priority());
    return AnalyticsAccessor.analyticsQueryAsync(core, request);
  }

  /**
   * Opens a {@link AsyncBucket} with the given name.
   *
   * @param name the name of the bucket to open.
   * @return a {@link AsyncBucket} once opened.
   */
  public CompletableFuture<AsyncBucket> bucket(final String name) {
    notNullOrEmpty(name, "Name");
    return core
      .openBucket(name)
      .thenReturn(new AsyncBucket(name, core, environment.get()))
      .toFuture();
  }

  /**
   * Performs a non-reversible shutdown of this {@link AsyncCluster}.
   */
  public CompletableFuture<Void> shutdown() {
    return core.shutdown().then(Mono.defer(() -> {
      if (environment instanceof OwnedSupplier) {
        return environment
          .get()
          .shutdownReactive(environment.get().timeoutConfig().disconnectTimeout());
      } else {
        return Mono.empty();
      }
    })).toFuture();
  }

  @Stability.Internal
  LFUCache<String, PreparedQuery> preparedQueryCache() {
    return preparedQueryCache;
  }
}