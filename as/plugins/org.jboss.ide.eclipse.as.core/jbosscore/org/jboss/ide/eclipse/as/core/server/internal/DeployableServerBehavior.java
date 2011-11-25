/******************************************************************************* 
 * Copyright (c) 2007 Red Hat, Inc. 
 * Distributed under license by Red Hat, Inc. All rights reserved. 
 * This program is made available under the terms of the 
 * Eclipse Public License v1.0 which accompanies this distribution, 
 * and is available at http://www.eclipse.org/legal/epl-v10.html 
 * 
 * Contributors: 
 * Red Hat, Inc. - initial API and implementation 
 ******************************************************************************/ 
package org.jboss.ide.eclipse.as.core.server.internal;

import java.util.HashMap;
import java.util.List;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.wst.server.core.IModule;
import org.eclipse.wst.server.core.IServer;
import org.eclipse.wst.server.core.IServerListener;
import org.eclipse.wst.server.core.ServerEvent;
import org.eclipse.wst.server.core.model.IModuleResourceDelta;
import org.eclipse.wst.server.core.model.ServerBehaviourDelegate;
import org.jboss.ide.eclipse.as.core.JBossServerCorePlugin;
import org.jboss.ide.eclipse.as.core.publishers.LocalPublishMethod;
import org.jboss.ide.eclipse.as.core.server.IJBossServerPublishMethod;
import org.jboss.ide.eclipse.as.core.server.IJBossServerPublishMethodType;
import org.jboss.ide.eclipse.as.core.server.IJBossServerPublisher;
import org.jboss.ide.eclipse.as.core.server.internal.launch.DeployableLaunchConfiguration;
import org.jboss.ide.eclipse.as.core.util.DeploymentPreferenceLoader;

/**
 * @author Rob Stryker
 */
public class DeployableServerBehavior extends ServerBehaviourDelegate {

	public DeployableServerBehavior() {
	}

	public void stop(boolean force) {
		setServerStopped(); // simple enough
	}
	
	public void setupLaunchConfiguration(ILaunchConfigurationWorkingCopy workingCopy, IProgressMonitor monitor) throws CoreException {
		workingCopy.setAttribute(DeployableLaunchConfiguration.ACTION_KEY, DeployableLaunchConfiguration.START);
	}
	
	
	private IJBossServerPublishMethod method;
	protected HashMap<String, Object> publishTaskModel;
	protected void publishStart(IProgressMonitor monitor) throws CoreException {
		if( method != null )
			throw new CoreException(new Status(IStatus.ERROR, JBossServerCorePlugin.PLUGIN_ID, "Already publishing")); //$NON-NLS-1$
		method = getOrCreatePublishMethod();
		publishTaskModel = new HashMap<String, Object>();
		method.publishStart(this, monitor);
	}

	protected IJBossServerPublishMethod getOrCreatePublishMethod() throws CoreException {
		if( method == null )
			method = createPublishMethod();
		return method;
	}
	
	protected void publishFinish(IProgressMonitor monitor) throws CoreException {
		if( method == null )
			throw new CoreException(new Status(IStatus.ERROR, JBossServerCorePlugin.PLUGIN_ID, "Not publishing")); //$NON-NLS-1$
		int result = method.publishFinish(this, monitor);
		setServerPublishState(result);
		publishTaskModel = null;
		method = null;
	}

	protected void setPublishData(String key, Object val) {
		if( publishTaskModel != null )
			publishTaskModel.put(key, val);
	}
	
	protected Object getPublishData(String key) {
		if( publishTaskModel != null )
			return publishTaskModel.get(key);
		return null;
	}
	
	protected void publishModule(int kind, int deltaKind, IModule[] module, IProgressMonitor monitor) throws CoreException {
		if( method == null )
			throw new CoreException(new Status(IStatus.ERROR, JBossServerCorePlugin.PLUGIN_ID, "Not publishing")); //$NON-NLS-1$
		try { 
			int result = method.publishModule(this, kind, deltaKind, module, monitor);
			setModulePublishState(module, result);
			setModuleState(module, IServer.STATE_STARTED );
		} catch(CoreException ce) {
			setModulePublishState(module, IServer.PUBLISH_STATE_FULL);
			setModuleState(module, IServer.STATE_UNKNOWN );
			throw ce;
		}
	}
	
	/**
	 * This should only be called once per overall publish. 
	 * publishStart() should call this, cache the method, and use it 
	 * until after publishFinish() is called. 
	 * 
	 * @return
	 */
	public IJBossServerPublishMethod createPublishMethod() {
		IJBossServerPublishMethodType type = DeploymentPreferenceLoader.getCurrentDeploymentMethodType(getServer());
		if( type != null )
			return type.createPublishMethod();
		return new LocalPublishMethod(); // sensible default
	}
	
	public IModuleResourceDelta[] getPublishedResourceDelta(IModule[] module) {
		return super.getPublishedResourceDelta(module);
	}

	public int getPublishType(int kind, int deltaKind, int modulePublishState) {
		if( deltaKind == ServerBehaviourDelegate.ADDED ) 
			return IJBossServerPublisher.FULL_PUBLISH;
		else if (deltaKind == ServerBehaviourDelegate.REMOVED) {
			return IJBossServerPublisher.REMOVE_PUBLISH;
		} else if (kind == IServer.PUBLISH_FULL 
				|| modulePublishState == IServer.PUBLISH_STATE_FULL 
				|| kind == IServer.PUBLISH_CLEAN ) {
			return IJBossServerPublisher.FULL_PUBLISH;
		} else if (kind == IServer.PUBLISH_INCREMENTAL 
				|| modulePublishState == IServer.PUBLISH_STATE_INCREMENTAL 
				|| kind == IServer.PUBLISH_AUTO) {
			if( ServerBehaviourDelegate.CHANGED == deltaKind ) 
				return IJBossServerPublisher.INCREMENTAL_PUBLISH;
		} 
		return IJBossServerPublisher.NO_PUBLISH;
	}
	
	// Expose 
	public List<IModule[]> getRemovedModules() {
		final List<IModule[]> moduleList = getAllModules();
		int size = moduleList.size();
		super.addRemovedModules(moduleList, null);
		for( int i = 0; i < size; i++ ) 
			moduleList.remove(0);
		return moduleList;
	}

	public boolean hasBeenPublished(IModule[] module) {
		return super.hasBeenPublished(module);
	}

	
	
	/*
	 * Change the state of the server
	 * Also, cache the state we think we're setting it to.
	 * 
	 * Much of this can be changed once eclipse bug 231956 is fixed
	 */
	protected int serverStateVal;
	protected int getServerStateVal() {
		return serverStateVal;
	}
	
	public void setServerStarted() {
		serverStateVal = IServer.STATE_STARTED;
		setServerState(IServer.STATE_STARTED);
	}
	
	public void setServerStarting() {
		serverStateVal = IServer.STATE_STARTING;
		setServerState(IServer.STATE_STARTING);
	}
	
	public void setServerStopped() {
		serverStateVal = IServer.STATE_STOPPED;
		setServerState(IServer.STATE_STOPPED);
	}
	
	public void setServerStopping() {
		serverStateVal = IServer.STATE_STOPPING;
		setServerState(IServer.STATE_STOPPING);
	}
	
	protected void initialize(IProgressMonitor monitor) {
		serverStateVal =  getServer().getServerState();
		getServer().addServerListener(new IServerListener() {
			public void serverChanged(ServerEvent event) {
				if( event.getState() != serverStateVal ) {
					// something's been changed by the framework and NOT by us. 
					if( serverStateVal == IServer.STATE_STARTING && event.getState() == IServer.STATE_STOPPED) {
						stop(true);
					}
				}
			} 
		} );
	}
}
