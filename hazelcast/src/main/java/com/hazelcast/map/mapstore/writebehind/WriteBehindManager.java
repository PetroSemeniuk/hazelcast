package com.hazelcast.map.mapstore.writebehind;

import com.hazelcast.map.MapContainer;
import com.hazelcast.map.MapService;
import com.hazelcast.map.PartitionContainer;
import com.hazelcast.map.RecordStore;
import com.hazelcast.map.mapstore.MapDataStore;
import com.hazelcast.map.mapstore.MapDataStores;
import com.hazelcast.map.mapstore.MapStoreManager;
import com.hazelcast.nio.serialization.Data;
import com.hazelcast.spi.ExecutionService;
import com.hazelcast.spi.NodeEngine;
import com.hazelcast.util.Clock;
import com.hazelcast.util.executor.ExecutorType;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Write behind map store manager.
 */
public class WriteBehindManager implements MapStoreManager {

    private static final String EXECUTOR_NAME_PREFIX = "hz:scheduled:mapstore:";

    private static final int EXECUTOR_DEFAULT_QUEUE_CAPACITY = 10000;

    private final ScheduledExecutorService scheduledExecutor;

    private WriteBehindProcessor writeBehindProcessor;

    private StoreWorker storeWorker;

    private MapContainer mapContainer;

    public WriteBehindManager(MapContainer mapContainer) {
        this.mapContainer = mapContainer;
        writeBehindProcessor = createWriteBehindProcessor(mapContainer);
        storeWorker = new StoreWorker(mapContainer, writeBehindProcessor);
        scheduledExecutor = getScheduledExecutorService(mapContainer.getName(), mapContainer.getMapService());
    }

    public void start() {
        scheduledExecutor.scheduleAtFixedRate(storeWorker, 1, 1, TimeUnit.SECONDS);
    }

    // TODO add shutdown test.
    public void stop() {
        scheduledExecutor.shutdown();
    }

    @Override
    public MapDataStore getMapDataStore(int partitionId) {
        return MapDataStores.createWriteBehindStore(mapContainer, partitionId, writeBehindProcessor);
    }

    private WriteBehindProcessor createWriteBehindProcessor(final MapContainer mapContainer) {
        final MapService mapService = mapContainer.getMapService();
        final WriteBehindProcessor writeBehindProcessor
                = WriteBehindProcessors.createWriteBehindProcessor(mapContainer);
        writeBehindProcessor.addStoreListener(new StoreListener<DelayedEntry>() {
            @Override
            public void beforeStore(StoreEvent<DelayedEntry> storeEvent) {

            }

            @Override
            public void afterStore(StoreEvent<DelayedEntry> storeEvent) {
                final DelayedEntry delayedEntry = storeEvent.getSource();
                final Object value = delayedEntry.getValue();
                // only process store delete operations.
                if (value != null) {
                    return;
                }
                final Data key = (Data) storeEvent.getSource().getKey();
                final int partitionId = delayedEntry.getPartitionId();
                final PartitionContainer partitionContainer = mapService.getPartitionContainer(partitionId);
                final RecordStore recordStore = partitionContainer.getExistingRecordStore(mapContainer.getName());
                if (recordStore != null) {
                    recordStore.getMapDataStore().addTransient(key, Clock.currentTimeMillis());
                }
            }
        });
        return writeBehindProcessor;
    }


    private ScheduledExecutorService getScheduledExecutorService(String mapName, MapService mapService) {
        final NodeEngine nodeEngine = mapService.getNodeEngine();
        final ExecutionService executionService = nodeEngine.getExecutionService();
        final String executorName = EXECUTOR_NAME_PREFIX + mapName;
        executionService.register(executorName, 1, EXECUTOR_DEFAULT_QUEUE_CAPACITY, ExecutorType.CACHED);
        return executionService.getScheduledExecutor(executorName);
    }
}
