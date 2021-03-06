/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

 

package com.dtstack.flink.sql.side.hbase;

import com.dtstack.flink.sql.enums.ECacheContentType;
import com.dtstack.flink.sql.side.AsyncReqRow;
import com.dtstack.flink.sql.side.FieldInfo;
import com.dtstack.flink.sql.side.JoinInfo;
import com.dtstack.flink.sql.side.SideTableInfo;
import com.dtstack.flink.sql.side.cache.CacheObj;
import com.dtstack.flink.sql.side.hbase.rowkeydealer.AbsRowKeyModeDealer;
import com.dtstack.flink.sql.side.hbase.rowkeydealer.PreRowKeyModeDealerDealer;
import com.dtstack.flink.sql.side.hbase.rowkeydealer.RowKeyEqualModeDealer;
import com.dtstack.flink.sql.side.hbase.table.HbaseSideTableInfo;
import com.google.common.collect.Maps;
import com.stumbleupon.async.Deferred;
import org.apache.calcite.sql.SqlBasicCall;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.flink.api.java.typeutils.RowTypeInfo;
import org.apache.flink.calcite.shaded.com.google.common.collect.Lists;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.async.ResultFuture;
import org.apache.flink.types.Row;
import org.hbase.async.HBaseClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.dtstack.flink.sql.threadFactory.DTThreadFactory;
import java.sql.Timestamp;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Date: 2018/8/21
 * Company: www.dtstack.com
 * @author xuchao
 */

public class HbaseAsyncReqRow extends AsyncReqRow {

    private static final long serialVersionUID = 2098635104857937717L;

    private static final Logger LOG = LoggerFactory.getLogger(HbaseAsyncReqRow.class);

    private static final int HBASE_WORKER_POOL_SIZE = 10;

    private RowKeyBuilder rowKeyBuilder;

    private transient HBaseClient hBaseClient;

    private transient AbsRowKeyModeDealer rowKeyMode;

    private String tableName;

    private String[] colNames;

    private Map<String, String> colRefType;

    public HbaseAsyncReqRow(RowTypeInfo rowTypeInfo, JoinInfo joinInfo, List<FieldInfo> outFieldInfoList, SideTableInfo sideTableInfo) {
        super(rowTypeInfo, joinInfo, outFieldInfoList, sideTableInfo);
    }

    @Override
    public void buildEqualInfo(JoinInfo joinInfo, SideTableInfo sideTableInfo) {
        rowKeyBuilder = new RowKeyBuilder();
        if(sideTableInfo.getPrimaryKeys().size() < 1){
            throw new RuntimeException("Primary key dimension table must be filled");
        }

        HbaseSideTableInfo hbaseSideTableInfo = (HbaseSideTableInfo) sideTableInfo;
        rowKeyBuilder.init(sideTableInfo.getPrimaryKeys().get(0));

        colRefType = Maps.newHashMap();
        for(int i=0; i<hbaseSideTableInfo.getColumnRealNames().length; i++){
            String realColName = hbaseSideTableInfo.getColumnRealNames()[i];
            String colType = hbaseSideTableInfo.getFieldTypes()[i];
            colRefType.put(realColName, colType);
        }

        String sideTableName = joinInfo.getSideTableName();
        SqlNode conditionNode = joinInfo.getCondition();

        List<SqlNode> sqlNodeList = Lists.newArrayList();
        if(conditionNode.getKind() == SqlKind.AND){
            sqlNodeList.addAll(Lists.newArrayList(((SqlBasicCall)conditionNode).getOperands()));
        }else{
            sqlNodeList.add(conditionNode);
        }

        for(SqlNode sqlNode : sqlNodeList){
            dealOneEqualCon(sqlNode, sideTableName);
        }

        tableName = hbaseSideTableInfo.getTableName();
        colNames = hbaseSideTableInfo.getColumnRealNames();
    }

    @Override
    public void open(Configuration parameters) throws Exception {
        HbaseSideTableInfo hbaseSideTableInfo = (HbaseSideTableInfo) sideTableInfo;
        ExecutorService executorService =new ThreadPoolExecutor(HBASE_WORKER_POOL_SIZE, HBASE_WORKER_POOL_SIZE,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),new DTThreadFactory("hbase-aysnc"));
        hBaseClient = new HBaseClient(hbaseSideTableInfo.getHost(), hbaseSideTableInfo.getParent(), executorService);
        try {
            Deferred deferred = hBaseClient.ensureTableExists(tableName)
                    .addCallbacks(arg -> new CheckResult(true, ""), arg -> new CheckResult(false, arg.toString()));

            CheckResult result = (CheckResult) deferred.join();
            if(!result.isConnect()){
                throw new RuntimeException(result.getExceptionMsg());
            }

        } catch (Exception e) {
            throw new RuntimeException("create hbase connection fail:", e);
        }

        if(hbaseSideTableInfo.isPreRowKey()){
            rowKeyMode = new PreRowKeyModeDealerDealer(colRefType, colNames, hBaseClient,
                    openCache(), joinType, outFieldInfoList, inFieldIndex, sideFieldIndex);
        }else{
            rowKeyMode = new RowKeyEqualModeDealer(colRefType, colNames, hBaseClient,
                    openCache(), joinType, outFieldInfoList, inFieldIndex, sideFieldIndex);
        }
    }

    @Override
    public void asyncInvoke(Row input, ResultFuture<Row> resultFuture) throws Exception {
        Map<String, Object> refData = Maps.newHashMap();
        for (int i = 0; i < equalValIndex.size(); i++) {
            Integer conValIndex = equalValIndex.get(i);
            Object equalObj = input.getField(conValIndex);
            if(equalObj == null){
                resultFuture.complete(null);
            }

            refData.put(equalFieldList.get(i), equalObj);
        }

        String rowKeyStr = rowKeyBuilder.getRowKey(refData);

        //get from cache
        if(openCache()){
            CacheObj val = getFromCache(rowKeyStr);
            if(val != null){
                if(ECacheContentType.MissVal == val.getType()){
                    dealMissKey(input, resultFuture);
                    return;
                }else if(ECacheContentType.SingleLine == val.getType()){
                    Row row = fillData(input, val);
                    resultFuture.complete(Collections.singleton(row));
                }else if(ECacheContentType.MultiLine == val.getType()){
                    for(Object one : (List)val.getContent()){
                        Row row = fillData(input, one);
                        resultFuture.complete(Collections.singleton(row));
                    }
                }
                return;
            }
        }

        rowKeyMode.asyncGetData(tableName, rowKeyStr, input, resultFuture, sideCache);
    }

    @Override
    protected Row fillData(Row input, Object sideInput){

        List<Object> sideInputList = (List<Object>) sideInput;
        Row row = new Row(outFieldInfoList.size());
        for(Map.Entry<Integer, Integer> entry : inFieldIndex.entrySet()){
            Object obj = input.getField(entry.getValue());
            if(obj instanceof Timestamp){
                obj = ((Timestamp)obj).getTime();
            }
            row.setField(entry.getKey(), obj);
        }

        for(Map.Entry<Integer, Integer> entry : sideFieldIndex.entrySet()){
            if(sideInputList == null){
                row.setField(entry.getKey(), null);
            }else{
                row.setField(entry.getKey(), sideInputList.get(entry.getValue()));
            }
        }

        return row;
    }

    @Override
    public void close() throws Exception {
        super.close();
        hBaseClient.shutdown();
    }


    class CheckResult{

        private boolean connect;

        private String exceptionMsg;

        CheckResult(boolean connect, String msg){
            this.connect = connect;
            this.exceptionMsg = msg;
        }

        public boolean isConnect() {
            return connect;
        }

        public void setConnect(boolean connect) {
            this.connect = connect;
        }

        public String getExceptionMsg() {
            return exceptionMsg;
        }

        public void setExceptionMsg(String exceptionMsg) {
            this.exceptionMsg = exceptionMsg;
        }
    }
}
