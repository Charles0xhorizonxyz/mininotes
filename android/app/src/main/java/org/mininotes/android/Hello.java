// SPDX-License-Identifier: LicenseRef-Mininotes-NoPaidProducts
// Apache-2.0 with the Commons Clause and a paid-product condition. See LICENSE.
package org.mininotes.android;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Somebody saying yes.
 *
 * <p>A code is read in one direction. One phone shows it, the other photographs it, and only the one
 * holding the camera learns anything — so an offer accepted was an offer the offering phone never heard
 * about. It knew what it had put on its screen and had no idea anybody had taken it, which left the person
 * who accepted watching a shelf and the person who offered wondering what to press.
 *
 * <p>So the acceptance travels back: who I am, where to reach me, the keys to seal for me, and which thing
 * of yours I took you up on. The offering phone then has everything it needs to hand the thing over, and
 * nobody has to scan anything twice.
 *
 * <p>This is the plaintext of an envelope sealed for the offerer, so it is as private as a note is. It
 * proves nothing by itself: the envelope's signature says these bytes were not altered, and the keys inside
 * are checked against that signature, but whether to let this person have anything is a decision the
 * offering phone puts to its owner.
 *
 * <p>Holds no Android types: the wire format and its bounds are unit tested.
 */
final class Hello {

    /** The format, and the first four bytes. Not a {@link Parcel}, and never mistaken for one. */
    static final byte[] MAGIC={'M','N','H','1'};

    static final int NAME_MOST=80, ADDRESS_MOST=1024, KEY_MOST=4096, ID_MOST=100;

    /** One acceptance, as it arrived. */
    static final class Said {
        final String name,address; final byte[] agreement,signing;
        /** Which thing of the offerer's was accepted, in their own words for it. */
        final String scope,target;
        /** What the offer said they could do, carried back so the offerer need not remember. */
        final boolean writes;
        Said(String name,String address,byte[] agreement,byte[] signing,
             String scope,String target,boolean writes) {
            this.name=name;this.address=address;this.agreement=agreement;this.signing=signing;
            this.scope=scope;this.target=target;this.writes=writes;
        }
    }

    private Hello(){}

    static byte[] wrap(Said said) throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        DataOutputStream out=new DataOutputStream(bytes);
        out.write(MAGIC);
        out.writeBoolean(said.writes);
        put(out,said.name.getBytes(StandardCharsets.UTF_8),NAME_MOST);
        put(out,said.address.getBytes(StandardCharsets.UTF_8),ADDRESS_MOST);
        put(out,said.agreement,KEY_MOST);
        put(out,said.signing,KEY_MOST);
        put(out,said.scope.getBytes(StandardCharsets.UTF_8),ID_MOST);
        put(out,said.target.getBytes(StandardCharsets.UTF_8),ID_MOST);
        out.flush();
        return bytes.toByteArray();
    }

    /** What arrived, or null when this is not one of these. */
    static Said open(byte[] said) {
        if(said==null||said.length<MAGIC.length)return null;
        for(int at=0;at<MAGIC.length;at++)if(said[at]!=MAGIC[at])return null;
        try(DataInputStream in=new DataInputStream(new java.io.ByteArrayInputStream(said,MAGIC.length,
                said.length-MAGIC.length))) {
            boolean writes=in.readBoolean();
            String name=new String(get(in,NAME_MOST),StandardCharsets.UTF_8);
            String address=new String(get(in,ADDRESS_MOST),StandardCharsets.UTF_8);
            byte[] agreement=get(in,KEY_MOST), signing=get(in,KEY_MOST);
            String scope=new String(get(in,ID_MOST),StandardCharsets.UTF_8);
            String target=new String(get(in,ID_MOST),StandardCharsets.UTF_8);
            if(agreement.length==0||signing.length==0)return null;
            if(address.trim().isEmpty())return null;
            return new Said(name.trim().isEmpty()?"Their device":name.trim(),address.trim(),
                agreement,signing,scope.trim(),target.trim(),writes);
        } catch(IOException | IllegalArgumentException broken) {
            // Half of one of these is not one of these.
            return null;
        }
    }

    private static void put(DataOutputStream out,byte[] bytes,int most) throws IOException {
        if(bytes.length>most)throw new IOException("Too long to send: "+bytes.length+" of "+most);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static byte[] get(DataInputStream in,int most) throws IOException {
        int length=in.readInt();
        if(length<0||length>most)throw new IllegalArgumentException("A field said it was "+length+" long");
        byte[] bytes=new byte[length];
        in.readFully(bytes);
        return bytes;
    }
}
