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
import java.util.List;
import java.util.Vector;

import org.terracotta.testing.common.Assert;
import org.terracotta.testing.logging.ILogger;


public class SynchronousProcessControl implements IMultiProcessControl {
  private final ILogger logger;
  // Note that the active may be null if we haven't yet observed a server enter the active state.
  private ServerProcess activeServer;
  // These servers have at least once entered the passive state.  It is possible that an active may be in this list if we haven't yet tried to find the active after a restart.
  private final List<ServerProcess> passiveServers = new Vector<ServerProcess>();
  // These servers have recently been restarted so we don't yet know their states.
  private final List<ServerProcess> unknownServers = new Vector<ServerProcess>();
  
  public SynchronousProcessControl(ILogger logger) {
    this.logger = logger;
  }

  @Override
  public synchronized void synchronizeClient() {
    this.logger.log(">>> synchronizeClient");
    // Do nothing - this is just for demonstration purposes.
    this.logger.log("<<< synchronizeClient");
  }

  @Override
  public synchronized void restartActive() {
    this.logger.log(">>> restartActive");
    // First, make sure that there is an active.
    internalWaitForActive();
    // We MUST now have an active.
    Assert.assertTrue(null != this.activeServer);
    
    // Remove the active.
    ServerProcess victim = this.activeServer;
    this.activeServer = null;
    // Stop it.
    try {
      int ret = victim.stop();
      this.logger.log("Stopped server, for restart, returning: " + ret);
    } catch (InterruptedException e) {
      // We can't leave a consistent state if interrupted at this point.
      Assert.unexpected(e);
    }
    // Return the process to its installation and get a new one.
    ServerInstallation underlyingInstallation = victim.getUnderlyingInstallation();
    underlyingInstallation.retireProcess(victim);
    victim = null;
    ServerProcess freshProcess = underlyingInstallation.createNewProcess();
    
    // Start it.
    long pid = freshProcess.start();
    this.logger.log("Server restarted with PID: " + pid);
    // Enqueue it onto the unknown list.
    this.unknownServers.add(freshProcess);
    
    // At this point, we don't know the active server.
    this.logger.log("<<< restartActive");
  }

  @Override
  public synchronized void shutDown() {
    this.logger.log(">>> shutDown");
    // We don't care about any server states here.  Just walk all of them and stop everyone.
    
    // First the active.
    if (null != this.activeServer) {
      shutdownServer(this.activeServer, "active");
      this.activeServer = null;
    }
    
    // Then the passives.
    for (ServerProcess passive : this.passiveServers) {
      shutdownServer(passive, "passive");
    }
    this.passiveServers.clear();
    
    // Then the unknowns.
    for (ServerProcess unknown : this.unknownServers) {
      shutdownServer(unknown, "unknown");
    }
    this.unknownServers.clear();
    
    this.logger.log("<<< shutDown");
  }

  @Override
  public synchronized void waitForActive() {
    this.logger.log(">>> waitForActive");
    internalWaitForActive();
    Assert.assertTrue(null != this.activeServer);
    this.logger.log("<<< waitForActive");
  }

  @Override
  public synchronized void waitForPassive() {
    this.logger.log(">>> waitForPassive");
    // We wait for passives by making sure that nothing is left in the unknown list.
    waitForAllUnknowns();
    this.logger.log("<<< waitForPassive");
  }

  public void addServerAndStart(ServerInstallation installation) {
    this.logger.log(">>> addServerAndStart");
    // We don't want to track the actual installations, as we only need to know about them restarting a server, so just
    // create the processes.
    ServerProcess process = installation.createNewProcess();
    // We also want to start it.
    long pid = process.start();
    this.logger.log("Server up with PID: " + pid);
    // Now, add it to the unknown list.
    this.unknownServers.add(process);
    this.logger.log("<<< addServerAndStart");
  }


  private void shutdownServer(ServerProcess server, String serverType) {
    // Stop the server.
    try {
      int ret = server.stop();
      this.logger.log("Stopped " + serverType + " server, for shutdown, returning: " + ret);
    } catch (InterruptedException e) {
      // TODO:  Determine if we want to handle interruption in this harness.
      Assert.unexpected(e);
    }
    
    // Retire it.
    ServerInstallation underlyingInstallation = server.getUnderlyingInstallation();
    underlyingInstallation.retireProcess(server);
    
    // Close its logs.
    try {
      underlyingInstallation.closeStandardLogFiles();
    } catch (IOException e) {
      // We don't expect this IOException on closing the logs.
      Assert.unexpected(e);
    }
  }

  private void internalWaitForActive() {
    if (null == this.activeServer) {
      // First, walk the unknown servers.
      waitForAllUnknowns();
      // If we still have no active, walk the passives to see if one was promoted.
      if (null == this.activeServer) {
        // Note that we don't want to walk this list while placing servers in states since looping the list, at this point, is either a bug or a serious race condition.
        Vector<ServerProcess> oldPassives = new Vector<ServerProcess>(this.passiveServers);
        this.passiveServers.clear();
        while (!oldPassives.isEmpty()) {
          ServerProcess passive = oldPassives.remove(0);
          waitAndPlaceServerInState(passive);
        }
        // If there is no active at this point, we have a serious bug (this could be a race condition we haven't eliminated).
        Assert.assertTrue(null != this.activeServer);
      }
    }
  }

  private void waitForAllUnknowns() {
    while (!this.unknownServers.isEmpty()) {
      ServerProcess unknown = this.unknownServers.remove(0);
      waitAndPlaceServerInState(unknown);
    }
  }

  private void waitAndPlaceServerInState(ServerProcess unknown) {
    try {
      boolean isActive = unknown.waitForStartIsActive();
      if (isActive) {
        Assert.assertTrue(null == this.activeServer);
        this.activeServer = unknown;
      } else {
        this.passiveServers.add(unknown);
      }
    } catch (InterruptedException e) {
      // TODO:  Determine if we want to support interruption, here.
      Assert.unexpected(e);
    }
  }
}