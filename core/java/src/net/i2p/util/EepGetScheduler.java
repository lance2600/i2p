package net.i2p.util;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import net.i2p.I2PAppContext;

/**
 *
 */
public class EepGetScheduler implements EepGet.StatusListener {
    private I2PAppContext _context;
    private List _urls;
    private List _localFiles;
    private String _proxyHost;
    private int _proxyPort;
    private int _curURL;
    private EepGet.StatusListener _listener;
    
    public EepGetScheduler(I2PAppContext ctx, List urls, List localFiles, String proxyHost, int proxyPort, EepGet.StatusListener lsnr) {
        _context = ctx;
        _urls = urls;
        _localFiles = localFiles;
        _proxyHost = proxyHost;
        _proxyPort = proxyPort;
        _curURL = -1;
        _listener = lsnr;
    }
    
    public void fetch() {
        I2PThread t = new I2PThread(new Runnable() { public void run() { fetchNext(); } }, "EepGetScheduler");
        t.setDaemon(true);
        t.start();
    }
    
    private void fetchNext() {
        _curURL++;
        if (_curURL >= _urls.size()) return;
        String url = (String)_urls.get(_curURL);
        String out = EepGet.suggestName(url);
        if ( (_localFiles != null) && (_localFiles.size() > _curURL) ) {
            File f = (File)_localFiles.get(_curURL);
            out = f.getAbsolutePath();
        } else {
            if (_localFiles == null)
                _localFiles = new ArrayList(_urls.size());
            _localFiles.add(new File(out));
        }
        EepGet get = new EepGet(_context, ((_proxyHost != null) && (_proxyPort > 0)), _proxyHost, _proxyPort, 0, out, url);
        get.addStatusListener(this);
        get.fetch();
    }
    
    public void attemptFailed(String url, long bytesTransferred, long bytesRemaining, int currentAttempt, int numRetries, Exception cause) {
        _listener.attemptFailed(url, bytesTransferred, bytesRemaining, currentAttempt, numRetries, cause);
    }
    
    public void bytesTransferred(long alreadyTransferred, int currentWrite, long bytesTransferred, long bytesRemaining, String url) {
        _listener.bytesTransferred(alreadyTransferred, currentWrite, bytesTransferred, bytesRemaining, url);
    }
    
    public void transferComplete(long alreadyTransferred, long bytesTransferred, long bytesRemaining, String url, String outputFile) {
        _listener.transferComplete(alreadyTransferred, bytesTransferred, bytesRemaining, url, outputFile);
        fetchNext();
    }
    
    public void transferFailed(String url, long bytesTransferred, long bytesRemaining, int currentAttempt) {
        _listener.transferFailed(url, bytesTransferred, bytesRemaining, currentAttempt);
        fetchNext();
    }
    
}