/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package se.sics.kompics;

import org.jboss.netty.buffer.ChannelBuffer;
import se.sics.gvod.common.msgs.DirectMsgNetty;
import se.sics.gvod.common.msgs.MessageEncodingException;
import se.sics.gvod.net.BaseMsgFrameDecoder;
import se.sics.gvod.net.VodAddress;
import se.sics.gvod.net.msgs.RewriteableMsg;

/**
 *
 * @author Jim Dowling<jdowling@sics.se>
 */
public class Ping extends DirectMsgNetty {

    public Ping(VodAddress client, VodAddress server) {
        super(client, server);
    }

    @Override
    public int getSize() {
        return getHeaderSize();
    }

    @Override
    public byte getOpcode() {
        return BaseMsgFrameDecoder.PING;
    }

    @Override
    public ChannelBuffer toByteArray() throws MessageEncodingException {
        return createChannelBufferWithHeader();
    }

    @Override
    public RewriteableMsg copy() {
        Ping copy = new Ping(vodDest, vodSrc);
        copy.setTimeoutId(timeoutId);
        return copy;
    }
}