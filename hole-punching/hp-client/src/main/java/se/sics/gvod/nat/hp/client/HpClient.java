/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package se.sics.gvod.nat.hp.client;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.sics.gvod.address.Address;
import se.sics.gvod.common.RTTStore;
import se.sics.gvod.common.RetryComponentDelegator;
import se.sics.gvod.common.Self;
import se.sics.gvod.common.evts.GarbageCleanupTimeout;
import se.sics.gvod.common.hp.HPMechanism;
import se.sics.gvod.common.hp.HPRole;
import se.sics.gvod.common.hp.HolePunching;
import se.sics.gvod.common.hp.HpFeasability;
import se.sics.gvod.common.util.PortSelector;
import se.sics.gvod.common.util.ToVodAddr;
import se.sics.gvod.config.HpClientConfiguration;
import se.sics.gvod.config.VodConfig;
import se.sics.gvod.hp.events.OpenConnectionResponseType;
import se.sics.gvod.hp.msgs.DeleteConnectionMsg;
import se.sics.gvod.hp.msgs.GoMsg;
import se.sics.gvod.hp.msgs.HolePunchingMsg;
import se.sics.gvod.hp.msgs.HpConnectMsg;
import se.sics.gvod.hp.msgs.HpKeepAliveMsg;
import se.sics.gvod.hp.msgs.HpMsg;
import se.sics.gvod.hp.msgs.Interleaved_PRC_OpenHoleMsg;
import se.sics.gvod.hp.msgs.Interleaved_PRC_ServersRequestForPredictionMsg;
import se.sics.gvod.hp.msgs.Interleaved_PRP_ConnectMsg;
import se.sics.gvod.hp.msgs.PRC_OpenHoleMsg;
import se.sics.gvod.hp.msgs.PRC_ServerRequestForConsecutiveMsg;
import se.sics.gvod.hp.msgs.PRP_ConnectMsg;
import se.sics.gvod.hp.msgs.PRP_ServerRequestForAvailablePortsMsg;
import se.sics.gvod.hp.msgs.SHP_OpenHoleMsg;
import se.sics.gvod.nat.common.MsgRetryComponent;
import se.sics.gvod.nat.hp.client.events.DeleteConnection;
import se.sics.gvod.nat.hp.client.events.GoMsg_PortResponse;
import se.sics.gvod.nat.hp.client.events.InterleavedPRC_PortResponse;
import se.sics.gvod.nat.hp.client.events.InterleavedPRP_PortResponse;
import se.sics.gvod.nat.hp.client.events.OpenConnectionRequest;
import se.sics.gvod.nat.hp.client.events.OpenConnectionResponse;
import se.sics.gvod.nat.hp.client.events.PRC_PortResponse;
import se.sics.gvod.nat.hp.client.events.PRP_DummyMsgPortResponse;
import se.sics.gvod.nat.hp.client.events.PRP_PortResponse;
import se.sics.gvod.nat.hp.client.util.NatConnection;
import se.sics.gvod.net.Nat;
import se.sics.gvod.net.NatNetworkControl;
import se.sics.gvod.net.Transport;
import se.sics.gvod.net.VodAddress;
import se.sics.gvod.net.events.PortAllocRequest;
import se.sics.gvod.net.events.PortBindRequest;
import se.sics.gvod.net.events.PortBindResponse;
import se.sics.gvod.net.events.PortDeleteRequest;
import se.sics.gvod.net.events.PortDeleteResponse;
import se.sics.gvod.net.msgs.ScheduleRetryTimeout;
import se.sics.gvod.net.util.NatReporter;
import se.sics.gvod.timer.CancelTimeout;
import se.sics.gvod.timer.SchedulePeriodicTimeout;
import se.sics.gvod.timer.ScheduleTimeout;
import se.sics.gvod.timer.Timeout;
import se.sics.gvod.timer.TimeoutId;
import se.sics.gvod.timer.UUID;
import se.sics.kompics.Handler;
import se.sics.kompics.Negative;
import se.sics.kompics.Positive;
import se.sics.kompics.Start;
import se.sics.kompics.Stop;

/**
 * Protocols: CR, SHP, PRP, PRC, PRP-PRP, PRP-PRC See 'NatCracker: Combinations
 * Matter' for details on protocols.
 *
 * CR: Connection-reversal Applicability: b is open or UPnP Initiator protocol:
 * 1. a multicasts connect to {z}. {z} sends connect to b. b discards
 * duplicates. 2. b sends HpConnectMsg to a.
 *
 * SHP: Simple Hole-punching Applicability: f(a) == EI && f(b) == EI f(a) == EI
 * && m(a) == EI f(a) == EI && m(a) > EI && f(b) < PD --- requires dummyMsg from
 * initiator Initiator Protocol: 1a. a multicasts HpConnectMsg to {z} using p.
 * 2a. {z} creates session for SHP (a->b), registers record for a. 3a. {z} sends
 * ShpMsg.Initator to a, a sends HpRegisterMsg.Req to b, and z sends GoMsg to b
 * (using p). b discards duplicates. b (allocates new port and) tries to connect
 * to a. Responder Protocol: 1b. b sends HpConnectMsg.Req to Z. Z
 * ShpMsg.Initator to a and GoMsg to b.
 *
 *
 * PRP: Port prediction using preservation See Theorm 6.2 -- NatCracker:
 * Combinations Matter. Applicability: a(a) == PP && f(a) < PD && m(a) < PD a(a)
 * == PP && f(a) < PD && f(b) < PD Initiator Protocol: 1a. a allocates random
 * ports {p}. Multicasts msg m1 containng {p} to {z}. 2a. {z} acks m1 with p
 * port to use and dummy address for b. a sends dummy msg m2 to b. a discards
 * duplicates. {z} sends GoMsg to b. b retries GoMsg until success. Responder
 * Protocol: 1b. a sends hpConnect to {z}. {z} pops a preallocated port for b
 * from its ClientRegistrationRecord, and then b goes to 2a.
 *
 *
 * PRP-PRP: Port prediction using preservation on both sides Applicability: a(a)
 * == PP && a(b) == PP Initiator Protocol: 1. a allocates random ports {p}.
 * Multicasts msg m1 containg {p} to {z}. 2. {z} sends GoMsg with one of b's
 * cached prpPorts to a, and GoMsg with one of a's prpPorts to b. Responder
 * Protocol: Same.
 *
 * PRC = Port prediction using port contiguity Applicability: a(a) == PC && f(a)
 * < PD && m(a) < PD a(a) == PC && f(a) < PD && f(b) < PD Initiator Protocol:
 * 1a. a sends {z} PC connection request. {z} sends a du Responder Protocol: 1b.
 *
 *
 * PRC-PRC Applicability: a(a) == PC && a(b) == PC Initiator Protocol: 1a.
 * Responder Protocol: 1b.
 *
 *
 * PRC-PRP Applicability: a(a) == PP && a(b) == PC Initiator Protocol: 1a.
 * Responder Protocol: 1b.
 *
 * @author Salman, Jim In the comments Rendezvous server is sometimes referred
 * as z/Z server or just z/Z
 *
 *
 * State managed while doing holePunching:
 *
 * OpenedConnection: this is an object that stores a reference to a hole on the
 * destination address' NAT. This hole needs to be periodically refreshed, and
 * this is done by sending HpKeepAliveMsgPing messages.
 *
 * HpSession: this is a temporary object that holes the the state of an ongoing
 * hole-punching session between a src and a dest.
 *
 * HolePunching: these objects tells us for each HpSession, what type of
 * hole-punching algorithm to use, and what the roles of the participants are.
 *
 * HolePunchingMsg.Request/Response/ResponseAck: These are protocol messages
 * used to try and hole-punch through NATs. If they succeed, an OpenedConnection
 * object is created, and the HpSession/HolePunching objects are deleted.
 *
 * Garbage collection: we periodically clean up old OpenedConnection objects
 * that haven't been refreshed.
 *
 * Resource management: we don't bound the number of OpenedConnection objects,
 * so a DOS is possible.
 *
 *
 */
public class HpClient extends MsgRetryComponent {

    public static AtomicInteger portBoundFailure = new AtomicInteger();
    public static AtomicInteger pingSuccessCount = new AtomicInteger();
    public static AtomicInteger pingFailureCount = new AtomicInteger();
    public static AtomicInteger nonPingedConnections = new AtomicInteger();
    public static ConcurrentHashMap<String, Integer> failedPings
            = new ConcurrentHashMap<String, Integer>();
    private final Logger logger = LoggerFactory.getLogger(HpClient.class);
    private Negative<HpClientPort> hpClientPort = negative(HpClientPort.class);
    private Positive<NatNetworkControl> natNetworkControl = positive(NatNetworkControl.class);
    private Negative<HPStatsPort> hpStatsPort = negative(HPStatsPort.class);
    private Self self;
    private HpClientConfiguration config;
    /*
     * on going hole punching is stored in this map. map key is remoteClientID
     */
    HashMap<Integer, HpSession> hpSessions = new HashMap<Integer, HpSession>();
    /*
     * all the opened connections are stored in this map map key is remoteClientID.
     * openedConnections is a thread-safe data structure shared with the NatTraverser component.
     */
    ConcurrentHashMap<Integer, OpenedConnection> openedConnections;
    ConcurrentSkipListSet<Integer> portsInUse;

    /*
     * hp Stats
     */
    EnumMap<HPMechanism, HPStats> hpStats
            = new EnumMap<HPMechanism, HPStats>(HPMechanism.class);
    HashMap<Integer, Address> parents = new HashMap<Integer, Address>();
    /*
     * Measure time taken by heartbeat msgs.
     */
    private Map<Integer, Long> startTimers = new HashMap<Integer, Long>();
    /*
     * this is only for debugging. all out puts from this component will have
     * its name prepeneded to it. when multiple client are running at the same
     * time, this helps in identifying which message belongs to which client
     */
    private String compName;

    private class HpSession {

        private OpenConnectionRequest openConnectionRequest;
        private final int remoteClientID;
        private Nat remoteClientNat;
        private VodAddress remoteOpenedHole;
        private HPMechanism holePunchingMechanism;
        private HPRole holePunchingRole;
        private int portInUse;
        private Address dummyAddress;  // only used in prp and prc. initiator open a hole on the nat by sending a msg to dummy address
        private boolean hpOngoing = false;
        private int totalScanRetries = 5;
        private int scanRetriesCounter = 0;
        private final boolean scanningEnabled;
        private long sessionStartTime;
        private int delta = 1;
        private final boolean heartbeatConnection;
        private final TimeoutId msgTimeoutId;
        private boolean dedicatedPort;

        public HpSession(
                int remoteClientID,
                OpenConnectionRequest openConnectionRequest,
                int scanRetries,
                boolean scanningEnabled,
                long sessionStartTime,
                TimeoutId msgTimeoutId) {
            this.remoteClientID = remoteClientID;
            this.openConnectionRequest = openConnectionRequest;
            this.totalScanRetries = scanRetries;
            this.scanningEnabled = scanningEnabled;
            this.sessionStartTime = sessionStartTime;
            if (openConnectionRequest != null) {
                heartbeatConnection = openConnectionRequest.isKeepConnectionOpenWithHeartbeat();
            } else {
                // don't heartbeat the connection, as I didn't initiate it
                heartbeatConnection = false;
            }
            this.msgTimeoutId = msgTimeoutId;
            this.dedicatedPort = false;
        }

        public boolean isDedicatedPort() {
            return dedicatedPort;
        }

        public void setDedicatedPort() {
            this.dedicatedPort = true;
        }

        public TimeoutId getMsgTimeoutId() {
            return msgTimeoutId;
        }

        public boolean isHpOngoing() {
            return hpOngoing;
        }

        public void setHpOngoing(boolean hpOngoing) {
            this.hpOngoing = hpOngoing;
        }

        public boolean isHeartbeatConnection() {
            return heartbeatConnection;
        }

        public void setSessionStartTime(long sessionStartTime) {
            this.sessionStartTime = sessionStartTime;
        }

        public long getSessionStartTime() {
            return sessionStartTime;
        }

        public Nat getRemoteClientNat() {
            return remoteClientNat;
        }

        public void setRemoteClientNat(Nat remoteClientNat) {
            this.remoteClientNat = remoteClientNat;
        }

        public void setDelta(int delta) {
            this.delta = delta;
        }

        public int getDelta() {
            return delta;
        }

        public boolean isScanningPossible() {
            boolean val = false;
            if (holePunchingMechanism == HPMechanism.PRC
                    && holePunchingRole == HPRole.PRC_RESPONDER) {
                // we can do scanning
                val = true;
            } else if (holePunchingMechanism == HPMechanism.PRC_PRC) {
                // we can do scanning
                val = true;
            } else if (holePunchingMechanism == HPMechanism.PRP_PRC
                    && (holePunchingRole == HPRole.PRP_INTERLEAVED)) {
                val = true;
            }

            if (scanRetriesCounter < totalScanRetries && val && scanningEnabled) {
                return true;
            } else {
                return false;
            }
        }

        public VodAddress getNextScanningPort() {
            Address scanAddress = new Address(remoteOpenedHole.getIp(),
                    remoteOpenedHole.getPort() + delta,
                    remoteOpenedHole.getId());
            VodAddress toScan = new VodAddress(scanAddress, self.getOverlayId());
            scanRetriesCounter++;
            return toScan;
        }

        public void setOpenConnectionRequest(OpenConnectionRequest openConnectionRequest) {
            this.openConnectionRequest = openConnectionRequest;
        }

        public void setDummyAddress(Address dummyAddress) {
            this.dummyAddress = dummyAddress;
        }

        public Address getDummyAddress() {
            return dummyAddress;
        }

        public int getPortInUse() {
            return portInUse;
        }

        public void setPortInUse(int portInUse) {
            this.portInUse = portInUse;
        }

        public HPRole getHolePunchingRole() {
            return holePunchingRole;
        }

        public void setHolePunchingRole(HPRole holePunchingRole) {
            this.holePunchingRole = holePunchingRole;
        }

        public void setHolePunchingMechanism(HPMechanism holePunchingMechanism) {
            this.holePunchingMechanism = holePunchingMechanism;
        }

        public HPMechanism getHolePunchingMechanism() {
            return holePunchingMechanism;
        }

        public VodAddress getRemoteOpenedHole() {
            return remoteOpenedHole;
        }

        public void setRemoteOpenedHole(VodAddress remoteOpenedHole) {
            VodAddress dest = new VodAddress(remoteOpenedHole.getPeerAddress(),
                    self.getOverlayId(), remoteOpenedHole.getNat(), remoteOpenedHole.getParents());
            this.remoteOpenedHole = dest;
        }

        public OpenConnectionRequest getOpenConnectionRequest() {
            return openConnectionRequest;
        }

        public int getRemoteClientId() {
            return remoteClientID;
        }

        @Override
        public String toString() {
            return "(id:" + remoteClientID + "), (port:" + portInUse + "), (hole:"
                    + ((remoteOpenedHole == null) ? "null" : remoteOpenedHole.getPeerAddress())
                    + ")";
        }
    }

    private class SendHeartbeatTimeout extends Timeout {

        private final int remoteId;

        public SendHeartbeatTimeout(ScheduleTimeout st, int remoteId) {
            super(st);
            this.remoteId = remoteId;
        }

        public int getRemoteId() {
            return remoteId;
        }
    }

    public HpClient(HpClientInit init) {
        this(null, init);
    }

    public HpClient(RetryComponentDelegator delegator, HpClientInit init) {
        super(delegator);
        this.delegator.doAutoSubscribe();
        doInit(init);
    }

    private void doInit(HpClientInit init) {
        self = init.getSelf();
        config = init.getConfig();

        openedConnections = init.getOpenedConnections();
        portsInUse = init.getBoundPorts();

        compName = "(" + self.getId() + ") ";
    }

    public Handler<Start> handleStart = new Handler<Start>() {

        @Override
        public void handle(Start event) {
            // initialize the garbage collection
            SchedulePeriodicTimeout st = new SchedulePeriodicTimeout(1000, 1000);
            GarbageCleanupTimeout msgTimeout = new GarbageCleanupTimeout(st);
            st.setTimeoutEvent(msgTimeout);
            delegator.doTrigger(st, timer);
        }
    };
    Handler<GetHPStatsRequest> handleGetHPStatsRequest = new Handler<GetHPStatsRequest>() {
        @Override
        public void handle(GetHPStatsRequest event) {
            @SuppressWarnings("unchecked")
            GetHPStatsResponse response = new GetHPStatsResponse(event, event.getAttachment(),
                    (EnumMap<HPMechanism, HPStats>) hpStats.clone());
            delegator.doTrigger(response, hpStatsPort);
            logger.trace(compName + getHpStats());
        }
    };

    public String getHpStats() {

        //("Data point:             ");
        String msg = "";

        HPStats stats = hpStats.get(HPMechanism.SHP);
        if (stats != null) {
            msg += "[SHP: " + "Success: " + stats.getSuccessCounter() + " Total: " + stats.getStartCounter() + "], ";
        } else {
            msg += "[SHP: 0], ";
        }

        stats = hpStats.get(HPMechanism.PRP);
        if (stats != null) {
            msg += "[PRP: " + "Success: " + stats.getSuccessCounter() + " Total: " + stats.getStartCounter() + "], ";
        } else {
            msg += "[PRP: 0], ";
        }

        stats = hpStats.get(HPMechanism.PRC);
        if (stats != null) {
            msg += "[PRC: " + "Success: " + stats.getSuccessCounter() + " Total: " + stats.getStartCounter() + "], ";
        } else {
            msg += "[PRC: 0], ";
        }

        stats = hpStats.get(HPMechanism.PRP_PRP);
        if (stats != null) {
            msg += "[PRP-PRP: " + "Success: " + stats.getSuccessCounter() + " Total: " + stats.getStartCounter() + "], ";
        } else {
            msg += "[PRP-PRP: 0], ";
        }

        stats = hpStats.get(HPMechanism.PRP_PRC);
        if (stats != null) {
            msg += "[PRP-PRC: " + "Success: " + stats.getSuccessCounter() + " Total: " + stats.getStartCounter() + "], ";
        } else {
            msg += "[PRP-PRC: 0], ";
        }

        stats = hpStats.get(HPMechanism.PRC_PRC);
        if (stats != null) {
            msg += "[PRC-PRC: " + "Success: " + stats.getSuccessCounter() + " Total: " + stats.getStartCounter() + "], ";
        } else {
            msg += "[PRC-PRC: 0], ";
        }

        stats = hpStats.get(HPMechanism.CONNECTION_REVERSAL);
        if (stats != null) {
            msg += "[OHP: " + "Success: " + stats.getSuccessCounter() + " Total: " + stats.getStartCounter() + "], ";
        } else {
            msg += "[OHP: 0],";
        }

        return msg;
    }
    // Request Sent by upper component to open hole for communication between two clients
    Handler<OpenConnectionRequest> handleOpenConnectionRequest = new Handler<OpenConnectionRequest>() {
        @Override
        public void handle(OpenConnectionRequest request) {
            String compName = HpClient.this.compName + " - " + request.getMsgTimeoutId() + " - ";
            int remoteId = request.getRemoteClientId();
            logger.debug(compName + self.getNat().toString()
                    + ": Open Connection Request for remote ID ("
                    + remoteId + "): " + request.getRemoteAddress().getNatAsString());

            // TODO: pacing. Add a List<OpenConnectionRequest> objects. When
            // nat traversal completes, pull next OpenConnectionRequest off the last
            // and perform NAT traversal
            boolean pacing = request.isSkipPacing();
            TimeoutId msgTimeoutId = request.getMsgTimeoutId();

            VodAddress remoteAddr = request.getRemoteAddress();
            // first check if there is already an opened connection
            if (!openedConnections.containsKey(remoteId)) {
                logger.trace(compName + " Open Connectison Request. No openConnection"
                        + " found for remote client ID (" + remoteId + ")");
                if (hpSessions.containsKey(remoteId)) {
                    HpSession hp = hpSessions.get(remoteId);

                    if (hp.getHolePunchingMechanism() == HPMechanism.PRC_PRC) {
                        logger.debug(compName + "HP is already going on between " + self.getId() + ":"
                                + remoteId + " sessionID " + remoteId);
                        sendOpenConnectionResponseMessage(hp.getOpenConnectionRequest(),
                                remoteAddr,
                                OpenConnectionResponseType.HP_ALREADY_ONGOING,
                                HPMechanism.NONE, msgTimeoutId);
                        return;
                    }
                }
                if (!remoteAddr.hasParents()) {

                    sendOpenConnectionResponseMessage(request, remoteAddr,
                            OpenConnectionResponseType.NO_RENDEZVOUS_SERVERS_SUPPLIED,
                            HPMechanism.NONE,
                            request.getMsgTimeoutId());
                } else {
                    if (!hpSessions.containsKey(remoteId)) {
                        HpSession session = new HpSession(
                                remoteId,
                                request, config.getScanRetries(),
                                config.isScanningEnabled(),
                                System.currentTimeMillis(),
                                request.getMsgTimeoutId());
                        hpSessions.put(remoteId, session);
                    }
                    connectUsingHp(remoteAddr, request.getMsgTimeoutId());
                }
            } else { // connection already exists
                sendOpenConnectionResponseMessage(request,
                        remoteAddr, OpenConnectionResponseType.ALREADY_REGISTERED,
                        HPMechanism.NONE,
                        request.getMsgTimeoutId());
            }
        }
    };

    private void connectUsingHp(VodAddress remoteAddr, TimeoutId msgTimeoutId) {
        int remoteId = remoteAddr.getId();
        HpSession session = hpSessions.get(remoteId);

        // The hp object tells us what hp-algorithm to run and what our role is
        HolePunching hp = HpFeasability.isPossible(self.getAddress(), remoteAddr);
        if (hp != null) {
            if (hpStats.containsKey(hp.getHolePunchingMechanism())) {
                hpStats.get(hp.getHolePunchingMechanism()).incrementStartCounter();
            } else {
                HPStats stats = new HPStats();
                stats.incrementStartCounter();
                hpStats.put(hp.getHolePunchingMechanism(), stats);
            }

            logger.trace(compName + "HolePunching Client SimpleHolePunching Initiation request recvd. RemoteClient id is ("
                    + remoteId
                    + ") Mechanism: "
                    + hp.getHolePunchingMechanism() + " Role: " + hp.getClient_A_HPRole()
                    + " - " + msgTimeoutId);

            session.setHolePunchingMechanism(hp.getHolePunchingMechanism());
            session.setHolePunchingRole(hp.getClient_A_HPRole());
            if (hp.getClient_A_HPRole() == HPRole.PRP_INITIATOR) {
                // Tell the dest which port it can connect on.
                // Allocate a port, then tell zServers what the port is
                prpRequest(remoteId, hp.getClient_A_HPRole(),
                        msgTimeoutId);
            } else if (hp.getClient_A_HPRole() == HPRole.PRP_INTERLEAVED) {
                // both PRP_PRP and PRP_PRC will run this code
                for (Address p : remoteAddr.getParents()) {
                    interleavedPrpRequest(remoteId, p);
                }
            } else {
                // HPRole.PRP_RESPONDER, PRC_*, SHP_RESPONDER
                // NOTE: zServer will be rewritten in multicasting.
                if (remoteAddr.getParents().isEmpty()) {
                    logger.warn(compName + "Parents were null when zserver multicasting. "
                            + msgTimeoutId);
                    throw new IllegalStateException("Shouldn't have gotten this far");
                }
                Address addr = remoteAddr.getParents().iterator().next();
                // make sure to use SYSTEM_OVERLAY_ID for HolePunching
                VodAddress zServer = new VodAddress(addr, self.getOverlayId());
                HpConnectMsg.Request req = new HpConnectMsg.Request(self.getAddress(),
                        zServer, remoteId, remoteAddr.getDelta(), 0 /* rtt */, msgTimeoutId);
                ScheduleRetryTimeout st
                        = new ScheduleRetryTimeout(config.getRto(),
                                config.getRtoRetries(), config.getRtoScale());
                HpConnectMsg.RequestRetryTimeout requestRetryTimeout
                        = new HpConnectMsg.RequestRetryTimeout(st, req);
                delegator.doMulticast(requestRetryTimeout, remoteAddr.getParents());
            }
        } else {
            sendOpenConnectionResponseMessage(session.getOpenConnectionRequest(),
                    remoteAddr, OpenConnectionResponseType.NAT_COMBINATION_NOT_TRAVERSABLE,
                    HPMechanism.NOT_POSSIBLE, msgTimeoutId);
        }
    }

    /**
     * Delete the connection from the map, stops heartbeats to the remote node,
     * and if the connection had a dedicated port (because of a(PP)) then unbind
     * the port.
     *
     * @param remoteId
     * @param sendDeleteMsgToRemoteNode
     * @return
     */
    private boolean deleteConnection(int remoteId, boolean sendDeleteMsgToRemoteNode) {
        logger.info(compName + " request to delete the connection to: " + remoteId);
        OpenedConnection oc = openedConnections.get(remoteId);
        if (oc != null) {
            TimeoutId timeoutId = oc.getHeartbeatTimeoutId();
            if (timeoutId != null) {
                trigger(new CancelTimeout(timeoutId), timer);
            }
            if (sendDeleteMsgToRemoteNode) {
                Address srcAddr = new Address(self.getIp(), oc.getPortInUse(), self.getId());
                VodAddress newSourceAddress = new VodAddress(srcAddr,
                        self.getOverlayId(), self.getNat(), self.getParents());
                VodAddress destinationAddress
                        = new VodAddress(oc.getHoleOpened(), self.getOverlayId());
                DeleteConnectionMsg requestMsg = new DeleteConnectionMsg(newSourceAddress,
                        destinationAddress, self.getId(), UUID.nextUUID());
                delegator.doTrigger(requestMsg, network);
            }
            // Remove the local opened connection to the remote private node
            openedConnections.remove(remoteId);
            // If a port is allocated for this connection only, unbind the port.

            if (oc.isSharedPort() == false) {
                removeAndUnbindPort(oc.getPortInUse(), oc.isSharedPort());
            }
            return true;
        } else {
            logger.warn(compName + " No connection found to delete for id: " + remoteId);
            return false;
        }
    }

    /**
     * This both removes the port from our parentPorts map and unbinds the port
     * in Netty.
     *
     * @param port
     */
    private void unbindPort(int port) {
        removeAndUnbindPort(port, true);
    }

    Handler<DeleteConnection> handleDeleteConnectionRequest = new Handler<DeleteConnection>() {
        @Override
        public void handle(DeleteConnection request) {
            // steps in the deletion of the connection
            // first: stop the keepConnectionAlive comp. no more ping/pongs
            // second: send message to the remote client to delete the connection
            // third: delete the connection from the map
            deleteConnection(request.getRemoteId(), true);
        }
    };
    Handler<DeleteConnectionMsg> handleDeleteConnectionRequestMsg = new Handler<DeleteConnectionMsg>() {
        @Override
        public void handle(DeleteConnectionMsg request) {
            printMsg(request);
            deleteConnection(request.getRemoteClientId(), false);

        }
    };
    /**
     * Collect all the answers from the parents. Only send 1 answer back to the
     * client.
     */
    Handler<HpConnectMsg.Response> handleHpConnectMsgResponse = new Handler<HpConnectMsg.Response>() {
        @Override
        public void handle(HpConnectMsg.Response response) {
            printMsg(response);
            String compName = HpClient.this.compName + " - " + response.getMsgTimeoutId() + " - ";

            TimeoutId timeoutId = response.getTimeoutId();

            if (delegator.doCancelRetry(timeoutId)) {
                logger.debug(compName + timeoutId + " HpConnectMsg.Response " + response.getResponseType()
                        + " from " + response.getVodSource());
                HpSession hp = hpSessions.get(response.getRemoteClientId());
                OpenConnectionResponseType rt = response.getResponseType();
                if (hp != null && hp.getOpenConnectionRequest() != null) {
                    sendOpenConnectionResponseMessage(hp.getOpenConnectionRequest(),
                            response.getVodSource(), rt, hp.getHolePunchingMechanism(),
                            response.getMsgTimeoutId());
                } else {
                    logger.warn("HpSession was null for HpConnectMsgResponse");
                }
            }
        }
    };
    Handler<HpConnectMsg.RequestRetryTimeout> handleHpConnectMsgTimeout = new Handler<HpConnectMsg.RequestRetryTimeout>() {
        @Override
        public void handle(HpConnectMsg.RequestRetryTimeout event) {
            logger.trace(compName + event.getClass().getCanonicalName() + " "
                    + event.getRequestMsg().getMsgTimeoutId());

            HpSession hp = hpSessions.get(event.getRequestMsg().getDestination().getId());
            if (hp != null) {
                hp.setHpOngoing(false);
                sendOpenConnectionResponseMessage(hp.getOpenConnectionRequest(),
                        event.getRequestMsg().getVodDestination(),
                        OpenConnectionResponseType.HP_TIMEOUT,
                        hp.getHolePunchingMechanism(),
                        event.getRequestMsg().getMsgTimeoutId());
            } else {
                logger.warn("HpSession was null for HpConnectMsgTimeout");
            }
        }
    };
    Handler<SHP_OpenHoleMsg.Initiator> handleSHP_OpenHoleMsgInitiator = new Handler<SHP_OpenHoleMsg.Initiator>() {
        @Override
        public void handle(SHP_OpenHoleMsg.Initiator response) {
            printMsg(response);
            String compName = HpClient.this.compName + " - " + response.getMsgTimeoutId() + " - ";

            VodAddress dummyAddr = response.getDummyAddr();
            logger.debug(compName + " Open Hole message response is recvd Flag: "
                    + response.getResponseType()
                    + " zServer ID (" + response.getSource().getId() + ")");

            if (response.getResponseType() != SHP_OpenHoleMsg.ResponseType.OK) {
                HpSession session = hpSessions.get(dummyAddr.getId());
                if (session == null) {
                    Set<Address> zServers = new HashSet<Address>();
                    zServers.add(response.getSource());
                    session = new HpSession(dummyAddr.getId(),
                            null, 0, false, System.currentTimeMillis(),
                            response.getMsgTimeoutId());
                    session.setHolePunchingMechanism(HPMechanism.SHP);
                    session.setHolePunchingRole(HPRole.SHP_INITIATOR);
                    session.setHpOngoing(true);
                } else {
                    session.setSessionStartTime(System.currentTimeMillis());
                    session.setDummyAddress(dummyAddr.getPeerAddress());
                }
                session.setPortInUse(response.getVodDestination().getPort());

                int srcPort = response.getVodDestination().getPort();
                Address src = new Address(self.getIp(), srcPort, self.getId());
                sendHolePunchingMsg(new VodAddress(src, VodConfig.SYSTEM_OVERLAY_ID, self.getNat()),
                        session.getRemoteOpenedHole(),
                        response.getMsgTimeoutId(),
                        config.getRtoRetries(),
                        config.getRto(),
                        config.getRtoScale());
            }
        }
    };

//////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//
//                             HANDLERS SHARED BY ALL HP MECHANISMS
//
//////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    private void printSessions() {
        Set<Integer> keys = openedConnections.keySet();
        logger.debug(compName + "Session keys: ");
        for (Integer k : keys) {
            logger.debug(compName + "(" + k + ")");
        }
    }

    private void sendHolePunchingMsg(VodAddress sourceAddress, VodAddress dest,
            TimeoutId msgTimeoutId, int msgRetries, int rto, double scaleRetries) {
        dest = ToVodAddr.hpClient(dest.getPeerAddress(), dest.getNat());

        HolePunchingMsg.Request holePunchingMessage
                = new HolePunchingMsg.Request(sourceAddress, dest, msgTimeoutId);
        ScheduleRetryTimeout st
                = new ScheduleRetryTimeout(rto, msgRetries, scaleRetries);
        HolePunchingMsg.RequestTimeout requestRetryTimeout
                = new HolePunchingMsg.RequestTimeout(st, holePunchingMessage);
        delegator.doRetry(requestRetryTimeout);
        logger.debug(compName + " HolePunchingMsg.Request from "
                + sourceAddress.getPeerAddress() + " to "
                + dest.getPeerAddress() + " - " + msgTimeoutId);

    }
    Handler<HolePunchingMsg.Request> handleHolePunchingMsgRequest = new Handler<HolePunchingMsg.Request>() {
        @Override
        public void handle(HolePunchingMsg.Request request) {
            // hole punching is complete
            printMsg(request);
            String compName = HpClient.this.compName + " - " + request.getMsgTimeoutId() + " - ";

            int remoteId = request.getClientId();
            logger.debug(compName + " Hole Punching Request Message Recvd:  to {} from "
                    + request.getSource(), request.getDestination(), request.getSource());
            boolean holeExists = openedConnections.containsKey(remoteId) == true;
            if (holeExists) {
                logger.debug(compName + " Hole Punched connection already established with " + remoteId);
            }
            HpSession session = hpSessions.get(remoteId);
            int srcPort = request.getVodDestination().getPort();
            if (session == null && !holeExists) {
                session = new HpSession(remoteId, null,
                        config.getScanRetries(), config.isScanningEnabled(),
                        System.currentTimeMillis(), request.getMsgTimeoutId());
                session.setPortInUse(srcPort);
                session.setHpOngoing(true);
            }

            // sending the response to the remote client
            Address srcAddress = new Address(self.getIp(), srcPort, self.getId());
            VodAddress sourceAddress = new VodAddress(srcAddress, self.getOverlayId(),
                    self.getNat(), self.getParents());
            logger.debug(compName + "sending HolePunchingMsg.Response to ("
                    + request.getVodSource() + ") - " + request.getMsgTimeoutId() + " : "
                    + request.getTimeoutId() + " from " + srcAddress);
            HolePunchingMsg.Response hpResponse = new HolePunchingMsg.Response(sourceAddress,
                    request.getVodSource(), request.getTimeoutId(),
                    request.getMsgTimeoutId());
            ScheduleRetryTimeout st = new ScheduleRetryTimeout(
                    1000, 4, 1.0d //                    config.getRto(),config.getRtoRetries(), config.getRtoScale()
            );
            HolePunchingMsg.ResponseTimeout hrrt = new HolePunchingMsg.ResponseTimeout(st, hpResponse);
            delegator.doRetry(hrrt);

            // if the connection is not already opened, then send response to NatTraverser component
            if (!holeExists) {
                OpenConnectionRequest req = session.getOpenConnectionRequest();
                addOrUpdateOpenedConnectionNoSession(request.getSource(), srcPort, isSharedPort(session));
                if (req != null) {
                    logger.debug(compName + "sending response to the outer component from hp-ack-ack. remote client id ("
                            + request.getClientId() + ")");
                    sendOpenConnectionResponseMessage(req,
                            request.getVodSource(),
                            OpenConnectionResponseType.OK,
                            session.getHolePunchingMechanism(),
                            request.getMsgTimeoutId());
                    HPStats stats = hpStats.get(session.getHolePunchingMechanism());
                    if (stats != null) {
                        stats.incrementSuccessCounter();
                    }
                } else {
                    // no need to send response to the upper component
                    logger.debug(compName + "no need to send HpMsg.Request response to the upper component");
                }
                hpSessions.remove(session.getRemoteClientId());
            }
        }
    };
    Handler<HolePunchingMsg.Response> handleHolePunchingMsgResponse = new Handler<HolePunchingMsg.Response>() {
        @Override
        public void handle(HolePunchingMsg.Response response) {
            // if response is recvd --> hole punching was successful
            printMsg(response);
            String compName = HpClient.this.compName + " - " + response.getMsgTimeoutId() + " - "
                    + " from " + response.getSource() + " ";

            // use getSrcTimeoutId() - don't check the getTimeoutId() 
            // as this has the responseTimeoutId, not the requestTimeoutId
            if (delegator.doCancelRetry(response.getSrcTimeoutId())) {
                logger.debug(compName + "HolePunchingMsg.Response is recvd at: "
                        + response.getDestination() + " from: "
                        + response.getSource() + " - id: " + response.getMsgTimeoutId());

                int remoteId = response.getSource().getId();
                VodAddress openedHole = response.getVodSource();
                int srcPort = response.getDestination().getPort();
                HpSession session = hpSessions.get(remoteId);
                if (session != null) {
                    session.setHpOngoing(false);
                    srcPort = response.getVodDestination().getPort();
                    session.setPortInUse(srcPort);

//                    if (self.getNat().getMappingPolicy() == Nat.MappingPolicy.PORT_DEPENDENT
//                            && response.getVodSource().getNat().getFilteringPolicy()
//                            == Nat.FilteringPolicy.PORT_DEPENDENT) {
//                        // if the initiator receives a HolePunchingMsg.Request from the responder on 
//                        // port X, then the HolePunchingMsg.Response to the initiator is sent from port Y -
//                        // the initiator will create a new mapping if it sends a msg to port Y if
//                        // it has Nat.MappingPolicy.PORT_DEPENDENT. In this case, if the initiator
//                        // has Nat.FilteringPolicy.PORT_DEPENDENT, it will reject the response.
//                        // So, use the original openedHole to send the msg.
//                        if (session.getRemoteOpenedHole().equals(openedHole) == false) {
//                            logger.debug(compName + " ! openedHole {} <-> source of the msg {} ",
//                                    openedHole, response.getVodSource());
//                        }
//                        if (session.getRemoteOpenedHole() != null) {
//                            openedHole = session.getRemoteOpenedHole();
//                        }
//                    }
                    if (!openedConnections.containsKey(remoteId)) {
                        // If we use PRP, then we have to allocate a port for this session.
                        addOpenedConnection(openedHole, srcPort, session.isHeartbeatConnection(), isSharedPort(session));
                        logger.debug(compName + "Hole session registered f(" + self.getId() + ","
                                + remoteId + ") - received msg at " + response.getDestination());
                    }

                    // send response to upper component
                    if (session.getOpenConnectionRequest() != null) {
                        logger.debug(compName + "sending response to the outer component. remote client id (" + response.getSource().getId() + ")");
                        sendOpenConnectionResponseMessage(session.getOpenConnectionRequest(),
                                session.getRemoteOpenedHole(),
                                OpenConnectionResponseType.OK,
                                session.getHolePunchingMechanism(),
                                response.getMsgTimeoutId());
                        HPStats stats = hpStats.get(session.getHolePunchingMechanism());
                        if (stats != null) {
                            stats.incrementSuccessCounter();
                        }

                    } else {
                        // no need to send response to the upper component
                        logger.debug(compName + "no need to send HpMsg.Response response to the upper component");
                    }
                    logger.debug(compName + "Hole punching is successful for " + remoteId
                            + " Removing its key.");
                } else {
                    logger.debug(compName + "Session not found. Where the hell is my session from "
                            + remoteId);
                    printSessions();
                }
                Address srcAddr = new Address(self.getIp(), srcPort, self.getId());
                HolePunchingMsg.ResponseAck ack = new HolePunchingMsg.ResponseAck(
                        new VodAddress(srcAddr, self.getOverlayId(),
                                self.getNat(), self.getParents()),
                        openedHole, response.getTimeoutId(), response.getMsgTimeoutId());
                delegator.doTrigger(ack, network);
                logger.debug(compName + " sending HolePunchingMsg.ResponseAck to "
                        + response.getSource().getId());
            }
        }
    };
    Handler<HolePunchingMsg.RequestTimeout> handleHolePunchingRequestTimeout
            = new Handler<HolePunchingMsg.RequestTimeout>() {
                @Override
                public
                void handle(HolePunchingMsg.RequestTimeout event) {
                    String compName = HpClient.this.compName + " - "
                    + HolePunchingMsg.RequestTimeout.class
                    .getCanonicalName() + " "
                    + event.getRequestMsg().getMsgTimeoutId() + " - ";

                    logger.trace(compName
                            + event.getClass().getCanonicalName() + " - "
                            + event.getRequestMsg().getMsgTimeoutId());
                    int remoteId = event.getRequestMsg().getRemoteClientId();
                    // hole punching failed
                    // get the session and inform the upper layer
                    logger.debug(compName
                            + "Hole Punching Message request timeout remote client ID ("
                            + remoteId + ") zServer ID ("
                            + event.getRequestMsg().getRemoteClientId() + ") "
                            + " timeout ID " + event.getTimeoutId());
                    if (delegator.doCancelRetry(event.getTimeoutId())) {
                        HpSession session = hpSessions.get(remoteId);
                        // we only have to modify the destiantion address
                        HolePunchingMsg.Request requestMsg = event.getRequestMsg();

                        if (session != null) {
                            session.setHpOngoing(false);
                            if (session.isScanningPossible()
                            && !openedConnections.containsKey(session.getRemoteClientId())) {

                                // send the holepunching message on another port
                                VodAddress scanAddress = session.getNextScanningPort();
                                session.setRemoteOpenedHole(scanAddress);

                                logger.warn(compName + "Scanning for remote client (" + session.getRemoteClientId()
                                        + ") "
                                        //                                        + " new Destination Address is " + scanAddress
                                        + " session key " + remoteId);
                                sendHolePunchingMsg(self.getAddress(),
                                        scanAddress,
                                        requestMsg.getMsgTimeoutId(),
                                        config.getRtoRetries(),
                                        config.getRto(),
                                        config.getRtoScale());

                            } else {
                                if (session.getOpenConnectionRequest() != null) {

                                    if (session.isDedicatedPort()) {
                                        unbindPort(session.getPortInUse());
                                    }

                                    sendOpenConnectionResponseMessage(session.getOpenConnectionRequest(),
                                            session.getRemoteOpenedHole(),
                                            OpenConnectionResponseType.REMOTE_PEER_FAILED,
                                            session.getHolePunchingMechanism(),
                                            requestMsg.getMsgTimeoutId());
                                }
                            }
                        } else {
                            logger.debug(compName + " ERROR: session not found. session key "
                                    + remoteId);
                        }
                    }
                }
            };
    Handler<HolePunchingMsg.ResponseTimeout> handleHolePunchingResponseTimeout
            = new Handler<HolePunchingMsg.ResponseTimeout>() {
                @Override
                public void handle(HolePunchingMsg.ResponseTimeout event) {
                    if (delegator.doCancelRetry(event.getTimeoutId())) {
                        logger.debug(compName + "Couldn't establish connection even after "
                                + "receiving request with "
                                + event.getResponseMsg().getDestination()
                                + " - " + event.getResponseMsg().getMsgTimeoutId());
                    }
                }
            };

    private boolean isSharedPort(HpSession session) {
        boolean isSharedPort = false;
        if (session.getHolePunchingRole() != null) {
            isSharedPort = (session.getHolePunchingRole() == HPRole.PRP_INITIATOR
                    || session.getHolePunchingRole() == HPRole.PRP_INTERLEAVED);
        }
        return isSharedPort;
    }
    Handler<HolePunchingMsg.ResponseAck> handleHolePunchingResponseAck
            = new Handler<HolePunchingMsg.ResponseAck>() {
                @Override
                public void handle(HolePunchingMsg.ResponseAck msg) {
                    printMsg(msg);
                    String compName = HpClient.this.compName + " - " + msg.getMsgTimeoutId() + " - ";

                    if (delegator.doCancelRetry(msg.getTimeoutId())) {

                        int remoteId = msg.getSource().getId();
                        HpSession session = hpSessions.get(remoteId);
                        if (session == null) {
                            logger.warn(compName + "Couldn't find hpSession for " + remoteId);
                            return;
                        }

                        session.setHpOngoing(false);
                        if (!openedConnections.containsKey(remoteId)) {
                            if (session.getPortInUse() == 0) {
                                session.setPortInUse(msg.getDestination().getPort());
                            }
                            VodAddress openedHole = msg.getVodSource();
                            if (self.getNat().getMappingPolicy() == Nat.MappingPolicy.PORT_DEPENDENT
                            && msg.getVodSource().getNat().getFilteringPolicy()
                            == Nat.FilteringPolicy.PORT_DEPENDENT) {
                                openedHole = session.getRemoteOpenedHole();
                            }
                            addOrUpdateOpenedConnectionNoSession(openedHole.getPeerAddress(), msg.getDestination().getPort(),
                                    isSharedPort(session));
                            logger.debug(compName + "Local port :" + session.getPortInUse()
                                    + " for communicating with " + msg.getDestination());
                        } else {
                            openedConnections.get(remoteId).setLastUsed(System.currentTimeMillis());
                        }
                        // send response to the upper component
                        if (session.getOpenConnectionRequest() != null) {
                            logger.debug(compName + "sending response to the outer component. remote client id ("
                                    + msg.getSource().getId() + ")");
                            sendOpenConnectionResponseMessage(session.getOpenConnectionRequest(),
                                    msg.getVodSource(), OpenConnectionResponseType.OK,
                                    session.getHolePunchingMechanism(),
                                    msg.getMsgTimeoutId());

                            HPStats stats = hpStats.get(session.getHolePunchingMechanism());
                            if (stats != null) {
                                stats.incrementSuccessCounter();
                            }
                        } else {
                            logger.debug(compName + "not sending HpResponse.ResponseAck response to the outer component");
                        }

                    }
                }
            };

    private void addOpenedConnection(VodAddress openedHole, int srcPort,
            boolean isHeartbeat, boolean isSharedPort) {
        OpenedConnection openedConnection;
        int natBindingTimeout = (int) Math.min(self.getNat().getBindingTimeout(),
                openedHole.getNatBindingTimeout());
        openedConnection = new OpenedConnection(
                srcPort, isSharedPort,
                openedHole.getPeerAddress(),
                natBindingTimeout,
                isHeartbeat);
        openedConnections.put(openedHole.getId(), openedConnection);
        hpSessions.remove(openedHole.getId());
        if (isHeartbeat) {
            scheduleHeartbeat(openedHole.getId());
        } else {
            logger.debug(compName + "Not heartbeating " + openedHole.getId());
            nonPingedConnections.incrementAndGet();
        }
    }

    private void addOrUpdateOpenedConnectionNoSession(Address remote, int srcPort, boolean sharedPort) {
        OpenedConnection oc = openedConnections.get(remote.getId());
        if (oc == null) {
            logger.debug(compName + "Couldn't find openedConnection, but now adding one to: "
                    + remote + " from srcPort " + srcPort);
            OpenedConnection newOc = new OpenedConnection(srcPort, sharedPort, remote,
                    (int) self.getNat().getBindingTimeout(), true);
            openedConnections.put(remote.getId(), newOc);
            hpSessions.remove(remote.getId());
            scheduleHeartbeat(remote.getId());
        } else {
            logger.trace(compName + "Updating openedConnection to: " + remote.getId());
            oc.setLastUsed(System.currentTimeMillis());
        }
    }
    Handler<HpKeepAliveMsg.Ping> handleHpKeepAliveMsgPing
            = new Handler<HpKeepAliveMsg.Ping>() {
                @Override
                public void handle(HpKeepAliveMsg.Ping msg) {
                    NatConnection.refreshConnection(openedConnections,
                            msg.getVodSource(), msg.getDestination().getPort());
                    logger.trace(compName + "Received ping from: " + msg.getSource());
                    HpKeepAliveMsg.Pong reply = new HpKeepAliveMsg.Pong(msg.getVodDestination(),
                            msg.getVodSource(), msg.getTimeoutId());
                    delegator.doTrigger(reply, network);
                }
            };

    Handler<HpKeepAliveMsg.Pong> handleHpKeepAliveMsgPong
            = new Handler<HpKeepAliveMsg.Pong>() {
                @Override
                public void handle(HpKeepAliveMsg.Pong msg) {
                    int remoteId = msg.getSource().getId();
                    OpenedConnection oc = openedConnections.get(remoteId);
                    Long startTime = startTimers.remove(remoteId);
                    startTime = startTime == null ? 0 : startTime;
                    long timeTaken = System.currentTimeMillis() - startTime;
                    RTTStore.addSample(self.getId(), msg.getVodSource(), timeTaken);
                    if (delegator.doCancelRetry(msg.getTimeoutId())) {
                        logger.trace(compName + "Received pong from: " + msg.getSource());
                        if (oc == null) {
                            logger.warn(compName + "Couldn't find connection to heartbeat to: " + remoteId);
                        } else {
                            scheduleHeartbeat(oc.getHoleOpened().getId());
                        }
                        // update or add an openedConnection
                        addOrUpdateOpenedConnectionNoSession(msg.getSource(), msg.getDestination().getPort(), false);
                        pingSuccessCount.incrementAndGet();
                    } else {
                        logger.warn(compName + "Couldn't cancel timeoutId for HpKeepAliveMsg.Ping");
                    }
                }
            };

    private void scheduleHeartbeat(int remoteId) {
        OpenedConnection oc = openedConnections.get(remoteId);
        if (oc != null) {
            logger.trace(compName + "Scheduling heartbeat to " + remoteId);
            ScheduleTimeout st = new ScheduleTimeout(self.getNat().getBindingTimeout() / 2);
            SendHeartbeatTimeout sht = new SendHeartbeatTimeout(st, remoteId);
            st.setTimeoutEvent(sht);
            trigger(st, timer);
            oc.setHeartbeatTimeoutId(sht.getTimeoutId());
        }
    }

    private void sendHeartbeat(OpenedConnection oc) {
        VodAddress openedHole = new VodAddress(oc.getHoleOpened(), self.getOverlayId());
        VodAddress src = new VodAddress(new Address(self.getIp(), oc.getPortInUse(), self.getId()),
                self.getOverlayId(), self.getNat());
        HpKeepAliveMsg.Ping pingMsg = new HpKeepAliveMsg.Ping(src, openedHole);
        ScheduleRetryTimeout srt = new ScheduleRetryTimeout(2000, 3, 0.5);
        HpKeepAliveMsg.PingTimeout hbt = new HpKeepAliveMsg.PingTimeout(srt, pingMsg);
        delegator.doRetry(hbt);
        logger.trace(compName + "Sending heartbeat from " + self.getAddress()
                + "=>" + src.getPort() + " to : {} with timeout=" + 2000,
                openedHole);
        startTimers.put(openedHole.getId(), System.currentTimeMillis());
    }
    Handler<SendHeartbeatTimeout> handleSendHeartbeatTimeout
            = new Handler<SendHeartbeatTimeout>() {
                @Override
                public void handle(SendHeartbeatTimeout event) {
                    int remoteId = event.getRemoteId();
                    OpenedConnection oc = openedConnections.get(remoteId);
                    if (oc == null) {
                        logger.error(compName + "Couldn'd find connection to heartbeat to: " + remoteId);
                    } else {
                        sendHeartbeat(oc);
                    }
                }
            };
    Handler<HpKeepAliveMsg.PingTimeout> handleHpKeepAliveMsgPingTimeout
            = new Handler<HpKeepAliveMsg.PingTimeout>() {
                @Override
                public void handle(HpKeepAliveMsg.PingTimeout event) {
                    int remoteId = event.getMsg().getDestination().getId();

                    deleteConnection(remoteId, false);

                    logger.warn(compName + " heartbeat timed out to private node from "
                            + event.getRequestMsg().getSource()
                            + " Removing openedConnection to " + event.getMsg().getDestination()
                            + " #openNatConnections = " + openedConnections.size());
                    pingFailureCount.incrementAndGet();
                    HpKeepAliveMsg.Ping msg = (HpKeepAliveMsg.Ping) event.getMsg();
                    String str = self.getNat() + " - " + msg.getVodDestination().getNatAsString();
                    Integer i = failedPings.get(str);
                    if (i == null) {
                        i = 0;
                    }
                    i++;
                    failedPings.put(str, i);
                    startTimers.remove(remoteId);
                    NatReporter.report(delegator, network, self.getAddress(),
                            msg.getDestination().getPort(), msg.getVodSource(),
                            false, 0, "OpenedConnection Pong Failed ");
                }
            };
    Handler<GoMsg.Request> handleGoMsgRequest = new Handler<GoMsg.Request>() {
        @Override
        public void handle(GoMsg.Request request) {
            printMsg(request);
            String compName = HpClient.this.compName + " - " + request.getMsgTimeoutId() + " - ";

            // TODO:
            // CHECK FOR DUPLICATES
            // CHECK FOR ALREADY OPEN CONNECTIONS
            // TODO: Access control (DoS attacks)
            int remoteId = request.getRemoteId();
            logger.debug(compName + "Hole Punching Client Recvd GO Message. remote client is "
                    + remoteId
                    + " zServer ID (" + request.getSource().getId()
                    + ")  Mechanism: " + request.getHolePunchingMechanism()
                    + " Role: " + request.getHolePunchingRole()
                    + " MsgId " + request.getMsgTimeoutId());

            if (hpSessions.containsKey(remoteId)) {
                HpSession session = hpSessions.get(remoteId);
                if (session.isHpOngoing()) {
                    logger.debug(compName + "DUPLICATE Hole Punching Client Recvd GO Message. remote client is "
                            + remoteId
                            + " zServer ID (" + request.getSource().getId()
                            + ")  Mechanism: " + request.getHolePunchingMechanism()
                            + " Role: " + request.getHolePunchingRole()
                            + " MsgId " + request.getMsgTimeoutId());
                    if (request.get_PRP_PRP_InterleavedPort() != 0
                            && self.getNat().preallocatePorts()) {
                        removeAndUnbindPort(request.get_PRP_PRP_InterleavedPort(), true);
                    }
                    return;
                }
            }

            // first get my session
            // THE SESSION MAY NOT EXIST, why?
            // consider the situation where A and B register with z
            // and only A asks z to do hole punching with B.
            // and if A is the initiator then first message B will recv will be
            // a GO message. From this GO message B must infer that A is trying to
            // talk to it.
            // The session key will be the combination of the id of the client that
            // wants to talk to B and the id of the z server
            HpSession session = hpSessions.get(remoteId);

            if (session == null) { // PRP-remote nodes here
                // make a session and store it in the sessions map
                session = new HpSession(remoteId,
                        null /* upper app comp did not ask for HP */,
                        config.getScanRetries(),
                        config.isScanningEnabled(),
                        System.currentTimeMillis(),
                        request.getMsgTimeoutId());
                session.setHpOngoing(true);
                // put the session in the map
                hpSessions.put(remoteId, session);
                logger.debug(compName + " Putting session after GoMsg. Remote-id: " + remoteId);
            }

            session.setHolePunchingMechanism(request.getHolePunchingMechanism());
            session.setHolePunchingRole(request.getHolePunchingRole());
            session.setRemoteOpenedHole(request.getOpenedHole());
            session.setHpOngoing(true);
            Nat nat = request.getOpenedHole().getNat();
            session.setRemoteClientNat(nat);

            OpenedConnection oc = openedConnections.get(remoteId);
            // if the connection is already open, and has been used in the last
            // 30 seconds, and has the same hole, then don't re-run hole-punching
//            if (oc != null && oc.getLastUsed() > Nat.DEFAULT_RULE_EXPIRATION_TIME //                    && oc.getHoleOpened().equals(request.getOpenedHole())
//                    ) {
//                logger.debug(compName+"Duplicate hole-punching request. Hole-punching already"
//                        + "completed.");
//                int port = (request.get_PRP_PRP_InterleavedPort() == 0) ?
//                        request.getDestination().getPort() :
//                        request.get_PRP_PRP_InterleavedPort();
//                Address myAddr = new Address(request.getDestination().getIp(),
//                        port,  request.getDestination().getId());
//                
//                
//                sendHolePunchingMsg(new VodAddress(myAddr, self.getOverlayId(), 
//                        self.getNat(), self.getParents()),
//                        session.getRemoteOpenedHole(),
//                        request.getVodSource().getId(),
//                        request.getRtoRetries(), 1000, 1.2d);
//                return;
//            } else 
            if (oc != null && session.getOpenConnectionRequest() != null) {
                OpenConnectionRequest ocr = session.getOpenConnectionRequest();
                if (!oc.isExpired()) {
                    sendOpenConnectionResponseMessage(ocr,
                            session.getRemoteOpenedHole(),
                            OpenConnectionResponseType.ALREADY_REGISTERED,
                            session.getHolePunchingMechanism(),
                            request.getMsgTimeoutId());
                    return;
                } else {
                    openedConnections.remove(oc.getHoleOpened().getId());
                    logger.debug(compName + " removing openedConnection " + oc.getHoleOpened());
                }
            }

            int portToBeUsed = 0;
            boolean bindFirst = request.isBindPort();
            if (session.getHolePunchingMechanism() == HPMechanism.CONNECTION_REVERSAL) {
                logger.debug(compName + " Connection reversal");
                portToBeUsed = self.getPort();
            } else if ((session.getHolePunchingMechanism() == HPMechanism.PRP_PRP)
                    //                    && newSession == false)
                    || session.getHolePunchingMechanism() == HPMechanism.PRC_PRC
                    || session.getHolePunchingMechanism() == HPMechanism.PRP_PRC) {
                if (session.getHolePunchingRole() == HPRole.PRP_INTERLEAVED) {
                    logger.debug(compName + " PRP-PRC/P mechanism");
                    portToBeUsed = request.get_PRP_PRP_InterleavedPort();
                } else if (session.getHolePunchingRole() == HPRole.PRC_INTERLEAVED) {
                    // this is the port that was used to send the prediction message to the z server.
                    logger.debug(compName + " PRC-PRC/P mechanism");
                    portToBeUsed = session.getPortInUse();
                }
            } else if (session.getHolePunchingMechanism() == HPMechanism.SHP
                    || session.getHolePunchingMechanism() == HPMechanism.PRP
                    || session.getHolePunchingMechanism() == HPMechanism.PRC
                    || session.getHolePunchingMechanism() == HPMechanism.SHP) {
                logger.debug(compName + " SHP/PRP/PRC mechanism");
                bindFirst = true;
                portToBeUsed = PortSelector.selectRandomPortOver50000();
            }
            session.setPortInUse(portToBeUsed);

            if (bindFirst) {
                logger.debug(compName + " Need to bind to port before GoMsg : " + portToBeUsed
                        + " - " + request.getMsgTimeoutId());
                Address a = new Address(self.getIp(), portToBeUsed, self.getId());
                PortBindRequest bindReq = new PortBindRequest(a, Transport.UDP);
                GoMsg_PortResponse bindResp = new GoMsg_PortResponse(bindReq, remoteId,
                        request.getRtoRetries(),
                        request.isBindPort(),
                        request.getMsgTimeoutId(),
                        request.getClientId());
                bindReq.setResponse(bindResp);
                session.setDedicatedPort();
                delegator.doTrigger(bindReq, natNetworkControl);
                addBoundPort(portToBeUsed);
            } else {
                prepareAndSendHPMessage(portToBeUsed, remoteId,
                        request.getMsgTimeoutId());
            }
        }
    };

    private void addBoundPort(int port) {
        portsInUse.add(port);
    }

    private void removeAndUnbindPort(int port, boolean unbind) {
        if (port != VodConfig.getPort()) {  // paranoid check that we  don't delete the default port
            boolean succeed = portsInUse.remove(port);
            if (succeed && unbind) {
                logger.info(compName + "removing and unbinding port: " + port);
                Set<Integer> portsToDelete = new HashSet<Integer>();
                portsToDelete.add(port);
                PortDeleteRequest req = new PortDeleteRequest(self.getId(), portsToDelete);
                req.setResponse(new PortDeleteResponse(req, self.getId()) {
                });
                delegator.doTrigger(req, natNetworkControl);
            }
        }
    }

    private void removeAndUnbindPorts(Set<Integer> ports, boolean unbind) {
        ports.remove(VodConfig.getPort()); // paranoid, don't remove default port
        for (Integer port : ports) {
            boolean succeed = portsInUse.remove(port);
            if (succeed && unbind) {
                Set<Integer> portsToDelete = new HashSet<Integer>();
                portsToDelete.add(port);
                PortDeleteRequest req = new PortDeleteRequest(self.getId(), portsToDelete);
                req.setResponse(new PortDeleteResponse(req, self.getId()) {
                });
                delegator.doTrigger(req, natNetworkControl);
            }
        }
    }

    private void printMsg(HpMsg.Hp msg) {
        logger.trace(compName + msg.getClass().getCanonicalName() + " - "
                + msg.getMsgTimeoutId());
    }
    Handler<GoMsg_PortResponse> handleGoMsg_PortResponse
            = new Handler<GoMsg_PortResponse>() {
                @Override
                public void handle(GoMsg_PortResponse response) {
                    String compName = HpClient.this.compName + " GoMsg_PortResponse - " + response.getMsgTimeoutId() + " - ";
                    int port = response.getPort();

                    if (response.getStatus() == PortBindResponse.Status.SUCCESS
                    || response.getStatus() == PortBindResponse.Status.PORT_ALREADY_BOUND) {
                        prepareAndSendHPMessage(port, (Integer) response.getKey(),
                                response.getMsgTimeoutId());

                        // TODO - could get allocate a new port, and tell the parent to use it.
//                    } else if (
//                    && response.isFixedPort() == false) {
//                        portBoundFailure.incrementAndGet();
//                        // If the port is already bound and the MappingPolicy is PD, then the port
//                        // cannot be reused for a different network connection.
//                        if (response.getRetries() > 0) {
//                            int newPort = PortSelector.selectRandomPortOver50000();
//                            Address a = new Address(self.getIp(), newPort, self.getId());
//                            PortBindRequest bindReq = new PortBindRequest(a, Transport.UDP);
//                            GoMsg_PortResponse bindResp = new GoMsg_PortResponse(bindReq,
//                                    response.getKey(),
//                                    response.getRetries() - 1, false, response.getMsgTimeoutId());
//                            bindReq.setResponse(bindResp);
//                            delegator.doTrigger(bindReq, natNetworkControl);
//                            HpSession session = hpSessions.get(a);
//                            session.setDedicatedPort();
//                        } else {
//                            logger.warn(compName + "GoMsg failed. Already bound port {}, no retries left",
//                                    response.getPort());
//                        }
                    } else {
                        // some unrecoverable failure.
                        removeAndUnbindPort(port, false);
                        logger.warn(compName + "GoMsg failed when trying to bind a port "
                                + response.getStatus());
                    }
                }
            };

    private void prepareAndSendHPMessage(int srcPort, Integer remoteClientId,
            TimeoutId msgTimeoutId) {
        // sanity check
        assert (srcPort >= 1024 && srcPort <= 65535);
        HpSession session = hpSessions.get(remoteClientId);
        if (session == null) {
            logger.error(compName + " couldn't find session for " + remoteClientId);
            return;
        }

        session.setPortInUse(srcPort);
        session.setHpOngoing(true);

        Address newSourceAddr = new Address(self.getIp(), session.getPortInUse(), self.getId());
        VodAddress newSourceAddress = new VodAddress(newSourceAddr, self.getOverlayId(),
                self.getNat(), self.getParents());

        //sendDummyMessagesToTestScanning(1);
        // scenario "{m(EI)_a(PP)_alt(PC)_f(PD)}_{m(PD)_a(PC)_alt(PC)_f(PD)}"
        // in this scenario you will get an exception if you send the hp message 
        // "messageRetries" number of times.  
        // The client with a(PC) has to send the hp msg multiple times so that scanning succeeds
        // only for prp-prc not for prc-prc
        // TODO - guestimate an RTO, if not available in the RTTs cache.
        int rtoRetries = config.getRtoRetries();
        if (session.getHolePunchingMechanism() == HPMechanism.PRC_PRC) {
            rtoRetries = rtoRetries + config.getScanRetries() * (rtoRetries + 1) + 2 /*
                     * 2 is added to be on the safe side
                     */;
            logger.debug(compName + "possible situation where there has to be a lot"
                    + " of retries. remote client Id (" + session.getRemoteClientId()
                    + ") total retries " + rtoRetries);
        }

        sendHolePunchingMsg(newSourceAddress, session.getRemoteOpenedHole(),
                msgTimeoutId, rtoRetries, config.getRto(),
                config.getRtoScale());
    }
    Handler<GarbageCleanupTimeout> handleGarbageCleanupTimeout = new Handler<GarbageCleanupTimeout>() {
        @Override
        public void handle(GarbageCleanupTimeout event) {
            // First cleanup hpSessions
            Set<Integer> toBeDeleted = new HashSet<Integer>();
            for (Integer key : hpSessions.keySet()) {
                HpSession session = hpSessions.get(key);
                if ((System.currentTimeMillis() - session.getSessionStartTime()) > config.getSessionExpirationTime()) {
                    toBeDeleted.add(key);
                }
            }
            for (Integer key : toBeDeleted) {
                HpSession session = hpSessions.remove(key);
                logger.debug(compName + " GC: hp session time expired. deleting the session " + key + " mechanism: " + session.getHolePunchingMechanism()
                        + " from " + session.getPortInUse() + "=>" + session.getRemoteOpenedHole());
            }

            Set<OpenedConnection> toDeleteOc = new HashSet<OpenedConnection>();
            for (OpenedConnection oc : openedConnections.values()) {
                if (oc.isExpired()) {
                    toDeleteOc.add(oc);
                }
            }
            for (OpenedConnection oc : toDeleteOc) {
                // delete the connection, don't bother telling my neighbour that i'm 
                // deleting its connection
                deleteConnection(oc.getHoleOpened().getId(), false);
            }

            if (self.getNat().preallocatePorts()) {
                if (!portsInUse.isEmpty() || !openedConnections.isEmpty()) {
                    logger.debug(compName + " Number of allocated PRP parent ports:"
                            + portsInUse.size() + " number of open connections: "
                            + openedConnections.size());
                }
            }
        }
    };
//////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//
//                             HANDLERS FOR PRC
//
//////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    Handler<PRC_ServerRequestForConsecutiveMsg.Request> handlePRC_ServerRequestForConsecutiveMessages
            = new Handler<PRC_ServerRequestForConsecutiveMsg.Request>() {
                @Override
                public void handle(PRC_ServerRequestForConsecutiveMsg.Request request) {
                    printMsg(request);
                    String compName = HpClient.this.compName + " - " + request.getMsgTimeoutId() + " - ";
                    int remoteId = request.getRemoteClientId();
                    logger.debug(compName + "PRC Server's request to send consecutive messages. "
                            + "remote client ID (" + remoteId + ") "
                            + "zServer ID (" + request.getSource().getId() + ") Mechanism: "
                            + request.getHolePunchingMechanism()
                            + " Role: " + request.getHolePunchingRole());
                    // its possible that we dont have session for this request
                    // consider a situation where you only register with rendezvous servers z.
                    // later some client B ask z for opening a hole b/w A and B.
                    // if A is the initiator then A will recv the initiate hole punching msg
                    // so create a session if it does not exist

                    HpSession session = hpSessions.get(remoteId);
                    if (session == null) { // PRP-PRC
                        session = new HpSession(remoteId, null, 5, false,
                                System.currentTimeMillis(), request.getMsgTimeoutId());
                        session.setHolePunchingMechanism(request.getHolePunchingMechanism());
                        session.setHolePunchingRole(request.getHolePunchingRole());
                        hpSessions.put(remoteId, session);
                    }
                    // TODO - why is this here?
                    if (!session.isHpOngoing()) {
                        sendPrcPortsToServer(request.getVodSource(), remoteId,
                                request.getDummyRemoteClientPublicAddress(),
                                request.getMsgTimeoutId());
                    } else {
                        logger.debug(compName + "HP was ongoing: " + session + " - "
                                + request.getMsgTimeoutId());
                    }
                }
            };

    private void sendPrcPortsToServer(VodAddress zServer, int remoteId, VodAddress dummyAddr,
            TimeoutId msgTimeoutId) {

        HpSession session = hpSessions.get(remoteId);
        session.setDummyAddress(dummyAddr.getPeerAddress());

        logger.trace(compName + " sending request to port reservoir. remote client ID ("
                + session.getRemoteClientId() + ")"
                + " session key " + remoteId);
        PortAllocRequest allocReq = new PortAllocRequest(self.getIp(),
                self.getId(), 1, Transport.UDP);
        PRC_PortResponse allocResp = new PRC_PortResponse(allocReq, remoteId, zServer, msgTimeoutId);
        allocReq.setResponse(allocResp);
        delegator.doTrigger(allocReq, natNetworkControl);
    }
    Handler<PRC_PortResponse> handleRPC_PortResponse
            = new Handler<PRC_PortResponse>() {
                @Override
                public void handle(PRC_PortResponse response) {
                    HpSession session = hpSessions.get((Integer) response.getKey());
                    Iterator<Integer> iter = response.getAllocatedPorts().iterator();
                    int somePort = iter.next();
                    session.setPortInUse(somePort);

                    logger.debug(compName + "handleRPC_PortResponse. remote client (" + session.getRemoteClientId() + ") "
                            + " session key " + response.getKey() + " port in use " + session.getPortInUse());

                    //make two messages and send them at the same time
                    Address sourceAddr = new Address(self.getIp(), session.getPortInUse(),
                            self.getId());
                    VodAddress sourceAddress = new VodAddress(sourceAddr,
                            self.getOverlayId(), self.getNat(), self.getParents());

                    // Message 1 sent to dummy public address of remote client's nat
                    // For PRP-PRC, this is not a dummyMessage, but the target hole.
                    HolePunchingMsg.Request dummyMessage
                    = new HolePunchingMsg.Request(sourceAddress,
                            new VodAddress(session.getDummyAddress(), self.getOverlayId()),
                            response.getMsgTimeoutId());

                    // Message 2 sent to zServer
                    PRC_OpenHoleMsg.Request openHoleReqMsg
                    = new PRC_OpenHoleMsg.Request(sourceAddress,
                            response.getzServer(),
                            session.getRemoteClientId(), response.getMsgTimeoutId());

                    ScheduleRetryTimeout st
                    = new ScheduleRetryTimeout(config.getRto(),
                            config.getRtoRetries(), config.getRtoScale());
                    PRC_OpenHoleMsg.RequestRetryTimeout requestRetryTimeout
                    = new PRC_OpenHoleMsg.RequestRetryTimeout(st, openHoleReqMsg);

                    // first send message to zServer and then immediately send message to dummy address
                    // to open two consecutive holes on the nat (if mapping != EI)
                    delegator.doRetry(requestRetryTimeout);
                    if (session.getHolePunchingMechanism() == HPMechanism.PRP_PRC) {
                        delegator.doRetry(dummyMessage, 10, 3, 1.5d, self.getOverlayId());
                    } else {
                        delegator.doRetry(dummyMessage, self.getOverlayId());
                    }

                }
            };
    Handler<PRC_OpenHoleMsg.Response> handlePRC_OpenHoleMsgResponse = new Handler<PRC_OpenHoleMsg.Response>() {
        @Override
        public void handle(PRC_OpenHoleMsg.Response response) {
            printMsg(response);
            String compName = HpClient.this.compName + " - " + response.getMsgTimeoutId() + " - ";
            int remoteId = response.getRemoteClientId();
            if (delegator.doCancelRetry(response.getTimeoutId())) {
                logger.debug(compName + " handlePRC_OpenHoleMsgResponse recvd Flag: " + response.getResponseType()
                        + " remote client ID (" + remoteId + ") zServer ID (" + response.getSource().getId() + ")");
                if (response.getResponseType() != PRC_OpenHoleMsg.ResponseType.OK) {
                    HpSession session = hpSessions.get(remoteId);
                    if (session.getOpenConnectionRequest() != null) {
                        sendOpenConnectionResponseMessage(session.getOpenConnectionRequest(),
                                session.getRemoteOpenedHole(),
                                OpenConnectionResponseType.RENDEZVOUS_SERVER_ERROR,
                                session.getHolePunchingMechanism(), response.getMsgTimeoutId());
                    }

                    // send hpFinished (Fail) msg to zServer
//                    sendHpFinishedMsgMsgTozServer(self,
//                            response.getGVodSource(),
//                            remoteId,
//                            false/*hp failed*/);
                    // Clean up
                    // 1) clean the hole punching sessions map
//                    hpSessions.remove(remoteId);
                } else {
                    // TODO update the session about the state
                }
            }
        }
    };
    Handler<PRC_OpenHoleMsg.RequestRetryTimeout> handlePRC_OpenHoleMsgTimeout = new Handler<PRC_OpenHoleMsg.RequestRetryTimeout>() {
        @Override
        public void handle(PRC_OpenHoleMsg.RequestRetryTimeout event) {
            if (delegator.doCancelRetry(event.getTimeoutId())) {
                int remoteId = event.getRequestMsg().getRemoteClientId();
                logger.debug(compName + "zServer failed. Did not recv response for PRC_OpenHolMessage. "
                        + "zServer ID (" + event.getRequestMsg().getDestination().getId() + ") "
                        + "remote client ID (" + remoteId + ") - "
                        + event.getRequestMsg().getMsgTimeoutId());

                // it means zServer id D.E.A.D
                HpSession session = hpSessions.get(remoteId);
                session.setHpOngoing(false);

                // send response to the app/upper component if
                // this client asked for hole punching
                if (session.getOpenConnectionRequest() != null) {
                    sendOpenConnectionResponseMessage(
                            session.getOpenConnectionRequest(),
                            session.getRemoteOpenedHole(),
                            OpenConnectionResponseType.RENDEZVOUS_SERVER_FAILED,
                            session.getHolePunchingMechanism(),
                            session.getMsgTimeoutId());
                }

                // Clean up
                // 1) clean the registered servers map
                // 2) clean the hole punching sessions map
//                registeredServers.remove(session.getRendezvousServerAddress().getPeerAddress());
//                hpSessions.remove(remoteId);
            }
        }
    };
//////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//
//                             HANDLERS FOR PRC-PRC
//
//////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    Handler<Interleaved_PRC_ServersRequestForPredictionMsg.Request> handle_Interleaved_PRC_ServerRequestForPredictionMessage = new Handler<Interleaved_PRC_ServersRequestForPredictionMsg.Request>() {
        @Override
        public void handle(Interleaved_PRC_ServersRequestForPredictionMsg.Request request) {
            printMsg(request);
            String compName = HpClient.this.compName + " - " + request.getMsgTimeoutId() + " - ";

            int remoteId = request.getRemoteClientId();
            logger.debug(compName + "Interleaved PRC Server's request to send prediction message. Mechanism: " + request.getHolePunchingMechanism()
                    + " Role: " + request.getHolePunchingRole()
                    + " remote Client id (" + remoteId + ")"
                    + " zServer ID (" + request.getSource().getId() + ")");
            // its possible that we dont have session for this request
            // consider a situation where you only register with rendezvous servers z.
            // later some client B ask z for opening a hole b/w A and B.
            // if A is the initiator then A will recv the initiate hole punching msg
            // so create a session if it does not exist

            if (!hpSessions.containsKey(remoteId)) {
                HpSession session = new HpSession(remoteId,
                        null, // don't have the request object
                        config.getScanRetries(),
                        config.isScanningEnabled(),
                        System.currentTimeMillis(),
                        request.getMsgTimeoutId());
                hpSessions.put(remoteId, session);
            }
            HpSession session = hpSessions.get(remoteId);
            if (!session.isHpOngoing()) {
                session.setHolePunchingMechanism(request.getHolePunchingMechanism());
                session.setHolePunchingRole(request.getHolePunchingRole());
                session.setRemoteOpenedHole(request.getHole());
                session.setHpOngoing(true);
                PortAllocRequest allocReq = new PortAllocRequest(self.getIp(), self.getId(),
                        2, Transport.UDP);
                InterleavedPRC_PortResponse allocResp
                        = new InterleavedPRC_PortResponse(allocReq, remoteId, request.getVodSource());
                allocReq.setResponse(allocResp);
                delegator.doTrigger(allocReq, natNetworkControl);
            } else {
                logger.warn(compName + "HP was ongoing: " + session + " - "
                        + request.getMsgTimeoutId());
            }
        }
    };
    Handler<InterleavedPRC_PortResponse> handle_Interleaved_RPC_PortResponse
            = new Handler<InterleavedPRC_PortResponse>() {
                @Override
                public void handle(InterleavedPRC_PortResponse response) {
                    HpSession session = hpSessions.get((Integer) response.getKey());
                    Iterator<Integer> iter = response.getAllocatedPorts().iterator();
                    int zServerPort = iter.next();
                    session.setPortInUse(zServerPort);

                    logger.debug(compName + "handle_InterleavedPRC_PortResponse. remote client (" + session.getRemoteClientId() + ") "
                            + " session key " + response.getKey() + " port in use " + session.getPortInUse());

                    //make a message and send it to the server
                    Address zSelf = new Address(self.getIp(),
                            session.getPortInUse(), self.getId());
                    VodAddress zSelfAddr = new VodAddress(zSelf,
                            self.getOverlayId(), self.getNat(),
                            self.getParents()/*
                     * parents not needed
                     */);

                    Interleaved_PRC_OpenHoleMsg.Request openHoleReqMsg
                    = new Interleaved_PRC_OpenHoleMsg.Request(zSelfAddr,
                            response.getzServer(), session.getRemoteClientId(),
                            session.getMsgTimeoutId());
                    ScheduleRetryTimeout st = new ScheduleRetryTimeout(config.getRto(),
                            config.getRtoRetries(), config.getRtoScale());
                    Interleaved_PRC_OpenHoleMsg.RequestRetryTimeout requestRetryTimeout
                    = new Interleaved_PRC_OpenHoleMsg.RequestRetryTimeout(st, openHoleReqMsg);
                    delegator.doRetry(requestRetryTimeout);

                }
            };
    Handler<Interleaved_PRC_OpenHoleMsg.Response> handle_Interleaved_PRC_OpenHoleMsgResponse = new Handler<Interleaved_PRC_OpenHoleMsg.Response>() {
        @Override
        public void handle(Interleaved_PRC_OpenHoleMsg.Response response) {
            printMsg(response);
            String compName = HpClient.this.compName + " - " + response.getMsgTimeoutId() + " - ";

            if (delegator.doCancelRetry(response.getTimeoutId())) {
                int remoteId = response.getRemoteClientId();
                logger.debug(compName + " handle_Interleaved_PRC_OpenHoleMsgResponse recvd Flag: " + response.getResponseType()
                        + " remote client ID (" + remoteId + ") "
                        + "zServer ID (" + response.getSource().getId() + ")");
                if (response.getResponseType() != Interleaved_PRC_OpenHoleMsg.ResponseType.OK) {
                    HpSession session = hpSessions.get(remoteId);
//                    session.setPortInUse(response.getDestination().getPort());
//                    if (session.getOpenConnectionRequest() != null) // it means that hole punching was requested by upper component
//                    {
//                        OpenConnectionResponse openConnectionResponse =
//                                new OpenConnectionResponse(session.getOpenConnectionRequest(),
//                                OpenConnectionResponseType.RENDEZVOUS_SERVER_ERROR,
//                                session.getRemoteOpenedHole());
//                        openConnectionResponse.setHpMechanismUsed(session.getHolePunchingMechanism());
//                        delegator.doTrigger(openConnectionResponse, hpClientPort);
//                    }

                    // send hpFinished (Fail) msg to zServer
//                    sendHpFinishedMsgMsgTozServer(self,
//                            response.getGVodSource(),
//                            session.getRemoteClientId()/*other client id*/,
//                            false/*hp failed*/);
                    // Clean up
                    // 1) clean the hole punching sessions map
//                    hpSessions.remove(remoteId);
                } else {
                    // TODO update the session about the state
                }
            }
        }
    };
    Handler<Interleaved_PRC_OpenHoleMsg.RequestRetryTimeout> handle_Interleaved_PRC_OpenHoleMsgTimeout = new Handler<Interleaved_PRC_OpenHoleMsg.RequestRetryTimeout>() {
        @Override
        public void handle(Interleaved_PRC_OpenHoleMsg.RequestRetryTimeout event) {
            if (delegator.doCancelRetry(event.getTimeoutId())) {
                int remoteId = event.getRequestMsg().getRemoteClientId();
                logger.debug(compName + "zServer failed. Did not recv response for PRC_PRC_OpenHoleMsg."
                        + " remote client ID (" + remoteId + ") "
                        + "zServer ID (" + event.getRequestMsg().getDestination().getId() + ") - "
                        + event.getRequestMsg().getMsgTimeoutId());

                // it means zServer iz D.E.A.D
                HpSession session = hpSessions.get(remoteId);
                session.setHpOngoing(false);
                // send response to the app/upper component if
                // this client asked for hole punching
                if (session.getOpenConnectionRequest() != null) {
                    sendOpenConnectionResponseMessage(
                            session.getOpenConnectionRequest(),
                            session.getRemoteOpenedHole(),
                            OpenConnectionResponseType.RENDEZVOUS_SERVER_FAILED,
                            session.getHolePunchingMechanism(),
                            session.getMsgTimeoutId());
                }

                // Clean up
                // 1) clean the registered servers map
                // 2) clean the hole punching sessions map
//                registeredServers.remove(session.getRendezvousServerAddress().getPeerAddress());
//                hpSessions.remove(remoteId);
            }
        }
    };
//////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//
//                             HANDLERS FOR PRP
//
//////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    Handler<PRP_ServerRequestForAvailablePortsMsg.Request> handlePRP_ServerRequestForAvailablePortsMsg = new Handler<PRP_ServerRequestForAvailablePortsMsg.Request>() {
        @Override
        public void handle(PRP_ServerRequestForAvailablePortsMsg.Request request) {
            printMsg(request);
            String compName = HpClient.this.compName + " - " + request.getMsgTimeoutId() + " - ";

            int remoteId = request.getRemoteClientId();
            logger.debug(compName + "PRP Server's request to send available ports to it. Mechanism: "
                    + request.getHolePunchingMechanism() + " Role: " + request.getHolePunchingRole()
                    + " remote client ID (" + remoteId + ") zServer ID ("
                    + request.getSource().getId() + ")");
            // its possible that we dont have a session for this request
            // consider a situation where you only register with rendezvous servers z.
            // later some client B ask z for opening a hole b/w A and B.
            // if A is the initiator then A will recv the initiate hole punching msg
            // so create a session if it does not exist

            prpRequest(remoteId,
                    request.getHolePunchingRole(), request.getMsgTimeoutId());
        }
    };

    private HpSession prpRequest(int remoteId,
            HPRole role, TimeoutId msgTimeoutId) {
        if (!hpSessions.containsKey(remoteId)) {
            HpSession session = new HpSession(remoteId,
                    null, // don't have the request object
                    config.getScanRetries(),
                    config.isScanningEnabled(),
                    System.currentTimeMillis(),
                    msgTimeoutId);
            session.setHolePunchingMechanism(HPMechanism.PRP_PRP);
            session.setHolePunchingRole(role);
            hpSessions.put(remoteId, session);
        }

        HpSession session = hpSessions.get(remoteId);

        PortAllocRequest allocReq = new PortAllocRequest(self.getIp(), self.getId(),
                1, Transport.UDP);
        PRP_PortResponse allocResp = new PRP_PortResponse(allocReq, remoteId);
        allocReq.setResponse(allocResp);
        delegator.doTrigger(allocReq, natNetworkControl);
        return session;
    }
    Handler<PRP_PortResponse> handlePRP_PortResponse = new Handler<PRP_PortResponse>() {
        @Override
        public void handle(PRP_PortResponse response) {
            Integer key = (Integer) response.getKey();
            HpSession session = hpSessions.get(key);
            Set<Integer> someAvailablePorts = response.getAllocatedPorts();
            sendPrpPortsToServer(someAvailablePorts, key, session);
        }
    };

    private void sendPrpPortsToServer(Set<Integer> someAvailablePorts,
            int remoteId, HpSession session) {

        StringBuilder allPorts = new StringBuilder();
        for (Integer port : someAvailablePorts) {
            allPorts.append(port).append(",");
        }

        logger.trace(compName + "handleRPP_PortResponse. remote client (" + session.getRemoteClientId() + ") "
                + " session key " + remoteId + " ports recvd " + allPorts.toString());

        Address dest = parents.remove(session.getRemoteClientId());
        if (dest == null) {
            logger.error("Couldn't find parent for " + session.getRemoteClientId());
            NatReporter.report(delegator, network, self.getAddress(),
                    0, self.getAddress(), true, 0,
                    "Couldn't find parent for: " + session.getRemoteClientId());
            return;
        }
        PRP_ConnectMsg.Request availablePortsMsg
                = new PRP_ConnectMsg.Request(self.getAddress(),
                        new VodAddress(dest, self.getId()),
                        session.getRemoteClientId(),
                        someAvailablePorts, session.getMsgTimeoutId());

        ScheduleRetryTimeout st = new ScheduleRetryTimeout(config.getRto(),
                config.getRtoRetries(), config.getRtoScale());
        PRP_ConnectMsg.RequestRetryTimeout requestRetryTimeout
                = new PRP_ConnectMsg.RequestRetryTimeout(st, availablePortsMsg);
        Set<Address> p = new HashSet<Address>();
        p.add(dest);
        delegator.doMulticast(requestRetryTimeout, p);

    }
    Handler<PRP_ConnectMsg.RequestRetryTimeout> handlePRP_SendAvailablePortsTozServerMsgTimeout = new Handler<PRP_ConnectMsg.RequestRetryTimeout>() {
        @Override
        public void handle(PRP_ConnectMsg.RequestRetryTimeout event) {

            if (delegator.doCancelRetry(event.getTimeoutId())) {
                int remoteId = event.getRequestMsg().getRemoteClientId();
                logger.debug(compName + "zServer failed. PRP_SendAvailablePortsToZServer Timeout "
                        + " remote client ID (" + remoteId + ") "
                        + "zServer ID (" + event.getRequestMsg().getDestination().getId() + ") -"
                        + event.getRequestMsg().getMsgTimeoutId());

                removeAndUnbindPorts(event.getRequestMsg().getSetOfAvailablePorts(), true);

                HpSession session = hpSessions.get(remoteId);
                session.setHpOngoing(false);
                // send response to the app/upper component if
                // this client asked for hole punching
                if (session.getOpenConnectionRequest() != null) {
                    sendOpenConnectionResponseMessage(session,
                            OpenConnectionResponseType.RENDEZVOUS_SERVER_FAILED);
                }

                // Clean up
                // 1) clean the registered servers map
                // 2) clean the hole punching sessions map
//                registeredServers.remove(session.getRendezvousServerAddress().getPeerAddress());
//                hpSessions.remove(remoteId);
            }
        }
    };
    Handler<PRP_ConnectMsg.Response> handlePRP_ConnectMsgResponse = new Handler<PRP_ConnectMsg.Response>() {
        @Override
        public void handle(PRP_ConnectMsg.Response response) {
            printMsg(response);
            String compName = HpClient.this.compName + " - " + response.getMsgTimeoutId() + " - ";

            Integer remoteId = response.getRemoteClientId();
            // don't exit code if the timeout was already invoked.
            delegator.doCancelRetry(response.getTimeoutId());

            if (response.getResponseType() == PRP_ConnectMsg.ResponseType.OK) {
                // send a dummy message to the other client to open a hole on the nat
                // no reply is expected so no need for retries and fail timer
                logger.debug(compName + "handlePRP_SendAvailablePortsTozServerMsgResponse. Port to use " + response.getPortToUse()
                        + " remote client ID (" + remoteId + ") "
                        + "zServer ID (" + response.getSource().getId() + ")");

                HpSession session = hpSessions.get(remoteId);

                // TODO: remove the port from either the boundUnusedPorts or the
                // tempUnusedPorts. Add any tempUnusedPorts to the boundUnusedPorts
                if (session != null) {
                    if (session.isHpOngoing()) {
                        logger.debug(compName + " hp is ongoing: " + session + " - "
                                + response.getMsgTimeoutId());
                        return;
                    }
                    session.setPortInUse(response.getPortToUse());
                    session.setDummyAddress(
                            response.getRemoteClientDummyPublicAddress().getPeerAddress());

                    Address sourceAddr = new Address(self.getIp(), session.getPortInUse(), self.getId());
                    // TODO parents null here?
                    VodAddress sourceAddress = new VodAddress(sourceAddr,
                            self.getOverlayId(), self.getNat(), null);

                    HolePunchingMsg.Request dummyHolePunchingMsg
                            = new HolePunchingMsg.Request(sourceAddress,
                                    response.getRemoteClientDummyPublicAddress(),
                                    response.getMsgTimeoutId());
                    if (!response.isBindFirst()) {
                        logger.debug(compName + " Sending dummy message from " + sourceAddress.getId()
                                + " dest id:port " + response.getRemoteClientDummyPublicAddress().getId()
                                + ":" + response.getRemoteClientDummyPublicAddress().getPort());
                        if (session.getHolePunchingMechanism() == HPMechanism.PRP_PRC) {
                            delegator.doRetry(dummyHolePunchingMsg, 500, 5, 1.2d, self.getOverlayId());
                        } else {
                            delegator.doRetry(dummyHolePunchingMsg, self.getOverlayId());
                        }
                    } else {
                        Address a = new Address(self.getIp(), response.getPortToUse(), self.getId());
                        PortBindRequest bindReq = new PortBindRequest(a, Transport.UDP);
                        PRP_DummyMsgPortResponse bindResp
                                = new PRP_DummyMsgPortResponse(bindReq,
                                        session.getHolePunchingMechanism() == HPMechanism.PRP_PRC,
                                        dummyHolePunchingMsg, response.getClientId());
                        bindReq.setResponse(bindResp);
                        delegator.doTrigger(bindReq, natNetworkControl);
                        addBoundPort(response.getPortToUse());
                    }
                } else {
                    logger.debug(compName + "ERROR: Session not found for " + response.getClass());
                }
            } else { // The ports I sent to the server are not OK.
                HpSession session = hpSessions.get(remoteId);
                // it means that hole punching was requested by upper component                
                if (session.getOpenConnectionRequest() != null) {
                    sendOpenConnectionResponseMessage(session,
                            OpenConnectionResponseType.RENDEZVOUS_SERVER_FAILED);
                }
            }
        }
    };
    Handler<PRP_DummyMsgPortResponse> handlePRP_DummyMsgPortResponse = new Handler<PRP_DummyMsgPortResponse>() {
        @Override
        public void handle(PRP_DummyMsgPortResponse response) {
            HolePunchingMsg.Request msg = response.getMsg();
            if (response.getStatus() == PRP_DummyMsgPortResponse.Status.SUCCESS
                    || response.getStatus() == PRP_DummyMsgPortResponse.Status.PORT_ALREADY_BOUND) {
                if (response.isPrcPrp()) {
                    delegator.doRetry(msg, 500, 5, 1.2d, self.getOverlayId());
                } else {
                    delegator.doRetry(msg, self.getOverlayId());
                }
            } else if (response.getStatus() == PRP_DummyMsgPortResponse.Status.FAIL) {
                removeAndUnbindPort(msg.getDestport(), false);
            } else {
                throw new IllegalStateException("Invalid port bind response");
            }
        }
    };

//////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//
//                             HANDLERS FOR INTERLEAVED PRP PRP
//////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    private HpSession interleavedPrpRequest(int remoteId,
            Address parent) {
        HpSession session = hpSessions.get(remoteId);
        parents.put(remoteId, parent);

        PortAllocRequest allocReq = new PortAllocRequest(self.getIp(), self.getId(), 1,
                Transport.UDP); //freePortCount
        InterleavedPRP_PortResponse allocResp
                = new InterleavedPRP_PortResponse(allocReq, parent, remoteId);
        allocReq.setResponse(allocResp);
        delegator.doTrigger(allocReq, natNetworkControl);
        return session;
    }
    Handler<InterleavedPRP_PortResponse> handle_Interleaved_PRP_PortResponse
            = new Handler<InterleavedPRP_PortResponse>() {
                @Override
                public void handle(InterleavedPRP_PortResponse response) {
                    HpSession session = hpSessions.get((Integer) response.getKey());
                    Set<Integer> someAvailablePorts = response.getAllocatedPorts();
                    sendInterleavedPrpPortsToServer(session, response.getParent(),
                            someAvailablePorts);
                }
            };

    private void sendInterleavedPrpPortsToServer(HpSession session,
            Address parent,
            Set<Integer> someAvailablePorts) {

        if (someAvailablePorts.isEmpty()) {
            logger.debug(compName + "No ports available to send to Server: "
                    + parent + " - "
                    + session.getMsgTimeoutId());
            sendOpenConnectionResponseMessage(session,
                    OpenConnectionResponseType.NO_FREE_PORTS_LOCALLY);
            return;
        }
        logger.debug(compName + "Sending Interleaved_PRP_ConnectMsg.Request to "
                + parent + " - "
                + session.getMsgTimeoutId());

        Interleaved_PRP_ConnectMsg.Request availablePortsMsg
                = new Interleaved_PRP_ConnectMsg.Request(
                        self.getAddress(),
                        new VodAddress(parent, self.getOverlayId()),
                        session.getRemoteClientId(),
                        someAvailablePorts, session.getMsgTimeoutId());
        ScheduleRetryTimeout st = new ScheduleRetryTimeout(config.getRto(),
                config.getRtoRetries(), config.getRtoScale());
        Interleaved_PRP_ConnectMsg.RequestRetryTimeout requestRetryTimeout
                = new Interleaved_PRP_ConnectMsg.RequestRetryTimeout(st, availablePortsMsg);
        delegator.doRetry(requestRetryTimeout);
    }
    Handler<Interleaved_PRP_ConnectMsg.RequestRetryTimeout> handleInterleaved_PRP_ConnectMsgRequestRetryTimeout = new Handler<Interleaved_PRP_ConnectMsg.RequestRetryTimeout>() {
        @Override
        public void handle(Interleaved_PRP_ConnectMsg.RequestRetryTimeout event) {
            if (delegator.doCancelRetry(event.getTimeoutId())) {
                int remoteId = event.getRequestMsg().getRemoteClientId();
                logger.debug(compName + "handleInterleaved_PRP_ConnectMsgRequestRetryTimeout. zServer failed."
                        + " remote client ID (" + remoteId + ") "
                        + "zServer ID (" + event.getRequestMsg().getDestination().getId() + ") - "
                        + event.getRequestMsg().getMsgTimeoutId());

                // it means zServer id D.E.A.D
                HpSession session = hpSessions.get(remoteId);

                removeAndUnbindPorts(event.getRequestMsg().getSetOfAvailablePorts(), true);
                // send response to the app/upper component if
                // this client asked for hole punching
                sendOpenConnectionResponseMessage(session,
                        OpenConnectionResponseType.RENDEZVOUS_SERVER_FAILED);
            }
        }
    };
    Handler<Interleaved_PRP_ConnectMsg.Response> handle_Interleaved_PRP_SendAvailablePortsTozServerMsgResponse = new Handler<Interleaved_PRP_ConnectMsg.Response>() {
        @Override
        public void handle(Interleaved_PRP_ConnectMsg.Response response) {
            printMsg(response);
            String compName = HpClient.this.compName + " - " + response.getMsgTimeoutId() + " - ";

            if (delegator.doCancelRetry(response.getTimeoutId())) {
                int remoteId = response.getRemoteClientId();

                logger.debug(compName + "handle_Interleaved_PRP_SendAvailablePortsTozServerMsgResponse. Flag: "
                        + response.getResponseType() + " remote client ID (" + response.getRemoteClientId() + ") "
                        + "zServer ID (" + response.getSource().getId() + ")");
                if (response.getResponseType() != Interleaved_PRP_ConnectMsg.ResponseType.OK) {
                    HpSession session = hpSessions.get(remoteId);
                    if (session.getOpenConnectionRequest() != null) // it means that hole punching was requested by upper component
                    {
                        sendOpenConnectionResponseMessage(session,
                                OpenConnectionResponseType.RENDEZVOUS_SERVER_FAILED);
                    }
                } else {
                    // TODO update the session about the state
                }
            }
        }
    };

    private void sendOpenConnectionResponseMessage(HpSession session,
            OpenConnectionResponseType rt) {
        sendOpenConnectionResponseMessage(
                session.getOpenConnectionRequest(),
                session.getRemoteOpenedHole(),
                rt,
                session.getHolePunchingMechanism(),
                session.getMsgTimeoutId());
    }

    private void sendOpenConnectionResponseMessage(OpenConnectionRequest request,
            VodAddress remoteAddr,
            OpenConnectionResponseType resType,
            HPMechanism hpMechanism,
            TimeoutId msgTimeoutId) {
        if (request != null) {
            OpenConnectionResponse response = new OpenConnectionResponse(
                    request, remoteAddr, resType,
                    hpMechanism, msgTimeoutId);
            delegator.doTrigger(response, hpClientPort);
        } else {
            logger.warn(compName + "Cannot send OpenConnectionResponse with null request");
        }
    }

    @Override
    public void stop(Stop event) {
        // TODO: cleanup
    }
}
