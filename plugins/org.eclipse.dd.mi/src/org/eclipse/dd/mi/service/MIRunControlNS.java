/*******************************************************************************
 * Copyright (c) 2006, 2008 Wind River Systems and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Wind River Systems - initial API and implementation
 *     Ericsson	AB		  - Modified for handling of multiple threads
 *******************************************************************************/

package org.eclipse.dd.mi.service;

import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.dd.dsf.concurrent.DataRequestMonitor;
import org.eclipse.dd.dsf.concurrent.Immutable;
import org.eclipse.dd.dsf.concurrent.RequestMonitor;
import org.eclipse.dd.dsf.datamodel.AbstractDMEvent;
import org.eclipse.dd.dsf.datamodel.DMContexts;
import org.eclipse.dd.dsf.datamodel.IDMContext;
import org.eclipse.dd.dsf.datamodel.IDMEvent;
import org.eclipse.dd.dsf.debug.service.IRunControl;
import org.eclipse.dd.dsf.debug.service.IProcesses.IProcessDMContext;
import org.eclipse.dd.dsf.debug.service.IProcesses.IThreadDMContext;
import org.eclipse.dd.dsf.debug.service.IStack.IFrameDMContext;
import org.eclipse.dd.dsf.debug.service.command.CommandCache;
import org.eclipse.dd.dsf.service.AbstractDsfService;
import org.eclipse.dd.dsf.service.DsfServiceEventHandler;
import org.eclipse.dd.dsf.service.DsfSession;
import org.eclipse.dd.mi.internal.MIPlugin;
import org.eclipse.dd.mi.service.command.AbstractMIControl;
import org.eclipse.dd.mi.service.command.AbstractMIControl.BackendExitedEvent;
import org.eclipse.dd.mi.service.command.commands.MIExecContinue;
import org.eclipse.dd.mi.service.command.commands.MIExecFinish;
import org.eclipse.dd.mi.service.command.commands.MIExecInterrupt;
import org.eclipse.dd.mi.service.command.commands.MIExecNext;
import org.eclipse.dd.mi.service.command.commands.MIExecNextInstruction;
import org.eclipse.dd.mi.service.command.commands.MIExecStep;
import org.eclipse.dd.mi.service.command.commands.MIExecStepInstruction;
import org.eclipse.dd.mi.service.command.commands.MIExecUntil;
import org.eclipse.dd.mi.service.command.events.IMIDMEvent;
import org.eclipse.dd.mi.service.command.events.MIBreakpointHitEvent;
import org.eclipse.dd.mi.service.command.events.MIErrorEvent;
import org.eclipse.dd.mi.service.command.events.MIEvent;
import org.eclipse.dd.mi.service.command.events.MIRunningEvent;
import org.eclipse.dd.mi.service.command.events.MISharedLibEvent;
import org.eclipse.dd.mi.service.command.events.MISignalEvent;
import org.eclipse.dd.mi.service.command.events.MISteppingRangeEvent;
import org.eclipse.dd.mi.service.command.events.MIStoppedEvent;
import org.eclipse.dd.mi.service.command.events.MIThreadCreatedEvent;
import org.eclipse.dd.mi.service.command.events.MIThreadExitEvent;
import org.eclipse.dd.mi.service.command.events.MIWatchpointTriggerEvent;
import org.eclipse.dd.mi.service.command.output.MIInfo;
import org.osgi.framework.BundleContext;

/**
 * Implementation note: This class implements event handlers for the events that
 * are generated by this service itself. When the event is dispatched, these
 * handlers will be called first, before any of the clients. These handlers
 * update the service's internal state information to make them consistent with
 * the events being issued. Doing this in the handlers as opposed to when the
 * events are generated, guarantees that the state of the service will always be
 * consistent with the events. The purpose of this pattern is to allow clients
 * that listen to service events and track service state, to be perfectly in
 * sync with the service state.
 */
public class MIRunControlNS extends AbstractDsfService implements IRunControl
{
	@Immutable
	static class ExecutionData implements IExecutionDMData {
		private final StateChangeReason fReason;
		ExecutionData(StateChangeReason reason) {
			fReason = reason;
		}
		public StateChangeReason getStateChangeReason() { return fReason; }
	}

	/**
	 * Base class for events generated by the MI Run Control service.  Most events
	 * generated by the MI Run Control service are directly caused by some MI event.
	 * Other services may need access to the extended MI data carried in the event.
	 * 
	 * @param <V> DMC that this event refers to
	 * @param <T> MIInfo object that is the direct cause of this event
	 * @see MIRunControl
	 */
	@Immutable
	static class RunControlEvent<V extends IDMContext, T extends MIEvent<? extends IDMContext>> extends AbstractDMEvent<V>
	implements IDMEvent<V>, IMIDMEvent
	{
		final private T fMIInfo;
		public RunControlEvent(V dmc, T miInfo) {
			super(dmc);
			fMIInfo = miInfo;
		}

		public T getMIEvent() { return fMIInfo; }
	}

	/**
	 * Indicates that the given thread has been suspended.
	 */
	@Immutable
	static class SuspendedEvent extends RunControlEvent<IExecutionDMContext, MIStoppedEvent>
	implements ISuspendedDMEvent
	{
		SuspendedEvent(IExecutionDMContext ctx, MIStoppedEvent miInfo) {
			super(ctx, miInfo);
		}

		public StateChangeReason getReason() {
			if (getMIEvent() instanceof MIBreakpointHitEvent) {
				return StateChangeReason.BREAKPOINT;
			} else if (getMIEvent() instanceof MISteppingRangeEvent) {
				return StateChangeReason.STEP;
			} else if (getMIEvent() instanceof MISharedLibEvent) {
				return StateChangeReason.SHAREDLIB;
			}else if (getMIEvent() instanceof MISignalEvent) {
				return StateChangeReason.SIGNAL;
			}else if (getMIEvent() instanceof MIWatchpointTriggerEvent) {
				return StateChangeReason.WATCHPOINT;
			}else if (getMIEvent() instanceof MIErrorEvent) {
				return StateChangeReason.ERROR;
			}else {
				return StateChangeReason.USER_REQUEST;
			}
		}
	}

	@Immutable
	static class ContainerSuspendedEvent extends SuspendedEvent
	implements IContainerSuspendedDMEvent
	{
		final IExecutionDMContext[] triggeringDmcs;
		ContainerSuspendedEvent(IContainerDMContext containerDmc, MIStoppedEvent miInfo, IExecutionDMContext triggeringDmc) {
			super(containerDmc, miInfo);
			this.triggeringDmcs = triggeringDmc != null
			? new IExecutionDMContext[] { triggeringDmc } : new IExecutionDMContext[0];
		}

		public IExecutionDMContext[] getTriggeringContexts() {
			return triggeringDmcs;
		}
	}

	@Immutable
	static class ThreadSuspendedEvent extends SuspendedEvent
	{
		ThreadSuspendedEvent(IExecutionDMContext executionDmc, MIStoppedEvent miInfo) {
			super(executionDmc, miInfo);
		}
	}

	@Immutable
	static class ResumedEvent extends RunControlEvent<IExecutionDMContext, MIRunningEvent>
	implements IResumedDMEvent
	{
		ResumedEvent(IExecutionDMContext ctx, MIRunningEvent miInfo) {
			super(ctx, miInfo);
		}

		public StateChangeReason getReason() {
			switch(getMIEvent().getType()) {
			case MIRunningEvent.CONTINUE:
				return StateChangeReason.USER_REQUEST;
			case MIRunningEvent.NEXT:
			case MIRunningEvent.NEXTI:
				return StateChangeReason.STEP;
			case MIRunningEvent.STEP:
			case MIRunningEvent.STEPI:
				return StateChangeReason.STEP;
			case MIRunningEvent.FINISH:
				return StateChangeReason.STEP;
			case MIRunningEvent.UNTIL:
			case MIRunningEvent.RETURN:
				break;
			}
			return StateChangeReason.UNKNOWN;
		}
	}

	@Immutable
	static class ContainerResumedEvent extends ResumedEvent
	implements IContainerResumedDMEvent
	{
		final IExecutionDMContext[] triggeringDmcs;

		ContainerResumedEvent(IContainerDMContext containerDmc, MIRunningEvent miInfo, IExecutionDMContext triggeringDmc) {
			super(containerDmc, miInfo);
			this.triggeringDmcs = triggeringDmc != null
			? new IExecutionDMContext[] { triggeringDmc } : new IExecutionDMContext[0];
		}

		public IExecutionDMContext[] getTriggeringContexts() {
			return triggeringDmcs;
		}
	}

	@Immutable
	static class ThreadResumedEvent extends ResumedEvent
	{
		ThreadResumedEvent(IExecutionDMContext executionDmc, MIRunningEvent miInfo) {
			super(executionDmc, miInfo);
		}
	}

	@Immutable
	static class StartedDMEvent extends RunControlEvent<IExecutionDMContext,MIThreadCreatedEvent>
	implements IStartedDMEvent
	{
		StartedDMEvent(IMIExecutionDMContext executionDmc, MIThreadCreatedEvent miInfo) {
			super(executionDmc, miInfo);
		}
	}

	@Immutable
	static class ExitedDMEvent extends RunControlEvent<IExecutionDMContext,MIThreadExitEvent>
	implements IExitedDMEvent
	{
		ExitedDMEvent(IMIExecutionDMContext executionDmc, MIThreadExitEvent miInfo) {
			super(executionDmc, miInfo);
		}
	}

	protected class MIThreadRunState {
		// State flags
		boolean fSuspended = false;
		boolean fResumePending = false;
		boolean fStepping = false;
		StateChangeReason fStateChangeReason;
	}

	///////////////////////////////////////////////////////////////////////////
	// MIRunControlNS
	///////////////////////////////////////////////////////////////////////////

	private AbstractMIControl fConnection;

	// The command cache applies only for the thread-info command at the
	// container (process) level and is *always* available in non-stop mode.
	// The only thing to do is to reset it every time a thread is created/
	// terminated.
	private CommandCache fMICommandCache;

	private boolean fTerminated = false;

	// ThreadStates indexed by the execution context
	protected Map<IMIExecutionDMContext, MIThreadRunState> fThreadRunStates = new HashMap<IMIExecutionDMContext, MIThreadRunState>();

	///////////////////////////////////////////////////////////////////////////
	// Initialization and shutdown
	///////////////////////////////////////////////////////////////////////////

	public MIRunControlNS(DsfSession session) {
		super(session);
	}

	@Override
	public void initialize(final RequestMonitor rm) {
		super.initialize(new RequestMonitor(getExecutor(), rm) {
			@Override
			protected void handleSuccess() {
				doInitialize(rm);
			}
		});
	}

	private void doInitialize(final RequestMonitor rm) {
        register(new String[]{IRunControl.class.getName()}, new Hashtable<String,String>());
		fConnection = getServicesTracker().getService(AbstractMIControl.class);
        fMICommandCache = new CommandCache(getSession(), fConnection);
        fMICommandCache.setContextAvailable(fConnection.getControlDMContext(), true);
		getSession().addServiceEventListener(this, null);
		rm.done();
	}

	@Override
	public void shutdown(final RequestMonitor rm) {
        unregister();
		getSession().removeServiceEventListener(this);
		super.shutdown(rm);
	}

	///////////////////////////////////////////////////////////////////////////
	// AbstractDsfService
	///////////////////////////////////////////////////////////////////////////

	@Override
	protected BundleContext getBundleContext() {
		return MIPlugin.getBundleContext();
	}

	///////////////////////////////////////////////////////////////////////////
	// IDMService
	///////////////////////////////////////////////////////////////////////////

	@SuppressWarnings("unchecked")
	public void getModelData(IDMContext dmc, DataRequestMonitor<?> rm) {
		if (dmc instanceof IExecutionDMContext) {
			getExecutionData((IExecutionDMContext) dmc, (DataRequestMonitor<IExecutionDMData>) rm);
		} else {
			rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INVALID_HANDLE, "Unknown DMC type", null)); //$NON-NLS-1$
			rm.done();
		}
	}

	///////////////////////////////////////////////////////////////////////////
	// IRunControl
	///////////////////////////////////////////////////////////////////////////

	// ------------------------------------------------------------------------
	// Suspend
	// ------------------------------------------------------------------------

	public boolean isSuspended(IExecutionDMContext context) {

		// Thread case
		if (context instanceof IMIExecutionDMContext) {
			MIThreadRunState threadState = fThreadRunStates.get(context);
			return (threadState == null) ? false : !fTerminated && threadState.fSuspended;
		}

		// Container case
		if (context instanceof IContainerDMContext) {
			boolean isSuspended = false;
			for (IMIExecutionDMContext threadContext : fThreadRunStates.keySet()) {
				if (DMContexts.isAncestorOf(threadContext, context)) {
					isSuspended |= isSuspended(threadContext);
				}
			}
			return isSuspended;
		}

		// Default case
		return false;
	}

	public void canSuspend(IExecutionDMContext context, DataRequestMonitor<Boolean> rm) {

		// Thread case
		if (context instanceof IMIExecutionDMContext) {
			rm.setData(doCanSuspend(context));
			rm.done();
			return;
		}

		// Container case
		if (context instanceof IContainerDMContext) {
			boolean canSuspend = false;
			for (IMIExecutionDMContext threadContext : fThreadRunStates.keySet()) {
				if (DMContexts.isAncestorOf(threadContext, context)) {
					canSuspend |= doCanSuspend(threadContext);
				}
			}
			rm.setData(canSuspend);
			rm.done();
			return;
		}

		// Default case
		rm.setData(false);
		rm.done();
	}

	private boolean doCanSuspend(IExecutionDMContext context) {
		MIThreadRunState threadState = fThreadRunStates.get(context);
		return (threadState == null) ? false : !fTerminated && !threadState.fSuspended;
	}

	public void suspend(IExecutionDMContext context, final RequestMonitor rm) {

		assert context != null;

		// Thread case
		IMIExecutionDMContext thread = DMContexts.getAncestorOfType(context, IMIExecutionDMContext.class);
		if (thread != null) {
			doSuspendThread(thread, rm);
			return;
		}

		// Container case
		IContainerDMContext container = DMContexts.getAncestorOfType(context, IContainerDMContext.class);
		if (container != null) {
			doSuspendContainer(container, rm);
			return;
		}

		// Default case
		rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, NOT_SUPPORTED, "Invalid context type.", null)); //$NON-NLS-1$
		rm.done();
	}

	private void doSuspendThread(IMIExecutionDMContext context, final RequestMonitor rm) {

		if (!doCanSuspend(context)) {
			rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, NOT_SUPPORTED,
				"Given context: " + context + ", is already suspended.", null)); //$NON-NLS-1$ //$NON-NLS-2$
			rm.done();
			return;
		}

		MIExecInterrupt cmd = new MIExecInterrupt(context, true);
		fConnection.queueCommand(cmd, new DataRequestMonitor<MIInfo>(getExecutor(), rm));
	}

	private void doSuspendContainer(IExecutionDMContext context, final RequestMonitor rm) {
		MIExecInterrupt cmd = new MIExecInterrupt(context, true);
		fConnection.queueCommand(cmd, new DataRequestMonitor<MIInfo>(getExecutor(), rm));
	}

	// ------------------------------------------------------------------------
	// Resume
	// ------------------------------------------------------------------------

	public void canResume(IExecutionDMContext context, DataRequestMonitor<Boolean> rm) {

		// Thread case
		if (context instanceof IMIExecutionDMContext) {
			rm.setData(doCanResume(context));
			rm.done();
			return;
		}

		// Container case
		if (context instanceof IContainerDMContext) {
			boolean canSuspend = false;
			for (IMIExecutionDMContext threadContext : fThreadRunStates.keySet()) {
				if (DMContexts.isAncestorOf(threadContext, context)) {
					canSuspend |= doCanResume(threadContext);
				}
			}
			rm.setData(canSuspend);
			rm.done();
			return;
		}

		// Default case
		rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, NOT_SUPPORTED, "Invalid context type.", null)); //$NON-NLS-1$
		rm.done();
	}

	private boolean doCanResume(IExecutionDMContext context) {
		MIThreadRunState threadState = fThreadRunStates.get(context);
		return (threadState == null) ? false : !fTerminated && threadState.fSuspended && !threadState.fResumePending;
	}

	public void resume(IExecutionDMContext context, final RequestMonitor rm) {

		assert context != null;

		// Thread case
		IMIExecutionDMContext thread = DMContexts.getAncestorOfType(context, IMIExecutionDMContext.class);
		if (thread != null) {
			doResumeThread(thread, rm);
			return;
		}

		// Container case
		IContainerDMContext container = DMContexts.getAncestorOfType(context, IContainerDMContext.class);
		if (container != null) {
			doResumeContainer(container, rm);
			return;
		}

		// Default case
		rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, NOT_SUPPORTED, "Invalid context type.", null)); //$NON-NLS-1$
		rm.done();
	}

	private void doResumeThread(IMIExecutionDMContext context, final RequestMonitor rm) {

		if (!doCanResume(context)) {
			rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INVALID_STATE,
				"Given context: " + context + ", is already running.", null)); //$NON-NLS-1$ //$NON-NLS-2$
			rm.done();
			return;
		}

		MIThreadRunState threadState = fThreadRunStates.get(context);
		if (threadState == null) {
			rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INVALID_STATE,
				"Given context: " + context + " is not an MI execution context.", null)); //$NON-NLS-1$ //$NON-NLS-2$
			rm.done();
			return;
		}
		threadState.fResumePending = true;

		MIExecContinue cmd = new MIExecContinue(context, context.getThreadId());
		fConnection.queueCommand(cmd, new DataRequestMonitor<MIInfo>(getExecutor(), rm));
	}

	private void doResumeContainer(IContainerDMContext context, final RequestMonitor rm) {
		MIExecContinue cmd = new MIExecContinue(context, false);
		fConnection.queueCommand(cmd, new DataRequestMonitor<MIInfo>(getExecutor(), rm));
	}

	// ------------------------------------------------------------------------
	// Step
	// ------------------------------------------------------------------------

	public boolean isStepping(IExecutionDMContext context) {

		// If it's a thread, just look it up
		if (context instanceof IMIExecutionDMContext) {
			MIThreadRunState threadState = fThreadRunStates.get(context);
			return (threadState == null) ? false : !fTerminated && threadState.fStepping;
		}

		// Default case
		return false;
	}

	public void canStep(IExecutionDMContext context, StepType stepType, DataRequestMonitor<Boolean> rm) {

		// If it's a thread, just look it up
		if (context instanceof IMIExecutionDMContext) {
			canResume(context, rm);
			return;
		}

		// If it's a container, then we don't want to step it
		rm.setData(false);
		rm.done();
	}

	public void step(IExecutionDMContext context, StepType stepType, final RequestMonitor rm) {

		assert context != null;

		IMIExecutionDMContext dmc = DMContexts.getAncestorOfType(context, IMIExecutionDMContext.class);
		if (dmc == null) {
			rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, NOT_SUPPORTED,
				"Given context: " + context + " is not an MI execution context.", null)); //$NON-NLS-1$ //$NON-NLS-2$
			rm.done();
			return;
		}

		if (!doCanResume(context)) {
			rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INVALID_STATE,
				"Cannot resume context", null)); //$NON-NLS-1$
			rm.done();
			return;
		}

		MIThreadRunState threadState = fThreadRunStates.get(context);
		if (threadState == null) {
			rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INVALID_STATE,
				"Given context: " + context + " can't be found.", null)); //$NON-NLS-1$ //$NON-NLS-2$
			rm.done();
			return;
		}

		threadState.fResumePending = true;
		threadState.fStepping = true;

		switch (stepType) {
		case STEP_INTO:
			fConnection.queueCommand(new MIExecStep(dmc, true),
					new DataRequestMonitor<MIInfo>(getExecutor(), rm));
			break;
		case STEP_OVER:
			fConnection.queueCommand(new MIExecNext(dmc, true),
					new DataRequestMonitor<MIInfo>(getExecutor(), rm));
			break;
		case STEP_RETURN:
			// The -exec-finish command operates on the selected stack frame, but here we always
			// want it to operate on the stop stack frame. So we manually create a top-frame
			// context to use with the MI command.
			// We get a local instance of the stack service because the stack service can be shut
			// down before the run control service is shut down. So it is possible for the
			// getService() request below to return null.
			MIStack stackService = getServicesTracker().getService(MIStack.class);
			if (stackService != null) {
				IFrameDMContext topFrameDmc = stackService.createFrameDMContext(dmc, 0);
				fConnection.queueCommand(new MIExecFinish(topFrameDmc),
						new DataRequestMonitor<MIInfo>(getExecutor(), rm));
			} else {
				rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, NOT_SUPPORTED,
						"Cannot create context for command, stack service not available.", null)); //$NON-NLS-1$
				rm.done();
			}
			break;
		case INSTRUCTION_STEP_INTO:
			fConnection.queueCommand(new MIExecStepInstruction(dmc, true),
					new DataRequestMonitor<MIInfo>(getExecutor(), rm));
			break;
		case INSTRUCTION_STEP_OVER:
			fConnection.queueCommand(new MIExecNextInstruction(dmc, true),
					new DataRequestMonitor<MIInfo>(getExecutor(), rm));
			break;
		default:
			rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID,
					INTERNAL_ERROR, "Given step type not supported", null)); //$NON-NLS-1$
			rm.done();
		}
	}

	// ------------------------------------------------------------------------
	// Run to line
	// ------------------------------------------------------------------------

	// Later add support for Address and function.
	// skipBreakpoints is not used at the moment. Implement later
	public void runToLine(IExecutionDMContext context, String fileName, String lineNo, boolean skipBreakpoints, final DataRequestMonitor<MIInfo> rm) {

		assert context != null;

		IMIExecutionDMContext dmc = DMContexts.getAncestorOfType(context, IMIExecutionDMContext.class);
		if (dmc == null) {
			rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, NOT_SUPPORTED,
				"Given context: " + context + " is not an MI execution context.", null)); //$NON-NLS-1$ //$NON-NLS-2$
			rm.done();
			return;
		}

		if (!doCanResume(context)) {
			rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INVALID_STATE,
				"Cannot resume context", null)); //$NON-NLS-1$
			rm.done();
			return;
		}

		MIThreadRunState threadState = fThreadRunStates.get(context);
		if (threadState == null) {
			rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INVALID_STATE,
				"Given context: " + context + " is not an MI execution context.", null)); //$NON-NLS-1$ //$NON-NLS-2$
			rm.done();
			return;
		}

		threadState.fResumePending = true;
		fConnection.queueCommand(new MIExecUntil(dmc, fileName + ":" + lineNo), //$NON-NLS-1$
				new DataRequestMonitor<MIInfo>(getExecutor(), rm));
	}

	// ------------------------------------------------------------------------
	// Support functions
	// ------------------------------------------------------------------------

	public void getExecutionContexts(final IContainerDMContext containerDmc, final DataRequestMonitor<IExecutionDMContext[]> rm) {
        IMIProcesses procService = getServicesTracker().getService(IMIProcesses.class);
		procService.getProcessesBeingDebugged(
				containerDmc,
				new DataRequestMonitor<IDMContext[]>(getExecutor(), rm) {
					@Override
					protected void handleSuccess() {
						if (getData() instanceof IExecutionDMContext[]) {
							rm.setData((IExecutionDMContext[])getData());
						} else {
							rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INTERNAL_ERROR, "Invalid contexts", null)); //$NON-NLS-1$
						}
						rm.done();
					}
				});
	}

	public void getExecutionData(IExecutionDMContext dmc, DataRequestMonitor<IExecutionDMData> rm) {
		MIThreadRunState threadState = fThreadRunStates.get(dmc);
		if (threadState == null) {
			rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID,INVALID_HANDLE,
				"Given context: " + dmc + " is not a recognized execution context.", null)); //$NON-NLS-1$ //$NON-NLS-2$
			rm.done();
			return;
		}

		if (dmc instanceof IMIExecutionDMContext) {
			rm.setData(new ExecutionData(threadState.fSuspended ? threadState.fStateChangeReason : null));
		} else {
			rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INVALID_HANDLE,
				"Given context: " + dmc + " is not a recognized execution context.", null)); //$NON-NLS-1$ //$NON-NLS-2$
		}
		rm.done();
	}


	private IMIExecutionDMContext createMIExecutionContext(IContainerDMContext container, String threadId) {
        IMIProcesses procService = getServicesTracker().getService(IMIProcesses.class);

        IProcessDMContext procDmc = DMContexts.getAncestorOfType(container, IProcessDMContext.class);
        
        IThreadDMContext threadDmc = null;
        if (procDmc != null) {
        	// For now, reuse the threadId as the OSThreadId
        	threadDmc = procService.createThreadContext(procDmc, threadId);
        }

        return procService.createExecutionContext(container, threadDmc, threadId);
	}

	public CommandCache getCache() {
		 return fMICommandCache;
	}

	protected AbstractMIControl getConnection() {
		 return fConnection;
	}

	///////////////////////////////////////////////////////////////////////////
	// Event handlers
	///////////////////////////////////////////////////////////////////////////

	@DsfServiceEventHandler
	public void eventDispatched(final MIRunningEvent e) {

		IDMEvent<?> event = null;

		// If it's not an execution context (what else could it be?!?), just propagate it 
		IMIExecutionDMContext executionDmc = DMContexts.getAncestorOfType(e.getDMContext(), IMIExecutionDMContext.class);
		if (executionDmc == null) {
			event = new ResumedEvent(e.getDMContext(), e);
			getSession().dispatchEvent(event, getProperties());
			return;
		}

		// It's a thread execution context (since we are in non-stop mode)
		event = new ThreadResumedEvent(e.getDMContext(), e);
		updateThreadState(executionDmc, (ThreadResumedEvent) event);
		getSession().dispatchEvent(event, getProperties());
		fMICommandCache.reset();

        // Find the container context, which is used in multi-threaded debugging.
        IContainerDMContext containerDmc = DMContexts.getAncestorOfType(e.getDMContext(), IContainerDMContext.class);
        if (containerDmc != null) {
        	IExecutionDMContext triggeringCtx = !e.getDMContext().equals(containerDmc) ? e.getDMContext() : null;
            event = new ContainerResumedEvent(containerDmc, e, triggeringCtx);
            getSession().dispatchEvent(event, getProperties());
        }
	}

	private void updateThreadState(IMIExecutionDMContext context, ThreadResumedEvent event) {
		StateChangeReason reason = event.getReason();
		boolean isStepping = reason.equals(StateChangeReason.STEP);
		MIThreadRunState threadState = fThreadRunStates.get(context);
		if (threadState == null) {
			threadState = new MIThreadRunState();
			fThreadRunStates.put(context, threadState);
		}
		threadState.fSuspended = false;
		threadState.fResumePending = false;
		threadState.fStateChangeReason = reason;
		threadState.fStepping = isStepping;
	}

	@DsfServiceEventHandler
	public void eventDispatched(final MIStoppedEvent e) {

		IDMEvent<?> event = null;

		// If it's not an execution context (what else could it be?!?), just propagate it 
		IMIExecutionDMContext executionDmc = DMContexts.getAncestorOfType(e.getDMContext(), IMIExecutionDMContext.class);
		if (executionDmc == null) {
			event = new SuspendedEvent(e.getDMContext(), e);
			getSession().dispatchEvent(event, getProperties());
			return;
		}

		// It's a thread execution context (since we are in non-stop mode)
		event = new ThreadSuspendedEvent(e.getDMContext(), e);
		updateThreadState(executionDmc, (ThreadSuspendedEvent) event);
		getSession().dispatchEvent(event, getProperties());
		fMICommandCache.reset();

		// Find the container context, which is used in multi-threaded debugging.
        IContainerDMContext containerDmc = DMContexts.getAncestorOfType(e.getDMContext(), IContainerDMContext.class);
        if (containerDmc != null) {
        	IExecutionDMContext triggeringCtx = !e.getDMContext().equals(containerDmc) ? e.getDMContext() : null;
            event = new ContainerSuspendedEvent(containerDmc, e, triggeringCtx);
            getSession().dispatchEvent(event, getProperties());
        }
	}

	private void updateThreadState(IMIExecutionDMContext context, ThreadSuspendedEvent event) {
		StateChangeReason reason = event.getReason();
		MIThreadRunState threadState = fThreadRunStates.get(context);
		if (threadState == null) {
			threadState = new MIThreadRunState();
			fThreadRunStates.put(context, threadState);
		}
		threadState.fSuspended = true;
		threadState.fResumePending = false;
		threadState.fStepping = false;
		threadState.fStateChangeReason = reason;
	}

	@DsfServiceEventHandler
	public void eventDispatched(final MIThreadCreatedEvent e) {
		IContainerDMContext containerDmc = e.getDMContext();
		IMIExecutionDMContext executionCtx = null;
		if (e.getStrId() != null) {
			executionCtx = createMIExecutionContext(containerDmc, e.getStrId());
			if (fThreadRunStates.get(executionCtx) == null) {
				fThreadRunStates.put(executionCtx, new MIThreadRunState());
			}
		}
		getSession().dispatchEvent(new StartedDMEvent(executionCtx, e),	getProperties());
	}

	@DsfServiceEventHandler
	public void eventDispatched(final MIThreadExitEvent e) {
		IContainerDMContext containerDmc = e.getDMContext();
		IMIExecutionDMContext executionCtx = null;
		if (e.getStrId() != null) {
			executionCtx = createMIExecutionContext(containerDmc, e.getStrId());
			fThreadRunStates.remove(executionCtx);
		}
		getSession().dispatchEvent(new ExitedDMEvent(executionCtx, e), getProperties());
	}

	@DsfServiceEventHandler
	public void eventDispatched(ThreadResumedEvent e) {
		IMIExecutionDMContext context = DMContexts.getAncestorOfType(e.getDMContext(), IMIExecutionDMContext.class);
		if (context == null) {
			return;
		}
	}

	@DsfServiceEventHandler
	public void eventDispatched(ThreadSuspendedEvent e) {
		IMIExecutionDMContext context = DMContexts.getAncestorOfType(e.getDMContext(), IMIExecutionDMContext.class);
		if (context == null) {
			return;
		}
	}

	@DsfServiceEventHandler
	public void eventDispatched(ContainerResumedEvent e) {
		IMIExecutionDMContext context = DMContexts.getAncestorOfType(e.getDMContext(), IMIExecutionDMContext.class);
		if (context == null) {
			return;
		}
	}

	@DsfServiceEventHandler
	public void eventDispatched(ContainerSuspendedEvent e) {
		IMIExecutionDMContext context = DMContexts.getAncestorOfType(e.getDMContext(), IMIExecutionDMContext.class);
		if (context == null) {
			return;
		}
	}

	@DsfServiceEventHandler
	public void eventDispatched(BackendExitedEvent e) {
		fTerminated = true;
	}

}
