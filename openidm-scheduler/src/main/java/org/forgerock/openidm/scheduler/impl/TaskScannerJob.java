/**
* DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
*
* Copyright (c) 2012 ForgeRock AS. All Rights Reserved
*
* The contents of this file are subject to the terms
* of the Common Development and Distribution License
* (the License). You may not use this file except in
* compliance with the License.
*
* You can obtain a copy of the License at
* http://forgerock.org/license/CDDLv1.0.html
* See the License for the specific language governing
* permission and limitations under the License.
*
* When distributing Covered Code, include this CDDL
* Header Notice in each file and include the License file
* at http://forgerock.org/license/CDDLv1.0.html
* If applicable, add the following below the CDDL Header,
* with the fields enclosed by brackets [] replaced by
* your own identifying information:
* "Portions Copyrighted [year] [name of copyright owner]"
*/
package org.forgerock.openidm.scheduler.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.forgerock.json.fluent.JsonPointer;
import org.forgerock.json.fluent.JsonValue;
import org.forgerock.json.fluent.JsonValueException;
import org.forgerock.json.resource.FutureResult;
import org.forgerock.json.resource.PreconditionFailedException;
import org.forgerock.json.resource.QueryRequest;
import org.forgerock.json.resource.QueryResult;
import org.forgerock.json.resource.QueryResultHandler;
import org.forgerock.json.resource.ReadRequest;
import org.forgerock.json.resource.Requests;
import org.forgerock.json.resource.Resource;
import org.forgerock.json.resource.ResourceException;
import org.forgerock.json.resource.ServerContext;
import org.forgerock.json.resource.UpdateRequest;
import org.forgerock.openidm.quartz.impl.ExecutionException;
import org.forgerock.openidm.repo.QueryConstants;
import org.forgerock.openidm.util.ConfigMacroUtil;
import org.forgerock.openidm.util.DateUtil;
import org.forgerock.script.Script;
import org.forgerock.script.ScriptEntry;
import org.forgerock.script.ScriptRegistry;
import org.joda.time.DateTime;
import org.joda.time.ReadablePeriod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.script.ScriptException;

public class TaskScannerJob {
    private final static Logger logger = LoggerFactory.getLogger(TaskScannerJob.class);
    private final static DateUtil DATE_UTIL = DateUtil.getDateUtil("UTC");

    private TaskScannerContext context;
    private ServerContext router;
    private ScriptRegistry scopeFactory;
    private ScriptEntry script;

    public TaskScannerJob(TaskScannerContext context, ServerContext router, ScriptRegistry scopeFactory)
            throws ExecutionException {
        this.context = context;
        this.router = router;
        this.scopeFactory = scopeFactory;

        JsonValue scriptValue = context.getScriptValue();
        if (!scriptValue.isNull()) {
            this.script =  scopeFactory.takeScript(context.getScriptName());//   Scripts.newInstance(context.getScriptName(), scriptValue);
        } else {
            throw new ExecutionException("No valid script '" + scriptValue + "' configured in task scanner.");
        }
    }

    /**
     * Starts the task associated with a task scanner event.
     * This method may run synchronously or launch a new thread depending upon the settings in the TaskScannerContext
     * @return identifier associated with this task scan job
     * @throws ExecutionException
     */
    public String startTask() throws ExecutionException {
        if (context.getWaitForCompletion()) {
            performTask();
        } else {
            // Launch a new thread for the whole taskscan process
            Runnable command = new Runnable() {
                @Override
                public void run() {
                    try {
                        performTask();
                    } catch (Exception ex) {
                        logger.warn("Taskscanner failed with unexpected exception", ex);
                    }
                }
            };
            new Thread(command).start();
            // Shouldn't need to keep ahold of this, I don't think? Can just start it and let it go
        }
        return context.getTaskScanID();
    }

    /**
     * Performs the task associated with the task scanner event.
     * Runs the query and executes the script across each resulting object.
     *
     * @param invokerName name of the invoker
     * @param scriptName name of the script associated with the task scanner event
     * @param params parameters necessary for the execution of the script
     * @throws ExecutionException
     */
    private void performTask()
            throws ExecutionException {
        context.startJob();
        logger.info("Task {} started from {} with script {}",
                new Object[] { context.getTaskScanID(), context.getInvokerName(), context.getScriptName() });

        int numberOfThreads = context.getNumberOfThreads();

        Set<Resource> results;
        context.startQuery();

        //TODO Do a custom result handler and dispatch between threads instead of doing like this!!!

        QueryRequest request = Requests.newQueryRequest(context.getObjectID());
        //Setup the request

        final ExecutorService fullTaskScanExecutor = Executors.newFixedThreadPool(numberOfThreads);
        final ExecutorService executor = Executors.newCachedThreadPool();

        FutureResult<QueryResult> queryResult = router.getConnection().queryAsync(router,request,new QueryResultHandler() {
            @Override
            public void handleError(ResourceException error) {
                //To change body of implemented methods use File | Settings | File Templates.
            }

            @Override
            public boolean handleResource(final Resource resource) {
                executor.submit(new Callable<Object>() {
                    @Override
                    public Object call() throws Exception {
                        performTask();
                        return null;
                    }
                });

                return !context.isCanceled();
            }

            @Override
            public void handleResult(QueryResult result) {
                //To change body of implemented methods use File | Settings | File Templates.
            }
        });


        try {
            results = fetchAllObjects();
        } catch (ResourceException e1) {
            throw new ExecutionException("Error during query", e1);
        }
        context.endQuery();
        Integer maxRecords = context.getMaxRecords();
        if (maxRecords == null) {
            context.setNumberOfTasksToProcess(results.size());
        } else {
            context.setNumberOfTasksToProcess(Math.min(results.size(), maxRecords));
        }
        logger.debug("TaskScan {} query results: {}", context.getInvokerName(), results.size());
        // TODO jump out early if it's empty?

        // Split and prune the result set according to our max and if we're synchronous or not
        List<JsonValue> resultSets = splitResultsOverThreads(results, numberOfThreads, maxRecords);
        logger.debug("Split result set into {} units", resultSets.size());


        List<Callable<Object>> todo = new ArrayList<Callable<Object>>();
        for (final JsonValue result : resultSets) {
            Runnable command = new Runnable() {
                @Override
                public void run() {
                    try {
                        performTaskOverSet(result);
                    } catch (Exception ex) {
                        logger.warn("Taskscanner failed with unexpected exception", ex);
                    }
                }
            };
            todo.add(Executors.callable(command));
        }

        try {
            fullTaskScanExecutor.invokeAll(todo);
        } catch (InterruptedException e) {
            // Mark it interrupted
            context.interrupted();
            logger.warn("Task scan '" + context.getTaskScanID() + "' interrupted");
        }
        // Don't mark the job as completed if its been deactivated
        if (!context.isInactive()) {
            context.endJob();
        }

        logger.info("Task '{}' completed. Total time: {}ms. Query time: {}ms. Progress: {}",
                new Object[] { context.getTaskScanID(),
                context.getStatistics().getJobDuration(),
                context.getStatistics().getQueryDuration(),
                context.getProgress()
        });
    }

//    private List<JsonValue> splitResultsOverThreads(Set<Resource> results, int numberOfThreads, Integer max) {
//        List<List<Object>> resultSets = new ArrayList<List<Object>>();
//        for (int i = 0; i < numberOfThreads; i++) {
//            resultSets.add(new ArrayList<Object>());
//        }
//
//        int i = 0;
//        for (Resource obj : results) {
//            if (max != null && i >= max) {
//                break;
//            }
//            resultSets.get(i % numberOfThreads).add(obj.getObject());
//            i++;
//        }
//
//        List<JsonValue> jsonSets = new ArrayList<JsonValue>();
//        for (List<Object> set : resultSets) {
//            jsonSets.add(new JsonValue(set));
//        }
//
//        return jsonSets;
//    }

    private void performTaskOverSet(Set<Resource> results)
                    throws ExecutionException {
        for (Resource input : results) {
            if (context.isCanceled()) {
                logger.info("Task '" + context.getTaskScanID() + "' cancelled. Terminating execution.");
                break; // Jump out quick since we've cancelled the job
            }
            // Check if this object has a STARTED time already
            JsonValue startTime = input.getContent().get(context.getStartField());
            String startTimeString = null;
            if (startTime != null && !startTime.isNull()) {
                startTimeString = startTime.asString();
                DateTime startedTime = DATE_UTIL.parseTimestamp(startTimeString);

                // Skip if the startTime + interval has not been passed
                ReadablePeriod period = context.getRecoveryTimeout();
                DateTime expirationDate = startedTime.plus(period);
                if (expirationDate.isAfterNow()) {
                    logger.debug("Object already started and has not expired. Started at: {}. Timeout: {}. Expires at: {}",
                            new Object[] {
                            DATE_UTIL.formatDateTime(startedTime),
                            period,
                            DATE_UTIL.formatDateTime(expirationDate)});
                    continue;
                }
            }

            try {
                claimAndExecScript(input, startTimeString);
            } catch (ResourceException e) {
                throw new ExecutionException("Error during claim and execution phase", e);
            }
        }
    }

    /**
     * Flatten a list of parameters and perform a query to fetch all objects from storage
     * @param id the identifier of the resource to query
     * @param params the parameters of the query
     * @return JsonValue containing a list of all the retrieved objects
     * @throws ResourceException
     */
    private Set<Resource> fetchAllObjects() throws ResourceException {
        JsonValue flatParams = flattenJson(context.getScanValue());
        ConfigMacroUtil.expand(flatParams);
        return performQuery(context.getObjectID(), flatParams);
    }

    /**
     * Performs a query on a resource and returns the result set
     * @param resourceID the identifier of the resource to query
     * @param params parameters to supply to the query
     * @return the set of results from the performed query
     * @throws ResourceException
     */
    private Set<Resource> performQuery(String resourceID, JsonValue params) throws ResourceException {

        QueryRequest request = Requests.newQueryRequest(resourceID);
        request.setQueryId(QueryConstants.QUERY_ALL_IDS);
        Set<Resource> results = new HashSet<Resource>();

        QueryResult queryResults =  router.getConnection().query(router, request, results);

        return results;
    }

    /**
     * Adds an object to a JsonValue and performs an update
     * @param resourceID the resource identifier that the updated value belongs to
     * @param value value to perform the update with
     * @param path JsonPointer to the updated/added field
     * @param obj object to add to the field
     * @return the updated JsonValue
     * @throws ResourceException
     */
    private Resource updateValueWithObject(String resourceContainer, Resource value, JsonPointer path, Object obj) throws ResourceException {
        ensureJsonPointerExists(path, value.getContent());
        value.getContent().put(path, obj);
        return performUpdate(resourceContainer, value);
    }

    /**
     * Performs an update on a given resource with a supplied JsonValue
     * @param resourceContainer the resource identifier to perform the update on
     * @param value the object to update with
     * @return the updated object
     * @throws ResourceException
     */
    private Resource performUpdate(String resourceContainer, Resource value) throws ResourceException {
        UpdateRequest request = Requests.newUpdateRequest(resourceContainer,value.getId(),value.getContent());
        request.setRevision(value.getRevision());
        Resource updated = router.getConnection().update(router,request);
        return retrieveObject(resourceContainer, updated.getId());
    }

    /**
     * Constructs a full object ID from the supplied resourceID and the JsonValue
     * @param resourceID resource ID that the value originates from
     * @param value JsonValue to create the full ID with
     * @return string indicating the full id
     */
    private String retrieveFullID(String resourceID, JsonValue value) {
        String id = value.get("_id").required().asString();
        return retrieveFullID(resourceID, id);
    }

    /**
     * Constructs a full object ID from the supplied resourceID and the objectID
     * @param resourceID resource ID that the object originates from
     * @param objectID ID of some object
     * @return string indicating the full ID
     */
    private String retrieveFullID(String resourceID, String objectID) {
        return resourceID + '/' + objectID;
    }

    /**
     * Fetches an updated copy of some specified object from the given resource
     * @param resourceID the resource identifier to fetch an object from
     * @param value the value to retrieve an updated copy of
     * @return the updated value
     * @throws ResourceException
     */
    private Resource retrieveUpdatedObject(String resourceID, JsonValue value)
            throws JsonValueException, ResourceException {
        return retrieveObject(resourceID, value.get("_id").required().asString());
    }

    /**
     * Retrieves a specified object from a resource
     * @param resourceContainer the resource identifier to fetch the object from
     * @param resourceId the identifier of the object to fetch
     * @return the object retrieved from the resource
     * @throws ResourceException
     */
    private Resource retrieveObject(String resourceContainer, String resourceId) throws ResourceException {
        ReadRequest request = Requests.newReadRequest(resourceContainer,resourceId);
        return router.getConnection().read(router,request);
    }

    private void claimAndExecScript(Resource input, String expectedStartDateStr)
            throws ExecutionException, ResourceException {
        String id = input.getId();
        boolean claimedTask = false;
        boolean retryClaimTask = false;

        JsonPointer startField = context.getStartField();
        JsonPointer completedField = context.getCompletedField();
        String resourceID = context.getObjectID();

        Resource _input = input;
        do {
            try {
                retryClaimTask = false;
                _input = updateValueWithObject(resourceID, _input, startField, DATE_UTIL.now());
                logger.debug("Claimed task and updated StartField: {}", _input);
                claimedTask = true;
            } catch (PreconditionFailedException ex) {
                    // If the object changed since we queried, get the latest
                    // and check if it's still in a state we want to process the task.
                    _input = retrieveObject(resourceID, id);
                    String currentStartDateStr = _input.getContent().get(startField).asString();
                    String currentCompletedDateStr = _input.getContent().get(completedField).asString();
                    if (currentCompletedDateStr == null && (currentStartDateStr == null || currentStartDateStr.equals(expectedStartDateStr))) {
                        retryClaimTask = true;
                    } else {
                        // Someone else managed to update the started field first,
                        // claimed the task. Do not execute it here this run.
                        logger.debug("Task for {} {} was already claimed, ignore.", resourceID, id);
                    }
            }
        } while (retryClaimTask && !context.isCanceled());
        if (claimedTask) {
            execScript(_input);
        }
    }

    /**
     * Performs the individual executions of the supplied script
     *
     * Passes <b>"input"</b> and <b>"objectID"</b> to the script.<br>
     *   <b>"objectID"</b> contains the full ID of the supplied object (including resource identifier).
     *      Useful for performing updates.<br>
     *   <b>"input"</b> contains the supplied object
     *
     * @param scriptName name of the script
     * @param invokerName name of the invoker
     * @param script the script to execute
     * @param resourceID the resource identifier for the object that the script will be performed on
     * @param startField JsonPointer to the field that will be marked at script start
     * @param completedField JsonPointer to the field that will be marked at script completion
     * @param input value to input to the script
     * @throws ExecutionException
     * @throws ResourceException
     */
    private void execScript(Resource input)
            throws ExecutionException, ResourceException {
        if (script.isActive()) {
            String resourceID = context.getObjectID();
            Script executeable =  script.getScript(router);
            executeable.put("input", input.getContent().getObject());
            //TODO Fix the scope variables
            executeable.put("objectID", retrieveFullID(resourceID, input.getId()));

            executeable.put("resourceContainer", resourceID );
            executeable.put("resourceId",input.getId());

            try {
                Object returnedValue =  executeable.eval();
                Resource _input = retrieveObject(resourceID, input.getId());
                logger.debug("After script execution: {}", _input);

                if (returnedValue == Boolean.TRUE) {
                   _input = updateValueWithObject(resourceID, _input, context.getCompletedField(), DATE_UTIL.now());
                   context.getStatistics().taskSucceded();
                   logger.debug("Updated CompletedField: {}", _input);
                } else {
                    context.getStatistics().taskFailed();
                }

            } catch (ScriptException se) {
                context.getStatistics().taskFailed();
                String msg = context.getScriptName() + " script invoked by " +
                        context.getInvokerName() + " encountered exception";
                logger.debug(msg, se);
                throw new ExecutionException(msg, se);
            }
        }
    }

    /**
     * Flattens JsonValue into a one-level-deep object
     * @param original original JsonValue object
     * @return flattened JsonValue
     */
    private static JsonValue flattenJson(JsonValue original) {
        return flattenJson("", original);
    }

    /**
     * Flattens JsonValue into a one-level-deep object
     * @param parent name of the parent object (for nested objects)
     * @param original original JsonValue object
     * @return flattened JsonValue
     */
    private static JsonValue flattenJson(String parent, JsonValue original) {
        JsonValue flattened = new JsonValue(new HashMap<String, Object>());
        Iterator<String> iter = original.keys().iterator();
        while (iter.hasNext()) {
            String oKey = iter.next();
            String key = (parent.isEmpty() ? "" : parent + ".") + oKey;

            JsonValue value = original.get(oKey);
            if (value.isMap()) {
                addAllToJson(flattened, flattenJson(key, value));
            } else {
                flattened.put(key, value.getObject());
            }
        }
        return flattened;
    }

    /**
     * Adds all objects from one JsonValue to another (performs a merge).
     * Any values contained in both objects will be overwritten to reflect the values in <b>from</b>
     * <br><br>
     * <i><b>NOTE:</b> this should be a part of JsonValue itself (so we can support merging two JsonValue objects)</i>
     * @param to JsonValue that will have objects added to it
     * @param from JsonValue that will be used as reference for updating
     */
    private static void addAllToJson(JsonValue to, JsonValue from) {
        Iterator<String> iter = from.keys().iterator();
        while (iter.hasNext()) {
            String key = iter.next();
            to.put(key, from.get(key).getObject());
        }
    }

    /**
     * Ensure that some JsonPointer exists within a supplied object so that some object can be placed in that field
     * @param ptr JsonPointer to ensure exists at each level
     * @param obj object to ensure the JsonPointer exists within
     */
    private static void ensureJsonPointerExists(JsonPointer ptr, JsonValue obj) {
        JsonValue refObj = obj;

        for (String p : ptr) {
            if (!refObj.isDefined(p)) {
                refObj.put(p, new JsonValue(new HashMap<String, Object>()));
            }
            refObj = refObj.get(p);
        }
    }
}
