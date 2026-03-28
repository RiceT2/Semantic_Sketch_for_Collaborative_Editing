package com.semantic.sketch.protocol;

import com.semantic.sketch.crdt.Message;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Custom protocol codec using Netty ByteBuf and composite buffers for low-copy transport.
 */
public class SemanticProtocolCodec {
    private static final byte VERSION = 1;

    public ByteBuf encode(Message message, ByteBufAllocator allocator) {
        byte[] opId = message.getOpId().getBytes(StandardCharsets.UTF_8);
        byte[] actorId = message.getActorId().getBytes(StandardCharsets.UTF_8);
        byte[] payload = message.getPayload().getBytes(StandardCharsets.UTF_8);

        ByteBuf header = allocator.buffer(1 + 8 + 4 * 4);
        header.writeByte(VERSION);
        header.writeLong(message.getSemanticFingerprint());
        header.writeInt(opId.length);
        header.writeInt(actorId.length);
        header.writeInt(payload.length);
        header.writeInt(message.getVectorClock().size());

        ByteBuf body = allocator.buffer(opId.length + actorId.length + payload.length);
        body.writeBytes(opId);
        body.writeBytes(actorId);
        body.writeBytes(payload);

        ByteBuf vectorClockBuf = allocator.buffer(Math.max(16, message.getVectorClock().size() * 24));
        for (Map.Entry<String, Long> e : message.getVectorClock().entrySet()) {
            byte[] actor = e.getKey().getBytes(StandardCharsets.UTF_8);
            vectorClockBuf.writeInt(actor.length);
            vectorClockBuf.writeBytes(actor);
            vectorClockBuf.writeLong(e.getValue());
        }

        CompositeByteBuf composite = allocator.compositeBuffer(3);
        composite.addComponents(true, header, body, vectorClockBuf);
        return composite;
    }

    public Message decode(ByteBuf byteBuf) {
        byte version = byteBuf.readByte();
        if (version != VERSION) {
            throw new IllegalArgumentException("Unsupported protocol version: " + version);
        }

        long fingerprint = byteBuf.readLong();
        int opLen = byteBuf.readInt();
        int actorLen = byteBuf.readInt();
        int payloadLen = byteBuf.readInt();
        int vcSize = byteBuf.readInt();

        String opId = byteBuf.readCharSequence(opLen, StandardCharsets.UTF_8).toString();
        String actor = byteBuf.readCharSequence(actorLen, StandardCharsets.UTF_8).toString();
        String payload = byteBuf.readCharSequence(payloadLen, StandardCharsets.UTF_8).toString();

        Map<String, Long> vectorClock = new HashMap<>();
        for (int i = 0; i < vcSize; i++) {
            int actorBytes = byteBuf.readInt();
            String vcActor = byteBuf.readCharSequence(actorBytes, StandardCharsets.UTF_8).toString();
            long counter = byteBuf.readLong();
            vectorClock.put(vcActor, counter);
        }
        return new Message(opId, actor, payload, vectorClock, fingerprint);
    }
}
