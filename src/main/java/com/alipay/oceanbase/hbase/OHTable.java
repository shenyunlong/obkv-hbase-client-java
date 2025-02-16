/*-
 * #%L
 * OBKV HBase Client Framework
 * %%
 * Copyright (C) 2022 OceanBase Group
 * %%
 * OBKV HBase Client Framework  is licensed under Mulan PSL v2.
 * You can use this software according to the terms and conditions of the Mulan PSL v2.
 * You may obtain a copy of Mulan PSL v2 at:
 *          http://license.coscl.org.cn/MulanPSL2
 * THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
 * EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
 * MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
 * See the Mulan PSL v2 for more details.
 * #L%
 */

package com.alipay.oceanbase.hbase;

import com.alipay.oceanbase.hbase.constants.OHConstants;
import com.alipay.oceanbase.hbase.exception.FeatureNotSupportedException;
import com.alipay.oceanbase.hbase.execute.ServerCallable;
import com.alipay.oceanbase.hbase.filter.HBaseFilterUtils;
import com.alipay.oceanbase.hbase.result.ClientStreamScanner;
import com.alipay.oceanbase.hbase.util.ObTableClientManager;
import com.alipay.oceanbase.hbase.util.TableHBaseLoggerFactory;
import com.alipay.oceanbase.rpc.ObTableClient;
import com.alipay.oceanbase.rpc.exception.ExceptionUtil;
import com.alipay.oceanbase.rpc.protocol.payload.impl.ObObj;
import com.alipay.oceanbase.rpc.protocol.payload.impl.ObRowKey;
import com.alipay.oceanbase.rpc.protocol.payload.impl.execute.*;
import com.alipay.oceanbase.rpc.protocol.payload.impl.execute.mutate.ObTableQueryAndMutate;
import com.alipay.oceanbase.rpc.protocol.payload.impl.execute.mutate.ObTableQueryAndMutateRequest;
import com.alipay.oceanbase.rpc.protocol.payload.impl.execute.mutate.ObTableQueryAndMutateResult;
import com.alipay.oceanbase.rpc.protocol.payload.impl.execute.query.*;
import com.alipay.oceanbase.rpc.stream.ObTableClientQueryStreamResult;
import com.alipay.sofa.common.thread.SofaThreadPoolExecutor;
import com.alipay.oceanbase.hbase.exception.OperationTimeoutException;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.client.Append;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.HTableInterface;
import org.apache.hadoop.hbase.client.Increment;
import org.apache.hadoop.hbase.client.Mutation;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Row;
import org.apache.hadoop.hbase.client.RowLock;
import org.apache.hadoop.hbase.client.RowMutations;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.coprocessor.Batch;
import org.apache.hadoop.hbase.filter.Filter;
import org.apache.hadoop.hbase.io.TimeRange;
import org.apache.hadoop.hbase.ipc.CoprocessorProtocol;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.Pair;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

import static com.alipay.oceanbase.hbase.constants.OHConstants.*;
import static com.alipay.oceanbase.hbase.util.Preconditions.checkArgument;
import static com.alipay.oceanbase.hbase.util.TableHBaseLoggerFactory.LCD;
import static com.alipay.oceanbase.hbase.util.TableHBaseLoggerFactory.TABLE_HBASE_LOGGER_SPACE;
import static com.alipay.oceanbase.rpc.protocol.payload.impl.execute.ObTableOperation.getInstance;
import static com.alipay.oceanbase.rpc.protocol.payload.impl.execute.ObTableOperationType.*;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.apache.commons.lang.StringUtils.isBlank;
import static org.apache.commons.lang.StringUtils.isNotBlank;

public class OHTable implements HTableInterface {

    private static final Logger   logger                 = TableHBaseLoggerFactory
                                                             .getLogger(OHTable.class);
    /**
     * the table client for oceanbase
     */
    private final ObTableClient   obTableClient;

    /**
     * the ohTable name in byte array
     */
    private final byte[]          tableName;

    /**
     * the ohTable name in string
     */
    private final String          tableNameString;

    /**
     * operation timeout whose default value is <code>Integer.MaxValue</code> decide the timeout of executing in pool.
     * <p>
     * if operation timeout is not equal to the default value mean the <code>Get</code> execute in the pool
     */
    private int                   operationTimeout;

    /**
     * if the <code>Get</code> executing pool is specified by user cleanupPoolOnClose will be false ,
     * which means that user is responsible for the pool
     */
    private boolean               cleanupPoolOnClose     = true;

    /**
     * if the obTableClient is specified by user closeClientOnClose will be false ,
     * which means that user is responsible for obTableClient
     */
    private boolean               closeClientOnClose     = true;

    /**
     * when the operationExecuteInPool is true the <code>Get</code>
     * will be executed in the pool.
     */
    private final ExecutorService executePool;

    /**
     * the maximum number of threads to allow in the
     * <code>Get</code> executing pool
     */
    private final int             maxThreads;

    /**
     * the <code>Get</code> executing pool thread keep alive time.
     * when the number of threads is greater than the core, this
     * is the maximum time that excess idle threads will wait for
     * new tasks before terminating.d
     */
    private final long            keepAliveTime;

    /**
     * decide whether the <code>Get</code> request will be executed
     * in the pool.
     */
    private boolean               operationExecuteInPool = false;

    /**
     * the buffer of put request
     */
    private final ArrayList<Put>  writeBuffer            = new ArrayList<Put>();
    /**
     * when the put request reach the write buffer size the do put will
     * flush commits automatically
     */
    private long                  writeBufferSize;
    /**
     * the do put check write buffer every putWriteBufferCheck puts
     */
    private int                   putWriteBufferCheck;

    /**
     * decide whether clear the buffer when meet exception.the default
     * value is true. Be careful about the correctness when set it false
     */
    private boolean               clearBufferOnFail      = true;

    /**
     * whether flush the put automatically
     */
    private boolean               autoFlush              = true;

    /**
     * current buffer size
     */
    private long                  currentWriteBufferSize;

    /**
     * the max size of put key value
     */
    private int                   maxKeyValueSize;

    // i.e., doPut checks the writebuffer every X Puts.

    /**
     * <code>Configuration</code> extends from hbase configuration
     */
    private final Configuration   configuration;

    /**
     * Creates an object to access a HBase table.
     * Shares oceanbase table obTableClient and other resources with other OHTable instances
     * created with the same <code>configuration</code> instance.  Uses already-populated
     * region cache if one is available, populated by any other OHTable instances
     * sharing this <code>configuration</code> instance.  Recommended.
     *
     * @param configuration Configuration object to use.
     * @param tableName     Name of the table.
     * @throws IllegalArgumentException if the param error
     * @throws IOException              if a remote or network exception occurs
     */
    public OHTable(Configuration configuration, String tableName) throws IOException {
        checkArgument(configuration != null, "configuration is null.");
        checkArgument(isNotBlank(tableName), "tableNameString is blank.");
        this.configuration = configuration;
        this.tableName = tableName.getBytes();
        this.tableNameString = tableName;
        this.maxThreads = configuration.getInt(HBASE_HTABLE_PRIVATE_THREADS_MAX,
            DEFAULT_HBASE_HTABLE_PRIVATE_THREADS_MAX);
        this.keepAliveTime = configuration.getLong(HBASE_HTABLE_THREAD_KEEP_ALIVE_TIME,
            DEFAULT_HBASE_HTABLE_THREAD_KEEP_ALIVE_TIME);
        this.executePool = createDefaultThreadPoolExecutor(1, maxThreads, keepAliveTime);
        this.obTableClient = ObTableClientManager.getOrCreateObTableClient(configuration);

        finishSetUp();
    }

    /**
     * Creates an object to access a HBase table.
     * Shares oceanbase table obTableClient and other resources with other OHTable instances
     * created with the same <code>configuration</code> instance.  Uses already-populated
     * region cache if one is available, populated by any other OHTable instances
     * sharing this <code>configuration</code> instance.  Recommended.
     *
     * @param configuration Configuration object to use.
     * @param tableName     Name of the table.
     * @throws IOException              if a remote or network exception occurs
     * @throws IllegalArgumentException if the param error
     */
    public OHTable(Configuration configuration, final byte[] tableName) throws IOException {
        this(configuration, Arrays.toString(tableName));
    }

    /**
     * Creates an object to access a HBase table.
     * Shares oceanbase table obTableClient and other resources with other OHTable instances
     * created with the same <code>configuration</code> instance.  Uses already-populated
     * region cache if one is available, populated by any other OHTable instances
     * sharing this <code>configuration</code> instance.
     * Use this constructor when the ExecutorService is externally managed.
     *
     * @param configuration Configuration object to use.
     * @param tableName     Name of the table.
     * @param executePool   ExecutorService to be used.
     * @throws IOException              if a remote or network exception occurs
     * @throws IllegalArgumentException if the param error
     */
    public OHTable(Configuration configuration, final byte[] tableName,
                   final ExecutorService executePool) throws IOException {
        checkArgument(configuration != null, "configuration is null.");
        checkArgument(tableName != null, "tableNameString is blank.");
        checkArgument(executePool != null && !executePool.isShutdown(),
            "executePool is null or executePool is shutdown");
        this.configuration = configuration;
        this.executePool = executePool;
        this.tableName = tableName;
        this.tableNameString = Bytes.toString(tableName);
        this.cleanupPoolOnClose = false;
        this.maxThreads = configuration.getInt(HBASE_HTABLE_PRIVATE_THREADS_MAX,
            DEFAULT_HBASE_HTABLE_PRIVATE_THREADS_MAX);
        this.keepAliveTime = configuration.getLong(HBASE_HTABLE_THREAD_KEEP_ALIVE_TIME,
            DEFAULT_HBASE_HTABLE_THREAD_KEEP_ALIVE_TIME);
        this.obTableClient = ObTableClientManager.getOrCreateObTableClient(configuration);

        finishSetUp();
    }

    /**
     * Creates an object to access a HBase table.
     * Shares zookeeper connection and other resources with other OHTable instances
     * created with the same <code>connection</code> instance.
     * Use this constructor when the ExecutorService and HConnection instance are
     * externally managed.
     *
     * @param tableName     Name of the table.
     * @param obTableClient Oceanbase obTableClient to be used.
     * @param executePool   ExecutorService to be used.
     * @throws IllegalArgumentException if the param error
     */
    public OHTable(final byte[] tableName, final ObTableClient obTableClient,
                   final ExecutorService executePool) {
        checkArgument(tableName != null, "tableNameString is blank.");
        checkArgument(executePool != null && !executePool.isShutdown(),
            "executePool is null or executePool is shutdown");
        this.tableName = tableName;
        this.tableNameString = Bytes.toString(tableName);
        this.cleanupPoolOnClose = false;
        this.closeClientOnClose = false;
        this.maxThreads = DEFAULT_HBASE_HTABLE_PRIVATE_THREADS_MAX;
        this.keepAliveTime = DEFAULT_HBASE_HTABLE_THREAD_KEEP_ALIVE_TIME;
        this.executePool = executePool;
        this.obTableClient = obTableClient;
        this.configuration = new Configuration();
        finishSetUp();
    }

    /**
     * 创建默认的线程池
     * Using the "direct handoff" approach, new threads will only be created
     * if it is necessary and will grow unbounded. This could be bad but in HCM
     * we only create as many Runnables as there are region servers. It means
     * it also scales when new region servers are added.
     * @param coreSize core size
     * @param maxThreads max threads
     * @param keepAliveTime keep alive time
     * @return ThreadPoolExecutor
     */
    public static ThreadPoolExecutor createDefaultThreadPoolExecutor(int coreSize, int maxThreads,
                                                                     long keepAliveTime) {
        SofaThreadPoolExecutor executor = new SofaThreadPoolExecutor(coreSize, maxThreads,
            keepAliveTime, SECONDS, new SynchronousQueue<Runnable>(), "OHTableDefaultExecutePool",
            TABLE_HBASE_LOGGER_SPACE);
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    /**
     * 参数校验，防止在执行的时候才发现配置不正确
     */
    private void finishSetUp() {
        checkArgument(configuration != null, "configuration is null.");
        checkArgument(tableName != null, "tableNameString is null.");
        checkArgument(tableNameString != null, "tableNameString is null.");
        this.operationTimeout = this.configuration.getInt(
            HConstants.HBASE_CLIENT_OPERATION_TIMEOUT,
            HConstants.DEFAULT_HBASE_CLIENT_OPERATION_TIMEOUT);
        this.operationExecuteInPool = this.configuration.getBoolean(
            HBASE_CLIENT_OPERATION_EXECUTE_IN_POOL,
            (this.operationTimeout != HConstants.DEFAULT_HBASE_CLIENT_OPERATION_TIMEOUT));
        this.maxKeyValueSize = this.configuration.getInt(HBASE_CLIENT_KEYVALUE_MAXSIZE,
            OHConstants.DEFAULT_HBASE_CLIENT_KEYVALUE_MAXSIZE);
        this.putWriteBufferCheck = this.configuration.getInt(HBASE_HTABLE_PUT_WRITE_BUFFER_CHECK,
            DEFAULT_HBASE_HTABLE_PUT_WRITE_BUFFER_CHECK);
        this.writeBufferSize = this.configuration.getLong(HBASE_HTABLE_CLIENT_WRITE_BUFFER,
            DEFAULT_HBASE_HTABLE_CLIENT_WRITE_BUFFER);
    }

    public byte[] getTableName() {
        return tableName;
    }

    public Configuration getConfiguration() {
        return configuration;
    }

    public HTableDescriptor getTableDescriptor() {
        throw new FeatureNotSupportedException("not supported yet.");
    }

    /**
     * Test for the existence of columns in the table, as specified in the Get.
     *
     * This will return true if the Get matches one or more keys, false if not.
     *
     * Be carefully ,this is not a server-side call in real. May be optimized later
     *
     * @param get the Get
     * @return true if the specified Get matches one or more keys, false if not
     * @throws IOException e
     */
    public boolean exists(Get get) throws IOException {
        Result r = get(get);
        return !r.isEmpty();
    }

    public void batch(List<? extends Row> actions, Object[] results) {
        throw new FeatureNotSupportedException("not supported yet.");
    }

    public Object[] batch(List<? extends Row> actions) {
        throw new FeatureNotSupportedException("not supported yet.");
    }

    public Result get(final Get get) throws IOException {

        checkFamilyViolation(get.getFamilyMap().keySet());

        ServerCallable<Result> serverCallable = new ServerCallable<Result>(configuration,
            obTableClient, tableNameString, get.getRow(), get.getRow(), operationTimeout) {
            public Result call() throws IOException {
                List<KeyValue> keyValueList = new ArrayList<KeyValue>();
                for (Map.Entry<byte[], NavigableSet<byte[]>> entry : get.getFamilyMap().entrySet()) {

                    byte[] family = entry.getKey();
                    try {
                        ObHTableFilter filter = buildObHTableFilter(get.getFilter(),
                            get.getTimeRange(), get.getMaxVersions(), entry.getValue());

                        ObTableQuery obTableQuery = buildObTableQuery(filter, get.getRow(), true,
                            get.getRow(), true, -1);

                        ObTableQueryRequest request = buildObTableQueryRequest(obTableQuery,
                            getTargetTableName(tableNameString, Bytes.toString(family)));

                        ObTableClientQueryStreamResult clientQueryStreamResult = (ObTableClientQueryStreamResult) obTableClient
                            .execute(request);
                        for (List<ObObj> row : clientQueryStreamResult.getCacheRows()) {
                            KeyValue kv = new KeyValue((byte[]) row.get(0).getValue(),//K
                                family,//family
                                (byte[]) row.get(1).getValue(),//Q
                                (Long) row.get(2).getValue(),//T
                                (byte[]) row.get(3).getValue()//V
                            );
                            keyValueList.add(kv);
                        }
                    } catch (Exception e) {
                        logger.error(LCD.convert("01-00002"), tableNameString,
                            Bytes.toString(family), e);
                        throw new IOException("query table:" + tableNameString + " family "
                                              + Bytes.toString(family) + " error.", e);
                    }
                }
                return new Result(keyValueList);
            }
        };
        return executeServerCallable(serverCallable);
    }

    public Result[] get(List<Get> gets) throws IOException {
        Result[] results = new Result[gets.size()];
        for (int i = 0; i < gets.size(); i++) {
            results[i] = get(gets.get(i));
        }
        return results;
    }

    /**
     * hbase的获取这个rowkey，没有的话就前一个rowkey，不支持
     * @param row row
     * @param family family
     * @return Result
     */
    public Result getRowOrBefore(byte[] row, byte[] family) {
        throw new FeatureNotSupportedException("not supported yet.");
    }

    public ResultScanner getScanner(final Scan scan) throws IOException {

        checkFamilyViolation(scan.getFamilyMap().keySet());

        //be careful about the packet size ,may the packet exceed the max result size ,leading to error
        ServerCallable<ResultScanner> serverCallable = new ServerCallable<ResultScanner>(
            configuration, obTableClient, tableNameString, scan.getStartRow(), scan.getStopRow(),
            operationTimeout) {
            public ResultScanner call() throws IOException {

                for (Map.Entry<byte[], NavigableSet<byte[]>> entry : scan.getFamilyMap().entrySet()) {
                    byte[] f = entry.getKey();
                    try {
                        ObHTableFilter filter = buildObHTableFilter(scan.getFilter(),
                            scan.getTimeRange(), scan.getMaxVersions(), entry.getValue());
                        ObTableQuery obTableQuery;
                        if (Arrays.equals(scan.getStartRow(), HConstants.EMPTY_START_ROW)
                            && Arrays.equals(scan.getStopRow(), HConstants.EMPTY_START_ROW)) {
                            obTableQuery = buildObTableQuery(filter, null, scan.getBatch());
                        } else {
                            // not support reverse scan.
                            // 由于 HBase 接口与 OB 接口表达范围的差异，reverse scan 需要交换 startRow 和 stopRow
                            // if (scan.getReversed()) {
                            //     obTableQuery = buildObTableQuery(filter, scan.getStopRow(), false,
                            //         scan.getStartRow(), true, scan.getBatch());
                            // } else {
                            obTableQuery = buildObTableQuery(filter, scan.getStartRow(), true,
                                scan.getStopRow(), false, scan.getBatch());
                            // }
                        }

                        // not support reverse scan.
                        // if (scan.getReversed()) { // reverse scan 时设置为逆序
                        //     obTableQuery.setScanOrder(ObScanOrder.Reverse);
                        // }

                        // no support set maxResultSize.
                        // obTableQuery.setMaxResultSize(scan.getMaxResultSize());

                        ObTableQueryRequest request = buildObTableQueryRequest(obTableQuery,
                            getTargetTableName(tableNameString, Bytes.toString(f)));
                        ObTableClientQueryStreamResult clientQueryStreamResult = (ObTableClientQueryStreamResult) obTableClient
                            .execute(request);
                        return new ClientStreamScanner(clientQueryStreamResult, tableNameString, f);
                    } catch (Exception e) {
                        logger
                            .error(LCD.convert("01-00003"), tableNameString, Bytes.toString(f), e);
                        throw new IOException("scan table:" + tableNameString + " family "
                                              + Bytes.toString(f) + " error.", e);
                    }
                }

                throw new IOException("scan table:" + tableNameString + "has no family");
            }
        };
        return executeServerCallable(serverCallable);
    }

    public ResultScanner getScanner(final byte[] family) throws IOException {
        Scan scan = new Scan();
        scan.addFamily(family);
        return getScanner(scan);
    }

    public ResultScanner getScanner(byte[] family, byte[] qualifier) throws IOException {
        Scan scan = new Scan();
        scan.addColumn(family, qualifier);
        return getScanner(scan);
    }

    public void put(Put put) throws IOException {
        doPut(Collections.singletonList(put));
    }

    public void put(List<Put> puts) throws IOException {
        doPut(puts);
    }

    private void doPut(List<Put> puts) throws IOException {
        int n = 0;
        for (Put put : puts) {
            validatePut(put);
            checkFamilyViolation(put.getFamilyMap().keySet());
            writeBuffer.add(put);
            currentWriteBufferSize += put.heapSize();

            // we need to periodically see if the writebuffer is full instead of waiting until the end of the List
            n++;
            if (n % putWriteBufferCheck == 0 && currentWriteBufferSize > writeBufferSize) {
                flushCommits();
            }
        }
        if (autoFlush || currentWriteBufferSize > writeBufferSize) {
            flushCommits();
        }
    }

    /**
     * 校验 put 里的参数是否合法，需要传入 family ，并且 keyvalue 的 size 不能太大
     * @param put the put
     */
    private void validatePut(Put put) {
        if (put.isEmpty()) {
            throw new IllegalArgumentException("No columns to insert");
        }

        if (maxKeyValueSize > 0) {
            for (Map.Entry<byte[], List<KeyValue>> entry : put.getFamilyMap().entrySet()) {
                if (entry.getKey() == null || entry.getKey().length == 0) {
                    throw new IllegalArgumentException("family is empty");
                }
                for (KeyValue kv : entry.getValue()) {
                    if (kv.getLength() > maxKeyValueSize) {
                        throw new IllegalArgumentException("KeyValue size too large");
                    }
                }
            }
        }
    }

    /**
     * 例如当 key="key001", family = "family", c1="a" 时，才执行 put 操作，该命令是原子的
     * @param row row
     * @param family family
     * @param qualifier qualifier
     * @param value value
     * @param put put
     * @return boolean
     * @throws IOException if failed
     */
    public boolean checkAndPut(byte[] row, byte[] family, byte[] qualifier, byte[] value, Put put)
                                                                                                  throws IOException {
        return checkAndMutation(row, family, qualifier, value, put);
    }

    private void innerDelete(Delete delete) throws IOException {
        checkArgument(delete.getRow() != null, "row is null");
        checkArgument(!delete.isEmpty(), "delete is empty");
        List<Integer> errorCodeList = new ArrayList<Integer>();
        try {
            checkFamilyViolation(delete.getFamilyMap().keySet());

            Map.Entry<byte[], List<KeyValue>> entry = delete.getFamilyMap().entrySet().iterator()
                .next();
            ObTableBatchOperation batch = buildObTableBatchOperation(entry.getValue(), false, null);

            ObTableBatchOperationRequest request = buildObTableBatchOperationRequest(batch,
                getTargetTableName(tableNameString, Bytes.toString(entry.getKey())));
            ObTableBatchOperationResult result = (ObTableBatchOperationResult) obTableClient
                .execute(request);
            boolean hasError = false;
            int throwErrorCode = 0;
            for (ObTableOperationResult obTableOperationResult : result.getResults()) {
                int errorCode = obTableOperationResult.getHeader().getErrno();
                errorCodeList.add(errorCode);
                if (errorCode != 0) {
                    hasError = true;
                    throwErrorCode = errorCode;
                }
            }

            if (hasError) {
                ExceptionUtil.throwObTableException(throwErrorCode);
            }
        } catch (Exception e) {
            logger.error(LCD.convert("01-00004"), tableNameString, errorCodeList, e);
            throw new IOException("delete  table " + tableNameString + " error codes "
                                  + errorCodeList, e);
        }
    }

    public void delete(Delete delete) throws IOException {
        checkFamilyViolation(delete.getFamilyMap().keySet());
        innerDelete(delete);
    }

    public void delete(List<Delete> deletes) throws IOException {
        for (Delete delete : deletes) {
            innerDelete(delete);
        }
    }

    /**
     * 例如当 key="key001", family = "family", c1="a" 时，才执行 delete 操作，该命令是原子的
     * @param row row
     * @param family family
     * @param qualifier qualifier
     * @param value value
     * @param delete delete
     * @return boolean
     * @throws IOException if failed
     */
    public boolean checkAndDelete(byte[] row, byte[] family, byte[] qualifier, byte[] value,
                                  Delete delete) throws IOException {
        return checkAndMutation(row, family, qualifier, value, delete);
    }

    private boolean checkAndMutation(byte[] row, byte[] family, byte[] qualifier, byte[] value,
                                     Mutation mutation) throws IOException {
        try {
            checkArgument(row != null, "row is null");
            checkArgument(isNotBlank(Bytes.toString(family)), "family is blank");
            checkArgument(Bytes.equals(row, mutation.getRow()),
                "mutation row is not equal check row");

            checkArgument(!mutation.isEmpty(), "mutation is empty");

            String filterString = buildCheckAndMutateFilterString(family, qualifier, value);

            ObHTableFilter filter = buildObHTableFilter(filterString, null, 1, qualifier);

            checkFamilyViolation(mutation.getFamilyMap().keySet());
            // only one family operation is allowed
            Map.Entry<byte[], List<KeyValue>> entry = mutation.getFamilyMap().entrySet().iterator()
                .next();

            checkArgument(Arrays.equals(family, entry.getKey()),
                "mutation family is not equal check family");

            ObTableQuery obTableQuery = buildObTableQuery(filter, row, true, row, true, -1);

            ObTableBatchOperation batch = buildObTableBatchOperation(entry.getValue(), false, null);

            ObTableQueryAndMutateRequest request = buildObTableQueryAndMutateRequest(obTableQuery,
                batch, getTargetTableName(tableNameString, Bytes.toString(entry.getKey())));
            ObTableQueryAndMutateResult result = (ObTableQueryAndMutateResult) obTableClient
                .execute(request);
            return result.getAffectedRows() > 0;
        } catch (Exception e) {
            logger.error(LCD.convert("01-00005"), mutation.getClass().getSimpleName(),
                tableNameString, e);
            throw new IOException("checkAndMutation type " + mutation.getClass().getSimpleName()
                                  + " table " + tableNameString + " error.", e);
        }
    }

    public void mutateRow(RowMutations rm) {
        throw new FeatureNotSupportedException("not supported yet.");
    }

    /**
     * 例如 key = "key", c1="a"，在c1后面append，使c1="aaa"
     * 原子操作
     * @param append append
     * @return Result
     * @throws IOException if failed
     */
    public Result append(Append append) throws IOException {

        checkFamilyViolation(append.getFamilyMap().keySet());
        checkArgument(!append.isEmpty(), "append is empty.");
        try {
            byte[] r = append.getRow();
            Map.Entry<byte[], List<KeyValue>> entry = append.getFamilyMap().entrySet().iterator()
                .next();
            byte[] f = entry.getKey();
            List<byte[]> qualifiers = new ArrayList<byte[]>();
            ObTableBatchOperation batchOperation = buildObTableBatchOperation(entry.getValue(),
                true, qualifiers);
            // the later hbase has supported timeRange
            ObHTableFilter filter = buildObHTableFilter(null, null, 1, qualifiers);
            ObTableQuery obTableQuery = buildObTableQuery(filter, r, true, r, true, -1);
            ObTableQueryAndMutate queryAndMutate = new ObTableQueryAndMutate();
            queryAndMutate.setTableQuery(obTableQuery);
            queryAndMutate.setMutations(batchOperation);
            ObTableQueryAndMutateRequest request = buildObTableQueryAndMutateRequest(obTableQuery,
                batchOperation, getTargetTableName(tableNameString, Bytes.toString(f)));
            ObTableQueryAndMutateResult result = (ObTableQueryAndMutateResult) obTableClient
                .execute(request);
            ObTableQueryResult queryResult = result.getAffectedEntity();
            List<KeyValue> keyValues = new ArrayList<KeyValue>();
            for (List<ObObj> row : queryResult.getPropertiesRows()) {
                byte[] k = (byte[]) row.get(0).getValue();
                byte[] q = (byte[]) row.get(1).getValue();
                long t = (Long) row.get(2).getValue();
                byte[] v = (byte[]) row.get(3).getValue();
                KeyValue kv = new KeyValue(k, f, q, t, v);

                keyValues.add(kv);
            }
            return new Result(keyValues);
        } catch (Exception e) {
            logger.error(LCD.convert("01-00006"), tableNameString, e);
            throw new IOException("append table " + tableNameString + " error.", e);
        }
    }

    /**
     * 例如 key = "key", c2=1，在c2后面increment，在c2后面加2，变成 c2=3
     * 原子操作
     * @param increment increment
     * @return Result
     * @throws IOException if failed
     */
    public Result increment(Increment increment) throws IOException {

        checkFamilyViolation(increment.getFamilyMap().keySet());

        try {
            List<byte[]> qualifiers = new ArrayList<byte[]>();

            byte[] rowKey = increment.getRow();
            Map.Entry<byte[], NavigableMap<byte[], Long>> entry = increment.getFamilyMap()
                .entrySet().iterator().next();

            byte[] f = entry.getKey();

            ObTableBatchOperation batch = new ObTableBatchOperation();
            for (Map.Entry<byte[], Long> qualifiersIncrements : entry.getValue().entrySet()) {
                byte[] qualifier = qualifiersIncrements.getKey();
                qualifiers.add(qualifier);
                batch.addTableOperation(getInstance(INCREMENT, new Object[] { rowKey, qualifier,
                        Long.MAX_VALUE }, V_COLUMNS,
                    new Object[] { Bytes.toBytes(qualifiersIncrements.getValue()) }));
            }

            ObHTableFilter filter = buildObHTableFilter(null, increment.getTimeRange(), 1,
                qualifiers);

            ObTableQuery obTableQuery = buildObTableQuery(filter, rowKey, true, rowKey, true, -1);
            ObTableQueryAndMutate queryAndMutate = new ObTableQueryAndMutate();
            queryAndMutate.setMutations(batch);
            queryAndMutate.setTableQuery(obTableQuery);

            ObTableQueryAndMutateRequest request = buildObTableQueryAndMutateRequest(obTableQuery,
                batch, getTargetTableName(tableNameString, Bytes.toString(f)));

            ObTableQueryAndMutateResult result = (ObTableQueryAndMutateResult) obTableClient
                .execute(request);
            ObTableQueryResult queryResult = result.getAffectedEntity();
            List<KeyValue> keyValues = new ArrayList<KeyValue>();
            for (List<ObObj> row : queryResult.getPropertiesRows()) {
                byte[] k = (byte[]) row.get(0).getValue();
                byte[] q = (byte[]) row.get(1).getValue();
                long t = (Long) row.get(2).getValue();
                byte[] v = (byte[]) row.get(3).getValue();
                KeyValue kv = new KeyValue(k, f, q, t, v);

                keyValues.add(kv);
            }
            return new Result(keyValues);
        } catch (Exception e) {
            logger.error(LCD.convert("01-00007"), tableNameString, e);
            throw new IOException("increment table " + tableNameString + " error.", e);
        }
    }

    /**
     * 直接通过 column 名进行 increment 操作
     * @param row row
     * @param family family
     * @param qualifier qualifier
     * @param amount amount
     * @return long
     * @throws IOException if failed
     */
    public long incrementColumnValue(byte[] row, byte[] family, byte[] qualifier, long amount)
                                                                                              throws IOException {
        try {

            List<byte[]> qualifiers = new ArrayList<byte[]>();

            ObTableBatchOperation batch = new ObTableBatchOperation();
            batch.addTableOperation(getInstance(INCREMENT, new Object[] { row, qualifier,
                    Long.MAX_VALUE }, V_COLUMNS, new Object[] { Bytes.toBytes(amount) }));

            ObHTableFilter filter = buildObHTableFilter(null, null, 1, qualifiers);

            ObTableQuery obTableQuery = buildObTableQuery(filter, row, true, row, true, -1);
            ObTableQueryAndMutate queryAndMutate = new ObTableQueryAndMutate();
            queryAndMutate.setMutations(batch);
            queryAndMutate.setTableQuery(obTableQuery);

            ObTableQueryAndMutateRequest request = buildObTableQueryAndMutateRequest(obTableQuery,
                batch, getTargetTableName(tableNameString, Bytes.toString(family)));

            ObTableQueryAndMutateResult result = (ObTableQueryAndMutateResult) obTableClient
                .execute(request);
            ObTableQueryResult queryResult = result.getAffectedEntity();
            if (queryResult.getPropertiesRows().size() != 1) {
                throw new IllegalStateException("the increment result size illegal "
                                                + queryResult.getPropertiesRows().size());
            }
            return Bytes.toLong((byte[]) queryResult.getPropertiesRows().get(0).get(3).getValue());
        } catch (Exception e) {
            logger.error(LCD.convert("01-00007"), tableNameString, e);
            throw new IOException("increment table " + tableNameString + " error.", e);
        }
    }

    public long incrementColumnValue(byte[] row, byte[] family, byte[] qualifier, long amount,
                                     boolean writeToWAL) throws IOException {
        // WAL ignored
        return incrementColumnValue(row, family, qualifier, amount);
    }

    public boolean isAutoFlush() {
        return autoFlush;
    }

    public void flushCommits() throws IOException {

        try {
            boolean[] resultSuccess = new boolean[writeBuffer.size()];
            try {
                Map<String, Pair<List<Integer>, List<KeyValue>>> familyMap = new HashMap<String, Pair<List<Integer>, List<KeyValue>>>();
                for (int i = 0; i < writeBuffer.size(); i++) {
                    Put aPut = writeBuffer.get(i);
                    Map<byte[], List<KeyValue>> innerFamilyMap = aPut.getFamilyMap();
                    // multi family can not ensure automatic
                    for (Map.Entry<byte[], List<KeyValue>> entry : innerFamilyMap.entrySet()) {
                        String family = Bytes.toString(entry.getKey());
                        Pair<List<Integer>, List<KeyValue>> keyValueWithIndex = familyMap
                            .get(family);
                        if (keyValueWithIndex == null) {
                            keyValueWithIndex = new Pair<List<Integer>, List<KeyValue>>(
                                new ArrayList<Integer>(), new ArrayList<KeyValue>());
                            familyMap.put(family, keyValueWithIndex);
                        }
                        keyValueWithIndex.getFirst().add(i);
                        keyValueWithIndex.getSecond().addAll(entry.getValue());
                    }
                }
                for (Map.Entry<String, Pair<List<Integer>, List<KeyValue>>> entry : familyMap
                    .entrySet()) {
                    List<Integer> errorCodeList = new ArrayList<Integer>(entry.getValue()
                        .getSecond().size());
                    try {
                        String targetTableName = getTargetTableName(this.tableNameString,
                            entry.getKey());
                        ObTableBatchOperation batch = buildObTableBatchOperation(entry.getValue()
                            .getSecond(), false, null);
                        ObTableBatchOperationRequest request = buildObTableBatchOperationRequest(
                            batch, targetTableName);

                        ObTableBatchOperationResult result = (ObTableBatchOperationResult) obTableClient
                            .execute(request);
                        List<ObTableOperationResult> obTableOperationResults = result.getResults();

                        ObTableOperationResult throwResult = null;

                        for (ObTableOperationResult obTableOperationResult : obTableOperationResults) {
                            int errorCode = obTableOperationResult.getHeader().getErrno();
                            errorCodeList.add(errorCode);
                            if (errorCode != 0) {
                                throwResult = obTableOperationResult;
                            }
                        }

                        for (Integer index : entry.getValue().getFirst()) {
                            resultSuccess[index] = throwResult == null;
                        }

                        if (throwResult != null) {
                            ExceptionUtil.throwObTableException(throwResult.getExecuteHost(),
                                throwResult.getExecutePort(), throwResult.getSequence(),
                                throwResult.getUniqueId(), throwResult.getHeader().getErrno());
                        }
                    } catch (Exception e) {
                        logger.error(LCD.convert("01-00008"), tableNameString, errorCodeList,
                            autoFlush, writeBuffer.size(), e);
                        throw new IOException("put table " + tableNameString + " error codes "
                                              + errorCodeList + "auto flush " + autoFlush
                                              + " current buffer size " + writeBuffer.size(), e);
                    }

                }

            } finally {
                // mutate list so that it is empty for complete success, or contains
                // only failed records results are returned in the same order as the
                // requests in list walk the list backwards, so we can remove from list
                // without impacting the indexes of earlier members
                for (int i = resultSuccess.length - 1; i >= 0; i--) {
                    if (resultSuccess[i]) {
                        // successful Puts are removed from the list here.
                        writeBuffer.remove(i);
                    }
                }
            }
        } finally {
            if (clearBufferOnFail) {
                writeBuffer.clear();
                currentWriteBufferSize = 0;
            } else {
                // the write buffer was adjusted by processBatchOfPuts
                currentWriteBufferSize = 0;
                for (Put aPut : writeBuffer) {
                    currentWriteBufferSize += aPut.heapSize();
                }
            }
        }
    }

    public void close() throws IOException {
        if (cleanupPoolOnClose) {
            executePool.shutdown();
        }
    }

    public RowLock lockRow(byte[] row) {
        throw new FeatureNotSupportedException("not supported yet.");
    }

    public void unlockRow(RowLock rl) {
        throw new FeatureNotSupportedException("not supported yet.");
    }

    public <T extends CoprocessorProtocol> T coprocessorProxy(Class<T> protocol, byte[] row) {
        throw new FeatureNotSupportedException("not supported yet.");
    }

    public <T extends CoprocessorProtocol, R> Map<byte[], R> coprocessorExec(Class<T> protocol,
                                                                             byte[] startKey,
                                                                             byte[] endKey,
                                                                             Batch.Call<T, R> callable) {
        throw new FeatureNotSupportedException("not supported yet.");
    }

    public <T extends CoprocessorProtocol, R> void coprocessorExec(Class<T> protocol,
                                                                   byte[] startKey, byte[] endKey,
                                                                   Batch.Call<T, R> callable,
                                                                   Batch.Callback<R> callback) {
        throw new FeatureNotSupportedException("not supported yet.");
    }

    /**
     * See {@link #setAutoFlush(boolean, boolean)}
     *
     * @param autoFlush Whether or not to enable 'auto-flush'.
     */
    public void setAutoFlush(boolean autoFlush) {
        setAutoFlush(autoFlush, autoFlush);
    }

    /**
     * Turns 'auto-flush' on or off.
     * <p>
     * When enabled (default), {@link Put} operations don't get buffered/delayed
     * and are immediately executed. Failed operations are not retried. This is
     * slower but safer.
     * <p>
     * Turning off {@link #autoFlush} means that multiple {@link Put}s will be
     * accepted before any RPC is actually sent to do the write operations. If the
     * application dies before pending writes get flushed to HBase, data will be
     * lost.
     * <p>
     * When you turn {@link #autoFlush} off, you should also consider the
     * {@link #clearBufferOnFail} option. By default, asynchronous {@link Put}
     * requests will be retried on failure until successful. However, this can
     * pollute the writeBuffer and slow down batching performance. Additionally,
     * you may want to issue a number of Put requests and call
     * {@link #flushCommits()} as a barrier. In both use cases, consider setting
     * clearBufferOnFail to true to erase the buffer after {@link #flushCommits()}
     * has been called, regardless of success.
     *
     * @param autoFlush         Whether or not to enable 'auto-flush'.
     * @param clearBufferOnFail Whether to keep Put failures in the writeBuffer
     * @see #flushCommits
     */
    public void setAutoFlush(boolean autoFlush, boolean clearBufferOnFail) {
        this.autoFlush = autoFlush;
        this.clearBufferOnFail = autoFlush || clearBufferOnFail;
    }

    /**
     * Returns the maximum size in bytes of the write buffer for this HTable.
     * <p>
     * The default value comes from the configuration parameter
     * {@code hbase.client.write.buffer}.
     *
     * @return The size of the write buffer in bytes.
     */
    public long getWriteBufferSize() {
        return writeBufferSize;
    }

    /**
     * Sets the size of the buffer in bytes.
     * <p>
     * If the new size is less than the current amount of data in the
     * write buffer, the buffer gets flushed.
     *
     * @param writeBufferSize The new write buffer size, in bytes.
     * @throws IOException if a remote or network exception occurs.
     */
    public void setWriteBufferSize(long writeBufferSize) throws IOException {
        this.writeBufferSize = writeBufferSize;
        if (currentWriteBufferSize > writeBufferSize) {
            flushCommits();
        }
    }

    public void setOperationTimeout(int operationTimeout) {
        this.operationTimeout = operationTimeout;
        this.operationExecuteInPool = this.configuration.getBoolean(
            HBASE_CLIENT_OPERATION_EXECUTE_IN_POOL,
            (this.operationTimeout != HConstants.DEFAULT_HBASE_CLIENT_OPERATION_TIMEOUT));
    }

    public void setRuntimeBatchExecutor(ExecutorService runtimeBatchExecutor) {
        this.obTableClient.setRuntimeBatchExecutor(runtimeBatchExecutor);
    }

    <T> T executeServerCallable(final ServerCallable<T> serverCallable) throws IOException {

        if (!this.operationExecuteInPool) {
            return serverCallable.withRetries();
        }

        long startTime = System.currentTimeMillis();
        Future<T> future = this.executePool.submit(serverCallable);
        try {
            return future.get(this.operationTimeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            future.cancel(true);
            throw new IOException("Interrupted");
        } catch (ExecutionException e) {
            if (e.getCause() != null && e.getCause() instanceof IOException) {
                IOException ie = ((IOException) e.getCause());
                // In order to show the stack of this call, add a exception to the
                // root cause
                Throwable rootCause = ie;
                while (rootCause.getCause() != null) {
                    rootCause = rootCause.getCause();
                }
                throw ie;
            }
            throw new IOException(e);
        } catch (TimeoutException e) {
            future.cancel(true);
            String hostPort = "unknown";
            String regionEncodedName = "unknown";
            throw new OperationTimeoutException("Failed executing operation for table '"
                                                + Bytes.toString(this.tableName) + "' on server "
                                                + hostPort + ",region=" + regionEncodedName
                                                + ",operationTimeout=" + this.operationTimeout
                                                + ",waitTime="
                                                + (System.currentTimeMillis() - startTime));
        }
    }

    private String getTargetTableName(String tableNameString, String familyString) {
        checkArgument(tableNameString != null, "tableNameString is null");
        checkArgument(familyString != null, "familyString is null");
        if (configuration.getBoolean(HBASE_HTABLE_TEST_LOAD_ENABLE, false)) {
            return getTestLoadTargetTableName(tableNameString, familyString);
        }
        return getNormalTargetTableName(tableNameString, familyString);
    }

    private String getNormalTargetTableName(String tableNameString, String familyString) {
        return tableNameString + "$" + familyString;
    }

    private String getTestLoadTargetTableName(String tableNameString, String familyString) {
        String suffix = configuration.get(HBASE_HTABLE_TEST_LOAD_SUFFIX,
            DEFAULT_HBASE_HTABLE_TEST_LOAD_SUFFIX);
        return tableNameString + suffix + "$" + familyString;
    }

    private ObHTableFilter buildObHTableFilter(Filter filter, TimeRange timeRange, int maxVersion,
                                               Collection<byte[]> columnQualifiers) {
        ObHTableFilter obHTableFilter = new ObHTableFilter();

        if (filter != null) {
            obHTableFilter.setFilterString(HBaseFilterUtils.toParseableString(filter));
        }

        if (timeRange != null) {
            obHTableFilter.setMaxStamp(timeRange.getMax());
            obHTableFilter.setMinStamp(timeRange.getMin());
        }

        obHTableFilter.setMaxVersions(maxVersion);

        if (columnQualifiers != null) {
            for (byte[] columnQualifier : columnQualifiers) {
                if (columnQualifier == null) {
                    obHTableFilter.addSelectColumnQualifier(new byte[0]);
                } else {
                    obHTableFilter.addSelectColumnQualifier(columnQualifier);
                }
            }
        }
        return obHTableFilter;
    }

    private String buildCheckAndMutateFilterString(byte[] family, byte[] qualifier, byte[] value) {
        if (value != null) {
            return ("CheckAndMutateFilter(=, 'binary:" + Bytes.toString(value) + "', '"
                    + Bytes.toString(family) + "', '"
                    + (qualifier == null ? "" : Bytes.toString(qualifier)) + "', false)");
        } else {
            return ("CheckAndMutateFilter(=, 'binary:', '" + Bytes.toString(family) + "', '"
                    + (qualifier == null ? "" : Bytes.toString(qualifier)) + "', true)");
        }
    }

    private ObHTableFilter buildObHTableFilter(String filterString, TimeRange timeRange,
                                               int maxVersion, byte[]... columnQualifiers) {
        ObHTableFilter obHTableFilter = new ObHTableFilter();

        if (filterString != null) {
            obHTableFilter.setFilterString(filterString);
        }

        if (timeRange != null) {
            obHTableFilter.setMaxStamp(timeRange.getMax());
            obHTableFilter.setMinStamp(timeRange.getMin());
        }

        obHTableFilter.setMaxVersions(maxVersion);

        if (columnQualifiers != null) {
            for (byte[] columnQualifier : columnQualifiers) {
                if (columnQualifier == null) {
                    obHTableFilter.addSelectColumnQualifier(new byte[0]);
                } else {
                    obHTableFilter.addSelectColumnQualifier(columnQualifier);
                }
            }
        }
        return obHTableFilter;
    }

    private ObTableQuery buildObTableQuery(ObHTableFilter filter, byte[] start,
                                           boolean includeStart, byte[] stop, boolean includeStop,
                                           int batchSize) {
        ObNewRange obNewRange = new ObNewRange();

        if (includeStart) {
            obNewRange.setStartKey(ObRowKey.getInstance(start, ObObj.getMin(), ObObj.getMin()));
        } else {
            obNewRange.setStartKey(ObRowKey.getInstance(start, ObObj.getMax(), ObObj.getMax()));
        }

        if (Arrays.equals(stop, HConstants.EMPTY_START_ROW)) {
            obNewRange.setEndKey(ObRowKey.getInstance(ObObj.getMax(), ObObj.getMax(),
                ObObj.getMax()));
        } else if (includeStop) {
            obNewRange.setEndKey(ObRowKey.getInstance(stop, ObObj.getMax(), ObObj.getMax()));
        } else {
            obNewRange.setEndKey(ObRowKey.getInstance(stop, ObObj.getMin(), ObObj.getMin()));
        }

        return buildObTableQuery(filter, obNewRange, batchSize);
    }

    private ObTableQuery buildObTableQuery(ObHTableFilter filter, ObNewRange obNewRange,
                                           int batchSize) {
        ObTableQuery obTableQuery = new ObTableQuery();
        obTableQuery.setIndexName("PRIMARY");
        obTableQuery.sethTableFilter(filter);
        for (String column : ALL_COLUMNS) {
            obTableQuery.addSelectColumn(column);
        }
        if (obNewRange != null) {
            obTableQuery.addKeyRange(obNewRange);
        }
        if (batchSize > 0) {
            obTableQuery.setBatchSize(batchSize);
        }
        return obTableQuery;
    }

    private ObTableBatchOperation buildObTableBatchOperation(List<KeyValue> keyValueList,
                                                             boolean putToAppend,
                                                             List<byte[]> qualifiers) {
        ObTableBatchOperation batch = new ObTableBatchOperation();
        for (KeyValue kv : keyValueList) {
            if (qualifiers != null) {
                qualifiers.add(kv.getQualifier());
            }
            batch.addTableOperation(buildObTableOperation(kv, putToAppend));
        }
        batch.setSameType(true);
        batch.setSamePropertiesNames(true);
        return batch;
    }

    private ObTableOperation buildObTableOperation(KeyValue kv, boolean putToAppend) {
        KeyValue.Type kvType = KeyValue.Type.codeToType(kv.getType());
        switch (kvType) {
            case Put:
                ObTableOperationType operationType;
                if (putToAppend) {
                    operationType = APPEND;
                } else {
                    operationType = INSERT_OR_UPDATE;
                }
                return getInstance(operationType,
                    new Object[] { kv.getRow(), kv.getQualifier(), kv.getTimestamp() }, V_COLUMNS,
                    new Object[] { kv.getValue() });
            case Delete:
                return getInstance(DEL,
                    new Object[] { kv.getRow(), kv.getQualifier(), kv.getTimestamp() }, null, null);
            case DeleteColumn:
                return getInstance(DEL,
                    new Object[] { kv.getRow(), kv.getQualifier(), -kv.getTimestamp() }, null, null);
            case DeleteFamily:
                return getInstance(DEL, new Object[] { kv.getRow(), null, -kv.getTimestamp() },
                    null, null);
            default:
                throw new IllegalArgumentException("illegal mutation type " + kvType);
        }
    }

    private ObTableQueryRequest buildObTableQueryRequest(ObTableQuery obTableQuery,
                                                         String targetTableName) {
        ObTableQueryRequest request = new ObTableQueryRequest();
        request.setEntityType(ObTableEntityType.HKV);
        request.setTableQuery(obTableQuery);
        request.setTableName(targetTableName);
        return request;
    }

    private ObTableBatchOperationRequest buildObTableBatchOperationRequest(ObTableBatchOperation obTableBatchOperation,
                                                                           String targetTableName) {
        ObTableBatchOperationRequest request = new ObTableBatchOperationRequest();
        request.setTableName(targetTableName);
        request.setReturningAffectedRows(true);
        request.setEntityType(ObTableEntityType.HKV);
        request.setBatchOperation(obTableBatchOperation);
        return request;
    }

    private ObTableQueryAndMutateRequest buildObTableQueryAndMutateRequest(ObTableQuery obTableQuery,
                                                                           ObTableBatchOperation obTableBatchOperation,
                                                                           String targetTableName) {
        ObTableQueryAndMutate queryAndMutate = new ObTableQueryAndMutate();
        queryAndMutate.setTableQuery(obTableQuery);
        queryAndMutate.setMutations(obTableBatchOperation);
        ObTableQueryAndMutateRequest request = new ObTableQueryAndMutateRequest();
        request.setTableName(targetTableName);
        request.setTableQueryAndMutate(queryAndMutate);
        request.setEntityType(ObTableEntityType.HKV);
        return request;
    }

    private void checkFamilyViolation(Collection<byte[]> families) {
        if (families == null || families.size() == 0) {
            throw new FeatureNotSupportedException("family is empty.");
        }

        if (families.size() > 1) {
            throw new FeatureNotSupportedException("multi family is not supported yet.");
        }

        for (byte[] family : families) {
            if (isBlank(Bytes.toString(family))) {
                throw new IllegalArgumentException("family is blank");
            }
        }

    }

    public void refreshTableEntry(String familyString, boolean hasTestLoad) throws Exception {
        this.obTableClient.getOrRefreshTableEntry(
            getNormalTargetTableName(tableNameString, familyString), true, true);
        if (hasTestLoad) {
            this.obTableClient.getOrRefreshTableEntry(
                getTestLoadTargetTableName(tableNameString, familyString), true, true);
        }
    }
}
