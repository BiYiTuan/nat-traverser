package se.sics.gvod.stun.msgs;

import io.netty.buffer.ByteBuf;

import java.util.Set;

import se.sics.gvod.address.Address;
import se.sics.gvod.common.msgs.MessageDecodingException;
import se.sics.gvod.net.msgs.DirectMsg;
import se.sics.gvod.net.util.UserTypesDecoderFactory;

public class EchoMsgFactory  {

    public static class Request extends StunRequestMsgFactory {

        private Request() {
        }

        public static EchoMsg.Request fromBuffer(ByteBuf buffer)
                
                throws MessageDecodingException {
            return (EchoMsg.Request) new EchoMsgFactory.Request().decode(buffer);
        }

        @Override
        protected EchoMsg.Request process(ByteBuf buffer) throws MessageDecodingException {
         
            int typeId = UserTypesDecoderFactory.readUnsignedIntAsOneByte(buffer);
            EchoMsg.Test testType = EchoMsg.Test.create(typeId);
            int tryId = UserTypesDecoderFactory.readUnsignedIntAsOneByte(buffer);
            Address replyTo = UserTypesDecoderFactory.readAddress(buffer);
            return new EchoMsg.Request(vodSrc, vodDest, testType, transactionId, replyTo, tryId);
        }
    }

    public static class Response extends StunResponseMsgFactory {

        private Response() {
        }

        public static EchoMsg.Response fromBuffer(ByteBuf buffer)
                
                throws MessageDecodingException {
            return (EchoMsg.Response)
                    new EchoMsgFactory.Response().decode(buffer);
        }

        @Override
        protected EchoMsg.Response process(ByteBuf buffer) throws MessageDecodingException {
            int partnerPort  = UserTypesDecoderFactory.readUnsignedIntAsTwoBytes(buffer);
            int typeId = UserTypesDecoderFactory.readUnsignedIntAsOneByte(buffer);
            int tryId = UserTypesDecoderFactory.readUnsignedIntAsOneByte(buffer);
            EchoMsg.Test testType = EchoMsg.Test.create(typeId);
            Set<Address> partners = UserTypesDecoderFactory.readListAddresses(buffer);
            int bestPartnerRto = UserTypesDecoderFactory.readUnsignedIntAsTwoBytes(buffer);
            return new EchoMsg.Response(vodSrc, vodDest, retryPubAddr,
                    partners, bestPartnerRto, testType, transactionId, timeoutId, 
                    partnerPort, tryId);
        }


    }
};
