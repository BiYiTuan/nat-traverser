package se.sics.gvod.hp.msgs;

import java.util.logging.Level;
import java.util.logging.Logger;
import se.sics.gvod.common.msgs.*;
import org.jboss.netty.buffer.ChannelBuffer;
import se.sics.gvod.net.MsgFrameDecoder;
import se.sics.gvod.net.msgs.DirectMsg;

public class RelayRequestMsgFactory {

    public static class Request extends HpMsgFactory {

        private Request() {
        }

        public static RelayRequestMsg.ClientToServer fromBuffer(ChannelBuffer buffer)
                
                throws MessageDecodingException {
            return (RelayRequestMsg.ClientToServer)
                    new RelayRequestMsgFactory.Request().decode(buffer, true);
        }

        @Override
        protected RelayRequestMsg.ClientToServer process(ChannelBuffer buffer) throws MessageDecodingException {

            MsgFrameDecoder decoder= null;
            try {
                decoder = VodMsgNettyFactory.msgFrameDecoder.newInstance();
            } catch (InstantiationException ex) {
                Logger.getLogger(RelayRequestMsgFactory.class.getName()).log(Level.SEVERE, null, ex);
            } catch (IllegalAccessException ex) {
                Logger.getLogger(RelayRequestMsgFactory.class.getName()).log(Level.SEVERE, null, ex);
            }
            DirectMsg message = null;
            try {
                message = (DirectMsg) decoder.parse(buffer);
            } catch (Exception ex) {
                Logger.getLogger(RelayRequestMsgFactory.class.getName()).log(Level.SEVERE, null, ex);
                throw new MessageDecodingException(ex);
            }

            return new RelayRequestMsg.ClientToServer(vodSrc, vodDest, remoteClientId, message);
        }
    }

    public static class Response extends HpMsgFactory {

        private Response() {
        }

        public static RelayRequestMsg.ServerToClient fromBuffer(ChannelBuffer buffer)
                
                throws MessageDecodingException {
            return (RelayRequestMsg.ServerToClient)
                    new RelayRequestMsgFactory.Response().decode(buffer, true);
        }

        @Override
        protected RelayRequestMsg.ServerToClient process(ChannelBuffer buffer) throws MessageDecodingException {

            MsgFrameDecoder decoder;
            try {
                decoder = VodMsgNettyFactory.msgFrameDecoder.newInstance();
            } catch (InstantiationException ex) {
                Logger.getLogger(RelayRequestMsgFactory.class.getName()).log(Level.SEVERE, null, ex);
                throw new MessageDecodingException(ex.getMessage());
            } catch (IllegalAccessException ex) {
                Logger.getLogger(RelayRequestMsgFactory.class.getName()).log(Level.SEVERE, null, ex);
                throw new MessageDecodingException(ex.getMessage());
            }
            DirectMsg message = null;
            try {
                message = (DirectMsg) decoder.parse(buffer);
            } catch (Exception ex) {
                Logger.getLogger(RelayRequestMsgFactory.class.getName()).log(Level.SEVERE, null, ex);
            }
            return new RelayRequestMsg.ServerToClient(vodSrc, vodDest,
                    remoteClientId, message);
        }

    }
}
