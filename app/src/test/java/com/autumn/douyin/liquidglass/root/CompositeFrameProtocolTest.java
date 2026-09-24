package com.autumn.douyin.liquidglass.root;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class CompositeFrameProtocolTest {
    @Test
    public void readsOkHeaderWithTimestamp() throws IOException {
        CompositeFrameHeader header = CompositeFrameProtocol.INSTANCE.read(
            new DataInputStream(packet(CompositeFrameProtocol.StatusOk, 1234L, null))
        );

        assertEquals(1234L, header.getFrameTimestamp());
        assertTrue(header.getStatus() != CompositeFrameProtocol.StatusError);
    }

    @Test
    public void readsIdleHeaderWithTimestamp() throws IOException {
        CompositeFrameHeader header = CompositeFrameProtocol.INSTANCE.read(
            new DataInputStream(packet(CompositeFrameProtocol.StatusIdle, 5678L, null))
        );

        assertEquals(CompositeFrameProtocol.StatusIdle, header.getStatus());
        assertEquals(5678L, header.getFrameTimestamp());
    }

    @Test
    public void errorHeaderDoesNotConsumeMessageAsTimestamp() throws IOException {
        DataInputStream input = new DataInputStream(
            packet(CompositeFrameProtocol.StatusError, null, "capture failed")
        );
        CompositeFrameHeader header = CompositeFrameProtocol.INSTANCE.read(input);

        assertEquals(CompositeFrameProtocol.StatusError, header.getStatus());
        assertEquals("capture failed", CompositeFrameProtocol.INSTANCE.readError(input));
    }

    @Test
    public void rejectsInvalidStatus() throws IOException {
        try {
            CompositeFrameProtocol.INSTANCE.read(new DataInputStream(packet(99, null, null)));
            fail("expected invalid status to be rejected");
        } catch (IllegalArgumentException expected) {
        }
    }

    private ByteArrayInputStream packet(
        int status,
        Long timestamp,
        String errorMessage
    ) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeInt(CompositeFrameProtocol.Magic);
        output.writeInt(status);
        output.writeInt(10);
        output.writeInt(20);
        output.writeInt(1);
        output.writeInt(2);
        output.writeInt(11);
        output.writeInt(22);
        output.writeInt(800);
        if (timestamp != null) {
            output.writeLong(timestamp);
        }
        if (errorMessage != null) {
            output.writeUTF(errorMessage);
        }
        output.flush();
        return new ByteArrayInputStream(bytes.toByteArray());
    }
}
