package net.i2p.router.tunnel;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.I2NPMessageException;
import net.i2p.data.i2np.I2NPMessageHandler;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer;

/**
 * Handle fragments at the endpoint of a tunnel, peeling off fully completed 
 * I2NPMessages when they arrive, and dropping fragments if they take too long
 * to arrive.
 *
 */
public class FragmentHandler {
    private I2PAppContext _context;
    private Log _log;
    private Map _fragmentedMessages;
    private DefragmentedReceiver _receiver;
    
    /** don't wait more than 20s to defragment the partial message */
    private static final long MAX_DEFRAGMENT_TIME = 20*1000; 
    
    public FragmentHandler(I2PAppContext context, DefragmentedReceiver receiver) {
        _context = context;
        _log = context.logManager().getLog(FragmentHandler.class);
        _fragmentedMessages = new HashMap(4);
        _receiver = receiver;
    }
    
    /**
     * Receive the raw preprocessed message at the endpoint, parsing out each
     * of the fragments, using those to fill various FragmentedMessages, and 
     * sending the resulting I2NPMessages where necessary.  The received 
     * fragments are all verified.
     *
     */
    public void receiveTunnelMessage(byte preprocessed[], int offset, int length) {
        boolean ok = verifyPreprocessed(preprocessed, offset, length);
        if (!ok) {
            _log.error("Unable to verify preprocessed data");
            return;
        }
        offset += HopProcessor.IV_LENGTH; // skip the IV
        offset += 4; // skip the hash segment
        int padding = 0;
        while (preprocessed[offset] != (byte)0x00) {
            offset++; // skip the padding
            padding++;
        }
        offset++; // skip the final 0x00, terminating the padding
        if (_log.shouldLog(Log.DEBUG)) {
            _log.debug("Fragments begin at offset=" + offset + " padding=" + padding);
            _log.debug("fragments: " + Base64.encode(preprocessed, offset, preprocessed.length-offset));
        }
        try {
            while (offset < length)
                offset = receiveFragment(preprocessed, offset, length);
        } catch (Exception e) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Corrupt fragment received: offset = " + offset, e);
        }
    }
    
    /**
     * Verify that the preprocessed data hasn't been modified by checking the 
     * H(payload+IV)[0:3] vs preprocessed[16:19], where payload is the data 
     * after the padding.  Remember, the preprocessed data is formatted as
     * { IV + H[0:3] + padding + {instructions, fragment}* }.  This function is
     * very wasteful of memory usage as it doesn't operate inline (since IV and
     * payload are mixed up).  Later it may be worthwhile to explore optimizing
     * this.
     */
    private boolean verifyPreprocessed(byte preprocessed[], int offset, int length) {
        // now we need to verify that the message was received correctly
        int paddingEnd = HopProcessor.IV_LENGTH + 4;
        while (preprocessed[offset+paddingEnd] != (byte)0x00)
            paddingEnd++;
        paddingEnd++; // skip the last
        
        byte preV[] = new byte[length - offset - paddingEnd + HopProcessor.IV_LENGTH];
        System.arraycopy(preprocessed, offset + paddingEnd, preV, 0, preV.length - HopProcessor.IV_LENGTH);
        System.arraycopy(preprocessed, 0, preV, preV.length - HopProcessor.IV_LENGTH, HopProcessor.IV_LENGTH);
        Hash v = _context.sha().calculateHash(preV);
        boolean eq = DataHelper.eq(v.getData(), 0, preprocessed, offset + HopProcessor.IV_LENGTH, 4);
        if (!eq) 
            _log.error("Endpoint data doesn't match:\n" + Base64.encode(preprocessed, offset + paddingEnd, preV.length-HopProcessor.IV_LENGTH));
        return eq;
    }
    
    /** is this a follw up byte? */
    static final byte MASK_IS_SUBSEQUENT = (byte)(1 << 7);
    /** how should this be delivered?  shift this 5 the right and get TYPE_* */
    static final byte MASK_TYPE = (byte)(3 << 5);
    /** is this the first of a fragmented message? */
    static final byte MASK_FRAGMENTED = (byte)(1 << 3);
    /** are there follow up headers? */
    static final byte MASK_EXTENDED = (byte)(1 << 2);
    /** for subsequent fragments, which bits contain the fragment #? */
    private static final int MASK_FRAGMENT_NUM = (byte)((1 << 7) - 2); // 0x7E;
    
    static final short TYPE_LOCAL = 0;
    static final short TYPE_TUNNEL = 1;
    static final short TYPE_ROUTER = 2;
    
    /** 
     * @return the offset for the next byte after the received fragment 
     */
    private int receiveFragment(byte preprocessed[], int offset, int length) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("CONTROL: " + Integer.toHexString(preprocessed[offset]) + " / " 
                       + "/" + Base64.encode(preprocessed, offset, 1) + " at offset " + offset);
        if (0 == (preprocessed[offset] & MASK_IS_SUBSEQUENT))
            return receiveInitialFragment(preprocessed, offset, length);
        else
            return receiveSubsequentFragment(preprocessed, offset, length);
    }
    
    /**
     * Handle the initial fragment in a message (or a full message, if it fits)
     *
     * @return offset after reading the full fragment
     */
    private int receiveInitialFragment(byte preprocessed[], int offset, int length) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("initial begins at " + offset + " for " + length);
        int type = (preprocessed[offset] & MASK_TYPE) >>> 5;
        boolean fragmented = (0 != (preprocessed[offset] & MASK_FRAGMENTED));
        boolean extended = (0 != (preprocessed[offset] & MASK_EXTENDED));
        offset++;
        
        TunnelId tunnelId = null;
        Hash router = null;
        long messageId = -1;
        
        if (type == TYPE_TUNNEL) {
            long id = DataHelper.fromLong(preprocessed, offset, 4);
            tunnelId = new TunnelId(id);
            offset += 4;
        }
        if ( (type == TYPE_ROUTER) || (type == TYPE_TUNNEL) ) {
            byte h[] = new byte[Hash.HASH_LENGTH];
            System.arraycopy(preprocessed, offset, h, 0, Hash.HASH_LENGTH);
            router = new Hash(h);
            offset += Hash.HASH_LENGTH;
        }
        if (fragmented) {
            messageId = DataHelper.fromLong(preprocessed, offset, 4);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("reading messageId " + messageId + " at offset "+ offset 
                           + " type = " + type + "tunnelId = " + tunnelId);
            offset += 4;
        }
        if (extended) {
            int extendedSize = (int)DataHelper.fromLong(preprocessed, offset, 1);
            offset++;
            offset += extendedSize; // we don't interpret these yet, but skip them for now
        }
        
        int size = (int)DataHelper.fromLong(preprocessed, offset, 2);
        offset += 2;
        
        boolean isNew = false;
        FragmentedMessage msg = null;
        if (fragmented) {
            synchronized (_fragmentedMessages) {
                msg = (FragmentedMessage)_fragmentedMessages.get(new Long(messageId));
                if (msg == null) {
                    msg = new FragmentedMessage(_context);
                    _fragmentedMessages.put(new Long(messageId), msg);
                    isNew = true;
                }
            }
        } else {
            msg = new FragmentedMessage(_context);
        }
        
        if (isNew && fragmented) {
            RemoveFailed evt = new RemoveFailed(msg);
            msg.setExpireEvent(evt);
            _log.debug("In " + MAX_DEFRAGMENT_TIME + " dropping " + messageId);
            SimpleTimer.getInstance().addEvent(evt, MAX_DEFRAGMENT_TIME);
        }
        
        msg.receive(messageId, preprocessed, offset, size, !fragmented, router, tunnelId);
        if (msg.isComplete()) {
            if (fragmented) {
                synchronized (_fragmentedMessages) {
                    _fragmentedMessages.remove(new Long(messageId));
                }
            }
            if (msg.getExpireEvent() != null)
                SimpleTimer.getInstance().removeEvent(msg.getExpireEvent());
            receiveComplete(msg);
        }
        
        offset += size;
        return offset;
    }
    
    /**
     * Handle a fragment beyond the initial fragment in a message
     *
     * @return offset after reading the full fragment
     */
    private int receiveSubsequentFragment(byte preprocessed[], int offset, int length) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("subsequent begins at " + offset + " for " + length);
        int fragmentNum = ((preprocessed[offset] & MASK_FRAGMENT_NUM) >>> 1);
        boolean isLast = (0 != (preprocessed[offset] & 1));
        offset++;
        
        long messageId = DataHelper.fromLong(preprocessed, offset, 4);
        offset += 4;
        
        int size = (int)DataHelper.fromLong(preprocessed, offset, 2);
        offset += 2;
        
        boolean isNew = false;
        FragmentedMessage msg = null;
        synchronized (_fragmentedMessages) {
            msg = (FragmentedMessage)_fragmentedMessages.get(new Long(messageId));
            if (msg == null) {
                msg = new FragmentedMessage(_context);
                _fragmentedMessages.put(new Long(messageId), msg);
                isNew = true;
            }
        }
        
        if (isNew) {
            RemoveFailed evt = new RemoveFailed(msg);
            msg.setExpireEvent(evt);
            _log.debug("In " + MAX_DEFRAGMENT_TIME + " dropping " + msg.getMessageId() + "/" + fragmentNum);
            SimpleTimer.getInstance().addEvent(evt, MAX_DEFRAGMENT_TIME);
        }
        
        msg.receive(messageId, fragmentNum, preprocessed, offset, size, isLast);
        
        if (msg.isComplete()) {
            synchronized (_fragmentedMessages) {
                _fragmentedMessages.remove(new Long(messageId));
            }
            if (msg.getExpireEvent() != null)
                SimpleTimer.getInstance().removeEvent(msg.getExpireEvent());
            receiveComplete(msg);
        }
        
        offset += size;
        return offset;
    }
    
    private void receiveComplete(FragmentedMessage msg) {
        try {
            byte data[] = msg.toByteArray();
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("RECV(" + data.length + "): " + Base64.encode(data)  
                           + " " + _context.sha().calculateHash(data).toBase64());
            I2NPMessage m = new I2NPMessageHandler(_context).readMessage(data);
            _receiver.receiveComplete(m, msg.getTargetRouter(), msg.getTargetTunnel());
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error receiving fragmented message (corrupt?): " + msg, ioe);
        } catch (I2NPMessageException ime) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error receiving fragmented message (corrupt?): " + msg, ime);
        }
    }

    /**
     * Receive messages out of the tunnel endpoint.  There should be a single 
     * instance of this object per tunnel so that it can tell what tunnel various
     * messages come in on (e.g. to prevent DataMessages arriving from anywhere 
     * other than the client's inbound tunnels)
     * 
     */
    public interface DefragmentedReceiver {
        /**
         * Receive a fully formed I2NPMessage out of the tunnel
         *
         * @param msg message received 
         * @param toRouter where we are told to send the message (null means locally)
         * @param toTunnel where we are told to send the message (null means locally or to the specified router)
         */
        public void receiveComplete(I2NPMessage msg, Hash toRouter, TunnelId toTunnel);
    }
    
    private class RemoveFailed implements SimpleTimer.TimedEvent {
        private FragmentedMessage _msg;
        public RemoveFailed(FragmentedMessage msg) {
            _msg = msg;
        }
        public void timeReached() {
            boolean removed = false;
            synchronized (_fragmentedMessages) {
                removed = (null != _fragmentedMessages.remove(new Long(_msg.getMessageId())));
            }
            if (removed) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Dropped failed fragmented message: " + _msg);
            } else {
                // succeeded before timeout
            }
        }
        
    }
}