/*
 * Copyright (c) 2016 Couchbase, Inc.
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

package com.couchbase.client.java.search.queries;

import com.couchbase.client.core.api.search.CoreSearchQuery;
import com.couchbase.client.core.api.search.queries.CoreBooleanQuery;
import com.couchbase.client.java.search.SearchQuery;
import reactor.util.annotation.Nullable;

/**
 * A compound FTS query that allows various combinations of sub-queries.
 *
 * @since 2.3.0
 */
public class BooleanQuery extends SearchQuery {

    @Nullable private ConjunctionQuery must;
    @Nullable private DisjunctionQuery mustNot;
    @Nullable private DisjunctionQuery should;

/**
 * Affects the behavior of the BooleanQuery depending on the operators.
 * The three BooleanQuery operators are `must_not`, `should` and `must`.
 * With a `should` operator, the behaviour is different for different cases.
 * The presence or absence of `must_not` does not affect the `should` operator's behavior.
 * <ul>
 * <li>1: With `should` operator and without `must` operator
 *        in this case min=0 is same as min=1, which is at least one of the clause
 *        should match for document to be added as part of resultSet.
 * <li>2: With both `should` and `must` operators
 *        m=0 means, clauses in `should` will only be used for scoring and not as a document selection
 *        criteria m!=0 means, atleast m clauses must match for document to be eligible to be in resultSet.
 * </li>
 * </ul>
 */
    public BooleanQuery shouldMin(int minForShould) {
        if (this.should == null) {
          this.should = new DisjunctionQuery();
        }
        this.should.min(minForShould);
        return this;
    }

    public BooleanQuery must(SearchQuery... mustQueries) {
        if (this.must == null) {
          this.must = new ConjunctionQuery();
        }
        must.and(mustQueries);
        return this;
    }

    public BooleanQuery mustNot(SearchQuery... mustNotQueries) {
        if (this.mustNot == null) {
          this.mustNot = new DisjunctionQuery();
        }
        mustNot.or(mustNotQueries);
        return this;
    }

    public BooleanQuery should(SearchQuery... shouldQueries) {
        if (this.should == null) {
          this.should = new DisjunctionQuery();
        }
        should.or(shouldQueries);
        return this;
    }

    @Override
    public BooleanQuery boost(double boost) {
        super.boost(boost);
        return this;
    }

    @Override
    public CoreSearchQuery toCore() {
        return new CoreBooleanQuery(
            must == null ? null : must.toCore(),
            mustNot == null ? null : mustNot.toCore(),
            should == null ? null : should.toCore(),
            boost
        );
    }
}
