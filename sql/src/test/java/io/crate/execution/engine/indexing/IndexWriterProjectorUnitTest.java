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

package io.crate.execution.engine.indexing;

import io.crate.expression.symbol.InputColumn;
import io.crate.expression.symbol.Symbol;
import io.crate.data.BatchIterator;
import io.crate.data.InMemoryBatchIterator;
import io.crate.data.Row;
import io.crate.data.RowN;
import io.crate.metadata.ColumnIdent;
import io.crate.metadata.Reference;
import io.crate.metadata.ReferenceIdent;
import io.crate.metadata.RowGranularity;
import io.crate.metadata.Schemas;
import io.crate.metadata.TableIdent;
import io.crate.execution.jobs.NodeJobsCounter;
import io.crate.execution.engine.collect.CollectExpression;
import io.crate.execution.engine.collect.InputCollectExpression;
import io.crate.test.integration.CrateDummyClusterServiceUnitTest;
import io.crate.testing.TestingHelpers;
import io.crate.testing.TestingRowConsumer;
import io.crate.types.DataTypes;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.action.admin.indices.create.TransportCreatePartitionsAction;
import org.elasticsearch.common.settings.Settings;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static io.crate.data.SentinelRow.SENTINEL;
import static org.mockito.Mockito.mock;

public class IndexWriterProjectorUnitTest extends CrateDummyClusterServiceUnitTest {

    private final static ColumnIdent ID_IDENT = new ColumnIdent("id");
    private static final TableIdent bulkImportIdent = new TableIdent(Schemas.DOC_SCHEMA_NAME, "bulk_import");
    private static Reference rawSourceReference = new Reference(
        new ReferenceIdent(bulkImportIdent, "_raw"), RowGranularity.DOC, DataTypes.STRING);

    private ExecutorService executor;
    private ScheduledExecutorService scheduler;

    @Before
    public void setUpExecutors() throws Exception {
        scheduler = Executors.newScheduledThreadPool(1);
        executor = Executors.newFixedThreadPool(1);
    }

    @After
    public void tearDownExecutors() throws Exception {
        scheduler.shutdown();
        executor.shutdown();
        scheduler.awaitTermination(10, TimeUnit.SECONDS);
        executor.awaitTermination(10, TimeUnit.SECONDS);
    }

    @Test
    public void testNullPKValue() throws Throwable {
        InputCollectExpression sourceInput = new InputCollectExpression(0);
        List<CollectExpression<Row, ?>> collectExpressions = Collections.<CollectExpression<Row, ?>>singletonList(sourceInput);

        TransportCreatePartitionsAction transportCreatePartitionsAction = mock(TransportCreatePartitionsAction.class);
        IndexWriterProjector indexWriter = new IndexWriterProjector(
            clusterService,
            new NodeJobsCounter(),
            scheduler,
            executor,
            TestingHelpers.getFunctions(),
            Settings.EMPTY,
            Settings.EMPTY,
            transportCreatePartitionsAction,
            (request, listener) -> {},
            IndexNameResolver.forTable(new TableIdent(Schemas.DOC_SCHEMA_NAME, "bulk_import")),
            rawSourceReference,
            Arrays.asList(ID_IDENT),
            Arrays.<Symbol>asList(new InputColumn(1)),
            null,
            null,
            sourceInput,
            collectExpressions,
            20,
            null,
            null,
            false,
            false,
            UUID.randomUUID());

        RowN rowN = new RowN(new Object[]{new BytesRef("{\"y\": \"x\"}"), null});
        BatchIterator<Row> batchIterator = InMemoryBatchIterator.of(Collections.singletonList(rowN), SENTINEL);
        batchIterator = indexWriter.apply(batchIterator);

        TestingRowConsumer testingBatchConsumer = new TestingRowConsumer();
        testingBatchConsumer.accept(batchIterator, null);

        expectedException.expect(IllegalArgumentException.class);
        expectedException.expectMessage("A primary key value must not be NULL");
        testingBatchConsumer.getResult();
    }
}
