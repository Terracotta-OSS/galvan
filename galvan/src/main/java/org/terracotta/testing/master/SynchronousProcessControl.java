/*
 * Copyright Terracotta, Inc.
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
package org.terracotta.testing.master;

import java.io.IOException;

import org.terracotta.testing.common.Assert;
import org.terracotta.testing.logging.ContextualLogger;


/**
 * This component implements the IMultiProcessControl interface as a heavily-synchronized object in order to act as
 * a choke-point such that calls to interact with the test state are forced to be sequential even though the
 * underlying logic is asynchronous.  By doing the wait() calls, in a different object, while called through here,
 * we block other attempts to change test state.  This protects us from complex situations such as 2 clients trying
 * to restart the same ServerProcess object, because they both reached for the active at the same time, for example.
 * 
 * Hence, all the public methods are synchronized, even though we don't actually wait/notify here.
 */
public class SynchronousProcessControl implements IMultiProcessControl {
  private final GalvanStateInterlock stateInterlock;
  private final ContextualLogger logger;

  public SynchronousProcessControl(GalvanStateInterlock stateInterlock, ContextualLogger logger) {
    this.stateInterlock = stateInterlock;
    this.logger = logger;
  }

  @Override
  public synchronized void synchronizeClient() {
    this.logger.output(">>> synchronizeClient");
    // Do nothing - this is just for demonstration purposes.
    this.logger.output("<<< synchronizeClient");
  }

  @Override
  public synchronized void terminateActive() throws GalvanFailureException {
    this.logger.output(">>> terminateActive");
    
    // Get the active and stop it.
    ServerProcess active = this.stateInterlock.getActiveServer();
    // We expect that the test knows there is an active (might change in the future).
    if (null == active) {
      throw new IllegalStateException("No server in active state");
    }
    safeStop(active);
    
    // Wait until the server has gone down.
    this.stateInterlock.waitForServerTermination(active);
    
    this.logger.output("<<< terminateActive");
  }

  @Override
  public synchronized void terminateOnePassive() throws GalvanFailureException {
    this.logger.output(">>> terminateOnePassive");
    
    // Pick an arbitrary passive.
    ServerProcess onePassive = this.stateInterlock.getOnePassiveServer();
    // It is acceptable to call this in the case where there is no passive.  That is a "do nothing" situation.
    if (null != onePassive) {
      // Stop the server
      safeStop(onePassive);
      // Wait until the server has gone down.
      this.stateInterlock.waitForServerTermination(onePassive);
    }
    
    this.logger.output("<<< terminateOnePassive");
  }

  @Override
  public synchronized void startOneServer() throws GalvanFailureException {
    this.logger.output(">>> startOneServer");
    startServer();
    this.logger.output("<<< startOneServer");
  }

  public synchronized void startServer() throws GalvanFailureException {
    ServerProcess server = this.stateInterlock.getOneTerminatedServer();
    if (null == server) {
      throw new IllegalStateException("Tried to start one server when none are terminated");
    }
    safeStart(server);
    
    // Wait for it to start up (otherwise, later calls to wait for the servers to become ready may not know that
    //  this one was still expected, just not started).
    this.stateInterlock.waitForServerRunning(server);
  }

  @Override
  public synchronized void startAllServers() throws GalvanFailureException {
    this.logger.output(">>> startAllServers");
    startServers();
    this.logger.output("<<< startAllServers");
  }

  private void startServers() throws GalvanFailureException {
    ServerProcess server = this.stateInterlock.getOneTerminatedServer();
    while (null != server) {
      safeStart(server);

      // Wait for it to start up (since we need to grab a different one in the next call).
      this.stateInterlock.waitForServerRunning(server);
      ServerProcess nextServer = this.stateInterlock.getOneTerminatedServer();
      // Ensure that we don't somehow get the same instance (since we just watched it come online).
      Assert.assertTrue(server != nextServer);
      server = nextServer;
    }
  }

  @Override
  public synchronized void terminateAllServers() throws GalvanFailureException {
    this.logger.output(">>> terminateAllServers");
    // Wait until all servers are in a reasonable state.
    this.stateInterlock.waitForAllServerReady();
    
    // NOTE:  We want to get the passives, first, to avoid active fail-over causing us not to know the state of a server when looking for it.
    // Get all the passives.
    ServerProcess passive = this.stateInterlock.getOnePassiveServer();
    while (null != passive) {
      safeStop(passive);
      this.stateInterlock.waitForServerTermination(passive);
      passive = this.stateInterlock.getOnePassiveServer();
    }
    
    // Get the active.
    ServerProcess active = this.stateInterlock.getActiveServer();
    if (null != active) {
      safeStop(active);
      this.stateInterlock.waitForServerTermination(active);
    }
    
    this.logger.output("<<< terminateAllServers");
  }

  @Override
  public synchronized void waitForActive() throws GalvanFailureException {
    this.logger.output(">>> waitForActive");
    
    this.stateInterlock.waitForActiveServer();
    
    this.logger.output("<<< waitForActive");
  }

  @Override
  public synchronized void waitForRunningPassivesInStandby() throws GalvanFailureException {
    this.logger.output(">>> waitForRunningPassivesInStandby");
    
    this.stateInterlock.waitForAllServerReady();
    
    this.logger.output("<<< waitForRunningPassivesInStandby");
  }


  private void safeStart(ServerProcess server) {
    try {
      server.start();
    } catch (IOException e) {
      // Unexpected, given that this server was already started, at one point.
      Assert.unexpected(e);
    }
  }

  private void safeStop(ServerProcess server) {
    try {
      server.stop();
    } catch (InterruptedException e) {
      // Interruption not expected in these tests.
      Assert.unexpected(e);
    }
  }
}
