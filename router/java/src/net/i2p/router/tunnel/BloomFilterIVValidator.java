package net.i2p.router.tunnel;

import net.i2p.data.ByteArray;
import net.i2p.data.DataHelper;
import net.i2p.router.RouterContext;
import net.i2p.util.ByteCache;
import net.i2p.util.DecayingBloomFilter;
import net.i2p.util.DecayingHashSet;

/**
 * Manage the IV validation for all of the router's tunnels by way of a big
 * decaying bloom filter.  
 *
 */
public class BloomFilterIVValidator implements IVValidator {
    private RouterContext _context;
    private DecayingBloomFilter _filter;
    private ByteCache _ivXorCache = ByteCache.getInstance(32, HopProcessor.IV_LENGTH);
    
    /**
     * After 2*halflife, an entry is completely forgotten from the bloom filter.
     * To avoid the issue of overlap within different tunnels, this is set 
     * higher than it needs to be.
     *
     */
    private static final int HALFLIFE_MS = 10*60*1000;
    private static final int MIN_SHARE_KBPS_TO_USE_BLOOM = 64;
    private static final int MIN_SHARE_KBPS_FOR_BIG_BLOOM = 512;
    private static final int MIN_SHARE_KBPS_FOR_HUGE_BLOOM = 1536;

    public BloomFilterIVValidator(RouterContext ctx, int KBps) {
        _context = ctx;
        // Select the filter based on share bandwidth.
        // Note that at rates above 512KB, we increase the filter size
        // to keep acceptable false positive rates.
        // See DBF, BloomSHA1, and KeySelector for details.
        if (KBps < MIN_SHARE_KBPS_TO_USE_BLOOM)
            _filter = new DecayingHashSet(ctx, HALFLIFE_MS, 16, "TunnelIVV"); // appx. 4MB max
        else if (KBps >= MIN_SHARE_KBPS_FOR_HUGE_BLOOM)
            _filter = new DecayingBloomFilter(ctx, HALFLIFE_MS, 16, "TunnelIVV", 25);  // 8MB fixed
        else if (KBps >= MIN_SHARE_KBPS_FOR_BIG_BLOOM)
            _filter = new DecayingBloomFilter(ctx, HALFLIFE_MS, 16, "TunnelIVV", 24);  // 4MB fixed
        else
            _filter = new DecayingBloomFilter(ctx, HALFLIFE_MS, 16, "TunnelIVV");  // 2MB fixed
        ctx.statManager().createRateStat("tunnel.duplicateIV", "Note that a duplicate IV was received", "Tunnels", 
                                         new long[] { 10*60*1000l, 60*60*1000l, 3*60*60*1000l, 24*60*60*1000l });
    }
    
    public boolean receiveIV(byte ivData[], int ivOffset, byte payload[], int payloadOffset) {
        ByteArray buf = _ivXorCache.acquire();
        DataHelper.xor(ivData, ivOffset, payload, payloadOffset, buf.getData(), 0, HopProcessor.IV_LENGTH);
        boolean dup = _filter.add(buf.getData()); 
        _ivXorCache.release(buf);
        if (dup) _context.statManager().addRateData("tunnel.duplicateIV", 1, 1);
        return !dup; // return true if it is OK, false if it isn't
    }
    public void destroy() { _filter.stopDecaying(); }
}
