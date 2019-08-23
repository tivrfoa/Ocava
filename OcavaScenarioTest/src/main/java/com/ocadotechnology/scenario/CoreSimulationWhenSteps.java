/*
 * Copyright © 2017 Ocado (Ocava)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ocadotechnology.scenario;

import com.ocadotechnology.notification.Notification;
import com.ocadotechnology.scenario.StepManager.CheckStepExecutionType;

public class CoreSimulationWhenSteps extends AbstractWhenSteps {
    private final ScenarioSimulationApi simulationAPI;
    private final ScenarioNotificationListener listener;
    private final SimulationThenSteps simulationThen;

    public CoreSimulationWhenSteps(StepManager stepManager, ScenarioSimulationApi simulationAPI, ScenarioNotificationListener listener, NotificationCache notificationCache) {
        super(stepManager);
        this.simulationAPI = simulationAPI;
        this.listener = listener;
        this.simulationThen = new SimulationThenSteps(stepManager, notificationCache);
    }

    /**
     * Executes the simulationApi.start() method and waits for receipt of a notification indicating that the simulation
     * has been created successfully.  This is required to trigger the execution of all following steps.
     */
    public void starts(Class<? extends Notification> simulationStartedNotificationClass) {
        addExecuteStep(() -> simulationAPI.start(listener));
        simulationThen.notificationReceived(simulationStartedNotificationClass);
    }

    /**
     * Executes the simulationApi.start() method and asserts that no notification indicating that the simulation has
     * been created is sent.  This is intended to facilitate testing cases where it is desirable to demonstrate that a
     * given system setup is invalid.
     *
     * It is expected to be paired with an {@link ExceptionThenSteps} step to validate that an expected exception is
     * thrown instead.
     *
     * CARE - this step must always be followed by a then step of some kind.
     */
    public void attemptedStartupDoesNotComplete(Class<? extends Notification> simulationStartedNotificationClass) {
        addExecuteStep(() -> simulationAPI.start(listener));
        simulationThen.never().notificationReceived(simulationStartedNotificationClass);
    }

    /**
     * private internal class to allow the when steps to check for receipt of a notification
     */
    private static class SimulationThenSteps extends AbstractThenSteps {
        public SimulationThenSteps(StepManager stepManager, NotificationCache notificationCache, CheckStepExecutionType executionType) {
            super(stepManager, notificationCache, executionType);
        }

        public SimulationThenSteps(StepManager stepManager, NotificationCache notificationCache) {
            this(stepManager, notificationCache, CheckStepExecutionType.ordered());
        }

        @Override
        protected AbstractThenSteps<?> create(StepManager stepManager, NotificationCache notificationCache, CheckStepExecutionType checkStepExecutionType) {
            return new SimulationThenSteps(stepManager, notificationCache, checkStepExecutionType);
        }
    }
}
