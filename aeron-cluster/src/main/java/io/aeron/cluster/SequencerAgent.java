/*
 * Copyright 2017 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.cluster;

import io.aeron.*;
import io.aeron.archive.client.AeronArchive;
import io.aeron.cluster.codecs.*;
import io.aeron.cluster.service.ConsensusPos;
import io.aeron.cluster.service.RecordingLog;
import io.aeron.cluster.service.RecoveryState;
import io.aeron.logbuffer.ControlledFragmentHandler;
import io.aeron.logbuffer.Header;
import org.agrona.*;
import org.agrona.collections.ArrayListUtil;
import org.agrona.collections.Long2ObjectHashMap;
import org.agrona.concurrent.*;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static io.aeron.archive.client.AeronArchive.NULL_POSITION;
import static io.aeron.cluster.ClusterSession.State.*;
import static io.aeron.cluster.ConsensusModule.Configuration.SESSION_TIMEOUT_MSG;

class SequencerAgent implements Agent
{
    private final long sessionTimeoutMs;
    private long nextSessionId = 1;
    private long leadershipTermBeginPosition = 0;
    private int serviceAckCount = 0;
    private final Aeron aeron;
    private final AgentInvoker aeronClientInvoker;
    private final EpochClock epochClock;
    private final CachedEpochClock cachedEpochClock;
    private final TimerService timerService;
    private final ConsensusModuleAdapter consensusModuleAdapter;
    private final IngressAdapter ingressAdapter;
    private final EgressPublisher egressPublisher;
    private final AeronArchive aeronArchive;
    private final LogAppender logAppender;
    private final Counter messageIndex;
    private final Counter moduleState;
    private final Counter controlToggle;
    private final ClusterSessionSupplier sessionSupplier;
    private final Long2ObjectHashMap<ClusterSession> sessionByIdMap = new Long2ObjectHashMap<>();
    private final ArrayList<ClusterSession> pendingSessions = new ArrayList<>();
    private final ArrayList<ClusterSession> rejectedSessions = new ArrayList<>();
    private final ConsensusModule.Context ctx;
    private final Authenticator authenticator;
    private final SessionProxy sessionProxy;
    private final MutableDirectBuffer tempBuffer;
    private ConsensusTracker consensusTracker;
    private ConsensusModule.State state = ConsensusModule.State.INIT;

    SequencerAgent(
        final ConsensusModule.Context ctx,
        final EgressPublisher egressPublisher,
        final AeronArchive aeronArchive,
        final LogAppender logAppender,
        final IngressAdapterSupplier ingressAdapterSupplier,
        final TimerServiceSupplier timerServiceSupplier,
        final ClusterSessionSupplier clusterSessionSupplier,
        final ConsensusModuleAdapterSupplier consensusModuleAdapterSupplier)
    {
        this.ctx = ctx;
        this.aeron = ctx.aeron();
        this.epochClock = ctx.epochClock();
        this.cachedEpochClock = ctx.cachedEpochClock();
        this.sessionTimeoutMs = ctx.sessionTimeoutNs() / 1000;
        this.egressPublisher = egressPublisher;
        this.messageIndex = ctx.messageIndex();
        this.moduleState = ctx.moduleState();
        this.controlToggle = ctx.controlToggle();
        this.aeronArchive = aeronArchive;
        this.logAppender = logAppender;
        this.sessionSupplier = clusterSessionSupplier;
        this.sessionProxy = new SessionProxy(egressPublisher);
        this.tempBuffer = ctx.tempBuffer();

        ingressAdapter = ingressAdapterSupplier.newIngressAdapter(this);
        timerService = timerServiceSupplier.newTimerService(this);
        consensusModuleAdapter = consensusModuleAdapterSupplier.newConsensusModuleAdapter(this);
        authenticator = ctx.authenticatorSupplier().newAuthenticator(this.ctx);
        aeronClientInvoker = ctx.ownsAeronClient() ? ctx.aeron().conductorAgentInvoker() : null;
    }

    public void onClose()
    {
        if (!ctx.ownsAeronClient())
        {
            for (final ClusterSession session : sessionByIdMap.values())
            {
                session.close();
            }

            CloseHelper.close(ingressAdapter);
            CloseHelper.close(consensusModuleAdapter);
        }
    }

    public void onStart()
    {
        final IdleStrategy idleStrategy = ctx.idleStrategy();
        final RecordingLog.RecoveryPlan recoveryPlan = ctx.recordingLog().createRecoveryPlan(aeronArchive);

        serviceAckCount = 0;
        try (Counter ignore = addRecoveryStateCounter(recoveryPlan))
        {
            if (null != recoveryPlan.snapshotStep)
            {
                recoverFromSnapshot(recoveryPlan.snapshotStep, idleStrategy);
            }

            waitForServiceAcks(idleStrategy);

            if (recoveryPlan.termSteps.size() > 0)
            {
                state(ConsensusModule.State.REPLAY);
                recoverFromLog(recoveryPlan.termSteps, idleStrategy);
            }

            state(ConsensusModule.State.ACTIVE);
        }

        final long timestamp = epochClock.time();
        cachedEpochClock.update(timestamp);

        final long messageIndex = this.messageIndex.getWeak();
        final long position = leadershipTermBeginPosition;

        consensusTracker = new ConsensusTracker(
            aeron, ctx.tempBuffer(), position, messageIndex, logAppender.sessionId(), ctx.idleStrategy());

        ctx.recordingLog().appendTerm(consensusTracker.recordingId(), position, messageIndex, timestamp);
    }

    public int doWork()
    {
        int workCount = 0;

        final long nowMs = epochClock.time();
        cachedEpochClock.update(nowMs);

        if (null != aeronClientInvoker)
        {
            workCount += aeronClientInvoker.invoke();
        }

        if (null != consensusTracker)
        {
            consensusTracker.updatePosition();
        }

        workCount += checkControlToggle(nowMs);
        workCount += consensusModuleAdapter.poll();

        if (ConsensusModule.State.ACTIVE == state)
        {
            workCount += processPendingSessions(pendingSessions, nowMs);
            workCount += timerService.poll(nowMs);
            workCount += ingressAdapter.poll();
            workCount += checkSessions(sessionByIdMap, nowMs);
        }

        processRejectedSessions(rejectedSessions, nowMs);

        return workCount;
    }

    public String roleName()
    {
        return "sequencer";
    }

    public void onServiceActionAck(
        final long serviceId, final long logPosition, final long messageIndex, final ServiceAction action)
    {
        final long currentLogPosition = leadershipTermBeginPosition + logAppender.position();
        final long currentMessageIndex = this.messageIndex.getWeak();
        if (logPosition != currentLogPosition || messageIndex != currentMessageIndex)
        {
            throw new IllegalStateException("Invalid log state:" +
                " serviceId=" + serviceId +
                ", logPosition=" + logPosition + " current log position is " + currentLogPosition +
                ", messageIndex=" + messageIndex + " current message index is " + currentMessageIndex);
        }

        if (!state.isValid(action))
        {
            throw new IllegalStateException("Invalid action ack for state " + state + " action " + action);
        }

        if (++serviceAckCount == ctx.serviceCount())
        {
            switch (action)
            {
                case SNAPSHOT:
                    state(ConsensusModule.State.ACTIVE);
                    ClusterControl.ToggleState.reset(controlToggle);
                    break;

                case SHUTDOWN:
                    state(ConsensusModule.State.CLOSED);
                    ctx.shutdownSignalBarrier().signal();
                    break;

                case ABORT:
                    state(ConsensusModule.State.CLOSED);
                    ctx.shutdownSignalBarrier().signal();
                    break;
            }
        }
        else if (serviceAckCount > ctx.serviceCount())
        {
            throw new IllegalStateException("Service count exceeded: " + serviceAckCount);
        }
    }

    public void onSessionConnect(
        final long correlationId,
        final int responseStreamId,
        final String responseChannel,
        final byte[] credentialData)
    {
        final long nowMs = cachedEpochClock.time();
        final long sessionId = nextSessionId++;
        final ClusterSession session = sessionSupplier.newClusterSession(sessionId, responseStreamId, responseChannel);
        session.lastActivity(nowMs, correlationId);

        authenticator.onConnectRequest(sessionId, credentialData, nowMs);

        if (pendingSessions.size() + sessionByIdMap.size() < ctx.maxConcurrentSessions())
        {
            pendingSessions.add(session);
        }
        else
        {
            rejectedSessions.add(session);
        }
    }

    public void onSessionClose(final long clusterSessionId)
    {
        final ClusterSession session = sessionByIdMap.get(clusterSessionId);
        if (null != session)
        {
            session.close();
            if (appendClosedSession(session, CloseReason.USER_ACTION, cachedEpochClock.time()))
            {
                sessionByIdMap.remove(clusterSessionId);
            }
        }
    }

    public ControlledFragmentAssembler.Action onSessionMessage(
        final DirectBuffer buffer,
        final int offset,
        final int length,
        final long clusterSessionId,
        final long correlationId)
    {
        final long nowMs = cachedEpochClock.time();
        final ClusterSession session = sessionByIdMap.get(clusterSessionId);
        if (null == session || (session.state() == TIMED_OUT || session.state() == CLOSED))
        {
            return ControlledFragmentHandler.Action.CONTINUE;
        }

        if (session.state() == OPEN && logAppender.appendMessage(buffer, offset, length, nowMs))
        {
            messageIndex.incrementOrdered();
            session.lastActivity(nowMs, correlationId);

            return ControlledFragmentHandler.Action.CONTINUE;
        }

        return ControlledFragmentHandler.Action.ABORT;
    }

    public void onKeepAlive(final long correlationId, final long clusterSessionId)
    {
        final ClusterSession session = sessionByIdMap.get(clusterSessionId);
        if (null != session)
        {
            session.lastActivity(cachedEpochClock.time(), correlationId);
        }
    }

    public void onChallengeResponse(final long correlationId, final long clusterSessionId, final byte[] credentialData)
    {
        for (int lastIndex = pendingSessions.size() - 1, i = lastIndex; i >= 0; i--)
        {
            final ClusterSession session = pendingSessions.get(i);

            if (session.id() == clusterSessionId && session.state() == CHALLENGED)
            {
                final long nowMs = cachedEpochClock.time();

                session.lastActivity(nowMs, correlationId);

                authenticator.onChallengeResponse(clusterSessionId, credentialData, nowMs);
                break;
            }
        }
    }

    public boolean onTimerEvent(final long correlationId, final long nowMs)
    {
        if (logAppender.appendTimerEvent(correlationId, nowMs))
        {
            messageIndex.incrementOrdered();

            return true;
        }

        return false;
    }

    public void onScheduleTimer(final long correlationId, final long deadlineMs)
    {
        timerService.scheduleTimer(correlationId, deadlineMs);
    }

    public void onCancelTimer(final long correlationId)
    {
        timerService.cancelTimer(correlationId);
    }

    private void recoverFromSnapshot(final RecordingLog.ReplayStep snapshotStep, final IdleStrategy idleStrategy)
    {
        final RecordingLog.Entry snapshot = snapshotStep.entry;

        cachedEpochClock.update(snapshot.timestamp);
        leadershipTermBeginPosition = snapshot.logPosition;
        messageIndex.setOrdered(snapshot.messageIndex);

        loadSnapshot(snapshot.recordingId, idleStrategy);
    }

    private void recoverFromLog(final List<RecordingLog.ReplayStep> steps, final IdleStrategy idleStrategy)
    {
        final String channel = ctx.replayLogChannel();
        final int streamId = ctx.replayLogStreamId();

        try (Subscription subscription = aeron.addSubscription(channel, streamId))
        {
            for (int i = 0, size = steps.size(); i < size; i++)
            {
                final RecordingLog.ReplayStep step = steps.get(0);
                final RecordingLog.Entry entry = step.entry;
                final long recordingId = entry.recordingId;
                final long startPosition = step.recordingStartPosition;
                final long stopPosition = step.recordingStopPosition;
                final long length = NULL_POSITION == stopPosition ? Long.MAX_VALUE : stopPosition - startPosition;
                final long messageIndex = entry.messageIndex;
                final long logPosition = entry.logPosition;

                leadershipTermBeginPosition = logPosition;

                if (0 == startPosition)
                {
                    this.messageIndex.setOrdered(messageIndex);
                }

                final int sessionId = (int)aeronArchive.startReplay(
                    recordingId, startPosition, length, channel, streamId);

                idleStrategy.reset();
                Image image;
                while ((image = subscription.imageBySessionId(sessionId)) == null)
                {
                    idle(idleStrategy);
                }

                serviceAckCount = 0;
                try (Counter counter = ConsensusPos.allocate(
                    aeron, tempBuffer, recordingId, logPosition, messageIndex, sessionId, i))
                {
                    replayTerm(image, idleStrategy, counter);

                    waitForServiceAcks(idleStrategy);
                }
            }
        }
    }

    private Counter addRecoveryStateCounter(final RecordingLog.RecoveryPlan plan)
    {
        final int replayTermCount = plan.termSteps.size();
        final RecordingLog.ReplayStep snapshotStep = plan.snapshotStep;

        if (null != snapshotStep)
        {
            final RecordingLog.Entry snapshot = snapshotStep.entry;

            return RecoveryState.allocate(
                aeron, tempBuffer, snapshot.logPosition, snapshot.messageIndex, snapshot.timestamp, replayTermCount);
        }

        return RecoveryState.allocate(aeron, tempBuffer, 0, 0, 0, replayTermCount);
    }

    private static void checkInterruptedStatus()
    {
        if (Thread.currentThread().isInterrupted())
        {
            throw new RuntimeException("Unexpected interrupt");
        }
    }

    private void waitForServiceAcks(final IdleStrategy idleStrategy)
    {
        while (true)
        {
            final int fragmentsRead = consensusModuleAdapter.poll();
            if (serviceAckCount >= ctx.serviceCount())
            {
                break;
            }

            idle(idleStrategy, fragmentsRead);
        }
    }

    private void idle(final IdleStrategy idleStrategy, final int workCount)
    {
        checkInterruptedStatus();
        idleStrategy.idle(workCount);

        if (null != aeronClientInvoker)
        {
            aeronClientInvoker.invoke();
        }
    }

    private void idle(final IdleStrategy idleStrategy)
    {
        checkInterruptedStatus();
        idleStrategy.idle();

        if (null != aeronClientInvoker)
        {
            aeronClientInvoker.invoke();
        }
    }

    private void loadSnapshot(final long recordingId, final IdleStrategy idleStrategy)
    {
    }

    private void takeSnapshot()
    {
    }

    private void replayTerm(final Image image, final IdleStrategy idleStrategy, final Counter consensusPos)
    {
        final LogAdapter logAdapter = new LogAdapter(image, 10, this);

        while (true)
        {
            final int fragments = logAdapter.poll();
            if (fragments == 0)
            {
                if (image.isClosed())
                {
                    if (!image.isEndOfStream())
                    {
                        throw new IllegalStateException("Unexpected close");
                    }

                    break;
                }
            }
            else
            {
                consensusPos.setOrdered(image.position());
            }

            idle(idleStrategy, fragments);
        }
    }

    void state(final ConsensusModule.State state)
    {
        this.state = state;
        moduleState.set(state.code());
    }

    private int checkControlToggle(final long nowMs)
    {
        int workCount = 0;
        final ClusterControl.ToggleState toggleState = ClusterControl.ToggleState.get(controlToggle);

        switch (toggleState)
        {
            case SUSPEND:
                if (ConsensusModule.State.ACTIVE == state)
                {
                    state(ConsensusModule.State.SUSPENDED);
                    ClusterControl.ToggleState.reset(controlToggle);
                    workCount = 1;
                }
                break;

            case RESUME:
                if (ConsensusModule.State.SUSPENDED == state)
                {
                    state(ConsensusModule.State.ACTIVE);
                    ClusterControl.ToggleState.reset(controlToggle);
                    workCount = 1;
                }
                break;

            case SNAPSHOT:
                serviceAckCount = 0;
                if (ConsensusModule.State.ACTIVE == state && appendSnapshot(nowMs))
                {
                    state(ConsensusModule.State.SNAPSHOT);
                    takeSnapshot();
                    workCount = 1;
                }
                break;

            case SHUTDOWN:
                serviceAckCount = 0;
                if (ConsensusModule.State.ACTIVE == state && appendShutdown(nowMs))
                {
                    state(ConsensusModule.State.SHUTDOWN);
                    takeSnapshot();
                    workCount = 1;
                }
                break;

            case ABORT:
                serviceAckCount = 0;
                if (ConsensusModule.State.ACTIVE == state && appendAbort(nowMs))
                {
                    state(ConsensusModule.State.ABORT);
                    workCount = 1;
                }
                break;
        }

        return workCount;
    }

    private boolean appendSnapshot(final long nowMs)
    {
        final long position = resultingServiceActionPosition();

        if (logAppender.appendActionRequest(ServiceAction.SNAPSHOT, messageIndex.getWeak(), position, nowMs))
        {
            messageIndex.incrementOrdered();
            return true;
        }

        return false;
    }

    private boolean appendShutdown(final long nowMs)
    {
        final long position = resultingServiceActionPosition();

        if (logAppender.appendActionRequest(ServiceAction.SHUTDOWN, messageIndex.getWeak(), position, nowMs))
        {
            messageIndex.incrementOrdered();
            return true;
        }

        return false;
    }

    private boolean appendAbort(final long nowMs)
    {
        final long position = resultingServiceActionPosition();

        if (logAppender.appendActionRequest(ServiceAction.ABORT, messageIndex.getWeak(), position, nowMs))
        {
            messageIndex.incrementOrdered();
            return true;
        }

        return false;
    }

    private long resultingServiceActionPosition()
    {
        return leadershipTermBeginPosition +
            logAppender.position() +
            MessageHeaderEncoder.ENCODED_LENGTH +
            ServiceActionRequestEncoder.BLOCK_LENGTH;
    }

    private int processPendingSessions(final ArrayList<ClusterSession> pendingSessions, final long nowMs)
    {
        int workCount = 0;

        for (int lastIndex = pendingSessions.size() - 1, i = lastIndex; i >= 0; i--)
        {
            final ClusterSession session = pendingSessions.get(i);

            if (session.state() == INIT || session.state() == CONNECTED)
            {
                if (session.responsePublication().isConnected())
                {
                    session.state(CONNECTED);
                    sessionProxy.clusterSession(session);
                    authenticator.onProcessConnectedSession(sessionProxy, nowMs);
                }
            }

            if (session.state() == CHALLENGED)
            {
                if (session.responsePublication().isConnected())
                {
                    sessionProxy.clusterSession(session);
                    authenticator.onProcessChallengedSession(sessionProxy, nowMs);
                }
            }

            if (session.state() == AUTHENTICATED)
            {
                ArrayListUtil.fastUnorderedRemove(pendingSessions, i, lastIndex);
                lastIndex--;

                session.timeOfLastActivityMs(nowMs);
                sessionByIdMap.put(session.id(), session);

                appendConnectedSession(session, nowMs);

                workCount += 1;
            }
            else if (session.state() == REJECTED)
            {
                ArrayListUtil.fastUnorderedRemove(pendingSessions, i, lastIndex);
                lastIndex--;

                rejectedSessions.add(session);
            }
            else if (nowMs > (session.timeOfLastActivityMs() + sessionTimeoutMs))
            {
                ArrayListUtil.fastUnorderedRemove(pendingSessions, i, lastIndex);
                lastIndex--;

                session.close();
            }
        }

        return workCount;
    }

    private void processRejectedSessions(final ArrayList<ClusterSession> rejectedSessions, final long nowMs)
    {
        for (int lastIndex = rejectedSessions.size() - 1, i = lastIndex; i >= 0; i--)
        {
            final ClusterSession session = rejectedSessions.get(i);
            String detail = ConsensusModule.Configuration.SESSION_LIMIT_MSG;
            EventCode eventCode = EventCode.ERROR;

            if (session.state() == REJECTED)
            {
                detail = ConsensusModule.Configuration.SESSION_REJECTED_MSG;
                eventCode = EventCode.AUTHENTICATION_REJECTED;
            }

            if (egressPublisher.sendEvent(session, eventCode, detail) ||
                nowMs > (session.timeOfLastActivityMs() + sessionTimeoutMs))
            {
                ArrayListUtil.fastUnorderedRemove(rejectedSessions, i, lastIndex);
                lastIndex--;

                session.close();
            }
        }
    }

    private int checkSessions(final Long2ObjectHashMap<ClusterSession> sessionByIdMap, final long nowMs)
    {
        int workCount = 0;

        final Iterator<ClusterSession> iter = sessionByIdMap.values().iterator();
        while (iter.hasNext())
        {
            final ClusterSession session = iter.next();

            final ClusterSession.State state = session.state();
            if (nowMs > (session.timeOfLastActivityMs() + sessionTimeoutMs))
            {
                switch (state)
                {
                    case OPEN:
                        egressPublisher.sendEvent(session, EventCode.ERROR, SESSION_TIMEOUT_MSG);
                        if (appendClosedSession(session, CloseReason.TIMEOUT, nowMs))
                        {
                            iter.remove();
                            workCount += 1;
                        }
                        else
                        {
                            session.state(TIMED_OUT);
                        }
                        break;

                    case TIMED_OUT:
                    case CLOSED:
                        final CloseReason reason = state == TIMED_OUT ? CloseReason.TIMEOUT : CloseReason.USER_ACTION;
                        if (appendClosedSession(session, reason, nowMs))
                        {
                            iter.remove();
                            workCount += 1;
                        }
                        break;

                    default:
                        session.close();
                        iter.remove();
                }
            }
            else if (state == CONNECTED)
            {
                if (appendConnectedSession(session, nowMs))
                {
                    workCount += 1;
                }
            }
        }

        return workCount;
    }

    private boolean appendConnectedSession(final ClusterSession session, final long nowMs)
    {
        if (logAppender.appendConnectedSession(session, nowMs))
        {
            messageIndex.incrementOrdered();
            session.state(OPEN);

            return true;
        }

        return false;
    }

    private boolean appendClosedSession(final ClusterSession session, final CloseReason closeReason, final long nowMs)
    {
        if (logAppender.appendClosedSession(session, closeReason, nowMs))
        {
            messageIndex.incrementOrdered();
            session.close();

            return true;
        }

        return false;
    }

    void onReplaySessionMessage(
        final long correlationId,
        final long clusterSessionId,
        final long timestamp,
        final DirectBuffer buffer,
        final int offset,
        final int length,
        final Header header)
    {
        cachedEpochClock.update(timestamp);
        messageIndex.incrementOrdered();

        sessionByIdMap.get(clusterSessionId).lastActivity(timestamp, correlationId);
    }

    void onReplayTimerEvent(final long correlationId, final long timestamp)
    {
        cachedEpochClock.update(timestamp);
        messageIndex.incrementOrdered();

        if (!timerService.cancelTimer(correlationId))
        {
            // TODO: store correlationId until end of term replay and cancel it then due to race.
        }
    }

    void onReplaySessionOpen(
        final long correlationId,
        final long clusterSessionId,
        final long timestamp,
        final int responseStreamId,
        final String responseChannel)
    {
        cachedEpochClock.update(timestamp);
        messageIndex.incrementOrdered();

        final ClusterSession session = sessionSupplier.newClusterSession(
            clusterSessionId, responseStreamId, responseChannel);
        session.lastActivity(timestamp, correlationId);
        session.state(OPEN);

        sessionByIdMap.put(clusterSessionId, session);
    }

    void onReplaySessionClose(
        final long correlationId,
        final long clusterSessionId,
        final long timestamp,
        final CloseReason closeReason)
    {
        cachedEpochClock.update(timestamp);
        messageIndex.incrementOrdered();
        sessionByIdMap.remove(clusterSessionId).close();
    }

    void onReplayServiceAction(final long timestamp, final ServiceAction action)
    {
        cachedEpochClock.update(timestamp);
        messageIndex.incrementOrdered();
    }
}
