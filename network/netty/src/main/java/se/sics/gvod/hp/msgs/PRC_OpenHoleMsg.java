package se.sics.gvod.hp.msgs;

import io.netty.buffer.ByteBuf;
import se.sics.gvod.common.msgs.MessageEncodingException;
import se.sics.gvod.net.BaseMsgFrameDecoder;
import se.sics.gvod.net.VodAddress;
import se.sics.gvod.net.msgs.RewriteableMsg;
import se.sics.gvod.net.msgs.RewriteableRetryTimeout;
import se.sics.gvod.net.msgs.ScheduleRetryTimeout;
import se.sics.gvod.net.util.UserTypesEncoderFactory;
import se.sics.gvod.timer.TimeoutId;

/**
 * 
 * @author salman
 */
public class PRC_OpenHoleMsg
{

    public static final class Request extends HpMsg.Request
    {
        static final long serialVersionUID = 1L;


        public Request(VodAddress src, VodAddress dest,
                int remoteClientId, TimeoutId msgTimeoutId)
        {
            super(src, dest, remoteClientId, msgTimeoutId);
        }

        public Request(Request msg, VodAddress src, int clientId)
        {
            super(src, msg.getVodDestination(), msg.getRemoteClientId(),
                    msg.getMsgTimeoutId());
        }

        @Override
        public int getSize() {
            return super.getHeaderSize()
                    ;
        }

        @Override
        public byte getOpcode() {
            return BaseMsgFrameDecoder.PRC_OPENHOLE_REQUEST;
        }

        @Override
        public ByteBuf toByteArray() throws MessageEncodingException {
        	ByteBuf buffer = createChannelBufferWithHeader();
            return buffer;
        }

        @Override
        public RewriteableMsg copy() {
            PRC_OpenHoleMsg.Request copy = new PRC_OpenHoleMsg.Request(vodSrc, 
                    vodDest, remoteClientId, msgTimeoutId);
            copy.setTimeoutId(timeoutId);
            return copy;
        }

    }

    public enum ResponseType
    {
        OK, FAILED
    };

    public final static class Response extends HpMsg.Response
    {
        static final long serialVersionUID = 1L;
        private final ResponseType responseType;

        public Response(VodAddress src, VodAddress dest,  TimeoutId timeoutId, 
                ResponseType responseType,
                int remoteClientId, TimeoutId msgTimeoutId)
        {
            super(src, dest, timeoutId, remoteClientId, msgTimeoutId);
            this.responseType = responseType;
        }

       
        public ResponseType getResponseType()
        {
            return responseType;
        }
        @Override
        public int getSize() {
            return super.getHeaderSize()
                    + 1
                    ;
        }

        @Override
        public byte getOpcode() {
            return BaseMsgFrameDecoder.PRC_OPENHOLE_RESPONSE;
        }

        @Override
        public ByteBuf toByteArray() throws MessageEncodingException {
        	ByteBuf buffer = createChannelBufferWithHeader();
            UserTypesEncoderFactory.writeUnsignedintAsOneByte(buffer, responseType.ordinal());
            return buffer;
        }

        @Override
        public RewriteableMsg copy() {
            return new PRC_OpenHoleMsg.Response(vodSrc, vodDest, timeoutId, 
                    responseType, remoteClientId, msgTimeoutId);
        }
    }

    public static final class RequestRetryTimeout extends RewriteableRetryTimeout
    {

        private final Request requestMsg;

        public RequestRetryTimeout(ScheduleRetryTimeout st, Request requestMsg)
        {
            super(st, requestMsg, requestMsg.getVodSource().getOverlayId());
            this.requestMsg = requestMsg;
        }

        public Request getRequestMsg()
        {
            return requestMsg;
        }

    }

}
