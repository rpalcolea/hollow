/*
 *  Copyright 2016-2019 Netflix, Inc.
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
package com.netflix.hollow.core.read.engine.set;

import static com.netflix.hollow.core.HollowConstants.ORDINAL_NONE;

import com.netflix.hollow.core.memory.encoding.FixedLengthElementArray;
import com.netflix.hollow.core.memory.encoding.HashCodes;
import com.netflix.hollow.core.memory.pool.WastefulRecycler;
import com.netflix.hollow.core.read.engine.PopulatedOrdinalListener;
import com.netflix.hollow.core.util.IntMap;
import com.netflix.hollow.core.util.RemovedOrdinalIterator;

/**
 * This class contains the logic for extracting the removed records from an SET type state
 * to produce a historical type state.
 * 
 * Not intended for external consumption.
 */
public class HollowSetDeltaHistoricalStateCreator {

    private final HollowSetTypeDataElements historicalDataElements;

    private final int shardNumberMask;
    private final int shardOrdinalShift;

    private HollowSetTypeReadState typeState;
    private HollowSetTypeDataElements stateEngineDataElements[];
    private RemovedOrdinalIterator iter;
    private IntMap ordinalMapping;
    private int nextOrdinal;
    private long nextStartBucket;

    public HollowSetDeltaHistoricalStateCreator(HollowSetTypeReadState typeState, boolean reverse) {
        this.typeState = typeState;
        this.stateEngineDataElements = typeState.currentDataElements();
        this.historicalDataElements = new HollowSetTypeDataElements(WastefulRecycler.DEFAULT_INSTANCE);
        this.iter = new RemovedOrdinalIterator(typeState.getListener(PopulatedOrdinalListener.class), reverse);
        this.shardNumberMask = stateEngineDataElements.length - 1;
        this.shardOrdinalShift = 31 - Integer.numberOfLeadingZeros(stateEngineDataElements.length);
    }

    public void populateHistory() {
        populateStats();

        historicalDataElements.setPointerAndSizeData = new FixedLengthElementArray(historicalDataElements.memoryRecycler, ((long)historicalDataElements.maxOrdinal + 1) * historicalDataElements.bitsPerFixedLengthSetPortion);
        historicalDataElements.elementData = new FixedLengthElementArray(historicalDataElements.memoryRecycler, historicalDataElements.totalNumberOfBuckets * historicalDataElements.bitsPerElement);

        iter.reset();

        int ordinal = iter.next();
        while(ordinal != ORDINAL_NONE) {
            ordinalMapping.put(ordinal, nextOrdinal);
            copyRecord(ordinal);

            ordinal = iter.next();
        }
    }

    public void dereferenceTypeState() {
        this.typeState = null;
        this.stateEngineDataElements = null;
        this.iter = null;
    }

    public IntMap getOrdinalMapping() {
        return ordinalMapping;
    }

    public HollowSetTypeReadState createHistoricalTypeReadState() {
        HollowSetTypeReadState historicalTypeState = new HollowSetTypeReadState(typeState.getSchema(), historicalDataElements);
        return historicalTypeState;
    }

    private void populateStats() {
        iter.reset();
        int removedEntryCount = 0;
        int maxSize = 0;
        long totalBucketCount = 0;
        int ordinal = iter.next();

        while(ordinal != ORDINAL_NONE) {
            removedEntryCount++;
            int size = typeState.size(ordinal);
            if(size > maxSize)
                maxSize = size;
            totalBucketCount += HashCodes.hashTableSize(size);
            ordinal = iter.next();
        }

        historicalDataElements.maxOrdinal = removedEntryCount - 1;
        historicalDataElements.bitsPerSetPointer = 64 - Long.numberOfLeadingZeros(totalBucketCount);
        historicalDataElements.bitsPerSetSizeValue = 64 - Long.numberOfLeadingZeros(maxSize);
        historicalDataElements.bitsPerFixedLengthSetPortion = historicalDataElements.bitsPerSetPointer + historicalDataElements.bitsPerSetSizeValue;
        for (int i=0;i<stateEngineDataElements.length;i++) {
            if (stateEngineDataElements[i].bitsPerElement > historicalDataElements.bitsPerElement) {
                historicalDataElements.bitsPerElement = stateEngineDataElements[i].bitsPerElement;
                historicalDataElements.emptyBucketValue = stateEngineDataElements[i].emptyBucketValue;
            }
        }
        historicalDataElements.totalNumberOfBuckets = totalBucketCount;

        ordinalMapping = new IntMap(removedEntryCount);
    }

    private void copyRecord(int ordinal) {
        int shard = ordinal & shardNumberMask;
        int shardOrdinal = ordinal >> shardOrdinalShift; 
        
        long bitsPerBucket = historicalDataElements.bitsPerElement;
        long size = typeState.size(ordinal);

        long fromStartBucket = stateEngineDataElements[shard].getStartBucket(shardOrdinal);
        long fromEndBucket = stateEngineDataElements[shard].getEndBucket(shardOrdinal);
        long numBuckets = fromEndBucket - fromStartBucket;

        historicalDataElements.setPointerAndSizeData.setElementValue((long)nextOrdinal * historicalDataElements.bitsPerFixedLengthSetPortion, historicalDataElements.bitsPerSetPointer, nextStartBucket + numBuckets);
        historicalDataElements.setPointerAndSizeData.setElementValue((long)(nextOrdinal * historicalDataElements.bitsPerFixedLengthSetPortion) + historicalDataElements.bitsPerSetPointer, historicalDataElements.bitsPerSetSizeValue, size);

        historicalDataElements.elementData.copyBits(stateEngineDataElements[shard].elementData, fromStartBucket * bitsPerBucket, nextStartBucket * bitsPerBucket, numBuckets * bitsPerBucket);

        nextOrdinal++;
        nextStartBucket += numBuckets;
    }

}
