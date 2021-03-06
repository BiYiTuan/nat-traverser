//package se.sics.gvod.simulator.nattraverser;
//
//import se.sics.gvod.simulator.common.PeerFail;
//import se.sics.gvod.simulator.common.StartCollectData;
//import se.sics.gvod.simulator.common.PeerJoin;
//import se.sics.gvod.simulator.common.StopCollectData;
//import java.net.InetAddress;
//import java.util.ArrayList;
//import java.util.HashMap;
//import java.util.HashSet;
//import java.util.List;
//import java.util.Map;
//import java.util.Random;
//import java.util.Set;
//
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//
//import se.sics.ipasdistances.AsIpGenerator;
//import se.sics.gvod.address.Address;
//import se.sics.gvod.common.*;
//import se.sics.gvod.common.util.ToVodAddr;
//import se.sics.gvod.config.VodConfig;
//import se.sics.gvod.net.VodAddress;
//import se.sics.gvod.net.VodNetwork;
//import se.sics.gvod.net.Nat;
//import se.sics.gvod.config.ParentMakerConfiguration;
//import se.sics.gvod.simulator.common.ConsistentHashtable;
//import se.sics.gvod.config.CroupierConfiguration;
//import se.sics.gvod.filters.MsgDestFilterNodeId;
//import se.sics.gvod.nat.common.PortInit;
//import se.sics.gvod.nat.common.PortReservoirComp;
//import se.sics.gvod.nat.emu.DistributedNatGatewayEmulator;
//import se.sics.gvod.nat.emu.events.DistributedNatGatewayEmulatorInit;
//import se.sics.gvod.config.HpClientConfiguration;
//import se.sics.gvod.config.RendezvousServerConfiguration;
//import se.sics.gvod.nat.traversal.NatTraverser;
//import se.sics.gvod.config.NatTraverserConfiguration;
//import se.sics.gvod.config.StunClientConfiguration;
//import se.sics.gvod.nat.traversal.events.NatTraverserInit;
//import se.sics.gvod.net.NatNetworkControl;
//import se.sics.gvod.config.StunServerConfiguration;
//import se.sics.gvod.croupier.Croupier;
//import se.sics.gvod.croupier.CroupierPort;
//import se.sics.gvod.croupier.PeerSamplePort;
//import se.sics.gvod.croupier.events.CroupierInit;
//import se.sics.gvod.croupier.events.CroupierJoin;
//import se.sics.gvod.croupier.snapshot.CroupierStats;
//import se.sics.gvod.nat.hp.client.HpClient;
//import se.sics.gvod.nat.traversal.NatTraverserPort;
//import se.sics.gvod.timer.Timer;
//import se.sics.kompics.Component;
//import se.sics.kompics.ComponentDefinition;
//import se.sics.kompics.Handler;
//import se.sics.kompics.Kompics;
//import se.sics.kompics.Positive;
//import se.sics.kompics.Stop;
//
//public final class NatTraverserSimulator extends ComponentDefinition {
//
//    private static final boolean RANDOM_NAT_TYPE = false;
//
//    private static final int NT_PEER_OVERLAY_ID = 5;
//    private static final Logger logger = LoggerFactory.getLogger(NatTraverserSimulator.class);
//    Positive<NatTraverserSimulatorPort> simulator = positive(NatTraverserSimulatorPort.class);
//    Positive<VodNetwork> network = positive(VodNetwork.class);
//    Positive<Timer> timer = positive(Timer.class);
//    private final HashMap<Integer, Component> publicPeers;
//    private final HashMap<Integer, Component> privatePeers;
//    private final HashMap<Integer, VodAddress.NatType> peerTypes;
//    private final HashMap<Integer, Self> privateAddress;
//    private final HashMap<Integer, Self> publicAddress;
//    // peer initialization state
//    private int peerIdSequence;
//    private ConsistentHashtable<Integer> view;
//    private AsIpGenerator ipGenerator;
//    private Map<String, Integer> successCount = new HashMap<String, Integer>();
//    private Map<String, Integer> failCount = new HashMap<String, Integer>();
//    private VodAddress server1, server2;
//    private Random r;
//    private CroupierConfiguration croupierConfig;
//    private NatTraverserConfiguration ntConfig;
//    private HpClientConfiguration hpClientConfig;
//    private RendezvousServerConfiguration rendezvousServerConfig;
//    private StunClientConfiguration stunClientConfig;
//    private StunServerConfiguration stunServerConfig;
//    private ParentMakerConfiguration parentMakerConfig;
//
//    public NatTraverserSimulator() {
//        publicPeers = new HashMap<Integer, Component>();
//        privatePeers = new HashMap<Integer, Component>();
//        privateAddress = new HashMap<Integer, Self>();
//        publicAddress = new HashMap<Integer, Self>();
//        peerTypes = new HashMap<Integer, VodAddress.NatType>();
//        view = new ConsistentHashtable<Integer>();
//
//        r = new Random(VodConfig.getSeed());
//        subscribe(handleInit, control);
//
//        subscribe(handlePeerJoin, simulator);
//        subscribe(handlePeerFail, simulator);
//        subscribe(handleConnectPeers, simulator);
//        subscribe(handleDisconnect, simulator);
//
//        subscribe(handleStartCollectData, simulator);
//        subscribe(handleStopCollectData, simulator);
//
//    }
//
//    Handler<NatTraverserSimulatorInit> handleInit = new Handler<NatTraverserSimulatorInit>() {
//        @Override
//        public void handle(NatTraverserSimulatorInit init) {
//            publicPeers.clear();
//            privatePeers.clear();
//            peerIdSequence = 100;
//            ipGenerator = AsIpGenerator.getInstance(init.getNatTraverserConfig().getSeed());
//            ntConfig = init.getNatTraverserConfig();
//            croupierConfig = init.getCroupierConfig();
//            hpClientConfig = init.getHpClientConfig();
//            rendezvousServerConfig = init.getRendezvousServerConfig();
//            stunClientConfig = init.getStunClientConfig();
//            stunServerConfig = init.getStunServerConfig();
//            parentMakerConfig = init.getParentMakerConfig();
//            
//        }
//    };
//
//    Handler<PeerJoin> handlePeerJoin = new Handler<PeerJoin>() {
//        @Override
//        public void handle(PeerJoin event) {
//            Integer id = event.getPeerId();
//            VodAddress.NatType peerType = event.getPeerType();
//            join(id, peerType);
//        }
//    };
//
//    Handler<PeerFail> handlePeerFail = new Handler<PeerFail>() {
//        @Override
//        public void handle(PeerFail event) {
//            Integer id = event.getPeerId();
//            VodAddress.NatType peerType = event.getPeerType();
//            fail(id, peerType);
//        }
//    };
//
//    Handler<Disconnect> handleDisconnect = new Handler<Disconnect>() {
//        @Override
//        public void handle(Disconnect event) {
//            Integer id = event.getPeerId();
//            id = view.getNode(id);
//            Component peer = getComponent(id);
//            trigger(new Disconnect(id, event.getNumToDisconnect()),
//                    peer.getNegative(NatTraverserSimulatorPort.class));
//        }
//    };
//
//    Handler<ConnectPeers> handleConnectPeers = new Handler<ConnectPeers>() {
//        @Override
//        public void handle(ConnectPeers event) {
//            Integer sId = event.getSrcId();
//            Integer dId = event.getDestId();
//            Integer src = view.getNode(sId);
//            if (src != null) {
//                Integer dest = view.getNode(dId);
//                VodAddress srcAddr = getAddress(src);
//                VodAddress destAddr = getAddress(dest);
//                while (src.equals(dest) || !srcAddr.isHpPossible(destAddr)) {
//                    dest++;
//                    dest = view.getNode(dest);
//                    destAddr = getAddress(dest);
//                }
//                connect(src, dest);
//            } else {
//                System.err.println("SRC IS NULL");
//            }
//
//        }
//    };
//
//    private VodAddress getAddress(int id) {
//        Self destNode = privateAddress.get(id);
//        if (destNode == null) {
//            destNode = publicAddress.get(id);
//        }
//
//        return destNode.getAddress();
//    }
//
//    Component getComponent(int id) {
//        Component peer = privatePeers.get(id);
//        if (peer == null) {
//            peer = publicPeers.get(id);
//        }
//        return peer;
//    }
//
//    void connect(Integer src, Integer dest) {
//        Component srcPeer = privatePeers.get(src);
//        if (srcPeer == null) {
//            srcPeer = publicPeers.get(src);
//        }
//
//        Self destNode = privateAddress.get(dest);
//        if (destNode == null) {
//            destNode = publicAddress.get(dest);
//        }
//
//        trigger(new Connect(destNode.clone(NT_PEER_OVERLAY_ID).getAddress()), srcPeer.getNegative(NatTraverserSimulatorPort.class));
//    }
//
//    private void createAndStartNewPeer(Integer id, VodAddress.NatType natType) {
//        long seed = r.nextInt();
//        Component peer = create(NtPeer.class);
//        Component natTraverser = create(NatTraverser.class);
//        Component natGateway = create(DistributedNatGatewayEmulator.class);
//        Component portReservoir = create(PortReservoirComp.class);
//        Component croupier = create(Croupier.class);
//
//        InetAddress ip = ipGenerator.generateIP();
//        Address peerAddress = new Address(ip, VodConfig.getPort(), id);
//
//        Nat nat;
//        InetAddress natIp = ipGenerator.generateIP();
//        VodAddress addr;
//        Self self;
//        boolean isOpen = false;
//        if (natType == VodAddress.NatType.OPEN) {
//            publicPeers.put(id, peer);
//            trigger(new DistributedNatGatewayEmulatorInit(new Nat(Nat.Type.OPEN), natIp, 10000, 65000), natGateway.control());
//            addr = new VodAddress(peerAddress, NT_PEER_OVERLAY_ID);
//            self = new SelfImpl(addr);
//            publicAddress.put(id, self);
//            if (publicPeers.size() == 1) {
//                server1 = addr;
//            } else if (publicPeers.size() == 2) {
//                server2 = addr;
//            }
//            if (publicPeers.size() <= 2) {
//                isOpen = true;
//            }
//        } else {
//            nat = RANDOM_NAT_TYPE ? new NatFactory(seed).getRandomNat()
//                    : new NatFactory(seed).getProbabilisticNat();
//            trigger(new DistributedNatGatewayEmulatorInit(nat, natIp, 50000, 65000), natGateway.control());
//            trigger(new PortInit(seed), portReservoir.control());
//            privatePeers.put(id, peer);
//            addr = new VodAddress(peerAddress, NT_PEER_OVERLAY_ID, nat);
//            self = new SelfImpl(addr);
//            privateAddress.put(id, self);
//            logger.info("Starting peer " + peerAddress + " nat ip" + natIp + " (Nat Type is) : " + nat);
//        }
//
//        int filterId = peerAddress.getId();
//        connect(natTraverser.getPositive(NatTraverserPort.class), peer.getNegative(NatTraverserPort.class));
//        connect(natTraverser.getPositive(VodNetwork.class), peer.getNegative(VodNetwork.class));
//        connect(natTraverser.getPositive(VodNetwork.class), croupier.getNegative(VodNetwork.class));
//        connect(natGateway.getPositive(VodNetwork.class), natTraverser.getNegative(VodNetwork.class));
//        connect(natGateway.getNegative(VodNetwork.class), network
//                , new MsgDestFilterNodeId(filterId)
//        );
//
//        connect(timer, peer.getNegative(Timer.class));
//        connect(timer, natTraverser.getNegative(Timer.class));
//        connect(timer, natGateway.getNegative(Timer.class));
//        connect(timer, croupier.getNegative(Timer.class));
//
//        connect(natGateway.getPositive(NatNetworkControl.class),
//                natTraverser.getNegative(NatNetworkControl.class));
//        connect(portReservoir.getPositive(NatNetworkControl.class),
//                natGateway.getNegative(NatNetworkControl.class));
//
//        connect(natTraverser.getNegative(PeerSamplePort.class), croupier.getPositive(PeerSamplePort.class));
//
//        subscribe(handleConnectionResult, peer.getNegative(NatTraverserSimulatorPort.class));
//
//        Set<Address> servers = new HashSet<Address>();
//        if (server2 != null && self.isOpen()) {
//            servers.add(server1.getPeerAddress());
//        } else if (server2 != null) {
//            servers.add(server2.getPeerAddress());
//        }
//
//        trigger(new NatTraverserInit(self,
//                servers,
//                seed,
//                ntConfig,
//                hpClientConfig,
//                rendezvousServerConfig,
//                stunServerConfig,
//                stunClientConfig,
//                parentMakerConfig,
//                isOpen), natTraverser.control());
//
//        trigger(new NtPeerInit(self.clone(NT_PEER_OVERLAY_ID)), peer.getControl());
//        trigger(new CroupierInit(self.clone(VodConfig.SYSTEM_OVERLAY_ID),
//                croupierConfig), croupier.getControl());
//
//        List<VodDescriptor> bootstrappers = new ArrayList<VodDescriptor>();
//        if (server1 != null) {
//            bootstrappers.add(new VodDescriptor(ToVodAddr.systemAddr(server1.getPeerAddress()), new UtilityVod(-1), 0, VodConfig.DEFAULT_MTU));
//        }
//
//        if (server2 != null) {
//            bootstrappers.add(new VodDescriptor(ToVodAddr.systemAddr(server2.getPeerAddress()), new UtilityVod(-1), 0, VodConfig.DEFAULT_MTU));
//        }
//
//        trigger(new CroupierJoin(bootstrappers), croupier.getPositive(CroupierPort.class));
//    }
//
//    private void stopAndDestroyPeer(Integer id) {
//        Component peer = privatePeers.get(id);
//        if (peer == null) {
//            peer = publicPeers.get(id);
//        }
//
//        trigger(new Stop(), peer.getControl());
//
//        privatePeers.remove(id);
//        publicPeers.remove(id);
//        privateAddress.remove(id);
//        publicAddress.remove(id);
//        peerTypes.remove(id);
//
//        destroy(peer);
//    }
//
//    Handler<StartCollectData> handleStartCollectData = new Handler<StartCollectData>() {
//        @Override
//        public void handle(StartCollectData event) {
//            logger.info(" START COLLECT DATA");
//        }
//    };
//
//    Handler<StopCollectData> handleStopCollectData = new Handler<StopCollectData>() {
//        @Override
//        public void handle(StopCollectData event) {
//            int totalSuccess = 0;
//            int totalFail = 0;
//            logger.info("Croupier Stats:");
//            CroupierStats.report(VodConfig.SYSTEM_OVERLAY_ID);
//
//            logger.info("Port bind errors: {}", HpClient.portBoundFailure);
//
//            logger.info("Hp Ping success/failure: {}/{} ", HpClient.pingSuccessCount.get(),
//                    HpClient.pingFailureCount);
//
//            logger.info("Hp No heartbeats: {}", HpClient.nonPingedConnections);
//
//            for (String str : HpClient.failedPings.keySet()) {
//                logger.info(str + " = " + HpClient.failedPings.get(str));
//            }
//
//            System.out.println("Success");
//            for (String natType : successCount.keySet()) {
//                System.out.println("\t" + natType + "/" + successCount.get(natType));
//                totalSuccess += successCount.get(natType);
//            }
//
//            System.out.println("Fail");
//            for (String natType : failCount.keySet()) {
//                System.out.println("\t" + natType + "/" + failCount.get(natType));
//                totalFail += failCount.get(natType);
//            }
//
//            System.out.println("Total Success = " + totalSuccess);
//            System.out.println("Total Fail = " + totalFail);
//
//            Kompics.shutdown();
//            System.exit(0);
//        }
//    };
//
//    Handler<ConnectionResult> handleConnectionResult = new Handler<ConnectionResult>() {
//        @Override
//        public void handle(ConnectionResult event) {
//            logger.debug(event.isRes() + ":: " + event.getSrc() + " ---> " + event.getDest());
//            String natPair = event.getNatPair();
//            if (event.isRes()) {
//                Integer c = successCount.get(natPair);
//                if (c == null) {
//                    successCount.put(natPair, 1);
//                } else {
//                    successCount.put(natPair, c + 1);
//                }
//            } else {
//                Integer c = failCount.get(natPair);
//                if (c == null) {
//                    failCount.put(natPair, 1);
//                } else {
//                    failCount.put(natPair, c + 1);
//                }
//            }
//        }
//    };
//
//    private void join(Integer id, VodAddress.NatType peerType) {
//        // join with the next id if this id is taken
//        Integer successor = view.getNode(id);
//        while (successor != null && successor.equals(id)) {
//            id = (id == Integer.MAX_VALUE) ? 0 : ++peerIdSequence;
//            successor = view.getNode(id);
//        }
//
//        logger.debug("JOIN@{}", id);
//
//        createAndStartNewPeer(id, peerType);
//        view.addNode(id);
//        peerTypes.put(id, peerType);
//    }
//
//    private void fail(Integer id, VodAddress.NatType peerType) {
//        if (view.size() == 0) {
//            System.err.println("Empty network");
//            return;
//        }
//        id = view.getNode(id);
//        int finishId = id;
//        while (peerTypes.get(id) != peerType) {
//            id = (id == Integer.MAX_VALUE) ? 0 : ++id;
//            id = view.getNode(id);
//            if (id == finishId) {
//                break;
//            }
//        }
//
//        logger.debug("FAIL@" + id);
//
//        if (id != finishId + 1) {
//            view.removeNode(id);
//            stopAndDestroyPeer(id);
//        }
//    }
//}
