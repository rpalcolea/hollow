/*
 *
 *  Copyright 2017 Netflix, Inc.
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */
package com.netflix.hollow.api.producer;

import com.netflix.hollow.api.consumer.HollowConsumer.BlobRetriever;
import com.netflix.hollow.core.util.SimultaneousExecutor;
import com.netflix.hollow.core.write.objectmapper.RecordPrimaryKey;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 
 * Warning: This is a BETA API and is subject to breaking changes.
 * 
 */
public class HollowIncrementalProducer {
    
    private static final Object DELETE_RECORD = new Object();

    private final HollowProducer producer;
    private final ConcurrentHashMap<RecordPrimaryKey, Object> mutations;
    private final HollowProducer.Populator populator;
    private final double threadsPerCpu;

    public HollowIncrementalProducer(HollowProducer producer) {
        this(producer, 1.0d);
    }

    public HollowIncrementalProducer(HollowProducer producer, double threadsPerCpu) {
        this.producer = producer;
        this.mutations = new ConcurrentHashMap<RecordPrimaryKey, Object>();
        this.populator = new HollowIncrementalCyclePopulator(mutations, threadsPerCpu);
        this.threadsPerCpu = threadsPerCpu;
    }

    public void restore(long versionDesired, BlobRetriever blobRetriever) {
        producer.hardRestore(versionDesired, blobRetriever);
    }
    
    public void addOrModify(Object obj) {
        RecordPrimaryKey pk = extractRecordPrimaryKey(obj);
        mutations.put(pk, obj);
    }

    public void addOrModify(Collection<Object> objList) {
        for(Object obj : objList) {
            addOrModify(obj);
        }
    }

    public void addOrModifyInParallel(Collection<Object> objList) {
        executeInParallel(objList, new Callback() {
            @Override
            public void call(Object obj) {
                addOrModify(obj);
            }
        });
    }

    public void delete(Object obj) {
        RecordPrimaryKey pk = extractRecordPrimaryKey(obj);
        delete(pk);
    }

    public void delete(Collection<Object> objList) {
        for(Object obj : objList) {
            delete(obj);
        }
    }

    public void deleteInParallel(Collection<Object> objList) {
        executeInParallel(objList, new Callback() {
            @Override
            public void call(Object obj) {
                delete(obj);
            }
        });
    }

    public void discard(Object obj) {
        RecordPrimaryKey pk = extractRecordPrimaryKey(obj);
        discard(pk);
    }

    public void discard(Collection<Object> objList) {
        for(Object obj : objList) {
            discard(obj);
        }
    }

    public void discardInParallel(Collection<Object> objList) {
        executeInParallel(objList, new Callback() {
            @Override
            public void call(Object obj) {
                discard(obj);
            }
        });
    }
    
    public void delete(RecordPrimaryKey key) {
        mutations.put(key, DELETE_RECORD);
    }

    public void discard(RecordPrimaryKey key) {
        mutations.remove(key);
    }

    public void clearChanges() {
        this.mutations.clear();
    }

    public boolean hasChanges() { return this.mutations.size() > 0; }

    /**
     * Runs a Hollow Cycle, if successful, cleans the mutations map.
     * @since 2.9.9
     * @return
     */
    public long runCycle() {
        long version = producer.runCycle(populator);
        clearChanges();
        return version;
    }

    private RecordPrimaryKey extractRecordPrimaryKey(Object obj) {
        return producer.getObjectMapper().extractPrimaryKey(obj);
    }

    /**
     * Parallel execution. Modifies the mutation ConcurrentHashMap in parallel based on a Callback.
     * <p>
     * Note: This could be replaced with Java 8 parallelStream and lambadas instead of Callback interface
     * </p>
     * @param objList
     * @param callback
     */
    private void executeInParallel(Collection<Object> objList, final Callback callback) {
        SimultaneousExecutor executor = new SimultaneousExecutor(threadsPerCpu);
        for(final Object obj : objList) {
            executor.execute(new Runnable() {
                public void run() {
                    callback.call(obj);
                }
            });
        }

        try {
            executor.awaitSuccessfulCompletion();
        } catch(Exception e) {
            throw new RuntimeException(e);
        }
    }

    private interface Callback {
        void call(Object obj);
    }
}
