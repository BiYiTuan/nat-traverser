/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package se.sics.gvod.nat.common;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.commons.lang.NotImplementedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.sics.gvod.address.Address;
import se.sics.gvod.common.RetryComponentDelegator;
import se.sics.gvod.common.msgs.DirectMsgNetty;
import se.sics.gvod.common.msgs.RelayMsgNetty;
import se.sics.gvod.net.VodNetwork;
import se.sics.gvod.net.msgs.DirectMsg;
import se.sics.gvod.net.msgs.RewriteableMsg;
import se.sics.gvod.net.msgs.RewriteableRetryTimeout;
import se.sics.gvod.net.msgs.ScheduleRetryTimeout;
import se.sics.gvod.timer.CancelTimeout;
import se.sics.gvod.timer.OverlayTimeout;
import se.sics.gvod.timer.ScheduleTimeout;
import se.sics.gvod.timer.TimeoutId;
import se.sics.gvod.timer.Timer;
import se.sics.kompics.AutoSubscribeComponent;
import se.sics.kompics.Channel;
import se.sics.kompics.ChannelFilter;
import se.sics.kompics.Component;
import se.sics.kompics.ComponentDefinition;
import se.sics.kompics.Handler;
import se.sics.kompics.KompicsEvent;
import se.sics.kompics.Negative;
import se.sics.kompics.Port;
import se.sics.kompics.PortType;
import se.sics.kompics.Positive;
import se.sics.kompics.Stop;

/**
 * This class is a subclass of AutoSubscribeComponent that adds the ability to
 * retry messages sent over the network port. Messages that will be retried are
 * sent by calling the retry() method. Components that inherit from
 * MsgRetryComponent also inherit a network and a timer port, so they shouldn't
 * define a network or timer port locally.
 *
 * The API for sending retry msgs is different from standard kompics. First you
 * define a message object, then a ScheduleRetry object, then a timer object.
 * You send retry on the timer object. The timer object contains a reference to
 * the msg object. If the message is retried up to the maximum number of
 * attempts and a 'cancelRetry()' method has not been called for the timoutId
 * (normally cancelRetry is called when a response msg is returned), then the
 * timeout object is triggered on the handler for that timeout object defined on
 * the subclass.
 * 
 * MsgRetryComponent allows us to run unit tests on components, but required
 * introducing a new 'delegator' object on which all kompics operations are performed.
 * Kompics' operations inherited from ComponentDefinition are now
 * instead invoked on a 'delegator' object:
 * 
 * trigger(new SomeEvent(..), somePort);
 * 
 * becomes 
 *
 * delegator.doTrigger(new SomeEvent(..), somePort);
 * 
 * 
 * See unit tests for concrete examples.
 *
 * @author jdowling
 */
public abstract class MsgRetryComponent extends AutoSubscribeComponent
        implements RetryComponentDelegator {

    private Logger logger = LoggerFactory.getLogger(MsgRetryComponent.class);
    protected Positive<VodNetwork> network = positive(VodNetwork.class);
    protected Positive<Timer> timer = positive(Timer.class);
    protected RetryComponentDelegator delegator;
    /**
     * Map of Message Types that each contain a map of timeoutId/retry objects.
     * Map is static, so that timeouts can be created in one component, but
     * cancelled in another component.
     */
    protected ConcurrentHashMap<TimeoutId, Retry> mapMessageRetry =
            new ConcurrentHashMap<TimeoutId, Retry>();

    /**
     * This is the timeout event that is recvd by MsgRetryComp if the timer
     * isn't cancelled before the timeout expires.
     */
    public static class RequestTimeoutEvent extends OverlayTimeout {

        private final RewriteableMsg msg;

        public RequestTimeoutEvent(ScheduleTimeout timeout, RewriteableMsg msg, int overlayId) {
            super(timeout, overlayId);
            this.msg = msg;
        }

        public RewriteableMsg getMsg() {
            return msg;
        }
    }

    /**
     * The Retry object is stored with each timeout event in a map, so that if a
     * timeout is triggered, the retry object contains state required to retry
     * the message.
     */
    protected static class Retry {

        private final Object context;
        private final RewriteableMsg message;
        private final ScheduleRetryTimeout retryScheduleTimeout;
        private long retransmissionTimeout;
        private int retriesLeft;
        private final double rtoScaleAfterRetry;
        private int rtoRetries;
        private int numReplies;
        private Set<Address> multicastAddrs;

        public Retry(RewriteableMsg message, long retransmissionTimeout, int rtoRetries,
                double rtoScaleAfterRetry, Object context, Set<Address> multicastAddrs) {
            this(message, retransmissionTimeout, rtoRetries, rtoScaleAfterRetry,
                    null, context, multicastAddrs);
        }

        public Retry(RewriteableMsg message, long retransmissionTimeout, int rtoRetries,
                double rtoScaleAfterRetry, ScheduleRetryTimeout retryTimeout,
                Object context, Set<Address> multicastAddrs) {
            this(message, retransmissionTimeout, rtoRetries, rtoScaleAfterRetry,
                    retryTimeout, 1, context, multicastAddrs);
        }

        public Retry(RewriteableMsg message, long retransmissionTimeout, int rtoRetries,
                double rtoScaleAfterRetry, ScheduleRetryTimeout retryTimeout, int numReplies,
                Object context, Set<Address> multicastAddrs) {
            super();
            if (retransmissionTimeout < 0) {
                throw new IllegalArgumentException("Retransmission timeout must be zero or greater");
            }
            if (rtoRetries < 0) {
                throw new IllegalArgumentException("Number of Retries must be zero or greater");
            }
            if (numReplies < 0) {
                throw new IllegalArgumentException("Number of Replies must be zero or greater");
            }
            if (rtoScaleAfterRetry == 0 || rtoScaleAfterRetry < 0) {
                throw new IllegalArgumentException("RtoScaleAfterRetry must be greater than zero.");
            }
            this.message = message;
            this.retransmissionTimeout = retransmissionTimeout;
            this.retriesLeft = rtoRetries;
            this.rtoRetries = rtoRetries;
            this.rtoScaleAfterRetry = rtoScaleAfterRetry;
            this.retryScheduleTimeout = retryTimeout;
            this.numReplies = numReplies;
            this.context = context;
            this.multicastAddrs = multicastAddrs;
        }

        public Object getContext() {
            return context;
        }

        public Set<Address> getMulticastAddrs() {
            return multicastAddrs;
        }

        public RewriteableMsg getMessage() {
            return message;
        }

        public long getRetransmissionTimeout() {
            return retransmissionTimeout;
        }

        public int getRetriesLeft() {
            return retriesLeft;
        }

        public int getRtoRetries() {
            return rtoRetries;
        }

        public double getRtoScaleAfterRetry() {
            return rtoScaleAfterRetry;
        }

        public void rtoScale() {
            this.retransmissionTimeout = (long) (rtoScaleAfterRetry * retransmissionTimeout);
        }

        public void decRetriesLeft() {
            retriesLeft--;
        }

        public TimeoutId getTimeoutId() {
            return getMessage().getTimeoutId();
        }

        public ScheduleTimeout getScheduleTimeout() {
            if (retryScheduleTimeout != null) {
                return retryScheduleTimeout.getScheduleTimeout();
            }
            return null;
        }

        public int decNumReplies() {
            numReplies--;
            if (numReplies < 0) {
                throw new IllegalStateException("Num of replies expected is less than 0.");
            }
            return numReplies;
        }

        public int getNumReplies() {
            return numReplies;
        }
    }

    /**
     * Subclasses typically have 2 constructors: 1. a no argument constructor
     * that call the constructor below with 'null' as a parameter.
     *
     * 2. the actual constructor that calls this constructor on its superclass.
     * The actual constructor normally calls autosubscribe() to subscribe the
     * SubClass' handlers:
     *
     * public SubClass extends MsgRetryComponent{
     *
     *
     * public SubClass() { 
     *  this(null); 
     * }
     *
     * public SubClass(RetryComponentDelegator delegator) {
     * // don't forget to include 'this.' when calling the delegator.
     *  this.delegator.doAutoSubscribe(); 
     * 
     * }
     *
     * @param delegator
     */
    public MsgRetryComponent(RetryComponentDelegator delegator) {
        this.delegator = (delegator == null) ? this : delegator;
    }

    protected TimeoutId multicast(RewriteableRetryTimeout timeout, Set<Address> multicastAddrs) {
        return retry(timeout, multicastAddrs, null);
    }

    protected TimeoutId multicast(RewriteableRetryTimeout timeout, Set<Address> multicastAddrs,
            Object request) {
        return retry(timeout, multicastAddrs, request);
    }

    protected TimeoutId multicast(RewriteableMsg msg, Set<Address> multicastAddrs,
            long timeoutInMilliSecs, int rtoRetries, int overlayId) {
        return multicast(msg, multicastAddrs, timeoutInMilliSecs, rtoRetries, 
                1.0d, null, overlayId);
    }

    protected TimeoutId multicast(RewriteableMsg msg, Set<Address> multicastAddrs,
            long timeoutInMilliSecs,
            int rtoRetries, Object request, int overlayId) {
        return multicast(msg, multicastAddrs, timeoutInMilliSecs, rtoRetries, 
                1.0d, request, overlayId);
    }

    protected TimeoutId multicast(RewriteableMsg msg, Set<Address> multicastAddrs,
            long timeoutInMilliSecs,
            int rtoRetries, double rtoScaleAfterRetry, int overlayId) {
        return multicast(msg, multicastAddrs, timeoutInMilliSecs, rtoRetries, 
                rtoScaleAfterRetry, null, overlayId);
    }

    protected TimeoutId multicast(RewriteableMsg msg, Set<Address> multicastAddrs,
            long timeoutInMilliSecs, int rtoRetries, double rtoScaleAfterRetry, Object request, int overlayId) {
        return retry(msg, timeoutInMilliSecs, rtoRetries, rtoScaleAfterRetry,
                request, multicastAddrs, null, overlayId);
    }

    protected TimeoutId retry(RewriteableRetryTimeout timeout) {
        return retry(timeout, null);
    }

    /**
     * Invoke this method to send a message.
     *
     * @param timeout object including msg to be sent
     * @param request request object (or any context) that will be availble to
     * be retrieved using getContext() when either the response or timeout is
     * received.
     * @return timeoutId
     */
    protected TimeoutId retry(RewriteableRetryTimeout timeout, Object request) {
        return retry(timeout, null, request);
    }

    private TimeoutId retry(RewriteableRetryTimeout timeout, Set<Address> multicastAddrs,
            Object request) {
        ScheduleRetryTimeout st = timeout.getScheduleRewriteableRetryTimeout();
        int rtoRetries = st.getRtoRetries();
        double rtoScaleAfterRetry = st.getRtoScaleAfterRetry();
        TimeoutId timeoutId = timeout.getTimeoutId();
        timeout.getMsg().setTimeoutId(timeoutId);
        RewriteableMsg msg = timeout.getMsg();
        return scheduleMessageRetry(new Retry(msg, st.getDelay(), rtoRetries, rtoScaleAfterRetry, st,
                request, multicastAddrs), st.getDelay(), null, timeout.getOverlayId());
    }

    protected TimeoutId retry(RewriteableMsg msg, long timeoutInMilliSecs,
            int rtoRetries, int overlayId) {
        return retry(msg, timeoutInMilliSecs, rtoRetries, 1.0d, null, overlayId);
    }

    protected TimeoutId retry(RewriteableMsg msg, long timeoutInMilliSecs,
            int rtoRetries, Object request, int overlayId) {
        return retry(msg, timeoutInMilliSecs, rtoRetries, 1.0d, request, overlayId);
    }

    protected TimeoutId retry(RewriteableMsg msg, long timeoutInMilliSecs,
            int rtoRetries, double rtoScaleAfterRetry, int overlayId) {
        return retry(msg, timeoutInMilliSecs, rtoRetries, rtoScaleAfterRetry, null, overlayId);
    }

    protected TimeoutId retry(RewriteableMsg msg, long timeoutInMilliSecs,
            int rtoRetries, double rtoScaleAfterRetry, Object request, int overlayId) {
        return retry(msg, timeoutInMilliSecs, rtoRetries, rtoScaleAfterRetry,
                request, null, null, overlayId);
    }

    private TimeoutId retry(RewriteableMsg msg, long timeoutInMilliSecs,
            int rtoRetries, double rtoScaleAfterRetry, Object request,
            Set<Address> multicastAddrs, TimeoutId oldTimeoutId, int overlayId) {

        return scheduleMessageRetry(new Retry(msg, timeoutInMilliSecs,
                rtoRetries, rtoScaleAfterRetry, request, multicastAddrs),
                timeoutInMilliSecs, oldTimeoutId, overlayId);
    }

    /**
     *
     * @param retry
     * @param timeoutInMilliSecs
     * @param oldTimeoutId leave this as null, normally. To replace the
     * timeoutId that would be generated with an oldTimeoutId, set this
     * variable.
     * @return
     */
    private TimeoutId scheduleMessageRetry(Retry retry,
            long timeoutInMilliSecs, TimeoutId oldTimeoutId, int overlayId) {
        RewriteableMsg msg = retry.getMessage();

        ScheduleTimeout st = new ScheduleTimeout(timeoutInMilliSecs);
        RequestTimeoutEvent requestTimeoutEvent = new RequestTimeoutEvent(st, msg, overlayId);
        st.setTimeoutEvent(requestTimeoutEvent);
        if (oldTimeoutId != null) {
            requestTimeoutEvent.setTimeoutId(oldTimeoutId);
        }
        TimeoutId timeoutId = requestTimeoutEvent.getTimeoutId();
        msg.setTimeoutId(timeoutId);


        // retransmissionTimeout is '0' if we just execute retry(msg) with no
        // parameters. In this case, we won't retry the message. We just set the
        // timeoutId. timeoutId is then used to discard duplicates in NatTraverser.
        if (st.getDelay() != 0) {
            logger.trace("Storing timer {} for {} .", timeoutId, msg.getClass().getName());
            mapMessageRetry.put(timeoutId, retry);
            trigger(st, timer);
        }

        if (retry.getMulticastAddrs() != null) {
            Set<Address> multicastAddrs = retry.getMulticastAddrs();
            for (Address addr : multicastAddrs) {
                msg.rewriteDestination(addr);
                trigger(msg, network);
            }
        } else {
            trigger(msg, network);
        }
        return timeoutId;
    }

    protected Object getContext(TimeoutId timeoutId) {
        Object request = null;
        logger.trace("Cancelling timer " + timeoutId);
        if (mapMessageRetry.containsKey(timeoutId)) {
            Retry r = this.mapMessageRetry.get(timeoutId);
            request = r.getContext();
        }
        return request;
    }
    

    /**
     * Called by handler in base class
     *
     * @param msgType class of the corresponding Request message sent.
     * @param timeoutId TimeoutId included in the Request message.
     * @return true if it finds and successfully cancels the timeout for the
     * msg, false otherwise.
     */
    protected boolean cancelRetry(TimeoutId timeoutId) {
        if (timeoutId == null) {
            throw new IllegalArgumentException("timeoutId was null when cancelling retry");
        }
        if (timeoutId.isSupported()) {
            logger.trace("Cancelling timer: " + timeoutId);

            if (mapMessageRetry.remove(timeoutId) != null) {
                CancelTimeout ct = new CancelTimeout(timeoutId);
                trigger(ct, timer);
                return true;
            } else {
                logger.trace("Cancelling timer failed: " + timeoutId.getId() + " . Couldn't find timeoutId.");
            }
        } else {
            throw new IllegalStateException("TimeoutId not used by this instance");
        }
        return false;
    }

    /**
     * Cannot be called from subclass.
     *
     * @param requestClass
     * @param timeoutId
     * @return
     */
    private Retry getRetryObj(TimeoutId timeoutId) {
        return mapMessageRetry.get(timeoutId);
    }
    protected Handler<RequestTimeoutEvent> handleRTO = new Handler<RequestTimeoutEvent>() {
        @Override
        public void handle(RequestTimeoutEvent timeout) {
            TimeoutId timeoutId = timeout.getTimeoutId();
            Retry retryData = getRetryObj(timeoutId);

            if (retryData == null) {
                logger.warn(getClass() + " couldn't find Retry object for {} and {}",
                        timeout.getTimeoutId(),
                        timeout.getMsg());
                return;
            }

            cancelRetry(timeoutId);

            RewriteableMsg msg = retryData.getMessage();
            if (retryData.getRetriesLeft() > 0) {
                retryData.decRetriesLeft();
                retryData.rtoScale();
                if (msg instanceof DirectMsg) {
                    DirectMsg m = (DirectMsg) msg;

                    logger.debug("Message Retry Comp (" + m.getSource().getId() + ")"
                            + " : Retrying Src: " + m.getVodSource().getId()
                            + " dest: " + m.getVodDestination().getId() + " "
                            + msg.getClass().toString() + " retries=" + retryData.getRetriesLeft());
                } else {
                    logger.debug("Message Retry Comp (" + msg.getSource().getId() + ")"
                            + " : Retrying Src: " + msg.getSource().getId()
                            + " dest: " + msg.getDestination().getId() + " "
                            + msg.getClass().toString() + " retries=" + retryData.getRetriesLeft());
                }
                scheduleMessageRetry(retryData, retryData.getRetransmissionTimeout(),
                        timeoutId, timeout.getOverlayId());
            } else if (retryData.getRetriesLeft() == 0) {
                // if there's a client-supplied timeout, send it back to the client
                if (retryData.getScheduleTimeout() != null) {
                    TimeoutId callbackTimeoutId = retryData.getTimeoutId();
                    mapMessageRetry.put(callbackTimeoutId, retryData);
                    ScheduleTimeout st = retryData.getScheduleTimeout();
                    st.getTimeoutEvent().setTimeoutId(callbackTimeoutId);
                    trigger(st, timer);
                    logger.debug("Msg timeout: no retries left: "
                            + retryData.getMessage().getClass().getName()
                            + " src: " + msg.getSource()
                            + " dest: " + msg.getDestination() + " "
                            + msg.getTimeoutId());
                } else {
                    logger.warn("MsgRetry: timeout obj was null with no retries left: {} ",
                            retryData.getMessage().getClass().getName());
                }
            } else {
                // shouldn't get here
                throw new IllegalStateException("Message retry component retry count < 0, shouldn't have happened.");
            }
        }
    };
    public Handler<Stop> handleStop = new Handler<Stop>() {
        @Override
        public void handle(Stop event) {
            // cancel all the schedule retry timers

            Set<TimeoutId> timerIds = mapMessageRetry.keySet();
            for (TimeoutId id : timerIds) {
                trigger(new CancelTimeout(id), timer);
            }
//            }
            // Call stop handler in subclass
            stop(event);
        }
    };

    abstract public void stop(Stop event);

    @Override
    public void doAutoSubscribe() {
        autoSubscribe();
    }

    @Override
    public <P extends PortType> void doTrigger(KompicsEvent event, Port<P> port) {
        if (event == null || port == null) {
            throw new NullPointerException("Null event or null port when calling trigger.");
        }
        if (event instanceof DirectMsgNetty.Request || 
                event instanceof RelayMsgNetty.Request ||
                event instanceof DirectMsgNetty.SystemRequest
                ) {
            logger.error("calling doTrigger - should call doRetry: " + event.getClass());
            throw new IllegalStateException("Request msgs should not call doTrigger(). "
                    + " They should call doRetry().");
        }
        trigger(event, port);
    }

    @Override
    public TimeoutId doMulticast(RewriteableRetryTimeout timeout, Set<Address> multicastAddrs) {
        return multicast(timeout, multicastAddrs, null);
    }

    @Override
    public TimeoutId doMulticast(RewriteableRetryTimeout timeout, Set<Address> multicastAddrs,
            Object request) {
        return multicast(timeout, multicastAddrs, request);
    }

    @Override
    public TimeoutId doRetry(RewriteableRetryTimeout timeout) {
        return retry(timeout);
    }

    @Override
    public TimeoutId doRetry(RewriteableRetryTimeout timeout, Object request) {
        return retry(timeout, request);
    }

    @Override
    public boolean doCancelRetry(
            TimeoutId timeoutId) {
        return cancelRetry(timeoutId);
    }

    @Override
    public <E extends KompicsEvent, P extends PortType> void doSubscribe(Handler<E> handler, Port<P> port) {
        subscribe(handler, port);
    }

    @Override
    public <P extends PortType> Negative<P> getNegative(Class<P> portType) {
        return negative(portType);
    }

    @Override
    public <P extends PortType> Positive<P> getPositive(Class<P> portType) {
        return positive(portType);
    }

    @Override
    public TimeoutId doRetry(RewriteableMsg msg, int overlayId) {
        return retry(msg, 0, 0, overlayId);
    }

    @Override
    public TimeoutId doRetry(RewriteableMsg msg, long timeoutInMilliSecs, 
        int rtoRetries, int overlayId) {
        return retry(msg, timeoutInMilliSecs, rtoRetries, overlayId);
    }

    @Override
    public TimeoutId doRetry(RewriteableMsg msg, long timeoutInMilliSecs, int rtoRetries,
            Object request, int overlayId) {
        return retry(msg, timeoutInMilliSecs, rtoRetries, request, overlayId);
    }

    @Override
    public TimeoutId doRetry(RewriteableMsg msg, long timeoutInMilliSecs, int rtoRetries,
            double rtoScaleAfterRetry, int overlayId) {
        return retry(msg, timeoutInMilliSecs, rtoRetries, rtoScaleAfterRetry,
                overlayId);
    }

    @Override
    public TimeoutId doRetry(RewriteableMsg msg, long timeoutInMilliSecs, int rtoRetries,
            double rtoScaleAfterRetry, Object request, int overlayId) {
        return retry(msg, timeoutInMilliSecs, rtoRetries, rtoScaleAfterRetry, request,
                overlayId);
    }

    @Override
    public TimeoutId doMulticast(RewriteableMsg msg, Set<Address> multicastAddrs,
            long timeoutInMilliSecs, int rtoRetries, int overlayId) {
        return multicast(msg, multicastAddrs, timeoutInMilliSecs, rtoRetries,
                overlayId);
    }

    @Override
    public TimeoutId doMulticast(RewriteableMsg msg, Set<Address> multicastAddrs,
            long timeoutInMilliSecs, int rtoRetries, Object request, int overlayId) {
        return multicast(msg, multicastAddrs, timeoutInMilliSecs, rtoRetries, 
                request, overlayId);
    }

    @Override
    public TimeoutId doMulticast(RewriteableMsg msg, Set<Address> multicastAddrs,
            long timeoutInMilliSecs, int rtoRetries, double rtoScaleAfterRetry, int overlayId) {
        return multicast(msg, multicastAddrs, timeoutInMilliSecs, rtoRetries, 
                rtoScaleAfterRetry, overlayId);
    }

    @Override
    public TimeoutId doMulticast(RewriteableMsg msg, Set<Address> multicastAddrs,
            long timeoutInMilliSecs, int rtoRetries, double rtoScaleAfterRetry, 
            Object request, int overlayId) {
        return retry(msg, timeoutInMilliSecs, rtoRetries, rtoScaleAfterRetry, request,
                multicastAddrs, null, overlayId);
    }

    @Override
    public Object doGetContext(
            TimeoutId timeoutId) {
        return getContext(timeoutId);
    }

    @Override
    public <P extends PortType> Channel<P> doConnect(
            Positive<P> positive, Negative<P> negative) {
        return connect(positive, negative);
    }

    @Override
    public <P extends PortType> Channel<P> doConnect(
            Negative<P> negative, Positive<P> positive) {
        return connect(negative, positive);
    }

    @Override
    public <P extends PortType> Channel<P> doConnect(Positive<P> positive,
            Negative<P> negative, ChannelFilter<?, ?> filter) {
        return connect(positive, negative, filter);
    }

    @Override
    public <P extends PortType> Channel<P> doConnect(Negative<P> negative,
            Positive<P> positive, ChannelFilter<?, ?> filter) {
        return connect(negative, positive, filter);
    }

    @Override
    public <P extends PortType> void doDisconnect(Negative<P> negative,
            Positive<P> positive) {
        // TODO - tell cosmin that disconnect should return a portType
        disconnect(negative, positive);
    }

    @Override
    public <P extends PortType> void doDisconnect(Positive<P> positive,
            Negative<P> negative) {
        // TODO - tell cosmin that disconnect should return a portType
        disconnect(positive, negative);
    }

    @Override
    public Component doCreate(Class<? extends ComponentDefinition> definition) {
        // TODO: implement doCreate in kompics core.
//        return create(definition);
        throw new NotImplementedException("delegator.doCreate() not supported yet in kompics");
    }

    @Override
    public boolean isUnitTest() {
        return false;
    }
}
