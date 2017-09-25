/*
 * Copyright (c) 2002-2017 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.api.impl.fulltext;

import org.apache.lucene.document.Document;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.neo4j.concurrent.BinaryLatch;
import org.neo4j.graphdb.Entity;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.ResourceIterable;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.event.PropertyEntry;
import org.neo4j.helpers.collection.Pair;
import org.neo4j.kernel.AvailabilityGuard;
import org.neo4j.kernel.api.impl.schema.writer.PartitionedIndexWriter;
import org.neo4j.logging.Log;
import org.neo4j.scheduler.JobScheduler;

import static org.neo4j.kernel.api.impl.fulltext.LuceneFulltextDocumentStructure.documentRepresentingProperties;
import static org.neo4j.kernel.api.impl.fulltext.LuceneFulltextDocumentStructure.newTermForChangeOrRemove;

class FulltextUpdateApplier
{
    private static final FulltextIndexUpdate STOP_SIGNAL = () -> null;
    private static final int POPULATING_BATCH_SIZE = 10_000;
    private static final JobScheduler.Group UPDATE_APPLIER = new JobScheduler.Group( "FulltextIndexUpdateApplier" );
    private static final String APPLIER_THREAD_NAME = "Fulltext Index Add-On Applier Thread";

    private final LinkedBlockingQueue<FulltextIndexUpdate> workQueue;
    private final Log log;
    private final AvailabilityGuard availabilityGuard;
    private final JobScheduler scheduler;
    private JobScheduler.JobHandle workerThread;

    FulltextUpdateApplier( Log log, AvailabilityGuard availabilityGuard, JobScheduler scheduler )
    {
        this.log = log;
        this.availabilityGuard = availabilityGuard;
        this.scheduler = scheduler;
        workQueue = new LinkedBlockingQueue<>();
    }

    <E extends Entity> AsyncFulltextIndexOperation updatePropertyData(
            Map<Long,Map<String,Object>> state, WritableFulltext index ) throws IOException
    {
        Latch completedLatch = new Latch();
        FulltextIndexUpdate update = () ->
        {
            PartitionedIndexWriter indexWriter = index.getIndexWriter();
            for ( Map.Entry<Long,Map<String,Object>> stateEntry : state.entrySet() )
            {
                Set<String> indexedProperties = index.properties();
                if ( !Collections.disjoint( indexedProperties, stateEntry.getValue().keySet() ) )
                {
                    long entityId = stateEntry.getKey();
                    Stream<Map.Entry<String,Object>> entryStream = stateEntry.getValue().entrySet().stream();
                    Predicate<Map.Entry<String,Object>> relevantForIndex =
                            entry -> indexedProperties.contains( entry.getKey() );
                    Map<String,Object> allProperties = entryStream.filter( relevantForIndex ).collect(
                            Collectors.toMap( Map.Entry::getKey, Map.Entry::getValue ) );

                    if ( !allProperties.isEmpty() )
                    {
                        updateDocument( indexWriter, entityId, allProperties );
                    }
                }
            }
            return Pair.of( index, completedLatch );
        };

        enqueueUpdate( update );
        return completedLatch;
    }

    private static void updateDocument(
            PartitionedIndexWriter indexWriter, long entityId, Map<String,Object> properties ) throws IOException
    {
        Document document = documentRepresentingProperties( entityId, properties );
        indexWriter.updateDocument( newTermForChangeOrRemove( entityId ), document );
    }

    <E extends Entity> AsyncFulltextIndexOperation removePropertyData(
            Iterable<PropertyEntry<E>> propertyEntries, Map<Long,Map<String,Object>> state, WritableFulltext index )
            throws IOException
    {
        Latch completedLatch = new Latch();
        FulltextIndexUpdate update = () ->
        {
            for ( PropertyEntry<E> propertyEntry : propertyEntries )
            {
                if ( index.properties().contains( propertyEntry.key() ) )
                {
                    long entityId = propertyEntry.entity().getId();
                    Map<String,Object> allProperties = state.get( entityId );
                    if ( allProperties == null || allProperties.isEmpty() )
                    {
                        index.getIndexWriter().deleteDocuments( newTermForChangeOrRemove( entityId ) );
                    }
                }
            }
            return Pair.of( index, completedLatch );
        };

        enqueueUpdate( update );
        return completedLatch;
    }

    AsyncFulltextIndexOperation writeBarrier() throws IOException
    {
        Latch barrierLatch = new Latch();
        enqueueUpdate( () -> Pair.of( null, barrierLatch ) );
        return barrierLatch;
    }

    AsyncFulltextIndexOperation populateNodes( WritableFulltext index, GraphDatabaseService db ) throws IOException
    {
        return enqueuePopulateIndex( index, db, db::getAllNodes );
    }

    AsyncFulltextIndexOperation populateRelationships( WritableFulltext index, GraphDatabaseService db )
            throws IOException
    {
        return enqueuePopulateIndex( index, db, db::getAllRelationships );
    }

    private AsyncFulltextIndexOperation enqueuePopulateIndex(
            WritableFulltext index, GraphDatabaseService db,
            Supplier<ResourceIterable<? extends Entity>> entitySupplier ) throws IOException
    {
        Latch completedLatch = new Latch();
        FulltextIndexUpdate population = () ->
        {
            PartitionedIndexWriter indexWriter = index.getIndexWriter();
            String[] indexedPropertyKeys = index.properties().toArray( new String[0] );
            ArrayList<Supplier<Document>> documents = new ArrayList<>();
            try ( Transaction ignore = db.beginTx( 1, TimeUnit.DAYS ) )
            {
                ResourceIterable<? extends Entity> entities = entitySupplier.get();
                for ( Entity entity : entities )
                {
                    long entityId = entity.getId();
                    Map<String,Object> properties = entity.getProperties( indexedPropertyKeys );
                    if ( !properties.isEmpty() )
                    {
                        documents.add( documentBuilder( entityId, properties ) );
                    }

                    if ( documents.size() > POPULATING_BATCH_SIZE )
                    {
                        indexWriter.addDocuments( documents.size(), reifyDocuments( documents ) );
                        documents.clear();
                    }
                }
            }
            indexWriter.addDocuments( documents.size(), reifyDocuments( documents ) );
            return Pair.of( index, completedLatch );
        };

        enqueueUpdate( population );
        return completedLatch;
    }

    private Supplier<Document> documentBuilder( long entityId, Map<String,Object> properties )
    {
        return () -> documentRepresentingProperties( entityId, properties );
    }

    private Iterable<Document> reifyDocuments( ArrayList<Supplier<Document>> documents )
    {
        return () -> documents.stream().map( Supplier::get ).iterator();
    }

    private void enqueueUpdate( FulltextIndexUpdate update ) throws IOException
    {
        try
        {
            workQueue.put( update );
        }
        catch ( InterruptedException e )
        {
            throw new IOException( "Fulltext index update failed.", e );
        }
    }

    void start()
    {
        if ( workerThread != null )
        {
            throw new IllegalStateException( APPLIER_THREAD_NAME + " already started." );
        }
        workerThread = scheduler.schedule( UPDATE_APPLIER, new ApplierWorker( workQueue, log, availabilityGuard ) );
    }

    void stop()
    {
        boolean enqueued;
        do
        {
            enqueued = workQueue.offer( STOP_SIGNAL );
        }
        while ( !enqueued );

        try
        {
            workerThread.waitTermination();
            workerThread = null;
        }
        catch ( InterruptedException e )
        {
            log.error( "Interrupted before " + APPLIER_THREAD_NAME + " could shut down.", e );
        }
        catch ( ExecutionException e )
        {
            log.error( "Exception while waiting for " + APPLIER_THREAD_NAME + " to shut down.", e );
        }
    }

    private interface FulltextIndexUpdate
    {
        Pair<WritableFulltext,BinaryLatch> applyUpdateAndReturnIndex() throws IOException;
    }

    private static class ApplierWorker implements Runnable
    {
        private LinkedBlockingQueue<FulltextIndexUpdate> workQueue;
        private final Log log;
        private final AvailabilityGuard availabilityGuard;

        ApplierWorker( LinkedBlockingQueue<FulltextIndexUpdate> workQueue, Log log,
                       AvailabilityGuard availabilityGuard )
        {
            this.workQueue = workQueue;
            this.log = log;
            this.availabilityGuard = availabilityGuard;
        }

        @Override
        public void run()
        {
            Thread.currentThread().setName( APPLIER_THREAD_NAME );
            waitForDatabaseToBeAvailable();
            Set<WritableFulltext> refreshableSet = new HashSet<>();
            List<BinaryLatch> latches = new ArrayList<>();

            FulltextIndexUpdate update;
            while ( (update = getNextUpdate()) != STOP_SIGNAL )
            {
                update = drainQueueAndApplyUpdates( update, refreshableSet, latches );
                refreshAndClearIndexes( refreshableSet );
                releaseAndClearLatches( latches );

                if ( update == STOP_SIGNAL )
                {
                    return;
                }
            }
        }

        private void waitForDatabaseToBeAvailable()
        {
            boolean isAvailable;
            do
            {
                isAvailable = availabilityGuard.isAvailable( 100 );
            }
            while ( !isAvailable );
        }

        private FulltextIndexUpdate drainQueueAndApplyUpdates(
                FulltextIndexUpdate update,
                Set<WritableFulltext> refreshableSet,
                List<BinaryLatch> latches )
        {
            do
            {
                applyUpdate( update, refreshableSet, latches );
                update = workQueue.poll();
            }
            while ( update != null && update != STOP_SIGNAL );
            return update;
        }

        private void refreshAndClearIndexes( Set<WritableFulltext> refreshableSet )
        {
            for ( WritableFulltext index : refreshableSet )
            {
                refreshIndex( index );
            }
            refreshableSet.clear();
        }

        private void releaseAndClearLatches( List<BinaryLatch> latches )
        {
            for ( BinaryLatch latch : latches )
            {
                latch.release();
            }
            latches.clear();
        }

        private FulltextIndexUpdate getNextUpdate()
        {
            FulltextIndexUpdate update = null;
            do
            {
                try
                {
                    update = workQueue.take();
                }
                catch ( InterruptedException e )
                {
                    log.debug( APPLIER_THREAD_NAME + " decided to ignore an interrupt.", e );
                }
            }
            while ( update == null );
            return update;
        }

        private void applyUpdate( FulltextIndexUpdate update,
                                  Set<WritableFulltext> refreshableSet,
                                  List<BinaryLatch> latches )
        {
            try
            {
                Pair<WritableFulltext,BinaryLatch> updateProgress = update.applyUpdateAndReturnIndex();
                refreshableSet.add( updateProgress.first() );
                latches.add( updateProgress.other() );
            }
            catch ( IOException e )
            {
                log.error( "Failed to apply fulltext index update.", e );
            }
        }

        private void refreshIndex( WritableFulltext index )
        {
            try
            {
                if ( index != null )
                {
                    index.maybeRefreshBlocking();
                }
            }
            catch ( IOException e )
            {
                log.error( "Failed to refresh fulltext after updates.", e );
            }
        }
    }

    private static class Latch extends BinaryLatch implements AsyncFulltextIndexOperation
    {
        @Override
        public void awaitCompletion()
        {
            await();
        }
    }
}