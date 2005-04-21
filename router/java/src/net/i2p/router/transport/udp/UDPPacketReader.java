package net.i2p.router.transport.udp;

import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.SessionKey;
import net.i2p.data.Signature;
import net.i2p.util.Log;

/**
 * To read a packet, initialize this reader with the data and fetch out
 * the appropriate fields.  If the interesting bits are in message specific
 * elements, grab the appropriate subreader.
 *
 */
public class UDPPacketReader {
    private I2PAppContext _context;
    private Log _log;
    private byte _message[];
    private int _payloadBeginOffset;
    private int _payloadLength;
    private SessionRequestReader _sessionRequestReader;
    private SessionCreatedReader _sessionCreatedReader;
    private SessionConfirmedReader _sessionConfirmedReader;
    private DataReader _dataReader;
    
    private static final int KEYING_MATERIAL_LENGTH = 64;
    
    public UDPPacketReader(I2PAppContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(UDPPacketReader.class);
        _sessionRequestReader = new SessionRequestReader();
        _sessionCreatedReader = new SessionCreatedReader();
        _sessionConfirmedReader = new SessionConfirmedReader();
        _dataReader = new DataReader();
    }
    
    public void initialize(UDPPacket packet) {
        int off = packet.getPacket().getOffset();
        int len = packet.getPacket().getLength();
        off += UDPPacket.MAC_SIZE + UDPPacket.IV_SIZE;
        len -= UDPPacket.MAC_SIZE + UDPPacket.IV_SIZE;
        initialize(packet.getPacket().getData(), off, len);
    }
    
    public void initialize(byte message[], int payloadOffset, int payloadLength) {
        _message = message;
        _payloadBeginOffset = payloadOffset;
        _payloadLength = payloadLength;
    }
    
    /** what type of payload is in here? */
    public int readPayloadType() {
        // 3 highest order bits == payload type
        return (_message[_payloadBeginOffset] & 0xFF) >>> 4;
    }
    
    /** does this packet include rekeying data? */
    public boolean readRekeying() {
        return (_message[_payloadBeginOffset] & (1 << 3)) != 0;
    }
    
    public boolean readExtendedOptionsIncluded() {
        return (_message[_payloadBeginOffset] & (1 << 2)) != 0;
    }
    
    public long readTimestamp() {
        return DataHelper.fromLong(_message, _payloadBeginOffset + 1, 4);
    }
    
    public void readKeyingMaterial(byte target[], int targetOffset) {
        if (!readRekeying())
            throw new IllegalStateException("This packet is not rekeying!");
        System.arraycopy(_message, _payloadBeginOffset + 1 + 4, target, targetOffset, KEYING_MATERIAL_LENGTH);
    }
    
    /** index into the message where the body begins */
    private int readBodyOffset() {
        int offset = _payloadBeginOffset + 1 + 4;
        if (readRekeying())
            offset += KEYING_MATERIAL_LENGTH;
        if (readExtendedOptionsIncluded()) {
            int optionsSize = (int)DataHelper.fromLong(_message, offset, 1);
            offset += optionsSize + 1;
        }
        return offset;
    }
    
    public SessionRequestReader getSessionRequestReader() { return _sessionRequestReader; }
    public SessionCreatedReader getSessionCreatedReader() { return _sessionCreatedReader; }
    public SessionConfirmedReader getSessionConfirmedReader() { return _sessionConfirmedReader; }
    public DataReader getDataReader() { return _dataReader; }
    
    public String toString() {
        switch (readPayloadType()) {
            case UDPPacket.PAYLOAD_TYPE_DATA:
                return _dataReader.toString();
            case UDPPacket.PAYLOAD_TYPE_SESSION_CONFIRMED:
                return "Session confirmed packet";
            case UDPPacket.PAYLOAD_TYPE_SESSION_CREATED:
                return "Session created packet";
            case UDPPacket.PAYLOAD_TYPE_SESSION_REQUEST:
                return "Session request packet";
            default:
                return "Other packet type...";
        }
    }
    
    public void toRawString(StringBuffer buf) {
        if (_message != null)
            buf.append(Base64.encode(_message, _payloadBeginOffset, _payloadLength));
    }
    
    /** Help read the SessionRequest payload */
    public class SessionRequestReader {
        public static final int X_LENGTH = 256;
        public void readX(byte target[], int targetOffset) {
            int readOffset = readBodyOffset();
            System.arraycopy(_message, readOffset, target, targetOffset, X_LENGTH);
        }
        
        public int readIPSize() {
            int offset = readBodyOffset() + X_LENGTH;
            return (int)DataHelper.fromLong(_message, offset, 1);
        }
        
        /** what IP bob is reachable on */
        public void readIP(byte target[], int targetOffset) {
            int offset = readBodyOffset() + X_LENGTH;
            int size = (int)DataHelper.fromLong(_message, offset, 1);
            offset++;
            System.arraycopy(_message, offset, target, targetOffset, size);
        }
    }
    
    /** Help read the SessionCreated payload */
    public class SessionCreatedReader {
        public static final int Y_LENGTH = 256;
        public void readY(byte target[], int targetOffset) {
            int readOffset = readBodyOffset();
            System.arraycopy(_message, readOffset, target, targetOffset, Y_LENGTH);
        }
        
        /** sizeof(IP) */
        public int readIPSize() {
            int offset = readBodyOffset() + Y_LENGTH;
            return (int)DataHelper.fromLong(_message, offset, 1);
        }
        
        /** what IP do they think we are coming on? */
        public void readIP(byte target[], int targetOffset) {
            int offset = readBodyOffset() + Y_LENGTH;
            int size = (int)DataHelper.fromLong(_message, offset, 1);
            offset++;
            System.arraycopy(_message, offset, target, targetOffset, size);
        }
        
        /** what port do they think we are coming from? */
        public int readPort() {
            int offset = readBodyOffset() + Y_LENGTH + 1 + readIPSize();
            return (int)DataHelper.fromLong(_message, offset, 2);
        }
        
        /** write out the 4 byte relayAs tag */
        public long readRelayTag() {
            int offset = readBodyOffset() + Y_LENGTH + 1 + readIPSize() + 2;
            return DataHelper.fromLong(_message, offset, 4);
        }
        
        public long readSignedOnTime() {
            int offset = readBodyOffset() + Y_LENGTH + 1 + readIPSize() + 2 + 4;
            long rv = DataHelper.fromLong(_message, offset, 4);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Signed on time offset: " + offset + " val: " + rv
                           + "\nRawCreated: " + Base64.encode(_message, _payloadBeginOffset, _payloadLength));
            return rv;
        }
        
        public void readEncryptedSignature(byte target[], int targetOffset) {
            int offset = readBodyOffset() + Y_LENGTH + 1 + readIPSize() + 2 + 4 + 4;
            System.arraycopy(_message, offset, target, targetOffset, Signature.SIGNATURE_BYTES + 8);
        }
        
        public void readIV(byte target[], int targetOffset) {
            int offset = _payloadBeginOffset - UDPPacket.IV_SIZE;
            System.arraycopy(_message, offset, target, targetOffset, UDPPacket.IV_SIZE);
        }
    }
    
    /** parse out the confirmed message */
    public class SessionConfirmedReader {
        /** which fragment is this? */
        public int readCurrentFragmentNum() {
            int readOffset = readBodyOffset();
            return (_message[readOffset] & 0xFF) >>> 4;
        }
        /** how many fragments will there be? */
        public int readTotalFragmentNum() {
            int readOffset = readBodyOffset();
            return (_message[readOffset] & 0xF);
        }
        
        public int readCurrentFragmentSize() {
            int readOffset = readBodyOffset() + 1;
            return (int)DataHelper.fromLong(_message, readOffset, 2);
        }

        /** read the fragment data from the nonterminal sessionConfirmed packet */
        public void readFragmentData(byte target[], int targetOffset) {
            int readOffset = readBodyOffset() + 1 + 2;
            int len = readCurrentFragmentSize();
            System.arraycopy(_message, readOffset, target, targetOffset, len);
        }
        
        /** read the time at which the signature was generated */
        public long readFinalFragmentSignedOnTime() {
            if (readCurrentFragmentNum() != readTotalFragmentNum()-1)
                throw new IllegalStateException("This is not the final fragment");
            int readOffset = readBodyOffset() + 1 + 2 + readCurrentFragmentSize();
            return DataHelper.fromLong(_message, readOffset, 4);
        }
        
        /** read the signature from the final sessionConfirmed packet */
        public void readFinalSignature(byte target[], int targetOffset) {
            if (readCurrentFragmentNum() != readTotalFragmentNum()-1)
                throw new IllegalStateException("This is not the final fragment");
            int readOffset = _payloadBeginOffset + _payloadLength - Signature.SIGNATURE_BYTES;
            System.arraycopy(_message, readOffset, target, targetOffset, Signature.SIGNATURE_BYTES);
        }
    }
    
    /** parse out the data message */
    public class DataReader {
        public boolean readACKsIncluded() {
            return flagSet(UDPPacket.DATA_FLAG_EXPLICIT_ACK);
        }
        public boolean readNACKsIncluded() {
            return flagSet(UDPPacket.DATA_FLAG_EXPLICIT_NACK);
        }
        public boolean readNumACKsIncluded() {
            return flagSet(UDPPacket.DATA_FLAG_NUMACKS);
        }
        public boolean readECN() {
            return flagSet(UDPPacket.DATA_FLAG_ECN);
        }
        public boolean readWantPreviousACKs() {
            return flagSet(UDPPacket.DATA_FLAG_WANT_ACKS);
        }
        public boolean readReplyRequested() { 
            return flagSet(UDPPacket.DATA_FLAG_WANT_REPLY);
        }
        public boolean readExtendedDataIncluded() {
            return flagSet(UDPPacket.DATA_FLAG_EXTENDED);
        }
        public long[] readACKs() {
            if (!readACKsIncluded()) return null;
            int off = readBodyOffset() + 1;
            int num = (int)DataHelper.fromLong(_message, off, 1);
            off++;
            long rv[] = new long[num];
            for (int i = 0; i < num; i++) {
                rv[i] = DataHelper.fromLong(_message, off, 4);
                off += 4;
            }
            return rv;
        }
        public long[] readNACKs() {
            if (!readNACKsIncluded()) return null;
            int off = readBodyOffset() + 1;
            if (readACKsIncluded()) {
                int numACKs = (int)DataHelper.fromLong(_message, off, 1);
                off++;
                off += 4 * numACKs;
            }
            
            int numNACKs = (int)DataHelper.fromLong(_message, off, 1);
            off++;
            long rv[] = new long[numNACKs];
            for (int i = 0; i < numNACKs; i++) {
                rv[i] = DataHelper.fromLong(_message, off, 4);
                off += 4;
            }
            return rv;
        }
        public int readNumACKs() {
            if (!readNumACKsIncluded()) return -1;
            int off = readBodyOffset() + 1;
            
            if (readACKsIncluded()) {
                int numACKs = (int)DataHelper.fromLong(_message, off, 1);
                off++;
                off += 4 * numACKs;
            }
            if (readNACKsIncluded()) {
                int numNACKs = (int)DataHelper.fromLong(_message, off, 1);
                off++;
                off += 4 * numNACKs;
            }
            return (int)DataHelper.fromLong(_message, off, 2);
        }
        
        public int readFragmentCount() {
            int off = readBodyOffset() + 1;
            if (readACKsIncluded()) {
                int numACKs = (int)DataHelper.fromLong(_message, off, 1);
                off++;
                off += 4 * numACKs;
            }
            if (readNACKsIncluded()) {
                int numNACKs = (int)DataHelper.fromLong(_message, off, 1);
                off++;
                off += 4 * numNACKs;
            }
            if (readNumACKsIncluded())
                off += 2;
            if (readExtendedDataIncluded()) {
                int size = (int)DataHelper.fromLong(_message, off, 1);
                off++;
                off += size;
            }
            return (int)_message[off];
        }
        
        public long readMessageId(int fragmentNum) {
            int fragmentBegin = getFragmentBegin(fragmentNum);
            return DataHelper.fromLong(_message, fragmentBegin, 4);
        }
        public int readMessageFragmentNum(int fragmentNum) {
            int off = getFragmentBegin(fragmentNum);
            off += 4; // messageId
            return (_message[off] & 0xFF) >>> 3;
        }
        public boolean readMessageIsLast(int fragmentNum) {
            int off = getFragmentBegin(fragmentNum);
            off += 4; // messageId
            return ((_message[off] & (1 << 2)) != 0);
        }
        public int readMessageFragmentSize(int fragmentNum) {
            int off = getFragmentBegin(fragmentNum);
            off += 4; // messageId
            off++; // fragment info
            return (int)DataHelper.fromLong(_message, off, 2);
        }
        public void readMessageFragment(int fragmentNum, byte target[], int targetOffset) {
            int off = getFragmentBegin(fragmentNum);
            off += 4; // messageId
            off++; // fragment info
            int size = (int)DataHelper.fromLong(_message, off, 2);
            off += 2;
            System.arraycopy(_message, off, target, targetOffset, size);
        }
        
        private int getFragmentBegin(int fragmentNum) {
            int off = readBodyOffset() + 1;
            if (readACKsIncluded()) {
                int numACKs = (int)DataHelper.fromLong(_message, off, 1);
                off++;
                off += 4 * numACKs;
            }
            if (readNACKsIncluded()) {
                int numNACKs = (int)DataHelper.fromLong(_message, off, 1);
                off++;
                off += 5 * numNACKs;
            }
            if (readNumACKsIncluded())
                off += 2;
            if (readExtendedDataIncluded()) {
                int size = (int)DataHelper.fromLong(_message, off, 1);
                off++;
                off += size;
            }
            off++; // # fragments
            
            if (fragmentNum == 0) {
                return off;
            } else {
                for (int i = 0; i < fragmentNum; i++) {
                    off += 5; // messageId+info
                    off += (int)DataHelper.fromLong(_message, off, 2);
                    off += 2;
                }
                return off;
            }
        }

        private boolean flagSet(byte flag) {
            int flagOffset = readBodyOffset();
            return ((_message[flagOffset] & flag) != 0);
        }
        
        public String toString() {
            StringBuffer buf = new StringBuffer(256);
            long msAgo = _context.clock().now() - readTimestamp()*1000;
            buf.append("Data packet sent ").append(msAgo).append("ms ago ");
            buf.append("IV ");
            buf.append(Base64.encode(_message, _payloadBeginOffset-UDPPacket.IV_SIZE, UDPPacket.IV_SIZE));
            buf.append(" ");
            int off = readBodyOffset() + 1;
            if (readACKsIncluded()) {
                int numACKs = (int)DataHelper.fromLong(_message, off, 1);
                off++;
                buf.append("with ACKs for ");
                for (int i = 0; i < numACKs; i++) {
                    buf.append(DataHelper.fromLong(_message, off, 4)).append(' ');
                    off += 4;
                }
            }
            if (readNACKsIncluded()) {
                int numNACKs = (int)DataHelper.fromLong(_message, off, 1);
                off++;
                buf.append("with NACKs for ");
                for (int i = 0; i < numNACKs; i++) {
                    buf.append(DataHelper.fromLong(_message, off, 4)).append(' ');
                    off += 5;
                }
                off += 5 * numNACKs;
            }
            if (readNumACKsIncluded()) {
                buf.append("with numACKs of ");
                buf.append(DataHelper.fromLong(_message, off, 2));
                buf.append(' ');
                off += 2;
            }
            if (readExtendedDataIncluded()) {
                int size = (int)DataHelper.fromLong(_message, off, 1);
                off++;
                buf.append("with extended size of ");
                buf.append(size);
                buf.append(' ');
                off += size;
            }
            
            int numFragments = (int)DataHelper.fromLong(_message, off, 1);
            off++;
            buf.append("with fragmentCount of ");
            buf.append(numFragments);
            buf.append(' ');
            
            for (int i = 0; i < numFragments; i++) {
                buf.append("containing messageId ");
                buf.append(DataHelper.fromLong(_message, off, 4));
                off += 4;
                int fragNum = (_message[off] & 0XFF) >>> 3;
                boolean isLast = (_message[off] & (1 << 2)) != 0;
                off++;
                buf.append(" frag# ").append(fragNum);
                buf.append(" isLast? ").append(isLast);
                buf.append(" info ").append((int)_message[off-1]);
                int size = (int)DataHelper.fromLong(_message, off, 2);
                buf.append(" with ").append(size).append(" bytes");
                buf.append(' ');
                off += size;
                off += 2;
            }
            
            return buf.toString();
        }
        
        public void toRawString(StringBuffer buf) { 
            UDPPacketReader.this.toRawString(buf); 
            buf.append(" payload: ");
                  
            int off = getFragmentBegin(0); // first fragment
            off += 4; // messageId
            off++; // fragment info
            int size = (int)DataHelper.fromLong(_message, off, 2);
            off += 2;
            buf.append(Base64.encode(_message, off, size));
        }
    }
}