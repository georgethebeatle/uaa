/*
 * ****************************************************************************
 *     Cloud Foundry
 *     Copyright (c) [2009-2017] Pivotal Software, Inc. All Rights Reserved.
 *
 *     This product is licensed to you under the Apache License, Version 2.0 (the "License").
 *     You may not use this product except in compliance with the License.
 *
 *     This product includes a number of subcomponents with
 *     separate copyright notices and license terms. Your use of these
 *     subcomponents is subject to the terms and conditions of the
 *     subcomponent's license, as noted in the LICENSE file.
 * ****************************************************************************
 */

package org.cloudfoundry.identity.uaa.metrics;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.cloudfoundry.identity.uaa.metrics.MetricsUtil.MutableDouble;
import org.cloudfoundry.identity.uaa.metrics.MetricsUtil.MutableLong;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL;
import static java.util.Optional.ofNullable;
import static org.cloudfoundry.identity.uaa.metrics.MetricsUtil.addAverages;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(NON_NULL)
public class MetricsQueue  {

    public static final int MAX_ENTRIES = 5;
    public static final int MAX_TIME = 3000;

    private ConcurrentLinkedDeque<RequestMetric> queue;
    private Map<Integer, RequestMetricSummary> statistics;

    public MetricsQueue() {
        this(null,null);
    }

    @JsonCreator
    public MetricsQueue(@JsonProperty("lastRequests") ConcurrentLinkedDeque<RequestMetric> queue,
                        @JsonProperty("detailed") Map<Integer, RequestMetricSummary> statistics) {
        this.queue = ofNullable(queue).orElse(new ConcurrentLinkedDeque<>());
        this.statistics = ofNullable(statistics).orElse(new ConcurrentHashMap<>());
    }

    public boolean offer(RequestMetric metric) {
        queue.offer(metric);
        //remove eariest entries
        while (queue.size() > MAX_ENTRIES) {
            queue.removeLast();
        }

        Integer statusCode = metric.getStatusCode();
        if (!statistics.containsKey(statusCode)) {
            statistics.putIfAbsent(statusCode, new RequestMetricSummary());
        }
        RequestMetricSummary totals = statistics.get(statusCode);
        totals.add(metric.getRequestCompleteTime()- metric.getRequestStartTime(),
                   metric.getNrOfDatabaseQueries(),
                   metric.getDatabaseQueryTime(),
                   metric.getQueries().stream().filter(q -> !q.isSuccess()).count(),
                   metric.getQueries().stream().filter(q -> !q.isSuccess()).mapToLong(q -> q.getRequestCompleteTime()-q.getRequestStartTime()).sum()
        );
        return true;
    }

    public Map<Integer, RequestMetricSummary> getDetailed() {
        return statistics;
    }


    public ConcurrentLinkedDeque<RequestMetric> getLastRequests() {
        return queue;
    }

    @JsonProperty("summary")
    public RequestMetricSummary getTotals() {
        MutableLong count = new MutableLong(0);
        MutableDouble averageTime = new MutableDouble(0);
        MutableLong intolerableCount = new MutableLong(0);
        MutableDouble averageIntolerableTime = new MutableDouble(0);
        MutableLong databaseQueryCount = new MutableLong(0);
        MutableDouble averageDatabaseQueryTime = new MutableDouble(0);
        MutableLong databaseFailedQueryCount = new MutableLong(0);
        MutableDouble averageDatabaseFailedQueryTime = new MutableDouble(0);
        statistics.entrySet().stream().forEach( s -> {
            RequestMetricSummary summary = s.getValue();
            averageTime.set(addAverages(count.get(),
                                        averageTime.get(),
                                        summary.getCount(),
                                        summary.getAverageTime())
            );
            count.add(summary.getCount());

            averageIntolerableTime.set(addAverages(intolerableCount.get(),
                                                   averageIntolerableTime.get(),
                                                   summary.getIntolerableCount(),
                                                   summary.getAverageIntolerableTime())
            );
            intolerableCount.add(summary.getIntolerableCount());

            averageDatabaseQueryTime.set(addAverages(databaseQueryCount.get(),
                                                     averageDatabaseQueryTime.get(),
                                                     summary.getDatabaseQueryCount(),
                                                     summary.getAverageDatabaseQueryTime()
                                                     )
            );
            databaseQueryCount.add(summary.getDatabaseQueryCount());

            averageDatabaseFailedQueryTime.set(addAverages(databaseFailedQueryCount.get(),
                                                           averageDatabaseFailedQueryTime.get(),
                                                     summary.getDatabaseFailedQueryCount(),
                                                     summary.getAverageDatabaseFailedQueryTime()
                                         )
            );
            databaseFailedQueryCount.add(summary.getDatabaseFailedQueryCount());

        });
        return new RequestMetricSummary(count.get(),
                                        averageTime.get(),
                                        intolerableCount.get(),
                                        averageIntolerableTime.get(),
                                        databaseQueryCount.get(),
                                        averageDatabaseQueryTime.get(),
                                        databaseFailedQueryCount.get(),
                                        averageDatabaseFailedQueryTime.get());
    }

}
