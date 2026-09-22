// SPDX-License-Identifier: LicenseRef-Mininotes-NoPaidProducts
// Apache-2.0 with the Commons Clause and a paid-product condition. See LICENSE.
package org.mininotes.android;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.ECFieldFp;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.EllipticCurve;
import java.security.spec.X509EncodedKeySpec;
import java.security.interfaces.ECPublicKey;

/**
 * A public key as the thirty-three bytes it really is.
 *
 * <p>Java hands a P-256 public key over as ninety-one bytes: the point itself, wrapped in a header saying
 * which curve it is on and that it is a key. That header is the same on every key this app will ever see,
 * and it was travelling twice in every pairing code — a hundred and eighty characters of a picture somebody
 * has to hold a camera steady against, saying nothing that both ends did not already know.
 *
 * <p>A point on this curve is an x and a y, and y is one of two values that x allows. So the whole key is x
 * and one bit saying which. The curve is fixed, so the other end can work y back out: y² = x³ − 3x + b, and
 * for this curve's prime — which is 3 more than a multiple of 4 — a square root is one modular power.
 *
 * <p>Holds no Android types: the round trip and the awkward cases are unit tested.
 */
final class Point {

    /** secp256r1 / P-256, as the standard gives it. */
    private static final BigInteger P=new BigInteger(
        "FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF",16);
    private static final BigInteger A=new BigInteger(
        "FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFC",16);
    private static final BigInteger B=new BigInteger(
        "5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B",16);
    private static final BigInteger GX=new BigInteger(
        "6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296",16);
    private static final BigInteger GY=new BigInteger(
        "4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5",16);
    private static final BigInteger N=new BigInteger(
        "FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551",16);

    /** How many bytes a compressed point is: one saying which half, and x. */
    static final int SHORT=33;

    private static final ECParameterSpec CURVE=new ECParameterSpec(
        new EllipticCurve(new ECFieldFp(P),A,B),new ECPoint(GX,GY),N,1);

    private Point(){}

    /** A key as the short form: 0x02 or 0x03, then x. */
    static byte[] shorten(PublicKey key) {
        if(!(key instanceof ECPublicKey))throw new IllegalArgumentException("Not a key on a curve");
        ECPoint w=((ECPublicKey)key).getW();
        byte[] out=new byte[SHORT];
        out[0]=(byte)(w.getAffineY().testBit(0)?0x03:0x02);
        byte[] x=fixed(w.getAffineX());
        System.arraycopy(x,0,out,1,32);
        return out;
    }

    /**
     * And back: x, and the one bit, become the point again.
     *
     * @throws GeneralSecurityException if those bytes are not a point on this curve — which is refused
     *         rather than worked around, because a point that is not on the curve is how a stranger gets
     *         an agreement to leak the private key it was made with
     */
    static PublicKey widen(byte[] shortened) throws GeneralSecurityException {
        if(shortened==null||shortened.length!=SHORT)
            throw new GeneralSecurityException("A short key is "+SHORT+" bytes");
        int first=shortened[0]&0xff;
        if(first!=0x02&&first!=0x03)throw new GeneralSecurityException("Not a short key");
        BigInteger x=new BigInteger(1,java.util.Arrays.copyOfRange(shortened,1,SHORT));
        if(x.signum()<0||x.compareTo(P)>=0)throw new GeneralSecurityException("x is off the field");
        // y² = x³ + ax + b, with a = −3 on this curve.
        BigInteger square=x.modPow(BigInteger.valueOf(3),P).add(A.multiply(x)).add(B).mod(P);
        // This prime is 3 more than a multiple of 4, so a square root is one power: s = v^((p+1)/4).
        BigInteger y=square.modPow(P.add(BigInteger.ONE).shiftRight(2),P);
        // That power gives a root of something; whether it is a root of this is not assumed.
        if(!y.multiply(y).mod(P).equals(square))throw new GeneralSecurityException("Not a point on the curve");
        if(y.testBit(0)!=(first==0x03))y=P.subtract(y);
        return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x,y),CURVE));
    }

    /**
     * Whatever form a key arrived in, as a key.
     *
     * <p>Codes made before this carry the long form and are still read: a pairing line somebody was sent
     * last week is not a thing to break, and telling the two apart is a matter of counting the bytes.
     */
    static PublicKey read(byte[] bytes) throws GeneralSecurityException {
        if(bytes!=null&&bytes.length==SHORT)return widen(bytes);
        return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(bytes));
    }

    /** x as exactly thirty-two bytes, however many BigInteger felt like giving. */
    private static byte[] fixed(BigInteger value) {
        byte[] raw=value.toByteArray();
        byte[] out=new byte[32];
        if(raw.length==32)return raw;
        if(raw.length==33&&raw[0]==0){System.arraycopy(raw,1,out,0,32);return out;}
        if(raw.length>32)throw new IllegalArgumentException("x does not fit a curve this size");
        System.arraycopy(raw,0,out,32-raw.length,raw.length);
        return out;
    }
}
