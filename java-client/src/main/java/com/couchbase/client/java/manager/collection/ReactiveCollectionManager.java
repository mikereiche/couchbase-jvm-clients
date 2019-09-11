/*
 * Copyright 2019 Couchbase, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.couchbase.client.java.manager.collection;

import com.couchbase.client.core.annotation.Stability;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static com.couchbase.client.core.Reactor.toMono;

/**
 * The {@link ReactiveCollectionManager} provides APIs to manage bucket collections and scopes.
 */
@Stability.Volatile
public class ReactiveCollectionManager {

  private final AsyncCollectionManager async;

  public ReactiveCollectionManager(AsyncCollectionManager async) {
    this.async = async;
  }

  /**
   * Creates a collection if it does not already exist.
   *
   * @param collectionSpec the collection spec that contains the properties of the collection.
   */
  public Mono<Void> createCollection(final CollectionSpec collectionSpec) {
    return toMono(() -> async.createCollection(collectionSpec));
  }

  /**
   * Checks if the given collection exists in this bucket.
   *
   * @param collectionSpec the collection spec that contains the properties of the collection.
   * @return true if it exists, false otherwise.
   */
  public Mono<Boolean> collectionExists(final CollectionSpec collectionSpec) {
    return toMono(() -> async.collectionExists(collectionSpec));
  }

  /**
   * Drops a collection if it exists.
   *
   * @param collectionSpec the collection spec that contains the properties of the collection.
   */
  public Mono<Void> dropCollection(final CollectionSpec collectionSpec) {
    return toMono(() -> async.dropCollection(collectionSpec));
  }

  /**
   * Creates a scope if it does not already exist.
   *
   * @param scopeSpec the scope spec that contains the properties of the scope.
   */
  public Mono<Void> createScope(final ScopeSpec scopeSpec) {
    return toMono(() -> async.createScope(scopeSpec));
  }

  /**
   * Drops a scope if it exists.
   *
   * @param scopeName the name of the scope to drop.
   */
  public Mono<Void> dropScope(final String scopeName) {
    return toMono(() -> async.dropScope(scopeName));
  }

  /**
   * Returns the scope if it exists.
   *
   * @param scopeName the name of the scope.
   * @return a mono containing the {@link ScopeSpec} or an exception if it does not exist.
   */
  public Mono<ScopeSpec> getScope(final String scopeName) {
    return toMono(() -> async.getScope(scopeName));
  }

  /**
   * Returns all scopes in this bucket.
   *
   * @return a flux of all scopes in this bucket.
   */
  public Flux<ScopeSpec> getAllScopes() {
    return toMono(() -> async.getAllScopes())
            .flatMapMany(scopes -> Flux.fromIterable(scopes));
  }

}