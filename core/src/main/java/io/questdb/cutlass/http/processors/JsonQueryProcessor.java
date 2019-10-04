/*******************************************************************************
 *    ___                  _   ____  ____
 *   / _ \ _   _  ___  ___| |_|  _ \| __ )
 *  | | | | | | |/ _ \/ __| __| | | |  _ \
 *  | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *   \__\_\\__,_|\___||___/\__|____/|____/
 *
 * Copyright (C) 2014-2019 Appsicle
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 ******************************************************************************/

package io.questdb.cutlass.http.processors;

import io.questdb.cairo.CairoEngine;
import io.questdb.cairo.CairoError;
import io.questdb.cairo.CairoException;
import io.questdb.cairo.ColumnType;
import io.questdb.cairo.sql.Record;
import io.questdb.cairo.sql.RecordCursor;
import io.questdb.cutlass.http.HttpChunkedResponseSocket;
import io.questdb.cutlass.http.HttpConnectionContext;
import io.questdb.cutlass.http.HttpRequestHeader;
import io.questdb.cutlass.http.HttpRequestProcessor;
import io.questdb.cutlass.json.JsonException;
import io.questdb.cutlass.text.Atomicity;
import io.questdb.cutlass.text.TextLoader;
import io.questdb.griffin.*;
import io.questdb.griffin.model.CopyModel;
import io.questdb.log.Log;
import io.questdb.log.LogFactory;
import io.questdb.log.LogRecord;
import io.questdb.network.*;
import io.questdb.std.*;
import io.questdb.std.str.CharSink;
import io.questdb.std.str.Path;
import io.questdb.std.time.DateFormatFactory;
import io.questdb.std.time.DateLocaleFactory;

import java.io.Closeable;
import java.util.concurrent.atomic.AtomicLong;

public class JsonQueryProcessor implements HttpRequestProcessor, Closeable {
    private static final LocalValue<JsonQueryProcessorState> LV = new LocalValue<>();
    private static final Log LOG = LogFactory.getLog(JsonQueryProcessor.class);
    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong cacheMisses = new AtomicLong();
    private final SqlCompiler compiler;
    private final JsonQueryProcessorConfiguration configuration;
    private final int floatScale;
    private final int doubleScale;
    private final SqlExecutionContextImpl sqlExecutionContext = new SqlExecutionContextImpl();
    private final ObjList<ValueWriter> valueWriters = new ObjList<>();
    private final ObjList<StateResumeAction> resumeActions = new ObjList<>();
    private final TextLoader textLoader;
    private final Path path = new Path();

    public JsonQueryProcessor(JsonQueryProcessorConfiguration configuration, CairoEngine engine) {
        // todo: add scheduler
        this.configuration = configuration;
        this.compiler = new SqlCompiler(engine);
        this.floatScale = configuration.getFloatScale();
        this.doubleScale = configuration.getDoubleScale();

        try {
            this.textLoader = new TextLoader(
                    configuration.getTextConfiguration(),
                    engine,
                    DateLocaleFactory.INSTANCE,
                    new DateFormatFactory(),
                    io.questdb.std.microtime.DateLocaleFactory.INSTANCE,
                    new io.questdb.std.microtime.DateFormatFactory()
            );
        } catch (JsonException e) {
            // todo: we must not do this
            throw new RuntimeException("damn");
        }

        this.valueWriters.extendAndSet(ColumnType.BOOLEAN, this::putBooleanValue);
        this.valueWriters.extendAndSet(ColumnType.BYTE, this::putByteValue);
        this.valueWriters.extendAndSet(ColumnType.DOUBLE, this::putDoubleValue);
        this.valueWriters.extendAndSet(ColumnType.FLOAT, this::putFloatValue);
        this.valueWriters.extendAndSet(ColumnType.INT, this::putIntValue);
        this.valueWriters.extendAndSet(ColumnType.LONG, this::putLongValue);
        this.valueWriters.extendAndSet(ColumnType.DATE, this::putDateValue);
        this.valueWriters.extendAndSet(ColumnType.TIMESTAMP, this::putTimestampValue);
        this.valueWriters.extendAndSet(ColumnType.SHORT, this::putShortValue);
        this.valueWriters.extendAndSet(ColumnType.CHAR, this::putCharValue);
        this.valueWriters.extendAndSet(ColumnType.STRING, this::putStrValue);
        this.valueWriters.extendAndSet(ColumnType.SYMBOL, this::putSymValue);
        this.valueWriters.extendAndSet(ColumnType.BINARY, this::putBinValue);
        this.valueWriters.extendAndSet(ColumnType.LONG256, this::putLong256Value);

        resumeActions.extendAndSet(AbstractQueryContext.QUERY_PREFIX, this::onQueryPrefix);
        resumeActions.extendAndSet(AbstractQueryContext.QUERY_METADATA, this::onQueryMetadata);
        resumeActions.extendAndSet(AbstractQueryContext.QUERY_METADATA_SUFFIX, this::onQueryMetadataSuffix);
        resumeActions.extendAndSet(AbstractQueryContext.QUERY_SETUP_FIRST_RECORD, this::doFirstRecordLoop);
        resumeActions.extendAndSet(AbstractQueryContext.QUERY_RECORD_PREFIX, this::onQueryRecordPrefix);
        resumeActions.extendAndSet(AbstractQueryContext.QUERY_RECORD, this::onQueryRecord);
        resumeActions.extendAndSet(AbstractQueryContext.QUERY_RECORD_SUFFIX, this::onQueryRecordSuffix);
        resumeActions.extendAndSet(AbstractQueryContext.QUERY_SUFFIX, this::doQuerySuffix);
    }

    @Override
    public void close() {
        Misc.free(compiler);
        Misc.free(path);
        Misc.free(textLoader);
    }

    public void execute(
            HttpConnectionContext context,
            IODispatcher<HttpConnectionContext> dispatcher,
            JsonQueryProcessorState state,
            HttpChunkedResponseSocket socket
    ) throws PeerDisconnectedException, PeerIsSlowToReadException {
        try {
            state.recordCursorFactory = AbstractQueryContext.FACTORY_CACHE.get().poll(state.query);
            int retryCount = 0;
            do {
                sqlExecutionContext.with(context.getCairoSecurityContext(), null);
                if (state.recordCursorFactory == null) {
                    final CompiledQuery cc = compiler.compile(state.query, sqlExecutionContext);
                    if (cc.getType() == CompiledQuery.SELECT) {
                        state.recordCursorFactory = cc.getRecordCursorFactory();
                    } else if (cc.getType() == CompiledQuery.COPY) {
                        copyTable(sqlExecutionContext, cc.getCopyModel());
                    }
                    cacheHits.incrementAndGet();
                    info(state).$("execute-new [q=`").$(state.query).
                            $("`, skip: ").$(state.skip).
                            $(", stop: ").$(state.stop).
                            $(']').$();
                } else {
                    cacheMisses.incrementAndGet();
                    info(state).$("execute-cached [q=`").$(state.query).
                            $("`, skip: ").$(state.skip).
                            $(", stop: ").$(state.stop).
                            $(']').$();
                }

                if (state.recordCursorFactory != null) {
                    try {
                        state.cursor = state.recordCursorFactory.getCursor(sqlExecutionContext);
                        state.metadata = state.recordCursorFactory.getMetadata();
                        header(socket, 200);
                        resumeSend(context, dispatcher);
                        break;
                    } catch (CairoError | CairoException e) {
                        // todo: investigate why we need to keep retrying to execute query when it is failing
                        //  perhaps this is unnecessary because we don't even check the type of error it is
                        //  we could be having severe hardware issues and continue trying
                        if (retryCount == 0) {
                            // todo: we want to clear cache, no need to create string to achieve this
                            AbstractQueryContext.FACTORY_CACHE.get().put(state.query.toString(), null);
                            state.recordCursorFactory = null;
                            LOG.error().$("RecordSource execution failed. ").$(e.getMessage()).$(". Retrying ...").$();
                            retryCount++;
                        } else {
                            internalError(socket, e, state);
                            break;
                        }
                    }
                } else {
                    header(socket, 200);
                    sendConfirmation(socket);
                    readyForNextRequest(context, dispatcher);
                    break;
                }
            } while (true);
        } catch (SqlException e) {
            syntaxError(socket, e, state);
            readyForNextRequest(context, dispatcher);
        } catch (CairoException | CairoError e) {
            internalError(socket, e, state);
            readyForNextRequest(context, dispatcher);
        }
    }

    @Override
    public void onHeadersReady(HttpConnectionContext context) {
    }

    @Override
    public void onRequestComplete(
            HttpConnectionContext context,
            IODispatcher<HttpConnectionContext> dispatcher
    ) throws PeerDisconnectedException, PeerIsSlowToReadException {
        JsonQueryProcessorState state = LV.get(context);
        if (state == null) {
            LV.set(context, state = new JsonQueryProcessorState(context.getFd(), configuration.getConnectionCheckFrequency()));
        }
        HttpChunkedResponseSocket socket = context.getChunkedResponseSocket();
        if (parseUrl(socket, context.getRequestHeader(), state)) {
            execute(context, dispatcher, state, socket);
        } else {
            readyForNextRequest(context, dispatcher);
        }
    }

    @Override
    public void resumeSend(
            HttpConnectionContext context,
            IODispatcher<HttpConnectionContext> dispatcher
    ) throws PeerDisconnectedException, PeerIsSlowToReadException {
        JsonQueryProcessorState state = LV.get(context);
        if (state == null || state.cursor == null) {
            return;
        }

        LOG.debug().$("resume [fd=").$(context.getFd()).$(']').$();

        final HttpChunkedResponseSocket socket = context.getChunkedResponseSocket();
        final int columnCount = state.metadata.getColumnCount();

        while (true) {
            try {
                resumeActions.getQuick(state.queryState).onResume(state, socket, columnCount);
                break;
            } catch (NoSpaceLeftInResponseBufferException ignored) {
                if (socket.resetToBookmark()) {
                    socket.sendChunk();
                } else {
                    // what we have here is out unit of data, column value or query
                    // is larger that response content buffer
                    // all we can do in this scenario is to log appropriately
                    // and disconnect socket
                    info(state).$("Response buffer is too small, state=").$(state.queryState).$();
                    throw PeerDisconnectedException.INSTANCE;
                }
            }
        }
        // reached the end naturally?
        readyForNextRequest(context, dispatcher);
    }

    private static void putStringOrNull(CharSink r, CharSequence str) {
        if (str == null) {
            r.put("null");
        } else {
            r.encodeUtf8AndQuote(str);
        }
    }

    private void copyTable(SqlExecutionContext executionContext, CopyModel model) throws SqlException {
        try {
            textLoader.clear();
            textLoader.setState(TextLoader.ANALYZE_STRUCTURE);
            textLoader.configureDestination(model.getTableName().token, true, false, Atomicity.SKIP_ROW);
            int len = 4 * 1024 * 1024;
            long buf = Unsafe.malloc(len);
            try {
                path.of(GenericLexer.unquote(model.getFileName().token)).$();
                long fd = Files.openRO(path);
                if (fd == -1) {
                    throw SqlException.$(model.getFileName().position, "could not open file [errno=").put(Os.errno()).put(']');
                }
                long fileLen = Files.length(fd);
                long n = (int) Files.read(fd, buf, len, 0);
                if (n > 0) {
                    textLoader.parse(buf, buf + n, executionContext.getCairoSecurityContext());
                    textLoader.setState(TextLoader.LOAD_DATA);
                    int read;
                    while (n < fileLen) {
                        read = (int) Files.read(fd, buf, len, n);
                        if (read < 1) {
                            throw SqlException.$(model.getFileName().position, "could not read file [errno=").put(Os.errno()).put(']');
                        }
                        textLoader.parse(buf, buf + read, executionContext.getCairoSecurityContext());
                        n += read;
                    }
                    textLoader.wrapUp();
                }
            } finally {
                Unsafe.free(buf, len);
            }
        } catch (JsonException e) {
            e.printStackTrace();
        } finally {
            LOG.info().$("copied").$();
        }
    }

    private void doFirstRecordLoop(JsonQueryProcessorState state, HttpChunkedResponseSocket socket, int columnCount) throws PeerDisconnectedException, PeerIsSlowToReadException {
        if (onQuerySetupFirstRecord(state)) {
            doRecordFetchLoop(state, socket, columnCount);
        } else {
            doQuerySuffix(state, socket, columnCount);
        }
    }

    private void doNextRecordLoop(JsonQueryProcessorState state, HttpChunkedResponseSocket socket, int columnCount) throws PeerDisconnectedException, PeerIsSlowToReadException {
        if (doQueryNextRecord(state)) {
            doRecordFetchLoop(state, socket, columnCount);
        } else {
            doQuerySuffix(state, socket, columnCount);
        }
    }

    private void doQueryMetadata(JsonQueryProcessorState state, HttpChunkedResponseSocket socket, int columnCount) {
        state.queryState = AbstractQueryContext.QUERY_METADATA;
        for (; state.columnIndex < columnCount; state.columnIndex++) {
            socket.bookmark();
            if (state.columnIndex > 0) {
                socket.put(',');
            }
            socket.put('{').
                    putQuoted("name").put(':').putQuoted(state.metadata.getColumnName(state.columnIndex)).
                    put(',').
                    putQuoted("type").put(':').putQuoted(ColumnType.nameOf(state.metadata.getColumnType(state.columnIndex)));
            socket.put('}');
        }
    }

    private void doQueryMetadataSuffix(JsonQueryProcessorState state, HttpChunkedResponseSocket socket) {
        state.queryState = AbstractQueryContext.QUERY_METADATA_SUFFIX;
        socket.bookmark();
        socket.put("],\"dataset\":[");
    }

    private boolean doQueryNextRecord(JsonQueryProcessorState state) {
        if (state.cursor.hasNext()) {
            if (state.count < state.stop) {
                return true;
            } else {
                onNoMoreData(state);
            }
        }
        return false;
    }

    private boolean doQueryPrefix(JsonQueryProcessorState state, HttpChunkedResponseSocket socket) {
        if (state.noMeta) {
            socket.bookmark();
            socket.put('{').putQuoted("dataset").put(":[");
            return false;
        }
        socket.bookmark();
        socket.put('{').putQuoted("query").put(':').encodeUtf8AndQuote(state.query);
        socket.put(',').putQuoted("columns").put(':').put('[');
        state.columnIndex = 0;
        return true;
    }

    private void doQueryRecord(JsonQueryProcessorState state, HttpChunkedResponseSocket socket, int columnCount) {
        state.queryState = AbstractQueryContext.QUERY_RECORD;
        for (; state.columnIndex < columnCount; state.columnIndex++) {
            socket.bookmark();
            if (state.columnIndex > 0) {
                socket.put(',');
            }
            final ValueWriter vw = valueWriters.getQuick(state.metadata.getColumnType(state.columnIndex));
            if (vw != null) {
                vw.write(socket, state.record, state.columnIndex);
            }
        }
    }

    private void doQueryRecordPrefix(JsonQueryProcessorState state, HttpChunkedResponseSocket socket) {
        state.queryState = AbstractQueryContext.QUERY_RECORD_PREFIX;
        socket.bookmark();
        if (state.count > state.skip) {
            socket.put(',');
        }
        socket.put('[');
        state.columnIndex = 0;
    }

    private void doQueryRecordSuffix(JsonQueryProcessorState state, HttpChunkedResponseSocket socket) {
        state.queryState = AbstractQueryContext.QUERY_RECORD_SUFFIX;
        state.count++;
        socket.bookmark();
        socket.put(']');
    }

    private void doQuerySuffix(
            JsonQueryProcessorState state,
            HttpChunkedResponseSocket socket,
            int columnCount
    ) throws PeerDisconnectedException, PeerIsSlowToReadException {
        state.queryState = AbstractQueryContext.QUERY_SUFFIX;
        if (state.count > -1) {
            socket.bookmark();
            socket.put(']');
            socket.put(',').putQuoted("count").put(':').put(state.count);
            socket.put('}');
            state.count = -1;
            socket.sendChunk();
        }
        socket.done();
    }

    private void doRecordFetchLoop(JsonQueryProcessorState state, HttpChunkedResponseSocket socket, int columnCount) throws PeerDisconnectedException, PeerIsSlowToReadException {
        do {
            doQueryRecordPrefix(state, socket);
            doQueryRecord(state, socket, columnCount);
            doQueryRecordSuffix(state, socket);
        } while (doQueryNextRecord(state));
        doQuerySuffix(state, socket, columnCount);
    }

    private LogRecord error(JsonQueryProcessorState state) {
        return LOG.error().$('[').$(state.fd).$("] ");
    }

    long getCacheHits() {
        return cacheHits.longValue();
    }

    long getCacheMisses() {
        return cacheMisses.longValue();
    }

    protected void header(
            HttpChunkedResponseSocket socket,
            int status
    ) throws PeerDisconnectedException, PeerIsSlowToReadException {
        socket.status(status, "application/json; charset=utf-8");
        socket.headers().setKeepAlive(configuration.getKeepAliveHeader());
        socket.sendHeader();
    }

    private LogRecord info(JsonQueryProcessorState state) {
        return LOG.info().$('[').$(state.fd).$("] ");
    }

    private void internalError(
            HttpChunkedResponseSocket socket,
            Throwable e,
            JsonQueryProcessorState state
    ) throws PeerDisconnectedException, PeerIsSlowToReadException {
        error(state).$("Server error executing query ").$(state.query).$(e).$();
        sendException(socket, 0, e.getMessage(), 500, state.query);
    }

    private void onNoMoreData(JsonQueryProcessorState state) {
        if (state.countRows) {
            // this is the tail end of the cursor
            // we don't need to read records, just round up record count
            final RecordCursor cursor = state.cursor;
            final long size = cursor.size();
            if (size < 0) {
                long count = 1;
                while (cursor.hasNext()) {
                    count++;
                }
                state.count += count;
            } else {
                state.count = size;
            }
        }
    }

    private void onQueryMetadata(JsonQueryProcessorState state, HttpChunkedResponseSocket socket, int columnCount) throws PeerDisconnectedException, PeerIsSlowToReadException {
        doQueryMetadata(state, socket, columnCount);
        onQueryMetadataSuffix(state, socket, columnCount);
    }

    private void onQueryMetadataSuffix(JsonQueryProcessorState state, HttpChunkedResponseSocket socket, int columnCount) throws PeerDisconnectedException, PeerIsSlowToReadException {
        doQueryMetadataSuffix(state, socket);
        doFirstRecordLoop(state, socket, columnCount);
    }

    private void onQueryPrefix(JsonQueryProcessorState state, HttpChunkedResponseSocket socket, int columnCount) throws PeerDisconnectedException, PeerIsSlowToReadException {
        if (doQueryPrefix(state, socket)) {
            doQueryMetadata(state, socket, columnCount);
            doQueryMetadataSuffix(state, socket);
        }
        doFirstRecordLoop(state, socket, columnCount);
    }

    private void onQueryRecord(JsonQueryProcessorState state, HttpChunkedResponseSocket socket, int columnCount) throws PeerDisconnectedException, PeerIsSlowToReadException {
        doQueryRecord(state, socket, columnCount);
        onQueryRecordSuffix(state, socket, columnCount);
    }

    private void onQueryRecordPrefix(JsonQueryProcessorState state, HttpChunkedResponseSocket socket, int columnCount) throws PeerDisconnectedException, PeerIsSlowToReadException {
        doQueryRecordPrefix(state, socket);
        onQueryRecord(state, socket, columnCount);
    }

    private void onQueryRecordSuffix(JsonQueryProcessorState state, HttpChunkedResponseSocket socket, int columnCount) throws PeerDisconnectedException, PeerIsSlowToReadException {
        doQueryRecordSuffix(state, socket);
        doNextRecordLoop(state, socket, columnCount);
    }

    private boolean onQuerySetupFirstRecord(JsonQueryProcessorState state) {
        if (state.skip > 0) {
            final RecordCursor cursor = state.cursor;
            long target = state.skip + 1;
            while (target > 0 && cursor.hasNext()) {
                target--;
            }
            if (target > 0) {
                return false;
            }
            state.count = state.skip;
        } else {
            if (!state.cursor.hasNext()) {
                return false;
            }
        }

        state.columnIndex = 0;
        state.record = state.cursor.getRecord();
        return true;
    }

    private boolean parseUrl(
            HttpChunkedResponseSocket socket,
            HttpRequestHeader request,
            JsonQueryProcessorState state
    ) throws PeerDisconnectedException, PeerIsSlowToReadException {
        // Query text.
        final CharSequence query = request.getUrlParam("query");
        if (query == null || query.length() == 0) {
            info(state).$("Empty query request received. Sending empty reply.").$();
            sendException(socket, 0, "No query text", 400, state.query);
            return false;
        }

        // Url Params.
        long skip = 0;
        long stop = Long.MAX_VALUE;

        CharSequence limit = request.getUrlParam("limit");
        if (limit != null) {
            int sepPos = Chars.indexOf(limit, ',');
            try {
                if (sepPos > 0) {
                    skip = Numbers.parseLong(limit, 0, sepPos) - 1;
                    if (sepPos + 1 < limit.length()) {
                        stop = Numbers.parseLong(limit, sepPos + 1, limit.length());
                    }
                } else {
                    stop = Numbers.parseLong(limit);
                }
            } catch (NumericException ex) {
                // Skip or stop will have default value.
            }
        }
        if (stop < 0) {
            stop = 0;
        }

        if (skip < 0) {
            skip = 0;
        }

        state.query = query;
        state.skip = skip;
        state.count = 0L;
        state.stop = stop;
        state.noMeta = Chars.equalsNc("true", request.getUrlParam("nm"));
        state.countRows = Chars.equalsNc("true", request.getUrlParam("count"));
        return true;
    }

    private void putBinValue(HttpChunkedResponseSocket socket, Record record, int col) {
        socket.put('[');
        socket.put(']');
    }

    private void putBooleanValue(HttpChunkedResponseSocket socket, Record rec, int col) {
        socket.put(rec.getBool(col));
    }

    private void putByteValue(HttpChunkedResponseSocket socket, Record rec, int col) {
        socket.put(rec.getByte(col));
    }

    private void putCharValue(HttpChunkedResponseSocket socket, Record rec, int col) {
        char c = rec.getChar(col);
        if (c == 0) {
            socket.put("\"\"");
        } else {
            socket.put('"').putUtf8(c).put('"');
        }
    }

    private void putDateValue(HttpChunkedResponseSocket socket, Record rec, int col) {
        final long d = rec.getDate(col);
        if (d == Long.MIN_VALUE) {
            socket.put("null");
            return;
        }
        socket.put('"').putISODateMillis(d).put('"');
    }

    private void putDoubleValue(HttpChunkedResponseSocket socket, Record rec, int col) {
        socket.put(rec.getDouble(col), doubleScale);
    }

    private void putFloatValue(HttpChunkedResponseSocket socket, Record rec, int col) {
        socket.put(rec.getFloat(col), floatScale);
    }

    private void putIntValue(HttpChunkedResponseSocket socket, Record rec, int col) {
        final int i = rec.getInt(col);
        if (i == Integer.MIN_VALUE) {
            socket.put("null");
        } else {
            Numbers.append(socket, i);
        }
    }

    private void putLong256Value(HttpChunkedResponseSocket socket, Record rec, int col) {
        socket.put('"');
        rec.getLong256(col, socket);
        socket.put('"');
    }

    private void putLongValue(HttpChunkedResponseSocket socket, Record rec, int col) {
        final long l = rec.getLong(col);
        if (l == Long.MIN_VALUE) {
            socket.put("null");
        } else {
            socket.put(l);
        }
    }

    private void putShortValue(HttpChunkedResponseSocket socket, Record rec, int col) {
        socket.put(rec.getShort(col));
    }

    private void putStrValue(HttpChunkedResponseSocket socket, Record rec, int col) {
        putStringOrNull(socket, rec.getStr(col));
    }

    private void putSymValue(HttpChunkedResponseSocket socket, Record rec, int col) {
        putStringOrNull(socket, rec.getSym(col));
    }

    private void putTimestampValue(HttpChunkedResponseSocket socket, Record rec, int col) {
        final long t = rec.getTimestamp(col);
        if (t == Long.MIN_VALUE) {
            socket.put("null");
            return;
        }
        socket.put('"').putISODate(t).put('"');
    }

    private void readyForNextRequest(HttpConnectionContext context, IODispatcher<HttpConnectionContext> dispatcher) {
        LOG.debug().$("all sent [fd=").$(context.getFd()).$(']').$();
        context.clear();
        dispatcher.registerChannel(context, IOOperation.READ);
    }

    private void sendConfirmation(HttpChunkedResponseSocket socket) throws PeerDisconnectedException, PeerIsSlowToReadException {
        socket.put('{').putQuoted("ddl").put(':').putQuoted("OK").put('}');
        socket.sendChunk();
        socket.done();
    }

    private void sendException(
            HttpChunkedResponseSocket socket,
            int position,
            CharSequence message,
            int status,
            CharSequence query
    ) throws PeerDisconnectedException, PeerIsSlowToReadException {
        header(socket, status);
        socket.put('{').
                putQuoted("query").put(':').encodeUtf8AndQuote(query == null ? "" : query).put(',').
                putQuoted("error").put(':').encodeUtf8AndQuote(message).put(',').
                putQuoted("position").put(':').put(position);
        socket.put('}');
        socket.sendChunk();
        socket.done();
    }

    private void syntaxError(
            HttpChunkedResponseSocket socket,
            SqlException sqlException,
            JsonQueryProcessorState state
    ) throws PeerDisconnectedException, PeerIsSlowToReadException {
        info(state)
                .$("syntax-error [q=`").$(state.query)
                .$("`, at=").$(sqlException.getPosition())
                .$(", message=`").$(sqlException.getFlyweightMessage()).$('`')
                .$(']').$();
        sendException(socket, sqlException.getPosition(), sqlException.getFlyweightMessage(), 400, state.query);
    }

    @FunctionalInterface
    private interface StateResumeAction {
        void onResume(JsonQueryProcessorState state, HttpChunkedResponseSocket socket, int columnCount) throws PeerDisconnectedException, PeerIsSlowToReadException;
    }

    @FunctionalInterface
    private interface ValueWriter {
        void write(HttpChunkedResponseSocket socket, Record rec, int col);
    }
}