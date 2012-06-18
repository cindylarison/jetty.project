package org.eclipse.jetty.websocket.parser;

import java.nio.ByteBuffer;
import java.util.EventListener;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.frames.BaseFrame;
import org.eclipse.jetty.websocket.frames.BinaryFrame;
import org.eclipse.jetty.websocket.frames.ControlFrame;
import org.eclipse.jetty.websocket.frames.TextFrame;

/**
 * Parsing of a frame in WebSocket land.
 * 
 * <pre>
 *    0                   1                   2                   3
 *    0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 *   +-+-+-+-+-------+-+-------------+-------------------------------+
 *   |F|R|R|R| opcode|M| Payload len |    Extended payload length    |
 *   |I|S|S|S|  (4)  |A|     (7)     |             (16/64)           |
 *   |N|V|V|V|       |S|             |   (if payload len==126/127)   |
 *   | |1|2|3|       |K|             |                               |
 *   +-+-+-+-+-------+-+-------------+ - - - - - - - - - - - - - - - +
 *   |     Extended payload length continued, if payload len == 127  |
 *   + - - - - - - - - - - - - - - - +-------------------------------+
 *   |                               |Masking-key, if MASK set to 1  |
 *   +-------------------------------+-------------------------------+
 *   | Masking-key (continued)       |          Payload Data         |
 *   +-------------------------------- - - - - - - - - - - - - - - - +
 *   :                     Payload Data continued ...                :
 *   + - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - +
 *   |                     Payload Data continued ...                |
 *   +---------------------------------------------------------------+
 * </pre>
 */
public class Parser {
    public interface Listener extends EventListener
    {
        public static class Adapter implements Listener
        {
            @Override
            public void onBinaryFrame(final BinaryFrame frame)
            {
            }

            @Override
            public void onControlFrame(final ControlFrame frame)
            {
            }

            @Override
            public void onTextFrame(final TextFrame frame)
            {
            }

            @Override
            public void onWebSocketException(WebSocketException e)
            {
            }
        }

        public void onBinaryFrame(final BinaryFrame frame);
        public void onControlFrame(final ControlFrame frame);
        public void onTextFrame(final TextFrame frame);
        public void onWebSocketException(WebSocketException e);
    }

    private enum State
    {
        FINOP, PAYLOAD_LEN, PAYLOAD_LEN_BYTES, MASK, MASK_BYTES, PAYLOAD
    }

    private static final Logger LOG = Log.getLogger(Parser.class);
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private State state = State.FINOP;

    // Holder for the values represented in the baseframe being parsed.
    private BaseFrame baseframe = new BaseFrame();
    private long length = 0;
    private int cursor = 0;

    public Parser()
    {
        /*
         * TODO: Investigate addition of decompression factory similar to SPDY work in situation of negotiated deflate extension?
         */
        baseframe = new BaseFrame();
        state = State.FINOP;
    }

    public void addListener(Listener listener)
    {
        listeners.add(listener);
    }

    protected void notifyBinaryFrame(final BinaryFrame frame) {
        LOG.debug("Notify Binary Frame: {}",frame);
        for (Listener listener : listeners)
        {
            try
            {
                listener.onBinaryFrame(frame);
            }
            catch (Throwable t)
            {
                LOG.warn(t);
            }
        }
    }

    protected void notifyControlFrame(final ControlFrame f)
    {
        LOG.debug("Notify Control Frame: {}",f);
        for (Listener listener : listeners)
        {
            try
            {
                listener.onControlFrame(f);
            }
            catch (Throwable t)
            {
                LOG.warn(t);
            }
        }
    }

    protected void notifyTextFrame(final TextFrame frame) {
        LOG.debug("Notify Text Frame: {}",frame);
        for (Listener listener : listeners)
        {
            try
            {
                listener.onTextFrame(frame);
            }
            catch (Throwable t)
            {
                LOG.warn(t);
            }
        }
    }

    protected void notifyWebSocketException(WebSocketException e)
    {
        LOG.debug(e);
        for (Listener listener : listeners)
        {
            listener.onWebSocketException(e);
        }
    }

    public void parse(ByteBuffer buffer)
    {
        try {
            LOG.debug("Parsing {} bytes",buffer.remaining());
            while (buffer.hasRemaining())
            {
                switch (state)
                {
                    case FINOP:
                    {
                        // peek at byte
                        byte b = buffer.get();
                        byte flags = (byte)(0xF & (b >> 4));
                        baseframe.setFin((flags & BaseFrame.FLAG_FIN) == 1);
                        baseframe.setRsv1((flags & BaseFrame.FLAG_RSV1) == 1);
                        baseframe.setRsv2((flags & BaseFrame.FLAG_RSV2) == 1);
                        baseframe.setRsv3((flags & BaseFrame.FLAG_RSV3) == 1);
                        baseframe.setOpcode((byte)(b & 0xF));

                        if (baseframe.isControlFrame() && !baseframe.isLastFrame())
                        {
                            throw new WebSocketException("Fragmented Control Frame");
                        }
                        state = State.PAYLOAD_LEN;
                        break;
                    }
                    case PAYLOAD_LEN:
                    {
                        byte b = buffer.get();
                        baseframe.setMasked((b & 0x80) != 0);
                        length = (byte)(0x7F & b);

                        if (b == 127)
                        {
                            // length 4 bytes (extended payload length)
                            if (buffer.remaining() >= 4)
                            {
                                length = buffer.getInt();
                            }
                            else
                            {
                                length = 0;
                                state = State.PAYLOAD_LEN_BYTES;
                                cursor = 4;
                                break; // continue onto next state
                            }
                        }
                        else if (b == 126)
                        {
                            // length 2 bytes (extended payload length)
                            if (buffer.remaining() >= 2)
                            {
                                length = buffer.getShort();
                            }
                            else
                            {
                                length = 0;
                                state = State.PAYLOAD_LEN_BYTES;
                                cursor = 2;
                                break; // continue onto next state
                            }
                        }

                        baseframe.setPayloadLength(length);
                        if (baseframe.isMasked())
                        {
                            state = State.MASK;
                        }
                        else
                        {
                            state = State.PAYLOAD;
                        }

                        break;
                    }
                    case PAYLOAD_LEN_BYTES:
                    {
                        byte b = buffer.get();
                        --cursor;
                        length |= (b & 0xFF) << (8 * cursor);
                        if (cursor == 0)
                        {
                            baseframe.setPayloadLength(length);
                            if (baseframe.isMasked())
                            {
                                state = State.MASK;
                            }
                            else
                            {
                                state = State.PAYLOAD;
                            }
                        }
                        break;
                    }
                    case MASK:
                    {
                        byte m[] = new byte[4];
                        baseframe.setMask(m);
                        if (buffer.remaining() >= 4)
                        {
                            buffer.get(m,0,4);
                            state = State.PAYLOAD;
                        }
                        else
                        {
                            state = State.MASK_BYTES;
                            cursor = 4;
                        }
                        break;
                    }
                    case MASK_BYTES:
                    {
                        byte b = buffer.get();
                        --cursor;
                        baseframe.getMask()[cursor] = b;
                        if (cursor == 0)
                        {
                            state = State.PAYLOAD;
                        }
                        break;
                    }
                    case PAYLOAD:
                    {
                        // TODO: establish specific type parser and hand off to them.

                        break;
                    }
                }
            }
        }
        catch (WebSocketException e)
        {
            notifyWebSocketException(e);
        } catch(Throwable t) {
            notifyWebSocketException(new WebSocketException(t));
        }
        finally {
            // Be sure to consume after exceptions
            buffer.position(buffer.limit());
        }
    }

    public void removeListener(Listener listener)
    {
        listeners.remove(listener);
    }

    private void reset()
    {
        state = State.FINOP;
    }
}
