package jcifs.util.transport;

import java.io.*;
import java.net.*;
import java.util.*;
import jcifs.util.LogStream;

/**
 * This class simplifies communication for protocols that support
 * multiplexing requests. It encapsulates a stream and some protocol
 * knowledge (provided by a concrete subclass) so that connecting,
 * disconnecting, sending, and receiving can be syncronized
 * properly. Apparatus is provided to send and receive requests
 * concurrently.
 */

public abstract class Transport implements Runnable {

    static int id = 0;
    static LogStream log = LogStream.getInstance();
    public static boolean cleoDebugOn = false;

    public static int readn( InputStream in,
                byte[] b,
                int off,
                int len ) throws IOException {
        int i = 0, n = -5;

        while (i < len) {
            n = in.read( b, off + i, len - i );
            if (n <= 0) {
                break;
            }
            i += n;
        }

        return i;
    }

    /* state values
     * 0 - not connected
     * 1 - connecting
     * 2 - run connected
     * 3 - connected
     * 4 - error
     */
    int state = 0;

    protected String name = "Transport" + id++;
    Thread thread;
    TransportException te;

    protected HashMap response_map = new HashMap( 4 );

    protected abstract void makeKey( Request request ) throws IOException;
    protected abstract Request peekKey() throws IOException;
    protected abstract void doSend( Request request ) throws IOException;
    protected abstract void doRecv( Response response ) throws IOException;
    protected abstract void doSkip() throws IOException;

    public synchronized void sendrecv( Request request,
                    Response response,
                    long timeout ) throws IOException {
            long startTimeMs;
            long endTimeMs;
            makeKey( request );
            response.isReceived = false;
            try {
                response_map.put( request, response );
                doSend( request );
                startTimeMs = System.currentTimeMillis();
                response.expiration = startTimeMs + timeout;
                while (!response.isReceived) {
                    wait( timeout );
                    endTimeMs = System.currentTimeMillis();
                    timeout = response.expiration - endTimeMs;
                                  
                    if ((cleoDebugOn) && (timeout <= 0) && (response.isReceived)) {
                      System.err.println("-----------------------------------------------------------------------------------");
                      System.err.println(timeStr() + " Thread=" + Thread.currentThread().getName() + "  " + name + " would have timed out previously");
                      System.err.println("-----------------------------------------------------------------------------------");
                    }
                    if ((!response.isReceived) && (timeout <= 0)) {
                        if (cleoDebugOn) {
                          System.err.println("...............................................................");
                          System.err.println(timeStr() + " Thread=" + Thread.currentThread().getName() + "  " + name + " timedout after " + String.format("%.3f", (endTimeMs - startTimeMs) / 1000.0) +
                                             " sec waiting for response to " + request );
                          Thread.dumpStack();
                          System.err.println("...............................................................");
                        }
                        throw new TransportException( name +
                                " timedout after " + String.format("%.3f", (endTimeMs - startTimeMs) / 1000.0) + " sec waiting for response to " +
                                request );
                    }
                }
            } catch( IOException ioe ) {
                if (log.level > 2)
                    ioe.printStackTrace( log );
                try {
                    disconnect( true );
                } catch( IOException ioe2 ) {
                    ioe2.printStackTrace( log );
                }
                throw ioe;
            } catch( InterruptedException ie ) {
                throw new TransportException( ie );
            } finally {
                response_map.remove( request );
            }
    }
    private void loop() {
        while( thread == Thread.currentThread() ) {
            try {
                Request key = peekKey();
                if (key == null)
                    throw new IOException( "end of stream" );
                synchronized (this) {
                    Response response = (Response)response_map.get( key );
                    if (response == null) {
                        if (log.level >= 4)
                            log.println( "Invalid key, skipping message" );
//                        if (cleoDebugOn)
//                            System.err.println(timeStr() + " Invalid key=" + key + ", skipping message");
                        doSkip();
                    } else {
                        doRecv( response );
                        response.isReceived = true;
                        notifyAll();
                    }
                }
                if (disconnectRequested())
                  throw new IOException("disconnectRequested");
            } catch( Exception ex ) {
                String msg = ex.getMessage();
                if (cleoDebugOn)
                  System.err.println(timeStr() + " Transport.loop> msg=" + msg);
                boolean timeout = msg != null && msg.equals( "Read timed out" );
                boolean disconReq = msg != null && msg.equals( "disconnectRequested" );
                if ((cleoDebugOn) && (!timeout) && (!disconReq))
                  ex.printStackTrace();
                /* If just a timeout, try to disconnect gracefully
                 */
                boolean hard = timeout == false;

                if (disconReq)
                  hard = true;
                if ((hard) && (disconnectRequested()))
                  requestDisconnect(false);

                if (!timeout && log.level >= 3)
                    ex.printStackTrace( log );

                try {
                    disconnect( hard );
                } catch( IOException ioe ) {
                    ioe.printStackTrace( log );
                }
            }
        }
    }

    /* Build a connection. Only one thread will ever call this method at
     * any one time. If this method throws an exception or the connect timeout
     * expires an encapsulating TransportException will be thrown from connect
     * and the transport will be in error.
     */

    protected abstract void doConnect() throws Exception;

    /* Tear down a connection. If the hard parameter is true, the diconnection
     * procedure should not initiate or wait for any outstanding requests on
     * this transport.
     */

    protected abstract void doDisconnect( boolean hard ) throws IOException;

    public synchronized void connect( long timeout ) throws TransportException {
        try {
            switch (state) {
                case 0:
                    break;
                case 3:
                    return; // already connected
                case 4:
                    state = 0;
                    if (cleoDebugOn)
                      System.err.println(timeStr() + " Transport.connect>1 state set to " + state);
                    throw new TransportException( "Connection in error", te );
                default:
                    TransportException te = new TransportException( "Invalid state: " + state );
                    state = 0;
                    if (cleoDebugOn)
                      System.err.println(timeStr() + " Transport.connect>2 state set to " + state);
                    throw te;
            }

            if (cleoDebugOn)
              System.err.println(timeStr() + " Transport> creating thread: " + name + "  state=" + state);
            state = 1;
            if (cleoDebugOn)
              System.err.println(timeStr() + " Transport.connect>3 state set to " + state);
            te = null;
            thread = new Thread( this, name );
            thread.setDaemon( true );

            synchronized (thread) {
                thread.start();
                thread.wait( timeout );          /* wait for doConnect */

                switch (state) {
                    case 1: /* doConnect never returned */
                        state = 0;
                        if (cleoDebugOn)
                          System.err.println(timeStr() + " Transport.connect>4 state set to " + state);
                        thread = null;
                        throw new TransportException( "Connection timeout" );
                    case 2:
                        if (te != null) { /* doConnect throw Exception */
                            state = 4;                        /* error */
                            if (cleoDebugOn)
                              System.err.println(timeStr() + " Transport.connect>5 state set to " + state);
                            thread = null;
                            throw te;
                        }
                        state = 3;                         /* Success! */
                        if (cleoDebugOn)
                          System.err.println(timeStr() + " Transport.connect>6 state set to " + state);
                        return;
                }
            }
        } catch( InterruptedException ie ) {
            state = 0;
            if (cleoDebugOn)
              System.err.println(timeStr() + " Transport.connect>7 state set to " + state);
            thread = null;
            throw new TransportException( ie );
        } finally {
            /* This guarantees that we leave in a valid state
             */
            if (state != 0 && state != 3 && state != 4) {
                if (log.level >= 1)
                    log.println("Invalid state: " + state);
                state = 0;
                if (cleoDebugOn)
                  System.err.println(timeStr() + " Transport.connect>8 state set to " + state);
                thread = null;
            }
        }
    }
    public synchronized void disconnect( boolean hard ) throws IOException {
		IOException ioe = null;
        if (cleoDebugOn) {
          System.err.println(timeStr() + " Transport.disconnect> Entering with HARD=" + hard + "  state=" + state);
          Thread.dumpStack();
        }
        switch (state) {
            case 0: /* not connected - just return */
                return;
            case 2:
                hard = true;
            case 3: /* connected - go ahead and disconnect */
                if (response_map.size() != 0 && !hard) {
                    break; /* outstanding requests */
                }
                try {
                    doDisconnect( hard );
                } catch (IOException ioe0) {
                    ioe = ioe0;
                }
            case 4: /* in error - reset the transport */
                thread = null;
                state = 0;
                if (cleoDebugOn)
                  System.err.println(timeStr() + " Transport.disconnect>1 state set to " + state);
                break;
            default:
                if (log.level >= 1)
                    log.println("Invalid state: " + state);
                thread = null;
                state = 0;
                if (cleoDebugOn)
                  System.err.println(timeStr() + " Transport.disconnect>2 state set to " + state);
                break;
        }

        if (ioe != null)
            throw ioe;
    }
    public void run() {
        Thread run_thread = Thread.currentThread();
        Exception ex0 = null;

        try {
            /* We cannot synchronize (run_thread) here or the caller's
             * thread.wait( timeout ) cannot reaquire the lock and
             * return which would render the timeout effectively useless.
             */
            doConnect();
        } catch( Exception ex ) {
            ex0 = ex; // Defer to below where we're locked
            return;
        } finally {
            synchronized (run_thread) {
                if (run_thread != thread) {
                    /* Thread no longer the one setup for this transport --
                     * doConnect returned too late, just ignore.
                     */
                    if (ex0 != null) {
                        if (log.level >= 2)
                            ex0.printStackTrace(log);
                    }
                    return;
                }
                if (ex0 != null) {
                    te = new TransportException( ex0 );
                }
                state = 2; // run connected
                if (cleoDebugOn)
                  System.err.println(timeStr() + " Transport.run>1 state set to " + state);
                run_thread.notify();
            }
        }

        /* Proccess responses
         */
        loop();
    }

    public static String timeStr() {
      java.text.SimpleDateFormat formatter = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSS");
      return formatter.format(new java.util.Date());
    }

    private boolean pendingDisconnectRequest = false;
    private final Object disconnectSyncObj = new Object();
    public void requestDisconnect(boolean disconnectRequest) {
      synchronized(disconnectSyncObj) {
        this.pendingDisconnectRequest = disconnectRequest;
      }
    }
    private boolean disconnectRequested() {
      synchronized(disconnectSyncObj) {
        return this.pendingDisconnectRequest;
      }
    }

    public String toString() {
        return name;
    }
}
