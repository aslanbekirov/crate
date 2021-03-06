/*
 * Licensed to CRATE Technology GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.blob.recovery;

import io.crate.blob.BlobContainer;
import io.crate.blob.BlobTransferTarget;
import io.crate.blob.v2.BlobIndices;
import io.crate.blob.v2.BlobShard;
import io.crate.common.Hex;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.common.StopWatch;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.index.shard.IndexShardClosedException;
import org.elasticsearch.index.shard.IndexShardState;
import org.elasticsearch.indices.recovery.*;
import org.elasticsearch.transport.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class BlobRecoveryHandler {

    private static final ESLogger logger = Loggers.getLogger(BlobRecoveryHandler.class);
    private final StartRecoveryRequest request;
    private final TransportService transportService;
    private final BlobShard blobShard;
    private final RecoverySettings recoverySettings;
    private final IndexShard shard;
    private final BlobTransferTarget blobTransferTarget;
    private final int GET_HEAD_TIMEOUT;

    public BlobRecoveryHandler(TransportService transportService,
                               RecoverySettings recoverySettings,
                               BlobTransferTarget blobTransferTarget,
                               BlobIndices blobIndices,
                               IndexShard shard, StartRecoveryRequest request)
    {
        this.recoverySettings = recoverySettings;
        this.blobShard = blobIndices.blobShardSafe(request.shardId().index().name(), request.shardId().id());
        this.request = request;
        this.transportService = transportService;
        this.blobTransferTarget = blobTransferTarget;
        this.shard = shard;
        String property = System.getProperty("tests.short_timeouts");
        if (property == null) {
            GET_HEAD_TIMEOUT = 30;
        } else {
            GET_HEAD_TIMEOUT = 2;
        }
    }

    private Set<BytesArray> getExistingDigestsFromTarget(byte prefix) {
        BlobStartPrefixResponse response =
            (BlobStartPrefixResponse)transportService.submitRequest(
                request.targetNode(),
                BlobRecoveryTarget.Actions.START_PREFIX,
                new BlobStartPrefixSyncRequest(request.recoveryId(), request.shardId(), prefix),
                TransportRequestOptions.options(),
                new FutureTransportResponseHandler<TransportResponse>() {
                    @Override
                    public TransportResponse newInstance() {
                        return new BlobStartPrefixResponse();
                    }
                }
            ).txGet();

        Set<BytesArray> result = new HashSet<BytesArray>();
        for (byte[] digests : response.existingDigests) {
            result.add(new BytesArray(digests));
        }
        return result;
    }

    public void phase1() throws Exception {
        logger.debug("[{}][{}] recovery [phase1] to {}: start",
            request.shardId().index().name(), request.shardId().id(), request.targetNode().getName());
        StopWatch stopWatch = new StopWatch().start();
        blobTransferTarget.startRecovery();
        blobTransferTarget.createActiveTransfersSnapshot();
        sendStartRecoveryRequest();

        final AtomicReference<Exception> lastException = new AtomicReference<Exception>();
        try {
            syncVarFiles(lastException);
        } catch (InterruptedException ex) {
            throw new ElasticsearchException("blob recovery phase1 failed", ex);
        }

        Exception exception = lastException.get();
        if (exception != null) {
            throw exception;
        }

        /**
         * as soon as the recovery starts the target node will receive PutChunkReplicaRequests
         * the target node will then request the bytes it is missing from the source node
         * (it is missing bytes from PutChunk/StartBlob requests that happened before the recovery)
         * here we need to block so that the target node has enough time to request the head chunks
         *
         * e.g.
         *      Target Node receives Chunk X with bytes 10-19
         *      Target Node requests bytes 0-9 from Source Node
         *      Source Node sends bytes 0-9
         *      Source Node sets transferTakenOver
         */

        blobTransferTarget.waitForGetHeadRequests(GET_HEAD_TIMEOUT, TimeUnit.SECONDS);
        blobTransferTarget.createActivePutHeadChunkTransfersSnapshot();

        /**
         * After receiving a getHeadRequest the source node starts to send HeadChunks to the target
         * wait for all PutHeadChunk-Runnables to finish before ending the recovery.
         */
        blobTransferTarget.waitUntilPutHeadChunksAreFinished();
        sendFinalizeRecoveryRequest();

        blobTransferTarget.stopRecovery();
        stopWatch.stop();
        logger.debug("[{}][{}] recovery [phase1] to {}: took [{}]",
            request.shardId().index().name(), request.shardId().id(), request.targetNode().getName(),
            stopWatch.totalTime());
    }

    public void phase2() throws ElasticsearchException {
    }

    private void syncVarFiles(AtomicReference<Exception> lastException) throws InterruptedException {

        for (byte prefix : BlobContainer.PREFIXES) {
            // byte[1] and byte[1] have different hashCodes
            // so setA.removeAll(setB) wouldn't work with byte[], that's why BytesArray is used here
            Set<BytesArray> remoteDigests = getExistingDigestsFromTarget(prefix);
            Set<BytesArray> localDigests = new HashSet<BytesArray>();
            for (byte[] digest : blobShard.currentDigests(prefix)) {
                localDigests.add(new BytesArray(digest));
            }

            Set<BytesArray> localButNotRemoteDigests = new HashSet<BytesArray>(localDigests);
            localButNotRemoteDigests.removeAll(remoteDigests);

            final CountDownLatch latch = new CountDownLatch(localButNotRemoteDigests.size());
            for (BytesArray digestBytes : localButNotRemoteDigests) {
                final String digest = Hex.encodeHexString(digestBytes.toBytes());
                logger.trace("[{}][{}] start to transfer file var/{} to {}",
                    request.shardId().index().name(), request.shardId().id(), digest,
                    request.targetNode().getName());

                recoverySettings.concurrentStreamPool().execute(
                    new TransferFileRunnable(blobShard.blobContainer().getFile(digest),
                        lastException, latch)
                );
            }
            latch.await();

            remoteDigests.removeAll(localDigests);
            if (!remoteDigests.isEmpty()) {
                deleteFilesRequest(remoteDigests.toArray(new BytesArray[remoteDigests.size()]));
            }
        }
    }

    private void deleteFilesRequest(BytesArray[] digests) {
        transportService.submitRequest(
            request.targetNode(),
            BlobRecoveryTarget.Actions.DELETE_FILE,
            new BlobRecoveryDeleteRequest(request.recoveryId(), digests),
            TransportRequestOptions.options(),
            EmptyTransportResponseHandler.INSTANCE_SAME
        ).txGet();
    }

    private void sendFinalizeRecoveryRequest() {
        transportService.submitRequest(request.targetNode(),
            BlobRecoveryTarget.Actions.FINALIZE_RECOVERY,
            new BlobFinalizeRecoveryRequest(request.recoveryId()),
            TransportRequestOptions.options(),
            EmptyTransportResponseHandler.INSTANCE_SAME
        ).txGet();
    }

    private void sendStartRecoveryRequest() {
        transportService.submitRequest(request.targetNode(),
            BlobRecoveryTarget.Actions.START_RECOVERY,
            new BlobStartRecoveryRequest(request.recoveryId(), request.shardId()),
            TransportRequestOptions.options(),
            EmptyTransportResponseHandler.INSTANCE_SAME
        ).txGet();
    }

    private class TransferFileRunnable implements Runnable {
        private final AtomicReference<Exception> lastException;
        private final String baseDir;
        private final File file;
        private final CountDownLatch latch;

        public TransferFileRunnable(File filePath, AtomicReference<Exception> lastException,
                                    CountDownLatch latch) {
            this.file = filePath;
            this.lastException = lastException;
            this.latch = latch;
            this.baseDir = blobShard.blobContainer().getBaseDirectory().getAbsolutePath();
        }

        @Override
        public void run() {

            try {
                final int BUFFER_SIZE = 4 * 4096;

                long fileSize = file.length();

                if (fileSize == 0) {
                    logger.warn("[{}][{}] empty file: {}",
                        request.shardId().index().name(), request.shardId().id(), file.getName());
                }

                try (FileInputStream fileStream = new FileInputStream(file)) {
                    String filePath = file.getAbsolutePath();
                    String relPath = filePath.substring(baseDir.length(), filePath.length());
                    byte[] buf = new byte[BUFFER_SIZE];
                    int bytesRead = fileStream.read(buf, 0, BUFFER_SIZE);
                    long bytesReadTotal = 0;
                    BytesArray content = new BytesArray(buf, 0, bytesRead);
                    BlobRecoveryStartTransferRequest startTransferRequest =
                        new BlobRecoveryStartTransferRequest(request.recoveryId(), relPath, content,
                            fileSize
                        );

                    if (bytesRead > 0) {
                        bytesReadTotal += bytesRead;

                        logger.trace("[{}][{}] send BlobRecoveryStartTransferRequest to {} for file {} with size {}",
                            request.shardId().index().name(), request.shardId().id(),
                            request.targetNode().getName(),
                            relPath,
                            fileSize
                        );
                        transportService.submitRequest(
                            request.targetNode(),
                            BlobRecoveryTarget.Actions.START_TRANSFER,
                            startTransferRequest,
                            TransportRequestOptions.options(),
                            EmptyTransportResponseHandler.INSTANCE_SAME
                        ).txGet();

                        boolean isLast = false;
                        boolean sentChunks = false;
                        while ((bytesRead = fileStream.read(buf, 0, BUFFER_SIZE)) > 0) {

                            sentChunks = true;
                            bytesReadTotal += bytesRead;

                            if (shard.state() == IndexShardState.CLOSED) { // check if the shard got closed on us
                                throw new IndexShardClosedException(shard.shardId());
                            }
                            if (bytesReadTotal == fileSize) {
                                isLast = true;
                            }
                            content = new BytesArray(buf, 0, bytesRead);

                            transportService.submitRequest(request.targetNode(),
                                BlobRecoveryTarget.Actions.TRANSFER_CHUNK,
                                new BlobRecoveryChunkRequest(request.recoveryId(),
                                    startTransferRequest.transferId(), content, isLast),
                                TransportRequestOptions.options(),
                                EmptyTransportResponseHandler.INSTANCE_SAME
                            ).txGet();
                        }

                        if (!isLast && sentChunks) {
                            logger.error("Sending isLast because it wasn't sent before for {}", relPath);
                            transportService.submitRequest(request.targetNode(),
                                BlobRecoveryTarget.Actions.TRANSFER_CHUNK,
                                new BlobRecoveryChunkRequest(request.recoveryId(),
                                    startTransferRequest.transferId(), BytesArray.EMPTY, true),
                                TransportRequestOptions.options(),
                                EmptyTransportResponseHandler.INSTANCE_SAME
                            ).txGet();
                        }
                    }

                    logger.trace("[{}][{}] completed to transfer file {} to {}",
                        request.shardId().index().name(), request.shardId().id(), file.getName(),
                        request.targetNode().getName());
                }
            } catch (IOException ex) {
                logger.error("exception while file transfer", ex);
                lastException.set(ex);
            } finally {
                latch.countDown();
            }
        }
    }
}
