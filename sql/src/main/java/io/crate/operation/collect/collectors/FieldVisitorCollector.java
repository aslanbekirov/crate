/*
 * Licensed to Crate under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.  Crate licenses this file
 * to you under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial
 * agreement.
 */

package io.crate.operation.collect.collectors;

import org.apache.lucene.index.AtomicReader;
import org.apache.lucene.index.AtomicReaderContext;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.Scorer;

import java.io.IOException;

class FieldVisitorCollector extends Collector {
    private final Collector collector;
    private final CollectorFieldsVisitor fieldsVisitor;
    private AtomicReader currentReader;

    public FieldVisitorCollector(Collector collector, CollectorFieldsVisitor fieldsVisitor) {
        this.collector = collector;
        this.fieldsVisitor = fieldsVisitor;
    }

    @Override
    public void setScorer(Scorer scorer) throws IOException {
        collector.setScorer(scorer);
    }

    @Override
    public void collect(int doc) throws IOException {
        fieldsVisitor.reset();
        currentReader.document(doc, fieldsVisitor);
        collector.collect(doc);
    }

    @Override
    public void setNextReader(AtomicReaderContext context) throws IOException {
        currentReader = context.reader();
        collector.setNextReader(context);
    }

    @Override
    public boolean acceptsDocsOutOfOrder() {
        return collector.acceptsDocsOutOfOrder();
    }
}
