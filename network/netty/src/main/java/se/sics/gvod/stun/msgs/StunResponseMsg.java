package se.sics.gvod.stun.msgs;

import org.jboss.netty.buffer.ChannelBuffer;
import se.sics.gvod.common.msgs.MessageEncodingException;
import se.sics.gvod.address.Address;
import se.sics.gvod.common.msgs.DirectMsgNetty;
import se.sics.gvod.net.VodAddress;
import se.sics.gvod.net.util.UserTypesEncoderFactory;
import se.sics.gvod.timer.TimeoutId;

public abstract class StunResponseMsg extends DirectMsgNetty {

    protected final long transactionId;
    protected final Address replyAddr;


    public StunResponseMsg(VodAddress src, VodAddress dest, Address replyAddr,
            long transactionId, TimeoutId timeoutId) {
        super(src, dest, timeoutId);
        this.replyAddr = replyAddr;
        this.transactionId = transactionId;
    }

    public Address getReplyPublicAddr() {
        return replyAddr;
    }

    public long getTransactionId() {
        return transactionId;
    }

    @Override
    protected int getHeaderSize() {
        return super.getHeaderSize()
                + 8 /* transactionId */
                + UserTypesEncoderFactory.ADDRESS_LEN
                ;
    }
    @Override
    protected void writeHeader(ChannelBuffer buffer) throws MessageEncodingException {
        super.writeHeader(buffer);
        buffer.writeLong(transactionId);
        UserTypesEncoderFactory.writeAddress(buffer, replyAddr);
    }
}
