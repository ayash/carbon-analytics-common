/*
 * Copyright (c) 2005-2015, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.wso2.carbon.event.processor.manager.core.internal;

import com.hazelcast.core.*;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.event.processor.manager.commons.utils.ByteSerializer;
import org.wso2.carbon.event.processor.manager.core.EventManagementService;
import org.wso2.carbon.event.processor.manager.core.config.HAConfiguration;
import org.wso2.carbon.event.processor.manager.core.internal.ds.EventManagementServiceValueHolder;
import org.wso2.carbon.event.processor.manager.core.internal.thrift.ManagementServiceClientThriftImpl;
import org.wso2.carbon.event.processor.manager.core.internal.util.Constants;

import java.util.ArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;


public class HAManager {
    private static final Log log = LogFactory.getLog(HAManager.class);

    private final HazelcastInstance hazelcastInstance;
    private HAConfiguration haConfiguration;
    private boolean activeLockAcquired;
    private boolean passiveLockAcquired;
    private ILock activeLock;
    private ILock passiveLock;
    private IMap<HAConfiguration, Boolean> members;
    private IMap<String, HAConfiguration> roleToMembershipMap;

    private ScheduledThreadPoolExecutor scheduledThreadPoolExecutor = new ScheduledThreadPoolExecutor(1);
    private Lock writeLock;
    private Future stateChanger = null;
    private String activeId;
    private String passiveId;
    private EventManagementService eventManagementService;

    public HAManager(HazelcastInstance hazelcastInstance, HAConfiguration haConfiguration,
                     Lock writeLock, EventManagementService eventManagementService) {
        this.hazelcastInstance = hazelcastInstance;
        this.writeLock = writeLock;
        this.haConfiguration = haConfiguration;
        activeId = Constants.ACTIVEID;
        passiveId = Constants.PASSIVEID;
        activeLock = hazelcastInstance.getLock(activeId);
        passiveLock = hazelcastInstance.getLock(passiveId);

        members = hazelcastInstance.getMap(Constants.MEMBERS);
        members.set(haConfiguration, true);

        this.eventManagementService = eventManagementService;
        ManagementServer.start(haConfiguration);
        hazelcastInstance.getCluster().addMembershipListener(new MembershipListener() {
            @Override
            public void memberAdded(MembershipEvent membershipEvent) {

            }

            @Override
            public void memberRemoved(MembershipEvent membershipEvent) {
                if (!activeLockAcquired) {
                    tryChangeState();
                }
            }

            @Override
            public void memberAttributeChanged(MemberAttributeEvent memberAttributeEvent) {

            }
        });

        try {
            roleToMembershipMap = hazelcastInstance.getMap(Constants.ROLE_MEMBERSHIP_MAP);
        } catch (Exception e) {
            log.error(e);
        }
        roleToMembershipMap.addEntryListener(new EntryAdapter<String, HAConfiguration>() {

            @Override
            public void entryRemoved(EntryEvent<String, HAConfiguration> stringCEPMembershipEntryEvent) {
                tryChangeState();
            }

        }, activeId, false);

    }


    public void init() {
        tryChangeState();
        if (!activeLockAcquired) {
            scheduledThreadPoolExecutor.execute(new PeriodicStateChanger());
        }
    }

    private void tryChangeState() {
       if (!passiveLockAcquired) {
            if (passiveLock.tryLock()) {
                passiveLockAcquired = true;
                if (activeLock.tryLock()) {
                    activeLockAcquired = true;
                    becomeActive();
                    passiveLockAcquired = false;
                    passiveLock.forceUnlock();


                } else {
                    becomePassive();
                }
            }
        } else if (!activeLockAcquired) {

           if (activeLock.tryLock()) {
                activeLockAcquired = true;
                becomeActive();
                passiveLockAcquired = false;
                passiveLock.forceUnlock();

            }
        }
    }

    private void becomePassive() {
        roleToMembershipMap.set(passiveId, haConfiguration);
        HAConfiguration activeMember = null;

        try {
            activeMember = roleToMembershipMap.get(activeId);
        } catch (Exception e) {
            log.error(e);
        }

        HAConfiguration passiveMember = roleToMembershipMap.get(passiveId);
        // Send non-duplicate events to active member
        eventManagementService.getEventReceiverManagementService().startServer(passiveMember.getTransport());
        eventManagementService.getEventReceiverManagementService().setOtherMember(activeMember.getTransport());
        eventManagementService.getEventReceiverManagementService().start();
        eventManagementService.getEventReceiverManagementService().pause();
        eventManagementService.getEventProcessorManagementService().pause();
        eventManagementService.getEventPublisherManagementService().setDrop(true);
        ManagementServiceClient client = new ManagementServiceClientThriftImpl();
        byte[] state = null;
        try {
            state =client.getSnapshot(activeMember.getManagement());
        } catch(Throwable e) {
            log.error(e);
        }
        ArrayList<byte[]> stateList = (ArrayList<byte[]>) ByteSerializer.BToO(state);
        // Synchronize the duplicate events with active member
        eventManagementService.getEventReceiverManagementService().syncState(stateList.get(1));
        eventManagementService.getEventProcessorManagementService().restoreState(stateList.get(0));
        eventManagementService.getEventProcessorManagementService().resume();
        eventManagementService.getEventReceiverManagementService().resume();
        //        writeLock.unlock();


    }

    private void becomeActive() {
        EventManagementService eventManagementService = EventManagementServiceValueHolder.getEventManagementService();

        roleToMembershipMap.set(activeId, haConfiguration);
        eventManagementService.getEventReceiverManagementService().startServer(haConfiguration.getTransport());
        eventManagementService.getEventPublisherManagementService().setDrop(false);
        eventManagementService.getEventReceiverManagementService().start();
    }

    public byte[] getState() {
        eventManagementService.getEventReceiverManagementService().pause();
        eventManagementService.getEventProcessorManagementService().pause();

        HAConfiguration passiveMember = roleToMembershipMap.get(passiveId);
        eventManagementService.getEventReceiverManagementService().setOtherMember(passiveMember.getTransport());

        byte[] processorState = eventManagementService.getEventProcessorManagementService().getState();
        byte[] receiverState = eventManagementService.getEventReceiverManagementService().getState();

        ArrayList<byte[]> stateList = new ArrayList<byte[]>(2);
        stateList.add(processorState);
        stateList.add(receiverState);

        byte[] state = ByteSerializer.OToB(stateList);

        eventManagementService.getEventProcessorManagementService().resume();
        eventManagementService.getEventReceiverManagementService().resume();

        return state;
    }

    public void shutdown() {
        if (passiveLockAcquired) {
            roleToMembershipMap.remove(passiveId);
            passiveLock.forceUnlock();
        }
        if (activeLockAcquired) {
            roleToMembershipMap.remove(activeId);
            activeLock.forceUnlock();

        }
        stateChanger.cancel(false);
    }

    class PeriodicStateChanger implements Runnable {

        /**
         * When an object implementing interface <code>Runnable</code> is used
         * to create a thread, starting the thread causes the object's
         * <code>run</code> method to be called in that separately executing
         * thread.
         * <p/>
         * The general contract of the method <code>run</code> is that it may
         * take any action whatsoever.
         *
         * @see Thread#run()
         */
        @Override
        public void run() {
            tryChangeState();
            if (!activeLockAcquired) {
                stateChanger = scheduledThreadPoolExecutor.schedule(this, 15, TimeUnit.SECONDS);
            }
        }
    }
}
