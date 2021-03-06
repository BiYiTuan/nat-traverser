/**
 * This file is part of the Kompics P2P Framework.
 *
 * Copyright (C) 2009 Swedish Institute of Computer Science (SICS) Copyright (C)
 * 2009 Royal Institute of Technology (KTH)
 *
 * Kompics is free software; you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 2 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program; if not, write to the Free Software Foundation, Inc., 59 Temple
 * Place - Suite 330, Boston, MA 02111-1307, USA.
 */
package se.sics.gvod.parentmaker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.sics.gvod.address.Address;
import se.sics.gvod.common.RTTStore;
import se.sics.gvod.common.RTTStore.RTT;
import se.sics.gvod.common.RetryComponentDelegator;
import se.sics.gvod.common.Self;
import se.sics.gvod.common.VodDescriptor;
import se.sics.gvod.common.evts.Join;
import se.sics.gvod.common.util.ToVodAddr;
import se.sics.gvod.config.ParentMakerConfiguration;
import se.sics.gvod.config.VodConfig;
import se.sics.gvod.croupier.PeerSamplePort;
import se.sics.gvod.croupier.events.CroupierSample;
import se.sics.gvod.croupier.snapshot.CroupierStats;
import se.sics.gvod.croupier.snapshot.Stats;
import se.sics.gvod.hp.msgs.HpRegisterMsg;
import se.sics.gvod.hp.msgs.HpRegisterMsg.RegisterStatus;
import se.sics.gvod.hp.msgs.HpUnregisterMsg;
import se.sics.gvod.hp.msgs.PRP_ConnectMsg;
import se.sics.gvod.hp.msgs.PRP_PreallocatedPortsMsg;
import se.sics.gvod.hp.msgs.ParentKeepAliveMsg;
import se.sics.gvod.nat.common.MsgRetryComponent;
import se.sics.gvod.net.NatNetworkControl;
import se.sics.gvod.net.Transport;
import se.sics.gvod.net.VodAddress;
import se.sics.gvod.net.events.PortAllocRequest;
import se.sics.gvod.net.events.PortDeleteRequest;
import se.sics.gvod.net.events.PortDeleteResponse;
import se.sics.gvod.net.msgs.ScheduleRetryTimeout;
import se.sics.gvod.net.util.NatReporter;
import se.sics.gvod.parentmaker.evts.PrpMorePortsResponse;
import se.sics.gvod.parentmaker.evts.PrpPortsResponse;
import se.sics.gvod.timer.*;
import se.sics.gvod.timer.TimeoutId;
import se.sics.kompics.Handler;
import se.sics.kompics.Negative;
import se.sics.kompics.Positive;
import se.sics.kompics.Start;
import se.sics.kompics.Stop;

/**
 * Private nodes (behind a NAT) require 'k' parent nodes so that other nodes are
 * able to talk to them, either via relaying or hole-punched connections. Parent
 * nodes can be any public node (node not behind a NAT) in the system. Private
 * nodes have to find public nodes, normally by discovering public nodes with a
 * peer sampling service, and then ask them to be their parent (send a
 * HpRegisterMsg.Request to the parent. The parent's RendezvousServer component
 * handles the request, and sends a HpRegisterMsg.Response, agreeing to be the
 * parent or not. If a parent agrees to take on a child (the private node), the
 * child starts heartbeating to the parent (KeepAliveMsg.Ping/Pong) to make sure
 * its NAT binding to the parent is always refreshed (otherwise it will timeout
 * and the parent won't be able to send msgs to the child).
 *
 * A parent can tell a child to disconnect (HpUnregisterMsg.Request) and a child
 * can change a parent, also sending the parent the same HpUnregisterMsg.Request
 * msg. Children swap out a parent with a new parent if the RTT to the new
 * parent is considerably better than the old parent. RTTStore is used to get
 * the RTT times to parents.
 *
 * For private nodes with PRP-type NATs, they send a set of available ports to
 * the parent, so that when other nodes want to talk to this private node, the
 * parent can tell them directly what port they should use to talk to the child.
 *
 * Parents can be black-listed for a short amount of time if
 * HpRegisterMsg.Request msgs to them fail a number of times.
 */
public class ParentMaker extends MsgRetryComponent {

    private static Logger logger = LoggerFactory.getLogger(ParentMaker.class);
    private Negative<ParentMakerPort> port = negative(ParentMakerPort.class);
    private Positive<NatNetworkControl> natNetworkControl = positive(NatNetworkControl.class);
    private Positive<PeerSamplePort> croupierPort = positive(PeerSamplePort.class);
    Self self;
    private String compName;
    Map<VodAddress, Connection> connections = new HashMap<VodAddress, Connection>();
    Map<Address, Long> rejections = new HashMap<Address, Long>();
    private Map<TimeoutId, Long> requestStartTimes = new HashMap<TimeoutId, Long>();
    private Set<Integer> outstandingParentRequests = new HashSet<Integer>();
    private Set<VodAddress> croupierSamples = new HashSet<VodAddress>();
    private ParentMakerConfiguration config;
    private long needParentsRoundTimeout, fullParentsRoundTimeout = 60 * 1000;
    private TimeoutId periodicTimeoutId;
    private boolean outstandingBids = false;
    private int count = 0;

    /*
     * For PRP-allocation-policy nodes, pre-bind a bunch of ports and send
     * them to the parent. If the parent needs more ports, it sends this
     * component a PRP_preallocatePortMsg.Request, which allocates more
     * ports and sends them back to the parent.
     */
    ConcurrentSkipListSet<Integer> portsInUse;
    Map<Integer, Set<Integer>> portsAssignedToParent = new HashMap<Integer, Set<Integer>>();

    class Connection {

        private RTT rtt;
        private TimeoutId timeoutId;
        private long lastSentPing;
        private long lastReceivedPong;

        public Connection(long lastKeepAliveMsgWasSentOn, RTT rtt, TimeoutId timeoutId) {
            if (rtt == null || timeoutId == null) {
                throw new NullPointerException("RTT or timeoutId cannot be null.");
            }
            this.rtt = rtt;
            this.timeoutId = timeoutId;
            this.lastSentPing = lastKeepAliveMsgWasSentOn;
        }

        public long getLastReceivedPong() {
            return lastReceivedPong;
        }

        public long getLastSentPing() {
            return lastSentPing;
        }

        public void setLastReceivedPong(long lastReceivedPong) {
            this.lastReceivedPong = lastReceivedPong;
        }

        public void setLastSentPing(long lastSentPing) {
            this.lastSentPing = lastSentPing;
        }

        public TimeoutId getTimeoutId() {
            return timeoutId;
        }

        public void setTimeoutId(TimeoutId timeoutId) {
            this.timeoutId = timeoutId;
        }

        public void setRtt(RTT rtt) {
            this.rtt = rtt;
        }

        public RTT getRtt() {
            return rtt;
        }
    }

    static class KeepBindingOpenTimeout extends Timeout {

        private final VodAddress parent;

        public KeepBindingOpenTimeout(SchedulePeriodicTimeout spt, VodAddress parent) {
            super(spt);
            this.parent = parent;
        }

        public VodAddress getParent() {
            return parent;
        }
    }

    public ParentMaker(ParentMakerInit init) {
        this(null, init);
    }

    public ParentMaker(RetryComponentDelegator delegator, ParentMakerInit init) {
        super(delegator);
        this.delegator.doAutoSubscribe();
        doInit(init);
    }

    private void doInit(ParentMakerInit init) {
        self = init.getSelf();
        compName = "PM(" + self.getId() + ") ";
        config = init.getConfig();
        portsInUse = init.getBoundPorts();
        VodConfig.PM_NUM_PARENTS = config.getNumParents();
    }

    public Handler<Start> handleStart = new Handler<Start>() {

        @Override
        public void handle(Start event) {
            if (!self.isOpen()) {
                needParentsRoundTimeout = config.getParentUpdatePeriod();
                ScheduleTimeout spt = new ScheduleTimeout(0);
                spt.setTimeoutEvent(new ParentMakerCycle(spt));
                delegator.doTrigger(spt, timer);
                periodicTimeoutId = spt.getTimeoutEvent().getTimeoutId();
            }
            logger.debug(compName + "started");
        }
    };
    Handler<Join> handleJoin = new Handler<Join>() {
        @Override
        public void handle(Join event) {
            List<VodAddress> candidateParents = event.getBootstrappers();
            for (VodAddress a : candidateParents) {
                sendRequest(a, config.getRto(), new HashSet<Integer>());
            }
        }
    };
    Handler<ParentMakerCycle> handleCycle = new Handler<ParentMakerCycle>() {
        @Override
        public void handle(ParentMakerCycle e) {

            // Print out list of parents periodically
            if (count % 5 == 0) {
                logger.debug(compName + getParentsAsStr());
            }

            List<RTT> currentRtts = getCurrentRtts();
            Set<VodAddress> currentParents = new HashSet<VodAddress>();
            for (RTT r : currentRtts) {
                currentParents.add(r.getAddress());
            }
            if (count++ > 5 && self.getParents().isEmpty()) {
                Stats pi = CroupierStats.instance(self.clone(VodConfig.SYSTEM_OVERLAY_ID));
                if (pi == null) {
                    logger.warn(compName + " Cannot find peer");
                } else {
                    logger.warn(compName + " no parents! " + self.getNat()
                            + pi
                            + " rejected: " + rejections.keySet().size()
                            + " num better rtts " + RTTStore.getOnAvgBest(self.getId(),
                                    config.getNumParents(),
                                    rejections.keySet()).size());
                }
            }

            List<RTT> betterRtts;
            if (currentRtts.size() < config.getNumParents()) {
                betterRtts = RTTStore.getOnAvgBest(self.getId(), config.getNumParents(),
                        rejections.keySet());
                if (betterRtts.isEmpty()) {
                    logger.warn(compName + "No better RTTS availabile ");
                }
            } else {
                RTT worstRtt = Collections.max(currentRtts, RTT.Order.ByRto);
                betterRtts = RTTStore.getAllOnAvgBetterRtts(self.getId(), worstRtt.getRTO(),
                        config.getKeepParentRttRange());
            }

            List<RTT> allRtts = new ArrayList<RTT>();
            allRtts.addAll(currentRtts);
            allRtts.addAll(betterRtts);
            if (!allRtts.isEmpty()) {
                Collections.sort(allRtts, RTT.Order.ByRto);
            }
            Iterator<RTT> iter = allRtts.iterator();
            for (int i = 0; i < (config.getNumParents() - currentRtts.size()); i++) {
                RTT min;
                if (!iter.hasNext()) {
                    logger.debug(compName + " no new RTT samples available");
                    croupierSamples.removeAll(currentParents);
                    if (croupierSamples.isEmpty()) {
                        removeRejections(config.getNumParents());
                        break;
                    }
                    min = new RTT(croupierSamples.iterator().next(), VodConfig.DEFAULT_RTO);
                } else {
                    min = iter.next();
                }
                if (min.getAddress().isOpen() == false) {
                    logger.error("Trying to add a NAT node as a parent!");
                    throw new IllegalStateException("Error with nat parent");
                }
                if (!currentRtts.contains(min)) {
                    if (self.getAddress().getNat().preallocatePorts()) {
                        PortAllocRequest allocReq = new PortAllocRequest(self.getIp(), self.getId(),
                                2, Transport.UDP);
                        PrpPortsResponse allocResp = new PrpPortsResponse(allocReq,
                                ToVodAddr.hpServer(min.getAddress().getPeerAddress()),
                                min.getRTO());
                        allocReq.setResponse(allocResp);
                        delegator.doTrigger(allocReq, natNetworkControl);
                    } else {
                        // if i have no connections, bid for the parent's slot with RTO as '0'
                        long normalizedRtt
                                = (connections.isEmpty() && !outstandingBids) ? 0 : min.getRTO();
                        sendRequest(min.getAddress(), normalizedRtt, new HashSet<Integer>());
                    }
                } else {
                    i--;
                }
            }

            // Cleanup all the parents that sent us rejections
            Set<Address> cleanupOldRejections = new HashSet<Address>();
            for (Entry<Address, Long> entry : rejections.entrySet()) {
                if (entry.getValue() > VodConfig.PM_PARENT_REJECTED_CLEANUP_TIMEOUT) {
                    cleanupOldRejections.add(entry.getKey());
                }
            }
            for (Address v : cleanupOldRejections) {
                rejections.remove(v);
            }

            long nextRound = needParentsRoundTimeout;
            if (config.getNumParents() == VodConfig.PM_NUM_PARENTS) {
                nextRound = fullParentsRoundTimeout;
            }

            ScheduleTimeout spt = new ScheduleTimeout(nextRound);
            spt.setTimeoutEvent(new ParentMakerCycle(spt));
            delegator.doTrigger(spt, timer);
            periodicTimeoutId = spt.getTimeoutEvent().getTimeoutId();
        }
    };

    /**
     * remove a random number of rejected server
     *
     * @param number
     */
    private void removeRejections(int number) {
        List<Address> nodes = new ArrayList<Address>();
        nodes.addAll(rejections.keySet());
        Random r = new Random(VodConfig.getSeed());
        if (number > rejections.size()) {
            number = rejections.size();
        }
        for (int i = 0; i < number; i++) {
            int n = r.nextInt(nodes.size());
            rejections.remove(nodes.get(n));
        }
    }

    private boolean isParent(VodAddress node) {
        return connections.containsKey(node);
    }

    private void addParent(VodAddress parent, Set<Integer> prpPorts) {
        if (!connections.containsKey(parent)) {
            // Ping my parents 5 seconds before the NAT binding will timeout
            SchedulePeriodicTimeout spt = new SchedulePeriodicTimeout(
                    self.getNat().getBindingTimeout() - 7000,
                    self.getNat().getBindingTimeout() - 7000);
            KeepBindingOpenTimeout pt = new KeepBindingOpenTimeout(spt, parent);
            spt.setTimeoutEvent(pt);
            TimeoutId timeoutId = pt.getTimeoutId();
            delegator.doTrigger(spt, timer);
            Connection connection = new Connection(System.currentTimeMillis(),
                    RTTStore.getRtt(self.getId(), parent),
                    timeoutId);
            connections.put(parent, connection);
            self.addParent(parent.getPeerAddress());
            logger.info(compName + "added new parent: {}. Now: " + getParentsAsStr(),
                    parent.getId());

            NatReporter.report(delegator, network, self.getAddress(),
                    self.getPort(), parent,
                    true, 0,
                    "New Parent Added with numPorts: " + prpPorts.size());
        } else {
            Connection c = connections.get(parent);
            c.setLastReceivedPong(System.currentTimeMillis());
            c.setRtt(RTTStore.getRtt(self.getId(), parent));
            logger.debug(compName + "Re-adding an existing parent: " + parent.toString());
        }
    }

    private void removeParent(VodAddress parent, boolean failed, boolean sendUnregisterReq) {
        if (failed) {
            RTTStore.removeSamples(self.getId(), parent);
            rejections.put(parent.getPeerAddress(), System.currentTimeMillis());
        }

        Connection c = connections.get(parent);
        if (c != null) {
            // cancel periodic pinging to parent
            TimeoutId timeoutId = c.getTimeoutId();
            delegator.doTrigger(new CancelPeriodicTimeout(timeoutId), timer);
            if (sendUnregisterReq) {
                // unregister from z-server
                HpUnregisterMsg.Request req = new HpUnregisterMsg.Request(self.getAddress(),
                        parent, config.getChildRemoveTimeout(), HpRegisterMsg.RegisterStatus.BETTER_PARENT);
                delegator.doRetry(req, config.getRto(), config.getRtoRetries(), self.getOverlayId());
            }
            connections.remove(parent);
            // free-up any PRP ports cached at the parent
            // There is a race condition here, where I may be in the middle of establishing 
            // a connection with another peer using one of these ports. That connection
            // will now fail.
            NatReporter.report(delegator, network, self.getAddress(),
                    self.getPort(), parent, true, 0,
                    "Parent removed with numPorts deleted: " + portsAssignedToParent.get(parent.getId()));
            logger.info(compName + "removing parent. Now: " + getParentsAsStr());
            deleteParentPorts(parent.getId());

        } else {
            logger.warn(compName + "Tried to remove non-existant parent: " + parent.getId());
            logger.warn(compName + "Existing parents: " + getParentsAsStr());
        }
        self.removeParent(parent.getPeerAddress());
    }

    Handler<KeepBindingOpenTimeout> handleKeepBindingOpenTimeout = new Handler<KeepBindingOpenTimeout>() {
        @Override
        public void handle(KeepBindingOpenTimeout event) {
            Connection c = connections.get(event.getParent());
            if (c != null) {
                long now = System.currentTimeMillis();
                c.setLastSentPing(now);
                VodAddress parentVodAddr = event.getParent();
                ParentKeepAliveMsg.Ping ping = new ParentKeepAliveMsg.Ping(self.getAddress(),
                        parentVodAddr);
                ScheduleRetryTimeout st
                        = new ScheduleRetryTimeout(config.getPingRto(),
                                config.getPingRetries(), config.getPingRtoScale());
                ParentKeepAliveMsg.PingTimeout pt = new ParentKeepAliveMsg.PingTimeout(st, ping);
                TimeoutId id = delegator.doRetry(pt);
                requestStartTimes.put(id, now);
            } else {
                logger.debug(compName + "No connection to parent to send Ping!");
                logger.debug(compName + "updated parents: " + getParentsAsStr());
            }
        }
    };
    Handler<ParentKeepAliveMsg.Pong> handleKeepAlivePong = new Handler<ParentKeepAliveMsg.Pong>() {
        @Override
        public void handle(ParentKeepAliveMsg.Pong msg) {
            if (cancelRetry(msg.getTimeoutId())) {
                Connection c = connections.get(msg.getVodSource());
                if (c != null) {
                    long t = System.currentTimeMillis();
                    c.setLastReceivedPong(t);
                    Long startTime = requestStartTimes.remove(msg.getTimeoutId());
                    if (startTime != null) {
                        long rttValue = t - startTime;
                        RTTStore.addSample(self.getId(), msg.getVodSource(), rttValue);
                    } else {
                        logger.warn("Couldn't find startTime at {} from {} for: "
                                + msg.getTimeoutId(), self.getAddress(),
                                msg.getVodSource());
                    }
                } else {
                    logger.debug(compName + "No connection to parent to receive Pong!");
                    logger.debug(compName + "Parents: " + getParentsAsStr());
                }
            }
        }
    };
    /**
     * parent is not responding to pings. Remove if enough parents left?
     */
    Handler<ParentKeepAliveMsg.PingTimeout> handleKeepAliveMsgPingTimeout = new Handler<ParentKeepAliveMsg.PingTimeout>() {
        @Override
        public void handle(ParentKeepAliveMsg.PingTimeout event) {
            if (delegator.doCancelRetry(event.getTimeoutId())) {
                requestStartTimes.remove(event.getTimeoutId());
                VodAddress parent = event.getRequestMsg().getVodDestination();
                CroupierStats.instance(self.clone(VodConfig.SYSTEM_OVERLAY_ID))
                        .parentChangeEvent(parent.getPeerAddress(),
                                HpRegisterMsg.RegisterStatus.DEAD_PARENT);
                removeParent(parent, true, true);
                logger.debug(compName + "Ping timeout to parent {} . Removing.", parent);
            }
        }
    };

    private void sendRequest(VodAddress hpServer, long rtt, Set<Integer> prpPorts) {
        if (connections.containsKey(hpServer)) {
            logger.debug(compName + " trying to re-add the parent: " + hpServer);
            deletePorts(prpPorts);
            return;
        }

        if (outstandingParentRequests.contains(hpServer.getId())) {
            logger.trace(compName + " connection request already ongoing for the parent: " + hpServer);
            deletePorts(prpPorts);
            return;
        }

        if (hpServer.getId() == self.getId()) {
            logger.debug(compName + " trying to add myself as a parent.");
            deletePorts(prpPorts);
            return;
        }

        if (hpServer.getPort() == VodConfig.DEFAULT_STUN_PORT
                || hpServer.getPort() == VodConfig.DEFAULT_STUN_PORT_2) {
            logger.debug(compName + " tried to send a parent request to a Stun Server");
            hpServer = ToVodAddr.hpServer(hpServer.getPeerAddress());
        }

        HpRegisterMsg.Request request = new HpRegisterMsg.Request(self.getAddress(),
                hpServer, rtt, prpPorts);
        outstandingBids = true;
        ScheduleRetryTimeout st = new ScheduleRetryTimeout(config.getRto(),
                config.getRtoRetries(), config.getRtoScale());
        HpRegisterMsg.RequestRetryTimeout requestTimeout
                = new HpRegisterMsg.RequestRetryTimeout(st, request);
        TimeoutId id = delegator.doRetry(requestTimeout);
        logger.debug(compName + "HpRegisterMsg.Request sent to " + hpServer);
        requestStartTimes.put(id, System.currentTimeMillis());
        outstandingParentRequests.add(hpServer.getId());
        addPortsToParent(hpServer.getId(), prpPorts);
    }

    Handler<HpRegisterMsg.Response> handleHpRegisterMsgResponse = new Handler<HpRegisterMsg.Response>() {
        @Override
        public void handle(HpRegisterMsg.Response msg) {

            outstandingParentRequests.remove(msg.getSource().getId());
            delegator.doCancelRetry(msg.getTimeoutId());
            // discard duplicate responses or late responses - unless I don't have any parents
            CroupierStats.instance(self.clone(VodConfig.SYSTEM_OVERLAY_ID)).parentChangeEvent(msg.getSource(),
                    msg.getResponseType());
            outstandingBids = false;
            Address peer = msg.getSource();
            TimeoutId id = msg.getTimeoutId();
            Long startTime = requestStartTimes.remove(id);
            long rtt = VodConfig.DEFAULT_RTO;
            if (startTime != null) {
                rtt = System.currentTimeMillis() - startTime;
                RTTStore.addSample(self.getId(), msg.getVodSource(), rtt);
            }
            if (msg.getResponseType() == HpRegisterMsg.RegisterStatus.REJECT) {
                rejections.put(peer, System.currentTimeMillis());
                logger.debug(compName + "Parent {} rejected client request",
                        peer);
                // free-up the ports that were allocated
                if (self.getNat().preallocatePorts()) {
                    for (int p : msg.getPrpPorts()) {
                        removePortFromParent(msg.getVodSource().getId(), p);
                    }
                    unbindPorts(msg.getPrpPorts());
                }
            } else if (msg.getResponseType() == HpRegisterMsg.RegisterStatus.ACCEPT) {
                addParentRtt(msg, rtt);
            } else if (msg.getResponseType() == HpRegisterMsg.RegisterStatus.ALREADY_REGISTERED) {
                if (!isParent(msg.getVodSource())) {
                    addParentRtt(msg, rtt);
                }
            } else {
                logger.warn(compName + "Parent {} client request failed due to "
                        + msg.getResponseType(), peer);
                if (self.getNat().preallocatePorts()) {
                    for (int p : msg.getPrpPorts()) {
                        removePortFromParent(msg.getVodSource().getId(), p);
                    }
                    unbindPorts(msg.getPrpPorts());
                }
            }
//            } else {
//                logger.warn(compName + "cancelRetry for ParentRequest failed for: {}. TimeoutId: {}. Current parents: "
//                + getParentsAsStr() + " reason: " + msg.getResponseType(), msg.getVodSource().getId(), 
//                msg.getTimeoutId().getId());
            // send unregister request to parent, as I don't want it as my parent.
//                HpUnregisterMsg.Request req = new HpUnregisterMsg.Request(self.getAddress(),
//                        msg.getVodSource(), 0, HpRegisterMsg.RegisterStatus.PARENT_REQUEST_FAILED);
//                delegator.doRetry(req, config.getRto(), config.getRtoRetries());
//                if (self.getNat().preallocatePorts()) {
//                }
//                CroupierStats.instance(self.clone(VodConfig.SYSTEM_OVERLAY_ID)).parentChangeEvent(msg.getSource(),
//                        HpRegisterMsg.RegisterStatus.PARENT_REQUEST_FAILED);
//            }

        }
    };

    private void deletePorts(Set<Integer> ports) {
        if (!ports.isEmpty()) {
            for (Integer p : ports) {
                portsInUse.remove(p);
            }
            PortDeleteRequest req = new PortDeleteRequest(self.getId(), ports);
            req.setResponse(new PortDeleteResponse(req, self.getId()) {
            });
            trigger(req, natNetworkControl);
        }
    }

    private void deleteParentPorts(int parentId) {
        Set<Integer> ports = portsAssignedToParent.remove(parentId);
        if (ports != null) {

            // don't unbind ports that are busy (allocated in HpClient)
            Set<Integer> portsBusy = new HashSet<Integer>();
            for (Integer p : ports) {
                if (portsInUse.contains(p)) {
                    portsBusy.add(p);
                }
            }
            ports.removeAll(portsBusy);
            // Now actually unbind the ports using Netty
            PortDeleteRequest req = new PortDeleteRequest(self.getId(), ports);
            req.setResponse(new PortDeleteResponse(req, self.getId()) {
            });
            trigger(req, natNetworkControl);
        } else {
            logger.warn("Couldn't find ports to delete for parent: " + parentId);
        }
    }

    private void addParentRtt(HpRegisterMsg.Response event, long rtt) {
        VodAddress candidateParent = event.getVodSource();

        // TODO check if already added, as then I should not start another timer
        if (connections.size() < config.getNumParents()) {
            addParent(candidateParent, event.getPrpPorts());
        } else {
            List<RTT> currentRtts = getCurrentRtts();
            RTT worstRtt = Collections.max(currentRtts, RTT.Order.ByRto);
            if (rtt + config.getKeepParentRttRange() < worstRtt.getRTO()) {
                VodAddress hpAddr = worstRtt.getAddress();
                removeParent(hpAddr, false, true);
                logger.info(compName + "Found a better parent {} . Removing {}."
                        + "Old/new RTTS = " + worstRtt.getRTO() + " vs "
                        + rtt,
                        candidateParent.getId(),
                        hpAddr.getId());
                addParent(candidateParent, event.getPrpPorts());
            }
        }
    }

    Handler<HpRegisterMsg.RequestRetryTimeout> handleHpRegisterMsgRequestTimeout
            = new Handler<HpRegisterMsg.RequestRetryTimeout>() {
                @Override
                public void handle(HpRegisterMsg.RequestRetryTimeout event) {

                    outstandingParentRequests.remove(event.getRequest().getDestination().getId());

                    if (delegator.doCancelRetry(event.getTimeoutId())) {
                        CroupierStats.instance(self.clone(VodConfig.SYSTEM_OVERLAY_ID)).parentChangeEvent(event.getRequest().getDestination(),
                                RegisterStatus.PARENT_REQUEST_FAILED);
                        outstandingBids = false;
                        requestStartTimes.remove(event.getTimeoutId());
                        rejections.put(event.getMsg().getDestination(), System.currentTimeMillis());
                        logger.warn(compName + "timeout HpRegisterReq {}",
                                event.getMsg().getDestination());
                        // free-up the ports that were allocated
                        if (self.getNat().preallocatePorts()) {
                            for (int p : event.getRequest().getPrpPorts()) {
                                removePortFromParent(event.getRequest().getVodDestination().getId(), p);
                            }
                            unbindPorts(event.getRequest().getPrpPorts());
                        }
                    }
                }
            };
    Handler<HpUnregisterMsg.Response> handleUnregisterParentResponse
            = new Handler<HpUnregisterMsg.Response>() {
                @Override
                public void handle(HpUnregisterMsg.Response event) {
                    if (delegator.doCancelRetry(event.getTimeoutId())) {
                        logger.debug(compName + "Successfully unregistered from {}",
                                event.getSource().getId());
                    } else {
                        logger.debug(compName + "Unsuccessful unregister from {}",
                                event.getSource().getId());
                    }
                }
            };

    Handler<HpUnregisterMsg.Request> handleUnregisterParentRequest
            = new Handler<HpUnregisterMsg.Request>() {
                @Override
                public void handle(HpUnregisterMsg.Request event) {
                    logger.debug(compName + "Parent {} telling me to unregister: {}",
                            event.getVodSource().getId(), event.getStatus());
                    CroupierStats.instance(self.clone(VodConfig.SYSTEM_OVERLAY_ID)).parentChangeEvent(event.getSource(),
                            HpRegisterMsg.RegisterStatus.BETTER_CHILD);
                    removeParent(event.getVodSource(), false, false);
                    logger.debug(compName + getParentsAsStr());
                }
            };

    private List<RTT> getCurrentRtts() {
        List<RTT> currentRtts = new ArrayList<RTT>();
        for (Connection c : connections.values()) {
            currentRtts.add(c.getRtt());
        }
        return currentRtts;
    }

    private String getParentsAsStr() {
        StringBuilder sb = new StringBuilder();
        sb.append(" Current parents: ");
        for (VodAddress a : connections.keySet()) {
            sb.append(a).append(" ;");
        }
        return sb.toString();
    }

    Handler<PRP_PreallocatedPortsMsg.Request> handlePRP_PreallocatedPortsMsg = new Handler<PRP_PreallocatedPortsMsg.Request>() {
        @Override
        public void handle(PRP_PreallocatedPortsMsg.Request request) {

            if (!connections.containsKey(request.getVodSource())) {
                PRP_PreallocatedPortsMsg.Response resp
                        = new PRP_PreallocatedPortsMsg.Response(self.getAddress(),
                                request.getVodSource(), request.getTimeoutId(),
                                PRP_PreallocatedPortsMsg.ResponseType.INVALID_NOT_A_PARENT,
                                null, request.getMsgTimeoutId());
                delegator.doTrigger(resp, network);
            } else {
                Long rto = 2000l;
                if (RTTStore.getRtt(self.getId(),
                        request.getVodSource()) != null) {
                    rto = RTTStore.getRtt(self.getId(),
                            request.getVodSource()).getRTO();
                }
                allocPorts(request.getVodSource(), rto, request.getTimeoutId(),
                        request.getMsgTimeoutId());
            }
        }
    };

    private void allocPorts(VodAddress server, Long rto, TimeoutId timeoutId,
            TimeoutId msgTimeoutId) {
        PortAllocRequest allocReq = new PortAllocRequest(self.getIp(), self.getId(), 3,
                Transport.UDP);
        PrpMorePortsResponse allocResp = new PrpMorePortsResponse(allocReq, server,
                timeoutId, rto, msgTimeoutId);
        allocReq.setResponse(allocResp);
        delegator.doTrigger(allocReq, natNetworkControl);
    }
    Handler<PrpMorePortsResponse> handlePrpMorePortsResponse
            = new Handler<PrpMorePortsResponse>() {
                @Override
                public void handle(PrpMorePortsResponse response) {
                    PRP_PreallocatedPortsMsg.Response resp = new PRP_PreallocatedPortsMsg.Response(
                            self.getAddress(), (VodAddress) response.getKey(),
                            response.getTimeoutId(),
                            PRP_PreallocatedPortsMsg.ResponseType.OK,
                            response.getAllocatedPorts(), response.getMsgTimeoutId());
                    delegator.doTrigger(resp, network);
                }
            };

    Handler<PrpPortsResponse> handlePrpPortsResponse = new Handler<PrpPortsResponse>() {
        @Override
        public void handle(PrpPortsResponse response) {
            long rtt = (connections.isEmpty() && !outstandingBids) ? 0
                    : response.getRto();
            for (Integer p : response.getAllocatedPorts()) {
                portsInUse.add(p);
            }

            sendRequest(response.getServer(), rtt, response.getAllocatedPorts());
        }
    };

    private void addPortsToParent(int parentId, Set<Integer> portsToAdd) {
        // These ports are now assigned to the parent - they are not 'in use'
        portsInUse.removeAll(portsToAdd);

        if (!portsToAdd.isEmpty()) {
            Set<Integer> ports = portsAssignedToParent.get(parentId);
            if (ports == null) {
                ports = new HashSet<Integer>();
            }
            ports.addAll(portsToAdd);
            portsAssignedToParent.put(parentId, ports);
        }
    }

    private void unbindPorts(Set<Integer> ports) {
        if (!ports.isEmpty()) {
            PortDeleteRequest req = new PortDeleteRequest(self.getId(), ports);
            req.setResponse(new PortDeleteResponse(req, self.getId()) {
            });
            trigger(req, natNetworkControl);
        }
    }

    private void removePortFromParent(int parentId, int port) {
        Set<Integer> ports = portsAssignedToParent.get(parentId);
        if (ports != null) {
            Set<Integer> updatedPorts = new HashSet<Integer>();
            updatedPorts.addAll(ports);
            updatedPorts.remove(port);
            if (updatedPorts.isEmpty()) {
                portsInUse.remove(parentId);
            } else {
                portsAssignedToParent.put(parentId, updatedPorts);
            }
        } else {
            logger.warn("Tried to remove port {} from parent {}, but was null", port,
                    parentId);
        }
    }

    /**
     * zServer tells me that it is using a PRP port. Delete it locally from my
     * connections. use this info when changing parent.
     *
     *
     */
    Handler<PRP_ConnectMsg.Response> handlePRP_PortUsedByParent = new Handler<PRP_ConnectMsg.Response>() {
        @Override
        public void handle(PRP_ConnectMsg.Response response) {
            removePortFromParent(response.getClientId(), response.getPortToUse());
        }
    };

    Handler<CroupierSample> handleCroupierSample = new Handler<CroupierSample>() {
        @Override
        public void handle(CroupierSample event) {
            List<VodDescriptor> newNodes = new ArrayList<VodDescriptor>();
            for (VodDescriptor d : event.getNodes()) {
                // create a copy of the VodDescriptors with the current node's overlayId
//                newNodes.add(d.clone(self.getOverlayId()));
            }
        }
    };

    @Override
    public void stop(Stop stop) {
        if (stop == null) {
            return;
        }
        if (periodicTimeoutId != null) {
            delegator.doTrigger(new CancelPeriodicTimeout(periodicTimeoutId), timer);
            periodicTimeoutId = null;
        }
        for (VodAddress parent : connections.keySet()) {
            removeParent(parent, false, true);
        }
    }
}
