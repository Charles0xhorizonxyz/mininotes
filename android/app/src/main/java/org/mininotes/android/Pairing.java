// SPDX-License-Identifier: GPL-3.0-or-later
// Mininotes is free software: GNU General Public License, version 3 or later. See LICENSE.
package org.mininotes.android;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * What one device hands another so the two can reach each other: where to send (the Maxima address), the key
 * to seal for (agreement), and the key to check against (signing). One line of text, because the two devices
 * may have nothing between them but a message you type into — and because a line can be read out, pasted,
 * or carried in a photograph of a screen.
 *
 * <p>A pairing line is not a secret and is not trust. Anybody who sees it can write to you; nobody who has it
 * can read what you send. Trust is the six digits both screens show afterwards, which come from the keys
 * themselves — see {@link Envelope#code}. A swapped key changes the digits, which is the whole point of them.
 *
 * <p>Holds no Android types, so the format and its refusals are unit tested without a device.
 */
final class Pairing {
    /**
     * The format, and the first characters of the line. A later format gets a later mark, never a silent
     * change — so a phone that was paired before still reads what it was paired with.
     *
     * <p>MN1 wrapped the whole line in a second layer of Base64 on top of the Base64 the keys were already
     * in, which cost a third of the line for nothing. With a real Maxima address on it — four hundred
     * characters — that pushed the QR code past what a camera can read at arm's length, which is a pairing
     * screen that shows something and pairs nothing. MN2 carries the same four fields with one layer,
     * separated by a character Base64 cannot contain and the other two fields are stripped of, so the whole
     * of it is still one line that survives being pasted through anything.
     */
    static final String MARK="MN2.";
    /** The first format. Still read, never written: a line somebody kept should keep working. */
    static final String MARK_1="MN1.";
    /** The most a whole line may be. A P-256 key is about ninety characters once encoded. */
    static final int MOST=4096;
    /** A Maxima contact address is a whole public key and then where to reach it: four hundred characters
     *  is ordinary, so the room for one is generous. Cutting one short would leave a line that looks right
     *  and reaches nobody. */
    static final int ADDRESS_MOST=1024;
    /** A name is for a person to recognise, not to carry anything, so it stays short. */
    static final int NAME_MOST=80;
    /** What separates the four fields. Base64 cannot hold it, and a name or an address is stripped of it. */
    private static final String BETWEEN="|";

    /**
     * One device, as the line says it - and, where the line was made to share something, what is being
     * offered and what the other side may do with it.
     */
    static final class Said {
        final String name,address; final byte[] agreement,signing;
        /** What is being offered, or empty where the line is only an introduction. */
        final String offer;
        /** True where the offer is to read and write. Reading is what an offer means unless it says so. */
        final boolean writes;
        /**
         * Which thing is being offered, in a form a machine can act on: the level, and its id on the
         * offering phone. {@code offer} is the sentence a person reads; this is what an acceptance quotes
         * back, so the offering end knows what to hand over without anybody describing it again.
         */
        final String scope,target;
        Said(String name,String address,byte[] agreement,byte[] signing) {
            this(name,address,agreement,signing,"",false,"","");
        }
        Said(String name,String address,byte[] agreement,byte[] signing,String offer,boolean writes) {
            this(name,address,agreement,signing,offer,writes,"","");
        }
        Said(String name,String address,byte[] agreement,byte[] signing,String offer,boolean writes,
             String scope,String target) {
            this.name=name;this.address=address;this.agreement=agreement;this.signing=signing;
            this.offer=offer==null?"":offer;this.writes=writes;
            this.scope=scope==null?"":scope;this.target=target==null?"":target;
        }
    }

    /**
     * The line this device hands out. Fields are separated by a character none of them can contain, and the
     * whole is encoded once so that a line survives being pasted through anything.
     */
    static String write(String name,String address,byte[] agreement,byte[] signing) {
        return write(name,address,agreement,signing,"",false);
    }

    /**
     * The same line, made to share one thing. What is offered and what may be done with it ride along, so
     * the phone that reads it can say whose it is, what it is, and what they are allowed - rather than
     * saying only that two devices now know each other and leaving the rest to be arranged by voice.
     */
    static String write(String name,String address,byte[] agreement,byte[] signing,String offer,boolean writes) {
        return write(name,address,agreement,signing,offer,writes,"","");
    }

    /** The same again, naming the thing so an acceptance can quote it back. */
    static String write(String name,String address,byte[] agreement,byte[] signing,String offer,boolean writes,
                        String scope,String target) {
        // A device may not know its own Maxima address yet — its node may not have been asked, or may not be
        // running. The keys are what pairing is for; where to send can be learnt afterwards and filled in.
        if(agreement==null||agreement.length==0||signing==null||signing.length==0)
            throw new IllegalArgumentException("A device with no keys cannot be written to");
        String line=MARK+clean(name,NAME_MOST)+BETWEEN+clean(address,ADDRESS_MOST)+BETWEEN
            +Base64.getEncoder().encodeToString(agreement)+BETWEEN+Base64.getEncoder().encodeToString(signing);
        // Only where there is something to offer: an introduction stays four fields, and a phone that has
        // only ever seen four goes on reading every line this one writes.
        if(offer!=null&&!offer.trim().isEmpty()) {
            line+=BETWEEN+clean(offer,NAME_MOST)+BETWEEN+(writes?"w":"r");
            // And which thing, where there is one to name. Two more fields, so a phone that has only ever
            // seen six goes on reading every line this one writes.
            if(scope!=null&&!scope.trim().isEmpty()&&target!=null&&!target.trim().isEmpty())
                line+=BETWEEN+clean(scope,NAME_MOST)+BETWEEN+clean(target,ADDRESS_MOST);
        }
        return line;
    }

    /**
     * What a line says, or a refusal. Everything a stranger sends is bounded and checked before anything is
     * built from it: a line that is not ours, or not whole, is not half-read.
     */
    static Said read(String line) {
        if(line==null)throw new IllegalArgumentException("Nothing was pasted");
        String one=line.trim();
        if(one.length()>MOST)throw new IllegalArgumentException("That is too long to be a pairing line");
        String said;
        if(one.startsWith(MARK))said=one.substring(MARK.length());
        else if(one.startsWith(MARK_1)) {
            try{said=new String(Base64.getUrlDecoder().decode(one.substring(MARK_1.length())),StandardCharsets.UTF_8);}
            catch(RuntimeException e){throw new IllegalArgumentException("That pairing line is damaged");}
        }
        else throw new IllegalArgumentException("That is not a Mininotes pairing line");
        String[] parts=said.split(one.startsWith(MARK)?"\\|":"\n",-1);
        if(parts.length!=4&&parts.length!=6&&parts.length!=8)
            throw new IllegalArgumentException("That pairing line is not whole");
        String name=parts[0].trim(),address=parts[1].trim();
        byte[] agreement,signing;
        try {
            agreement=Base64.getDecoder().decode(parts[2]);
            signing=Base64.getDecoder().decode(parts[3]);
        } catch(RuntimeException e){throw new IllegalArgumentException("That pairing line's keys are damaged");}
        if(agreement.length==0||signing.length==0)throw new IllegalArgumentException("That pairing line carries no keys");
        if(agreement.length>MOST||signing.length>MOST)throw new IllegalArgumentException("That pairing line's keys are not keys");
        // Anything but a plain "w" is read, which is the careful way round: a damaged or unknown mark means
        // the lesser of the two permissions, never the greater.
        String offer=parts.length>=6?parts[4].trim():"";
        boolean writes=parts.length>=6&&"w".equals(parts[5].trim());
        String scope=parts.length==8?parts[6].trim():"", target=parts.length==8?parts[7].trim():"";
        return new Said(name.isEmpty()?"Their device":name,address,agreement,signing,offer,writes,scope,target);
    }

    /**
     * How a code begins when it is drawn as something to point a camera at.
     *
     * <p>The line by itself is a line of text, and a phone's own camera shown a line of text offers to
     * search the web for it. Dressed as a link this app answers to, the same camera offers to open it here:
     * somebody handed a code does not have to know there is a scanner inside the app, or find it. The line
     * inside is unchanged - this is only what it wears on the way.
     */
    static final String LINK="mininotes://pair/";

    private static final char[] HEX="0123456789ABCDEF".toCharArray();

    /**
     * The line as that link. Everything a link cannot hold is written as a percent sign and two digits -
     * and so is everything something on the way might see fit to tidy: a plus, which half the world reads
     * as a space, and a slash, two of which together look like a mistake to be corrected. A key with one
     * character changed is not a slightly wrong key.
     */
    static String link(String line) {
        StringBuilder out=new StringBuilder(LINK);
        for(byte one:(line==null?"":line.trim()).getBytes(StandardCharsets.UTF_8)) {
            int c=one&0xff;
            boolean plain=(c>='A'&&c<='Z')||(c>='a'&&c<='z')||(c>='0'&&c<='9')
                ||c=='-'||c=='.'||c=='_'||c=='~'||c=='='||c==':'||c=='@';
            if(plain)out.append((char)c);
            else out.append('%').append(HEX[c>>4]).append(HEX[c&15]);
        }
        return out.toString();
    }

    /** Whether something scanned, pasted or opened is a code dressed as a link. */
    static boolean isLink(String said) {
        return said!=null&&said.trim().regionMatches(true,0,LINK,0,LINK.length());
    }

    /**
     * The line, out of whatever it arrived as: itself, or the link it was dressed as. Anything that is
     * neither comes back as it was, for {@link #read} to refuse in its own words.
     */
    static String line(String said) {
        if(said==null)return "";
        String one=said.trim();
        if(!isLink(one))return one;
        String dressed=one.substring(LINK.length());
        if(dressed.length()>MOST*3)return one;
        java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream(dressed.length());
        for(int at=0;at<dressed.length();at++) {
            char c=dressed.charAt(at);
            if(c!='%'){out.write(c<128?c:'?');continue;}
            if(at+2>=dressed.length())return one;
            int high=Character.digit(dressed.charAt(at+1),16),low=Character.digit(dressed.charAt(at+2),16);
            if(high<0||low<0)return one;
            out.write((high<<4)|low);at+=2;
        }
        return new String(out.toByteArray(),StandardCharsets.UTF_8).trim();
    }

    /**
     * Whether an address can actually be reached. There are two kinds and both count.
     *
     * <p>A <b>contact address</b> is a public key and then the host to reach that key through:
     * {@code Mx<key>@45.77.57.24:9501}. Without the part after the @ it is a key and nothing else — it
     * will look right on a screen and arrive nowhere, which is the worst way for this to fail, so it is
     * named as not an address rather than kept.
     *
     * <p>A <b>permanent address</b> is {@code MAX#<key>#<where to ask>}. It is not routable itself: the
     * sender resolves it through that lookup to get a live contact address. It is the better thing to hand
     * somebody, because it survives the host moving, and a note shared today is meant to still arrive next
     * month. Refusing one because it has no @ in it would refuse the more durable of the two.
     */
    static boolean reachable(String address) {
        if(address==null)return false;
        String one=address.trim();
        if(one.length()>ADDRESS_MOST)return false;
        if(one.startsWith("MAX#")) {
            String[] parts=one.split("#",-1);
            return parts.length==3&&!parts[1].trim().isEmpty()&&!parts[2].trim().isEmpty();
        }
        if(!one.startsWith("Mx"))return false;
        int at=one.lastIndexOf('@');
        if(at<3||at==one.length()-1)return false;
        String host=one.substring(at+1);
        return host.indexOf('@')<0&&!host.startsWith(":");
    }

    /** A field on one line, and short enough to read: what is pasted is never trusted to be either. */
    private static String clean(String said,int most) {
        if(said==null)return "";
        String one=said.replace('\n',' ').replace('\r',' ').replace('|',' ').trim();
        return one.length()>most?one.substring(0,most).trim():one;
    }

    private Pairing(){}
}
