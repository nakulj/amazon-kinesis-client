/*
 * Copyright 2019 Amazon.com, Inc. or its affiliates.
 * Licensed under the Apache License, Version 2.0 (the
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

package software.amazon.kinesis.coordinator;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import lombok.Data;
import lombok.NonNull;
import software.amazon.kinesis.annotations.KinesisClientInternalApi;
import software.amazon.kinesis.checkpoint.ShardRecordProcessorCheckpointer;
import software.amazon.kinesis.leases.ShardInfo;
import software.amazon.kinesis.processor.Checkpointer;

/**
 *
 */
@Data
@KinesisClientInternalApi
public class SchedulerCoordinatorFactory implements CoordinatorFactory {
    /**
     * {@inheritDoc}
     */
    @Override
    public ExecutorService createExecutorService() {
        return new SchedulerThreadPoolExecutor(new ThreadFactoryBuilder()
                .setNameFormat("ShardRecordProcessor-%04d")
                .build());
    }

    static class SchedulerThreadPoolExecutor extends ThreadPoolExecutor {
        private static final long DEFAULT_KEEP_ALIVE = 60L;

        SchedulerThreadPoolExecutor(ThreadFactory threadFactory) {
            super(0, Integer.MAX_VALUE, DEFAULT_KEEP_ALIVE, TimeUnit.SECONDS, new SynchronousQueue<>(), threadFactory);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ShardRecordProcessorCheckpointer createRecordProcessorCheckpointer(
            @NonNull final ShardInfo shardInfo, @NonNull final Checkpointer checkpoint) {
        return new ShardRecordProcessorCheckpointer(shardInfo, checkpoint);
    }
}
