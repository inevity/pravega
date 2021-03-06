/**
 * Copyright (c) 2017 Dell Inc., or its subsidiaries. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.pravega.test.system;

import io.pravega.client.stream.ScalingPolicy;
import io.pravega.client.stream.StreamConfiguration;
import io.pravega.client.stream.Transaction;
import io.pravega.client.stream.impl.Controller;
import io.pravega.client.stream.impl.ControllerImpl;
import io.pravega.client.stream.impl.ControllerImplConfig;
import io.pravega.client.stream.impl.StreamImpl;
import io.pravega.client.stream.impl.StreamSegments;
import io.pravega.client.stream.impl.TxnSegments;
import io.pravega.test.system.framework.Environment;
import io.pravega.test.system.framework.SystemTestRunner;
import io.pravega.test.system.framework.services.BookkeeperService;
import io.pravega.test.system.framework.services.PravegaControllerService;
import io.pravega.test.system.framework.services.PravegaSegmentStoreService;
import io.pravega.test.system.framework.services.Service;
import io.pravega.test.system.framework.services.ZookeeperService;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import lombok.extern.slf4j.Slf4j;
import mesosphere.marathon.client.MarathonException;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Controller fail over system test.
 */
@Slf4j
@RunWith(SystemTestRunner.class)
public class ControllerFailoverTest {
    private static final String TEST_CONTROLLER_SERVICE_NAME = "testcontroller";
    private static final ScheduledExecutorService EXECUTOR_SERVICE = Executors.newScheduledThreadPool(5);

    @Environment
    public static void setup() throws InterruptedException, MarathonException, URISyntaxException {

        //1. check if zk is running, if not start it
        Service zkService = new ZookeeperService("zookeeper");
        if (!zkService.isRunning()) {
            zkService.start(true);
        }

        List<URI> zkUris = zkService.getServiceDetails();
        log.debug("zookeeper service details: {}", zkUris);
        //get the zk ip details and pass it to bk, host, controller
        URI zkUri = zkUris.get(0);
        //2, check if bk is running, otherwise start, get the zk ip
        Service bkService = new BookkeeperService("bookkeeper", zkUri);
        if (!bkService.isRunning()) {
            bkService.start(true);
        }

        List<URI> bkUris = bkService.getServiceDetails();
        log.debug("bookkeeper service details: {}", bkUris);

        //3. start controller
        Service controllerService = new PravegaControllerService("controller", zkUri);
        if (!controllerService.isRunning()) {
            controllerService.start(true);
        }

        //4. start test controller instances
        Service testControllerService = new PravegaControllerService(TEST_CONTROLLER_SERVICE_NAME, zkUri);
        if (!testControllerService.isRunning()) {
            testControllerService.start(true);
        }

        List<URI> conUris = controllerService.getServiceDetails();
        log.debug("Pravega Controller service instance details: {}", conUris);

        List<URI> testConUris = testControllerService.getServiceDetails();
        log.debug("Pravega test Controller service instance details: {}", testConUris);

        //4.start host
        Service segService = new PravegaSegmentStoreService("segmentstore", zkUri, conUris.get(0));
        if (!segService.isRunning()) {
            segService.start(true);
        }

        List<URI> segUris = segService.getServiceDetails();
        log.debug("pravega host service details: {}", segUris);
    }

    private static URI getTestControllerServiceURI() {
        Service controllerService = new PravegaControllerService(TEST_CONTROLLER_SERVICE_NAME, null);
        List<URI> ctlURIs = controllerService.getServiceDetails();
        return ctlURIs.get(0);
    }

    private static URI getControllerURI() {
        Service controllerService = new PravegaControllerService("controller", null);
        List<URI> ctlURIs = controllerService.getServiceDetails();
        return ctlURIs.get(0);
    }


    private static void stopTestControllerService() {
        log.info("Stopping test controller service");
        Service controllerService = new PravegaControllerService(TEST_CONTROLLER_SERVICE_NAME, null);
        controllerService.stop();
    }

    @Test(timeout = 180000)
    public void failoverTest() throws URISyntaxException, InterruptedException {
        String scope = "testFailoverScope" + RandomStringUtils.randomAlphabetic(5);
        String stream = "testFailoverStream" + RandomStringUtils.randomAlphabetic(5);
        int initialSegments = 2;
        List<Integer> segmentsToSeal = Collections.singletonList(0);
        Map<Double, Double> newRangesToCreate = new HashMap<>();
        newRangesToCreate.put(0.0, 0.25);
        newRangesToCreate.put(0.25, 0.5);
        long lease = 29000;
        long maxExecutionTime = 60000;
        long scaleGracePeriod = 30000;

        // Connect with first controller instance.
        URI controllerUri = getTestControllerServiceURI();
        final Controller controller1 = new ControllerImpl(controllerUri,
                ControllerImplConfig.builder().build(), EXECUTOR_SERVICE);

        // Create scope, stream, and a transaction with high timeout value.
        controller1.createScope(scope).join();
        log.info("Scope {} created successfully", scope);

        createStream(controller1, scope, stream, ScalingPolicy.fixed(initialSegments));
        log.info("Stream {}/{} created successfully", scope, stream);

        long txnCreationTimestamp = System.nanoTime();
        StreamImpl stream1 = new StreamImpl(scope, stream);
        TxnSegments txnSegments = controller1.createTransaction(
                stream1, lease, maxExecutionTime, scaleGracePeriod).join();
        log.info("Transaction {} created successfully, beginTime={}", txnSegments.getTxnId(), txnCreationTimestamp);

        // Initiate scale operation. It will block until ongoing transaction is complete.
        controller1.startScale(stream1, segmentsToSeal, newRangesToCreate).join();

        // Ensure that scale is not yet done.
        boolean scaleStatus = controller1.checkScaleStatus(stream1, 0).join();
        log.info("Status of scale operation isDone={}", scaleStatus);
        Assert.assertTrue(!scaleStatus);

        // Now stop the controller instance executing scale operation.
        stopTestControllerService();
        log.info("Successfully stopped test controller service");

        // Connect to another controller instance.
        controllerUri = getControllerURI();
        final Controller controller2 = new ControllerImpl(controllerUri,
                ControllerImplConfig.builder().build(), EXECUTOR_SERVICE);

        // Fetch status of transaction.
        log.info("Fetching status of transaction {}, time elapsed since its creation={}",
                txnSegments.getTxnId(), System.nanoTime() - txnCreationTimestamp);
        Transaction.Status status = controller2.checkTransactionStatus(stream1,
                txnSegments.getTxnId()).join();
        log.info("Transaction {} status={}", txnSegments.getTxnId(), status);

        if (status == Transaction.Status.OPEN) {
            // Abort the ongoing transaction.
            log.info("Trying to abort transaction {}, by sending request to controller at {}", txnSegments.getTxnId(),
                    controllerUri);
            controller2.abortTransaction(stream1, txnSegments.getTxnId()).join();
        }

        // Scale operation should now complete on the second controller instance.
        // Note: if scale does not complete within desired time, test will timeout. 
        while (!scaleStatus) {
            scaleStatus = controller2.checkScaleStatus(stream1, 0).join();
            Thread.sleep(30000);
        }

        // Ensure that the stream has 3 segments now.
        log.info("Checking whether scale operation succeeded by fetching current segments");
        StreamSegments streamSegments = controller2.getCurrentSegments(scope, stream).join();
        log.info("Current segment count=", streamSegments.getSegments().size());
        Assert.assertEquals(initialSegments - segmentsToSeal.size() + newRangesToCreate.size(),
                streamSegments.getSegments().size());
    }

    private void createStream(Controller controller, String scope, String stream, ScalingPolicy scalingPolicy) {
        StreamConfiguration config = StreamConfiguration.builder()
                .scope(scope)
                .streamName(stream)
                .scalingPolicy(scalingPolicy)
                .build();
        controller.createStream(config).join();
    }
}
