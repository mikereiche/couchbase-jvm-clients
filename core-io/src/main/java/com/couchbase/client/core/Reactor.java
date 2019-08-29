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

package com.couchbase.client.core;

import com.couchbase.client.core.msg.CancellationReason;
import com.couchbase.client.core.msg.Request;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/**
 * This class provides utility methods when working with reactor.
 *
 * @since 2.0.0
 */
public class Reactor {
  private Reactor() {
    throw new AssertionError("not instantiable");
  }

  /**
   * Wraps a {@link Request} and returns it in a {@link Mono}.
   *
   * @param request the request to wrap.
   * @param response the full response to wrap, might not be the same as in the request.
   * @param propagateCancellation if a cancelled/unsubscribed mono should also cancel the
   *                              request.
   * @return the mono that wraps the request.
   */
  public static <T> Mono<T> wrap(final Request<?> request, final CompletableFuture<T> response,
                                 final boolean propagateCancellation) {
    Mono<T> mono = Mono.fromFuture(response);
    if (propagateCancellation) {
      mono = mono.doFinally(st -> {
        if (st == SignalType.CANCEL) {
          request.cancel(CancellationReason.STOPPED_LISTENING);
        }
      });
    }
    return mono.onErrorResume(err -> {
      if (err instanceof CompletionException) {
        return Mono.error(err.getCause());
      }
      else {
        return Mono.error(err);
      }
    });
  }

  /**
   * Helper method to wrap an async call into a reactive one and translate
   * exceptions appropriately.
   *
   * @param input a supplier that will be called on every subscription.
   * @return a mono that invokes the given supplier on each subscription.
   */
  public static <T> Mono<T> toMono(Supplier<CompletableFuture<T>> input) {
    return Mono.fromFuture(input)
        .onErrorMap(t -> t instanceof CompletionException ? t.getCause() : t);
  }
}
