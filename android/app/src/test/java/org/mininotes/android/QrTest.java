package org.mininotes.android;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import java.util.Random;
import static org.junit.Assert.*;

/**
 * The scanning half, without a camera: a code is drawn into the kind of buffer a camera hands over —
 * one byte of brightness per pixel — and read back out of it.
 */
public class QrTest {
    private static final byte[] AGREE="agreement-key-bytes".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SIGN="signing-key-bytes".getBytes(StandardCharsets.UTF_8);

    /** A code as a camera would hand it over: brightness only, dark squares on a light ground. */
    private static byte[] framed(String line,int size) throws Exception {
        Map<EncodeHintType,Object> how=new EnumMap<>(EncodeHintType.class);
        how.put(EncodeHintType.ERROR_CORRECTION,ErrorCorrectionLevel.L);
        how.put(EncodeHintType.MARGIN,2);
        how.put(EncodeHintType.CHARACTER_SET,"UTF-8");
        BitMatrix matrix=new QRCodeWriter().encode(line,BarcodeFormat.QR_CODE,size,size,how);
        byte[] brightness=new byte[size*size];
        for(int y=0;y<size;y++)for(int x=0;x<size;x++)
            brightness[y*size+x]=(byte)(matrix.get(x,y)?0:(byte)255);
        return brightness;
    }

    @Test public void aPairingLineSurvivesBeingDrawnAndReadBack() throws Exception {
        String line=Pairing.write("My tablet","MxG18TTN0S8BJTCJ4YHR@89.32.7.4:9001",AGREE,SIGN);
        assertEquals(line,Qr.inside(framed(line,600),600,600));
    }

    @Test public void aWholeDeviceWorthOfKeysStillFits() throws Exception {
        // A real pairing line carries two P-256 keys: about 350 characters, which is what has to fit.
        byte[] key=new byte[91];new Random(7).nextBytes(key);
        String line=Pairing.write("Pixel 7","MxG18TTN0S8BJTCJ4YHR@89.32.7.4:9001",key,key);
        assertTrue(line.length()>250);
        assertEquals(line,Qr.inside(framed(line,700),700,700));
    }

    @Test public void aFrameWithNoCodeInItIsNotAnAnswer() {
        byte[] noise=new byte[400*400];new Random(11).nextBytes(noise);
        assertNull(Qr.inside(noise,400,400));
        assertNull(Qr.inside(new byte[400*400],400,400));
    }

    @Test public void aFrameThatIsNotAFrameIsRefusedRatherThanThrown() {
        assertNull(Qr.inside(new byte[10],400,400));
        assertNull(Qr.inside(null,400,400));
        assertNull(Qr.inside(new byte[100],0,0));
    }

    /**
     * A real Maxima contact address is a whole public key and then the host: about four hundred characters,
     * so the line around it is nearer eight hundred once the two device keys are on it. That is a much denser
     * code than a short address makes, and a code too dense to read would be a pairing screen that shows
     * something and pairs nothing — so the density a real address produces is what is checked.
     */
    @Test public void aRealMaximaAddressStillMakesAReadableCode() throws Exception {
        // The shape the node actually produces: 288 characters, ending in the relay it is reachable
        // through. Measured off a running node rather than guessed at.
        StringBuilder address=new StringBuilder("MxG18HGG6FJ038614Y8CW46US6G20810K0070CD00Z83282");
        Random dice=new Random(3);
        String alphabet="ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        while(address.length()<271)address.append(alphabet.charAt(dice.nextInt(alphabet.length())));
        address.append("@45.77.57.24:9501");
        assertEquals(288,address.length());
        byte[] key=new byte[91];dice.nextBytes(key);
        String line=Pairing.write("Pixel 7 Pro",address.toString(),key,key);
        assertTrue("a real line is far longer than a short address suggests",line.length()>500);
        // The size the app actually draws it at on a phone: dp(240) at a 2.625 density.
        assertEquals(line,Qr.inside(framed(line,630),630,630));
        assertEquals("and the address comes back whole",address.toString(),Pairing.read(line).address);
    }
}
