package net.i2p.data;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.i2cp.ReceiveMessageEndMessage;

/**
 * Test harness for loading / storing Hash objects
 *
 * @author jrandom
 */
public class ReceiveMessageEndMessageTest extends StructureTest {
    public DataStructure createDataStructure() throws DataFormatException {
        ReceiveMessageEndMessage msg = new ReceiveMessageEndMessage();
        msg.setSessionId(321);
        msg.setMessageId(123);
        return msg; 
    }
    public DataStructure createStructureToRead() { return new ReceiveMessageEndMessage(); }
}
