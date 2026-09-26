package org.mininotes.android;

import com.google.zxing.*;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import javax.imageio.ImageIO;

final class DesktopQr {
    static BufferedImage draw(String text,int size) throws Exception {
        var bits=new MultiFormatWriter().encode(text,BarcodeFormat.QR_CODE,size,size,Map.of(EncodeHintType.MARGIN,4));
        var image=new BufferedImage(bits.getWidth(),bits.getHeight(),BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++)image.setRGB(x,y,bits.get(x,y)?0xff17261b:0xffffffff);
        return image;
    }
    static String read(BufferedImage image) throws Exception {
        int width=image.getWidth(),height=image.getHeight();
        if((long)width*height>16_000_000)throw new IOException("Choose an image smaller than 16 megapixels");
        var source=new RGBLuminanceSource(width,height,image.getRGB(0,0,width,height,null,0,width));
        // Several ways of reading, each tried both ways round. One alone missed about one clean code in
        // seventy: a code straight off a screen is read best as a pure code, a photograph by the binarizers.
        var hints=Map.of(DecodeHintType.TRY_HARDER,true,DecodeHintType.POSSIBLE_FORMATS,java.util.List.of(BarcodeFormat.QR_CODE));
        var pure=Map.of(DecodeHintType.TRY_HARDER,true,DecodeHintType.PURE_BARCODE,true,DecodeHintType.POSSIBLE_FORMATS,java.util.List.of(BarcodeFormat.QR_CODE));
        for(int inverted=0;inverted<2;inverted++) {
            LuminanceSource way=inverted==0?source:source.invert();
            for(int attempt=0;attempt<3;attempt++)try {
                Binarizer bits=attempt==1?new GlobalHistogramBinarizer(way):new HybridBinarizer(way);
                return new MultiFormatReader().decode(new BinaryBitmap(bits),attempt==2?pure:hints).getText();
            }catch(ReaderException absent){/* the next way */}
        }
        throw new IOException("No QR code found. Use a clear, uncropped picture of the code.");
    }
    static String read(Path path) throws Exception {
        try(var input=ImageIO.createImageInputStream(path.toFile())) {
            if(input==null)throw new IOException("Could not read this image");
            var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw new IOException("Choose a PNG, JPEG or other supported image");
            var reader=readers.next();try {
                reader.setInput(input);if((long)reader.getWidth(0)*reader.getHeight(0)>16_000_000)throw new IOException("Choose an image smaller than 16 megapixels");
                return read(reader.read(0));
            }finally{reader.dispose();}
        }
    }
}
