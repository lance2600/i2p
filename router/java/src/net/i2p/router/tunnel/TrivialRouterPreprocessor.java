package net.i2p.router.tunnel;

import net.i2p.router.RouterContext;

/** 
 * Minor extension to track fragmentation
 *
 */
public class TrivialRouterPreprocessor extends TrivialPreprocessor {
    private RouterContext _routerContext;
    
    public TrivialRouterPreprocessor(RouterContext ctx) {
        super(ctx);
        _routerContext = ctx;
    }

    protected void notePreprocessing(long messageId, int numFragments) {
        _routerContext.messageHistory().fragmentMessage(messageId, numFragments);
    }
}