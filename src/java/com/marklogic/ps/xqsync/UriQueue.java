/**
 * Copyright (c) 2008 Mark Logic Corporation. All rights reserved.
 */
package com.marklogic.ps.xqsync;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.marklogic.ps.SimpleLogger;
import com.marklogic.ps.timing.TimedEvent;

/**
 * @author Michael Blakeley, michael.blakeley@marklogic.com
 * 
 */
public class UriQueue extends Thread {

    protected static final long SLEEP_MILLIS = 125;

    public static final int MONITOR_UPDATE_COUNT = 10000;

    protected Configuration configuration;

    protected volatile BlockingQueue<String> queue;

    protected TaskFactory factory;

    protected CompletionService<TimedEvent[]> completionService;

    protected boolean active;

    protected ThreadPoolExecutor pool;

    protected SimpleLogger logger;

    protected Monitor monitor;

    /**
     * @param _configuration
     * @param _cs
     * @param _pool
     * @param _factory
     * @param _monitor
     * @param _queue
     */
    public UriQueue(Configuration _configuration,
            CompletionService<TimedEvent[]> _cs,
            ThreadPoolExecutor _pool, TaskFactory _factory,
            Monitor _monitor, BlockingQueue<String> _queue) {
        configuration = _configuration;
        pool = _pool;
        factory = _factory;
        monitor = _monitor;
        queue = _queue;
        completionService = _cs;
        logger = configuration.getLogger();
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.lang.Thread#run()
     */
    public void run() {
        SimpleLogger logger = configuration.getLogger();
        long count = 0;
        int updateCount = MONITOR_UPDATE_COUNT;

        active = true;
        String[] buffer = new String[configuration.getInputBatchSize()];
        int bufferIndex = 0;

        try {
            if (null == factory) {
                throw new SyncException("null factory");
            }
            if (null == completionService) {
                throw new SyncException("null completion service");
            }

            while (null != queue) {
                String uri = null;
                try {
                    uri = queue.poll(SLEEP_MILLIS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    logger.logException("interrupted", e);
                    if (null == uri) {
                        continue;
                    }
                }
                if (null == uri) {
                    if (active) {
                        continue;
                    }
                    // queue is empty
                    break;
                }
                if (0 == count) {
                    logger.fine("took first uri: " + uri);
                }
                logger.fine(count + ": uri = " + uri);
                buffer[bufferIndex] = uri;
                bufferIndex++;

                if (buffer.length == bufferIndex) {
                    logger.fine("submitting " + buffer.length);
                    completionService.submit(factory.newTask(buffer));
                    buffer = new String[buffer.length];
                    bufferIndex = 0;
                }
                count++;

                updateCount--;
                if (updateCount < 1) {
                    updateCount = MONITOR_UPDATE_COUNT;
                    monitor.setTaskCount(count
                            + (null == queue ? 0 : queue.size()));
                }
            }

            // handle any buffered uris
            logger.fine("cleaning up " + bufferIndex);
            if (bufferIndex > 0) {
                for (int i = bufferIndex; i < buffer.length; i++) {
                    buffer[i] = null;
                }
                completionService.submit(factory.newTask(buffer));
            }
            pool.shutdown();

        } catch (SyncException e) {
            // stop the world
            logger.logException("fatal error", e);
            System.exit(1);
        }

        logger.fine("finished queuing " + count + " uris");
    }

    public void shutdown() {
        // graceful shutdown, draining the queue
        logger.info("closing queue");
        active = false;
    }

    /**
     * 
     */
    public void halt() {
        // something bad happened - make sure we exit the loop
        logger.info("halting queue");
        queue = null;
        active = false;
        pool.shutdownNow();
        interrupt();
    }

    /**
     * @param _uri
     */
    public void add(String _uri) {
        queue.add(_uri);
    }

    /**
     * @param _factory
     * @throws InterruptedException
     */
    public void setFactory(TaskFactory _factory)
            throws InterruptedException {
        while (queue.size() > 0) {
            Thread.sleep(SLEEP_MILLIS);
        }
        factory = _factory;
    }

}
