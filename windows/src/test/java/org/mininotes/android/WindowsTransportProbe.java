package org.mininotes.android;

import com.eurobuddha.maxima.core.*;
import com.eurobuddha.maxima.core.crypto.Hashes;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.session.Bootstrap;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Opt-in live transport check. Two disposable identities; never reads a user's pad. */
public final class WindowsTransportProbe {
    public static void main(String[] args) throws Exception {
        Hashes.setSha3(Sha3::of);
        MaximaNode a=new MaximaNode(MaximaIdentity.create().identity,"mininotes-windows-probe",2);
        MaximaNode b=new MaximaNode(MaximaIdentity.create().identity,"mininotes-windows-probe",2);
        try {
            int ar=a.start(Bootstrap.RELAYS,30000),br=b.start(Bootstrap.RELAYS,30000);
            System.out.println("Attached relays: first="+ar+", second="+br);
            if(a.myAddresses().isEmpty()||b.myAddresses().isEmpty())throw new IllegalStateException("Two relay connections were not available");
            exchange(a,b,"Windows to peer");exchange(b,a,"Peer to Windows");
            System.out.println("PASS: sealed Mininotes parcel delivered and opened in both directions over live Maxima relays.");
        } finally {a.stop();b.stop();}
    }
    private static void exchange(MaximaNode from,MaximaNode to,String body) throws Exception {
        var signing=Envelope.keys();var receiving=Envelope.keys();CountDownLatch heard=new CountDownLatch(1);
        AtomicReference<Throwable> error=new AtomicReference<>();
        String app="mininotes.windows.probe";
        to.setMessageListener((message,id)->{
            if(message.mApplication==null||!app.equals(message.mApplication.toString()))return;
            try {
                var opened=Envelope.open(message.mData.getBytes(),receiving.getPrivate());
                var parcel=Parcel.open(opened.text);
                if(parcel==null||!body.equals(parcel.body))throw new AssertionError("Parcel mismatch");
                if(!java.util.Arrays.equals(opened.sender,Envelope.fingerprint(signing.getPublic())))throw new AssertionError("Sender mismatch");
            }catch(Throwable failure){error.set(failure);}finally{heard.countDown();}
        });
        byte[] clear=Parcel.wrap(new Parcel.Sent("test-collection","Test collection","test-book","Test book","Test note",body,true));
        byte[] sealed=Envelope.seal(new byte[16],1,System.currentTimeMillis(),clear,signing,receiving.getPublic());
        var result=from.sendRaw(to.myAddresses().get(0),app,sealed);
        if(result==null||!result.isOk())throw new IllegalStateException("Relay did not accept the probe");
        if(!heard.await(30,TimeUnit.SECONDS))throw new IllegalStateException("Relay accepted, but the recipient did not receive the probe");
        if(error.get()!=null)throw new AssertionError("Received parcel failed verification",error.get());
        System.out.println("Delivered and verified: "+body);
    }
}
