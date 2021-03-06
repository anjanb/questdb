/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2020 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.griffin;

import io.questdb.DefaultServerConfiguration;
import io.questdb.MessageBusImpl;
import io.questdb.cairo.CairoEngine;
import io.questdb.cairo.TableWriter;
import io.questdb.cairo.security.AllowAllCairoSecurityContext;
import io.questdb.cairo.sql.RecordCursor;
import io.questdb.cairo.sql.RecordCursorFactory;
import io.questdb.griffin.engine.functions.bind.BindVariableService;
import io.questdb.std.Files;
import io.questdb.std.Misc;
import io.questdb.std.Os;
import io.questdb.std.Unsafe;
import io.questdb.std.str.StringSink;
import org.junit.Assert;
import org.junit.Test;

public class MemoryLeakTest extends AbstractGriffinTest {

    private final static DefaultServerConfiguration serverConfiguration = new DefaultServerConfiguration(AbstractGriffinTest.configuration.getRoot());

    @Test
    public void testQuestDbForLeaks() throws Exception {
        testForLeaks(() -> {
            int N = 1_000_000;
            populateUsersTable(engine, N);
            try (SqlCompiler compiler = new SqlCompiler(engine)) {
                BindVariableService bindVariableService = new BindVariableService();
                bindVariableService.setLong("low", 0L);
                bindVariableService.setLong("high", 0L);
                final SqlExecutionContextImpl executionContext = new SqlExecutionContextImpl(
                        new MessageBusImpl(serverConfiguration), 1, engine).with(AllowAllCairoSecurityContext.INSTANCE,
                        bindVariableService,
                        null);
                StringSink sink = new StringSink();
                sink.clear();
                sink.put("users");
                sink.put(" latest by id where sequence > :low and sequence < :high");
                RecordCursorFactory rcf = compiler.compile(sink, executionContext).getRecordCursorFactory();
                bindVariableService.setLong("low", 0);
                bindVariableService.setLong("high", N + 1);
                RecordCursor cursor = rcf.getCursor(executionContext);
                Misc.free(cursor);
                Misc.free(rcf);
            } finally {
                engine.releaseAllReaders();
                engine.releaseAllWriters();
            }
        });
    }

    private void populateUsersTable(CairoEngine engine, int n) throws SqlException {
        try (SqlCompiler compiler = new SqlCompiler(engine)) {
            final SqlExecutionContextImpl executionContext = new SqlExecutionContextImpl(new MessageBusImpl(serverConfiguration), 1, engine).with(AllowAllCairoSecurityContext.INSTANCE,
                    new BindVariableService(),
                    null);
            compiler.compile("create table users (sequence long, event binary, timestamp timestamp, id long) timestamp(timestamp)", executionContext);
            long buffer = Unsafe.malloc(1024);
            try {
                try (TableWriter writer = engine.getWriter(executionContext.getCairoSecurityContext(), "users")) {
                    for (int i = 0; i < n; i++) {
                        long sequence = 20 + i * 2;
                        TableWriter.Row row = writer.newRow(Os.currentTimeMicros());
                        row.putLong(0, sequence);
                        row.putBin(1, buffer, 1024);
                        row.putLong(3, i);
                        row.append();
                    }
                    writer.commit();
                }
            } finally {
                Unsafe.free(buffer, 1024);
            }
        }
    }

    private void testForLeaks(RunnableCode runnable) throws Exception {
        long fileCount = Files.getOpenFileCount();
        long memUsed = Unsafe.getMemUsed();
        try {
            runnable.run();
            Assert.assertEquals(0, engine.getBusyReaderCount());
            Assert.assertEquals(0, engine.getBusyWriterCount());
        } finally {
            engine.releaseAllReaders();
            engine.releaseAllWriters();
        }
        Assert.assertEquals(fileCount, Files.getOpenFileCount());
        Assert.assertEquals(memUsed, Unsafe.getMemUsed());
    }

    private interface RunnableCode {
        void run() throws Exception;
    }
}
