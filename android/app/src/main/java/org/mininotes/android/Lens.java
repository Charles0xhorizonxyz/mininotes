// SPDX-License-Identifier: LicenseRef-Mininotes-NoPaidProducts
// Apache-2.0 with the Commons Clause and a paid-product condition. See LICENSE.
package org.mininotes.android;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * The camera, open only long enough to read one code. Nothing is recorded, nothing is written to disk, and
 * the frames never leave this class: each is looked at for a QR code and dropped. The camera is closed the
 * moment a code is found, or the moment the view goes away.
 *
 * <p>Camera2 rather than a support library, because this app carries none — and a preview plus one reader
 * surface is all a scanner needs.
 */
final class Lens implements AutoCloseable {
    static final int ASKING=4242;

    private final Activity where;
    private final TextureView shown;
    private final Consumer<String> found;
    private HandlerThread thread;
    private Handler hand;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private boolean done;

    Lens(Activity where,TextureView shown,Consumer<String> found) {
        this.where=where;this.shown=shown;this.found=found;
    }

    /** Whether this phone will let us look at all, asked for if not. */
    static boolean allowed(Activity where) {
        return where.checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED;
    }
    static void ask(Activity where){where.requestPermissions(new String[]{Manifest.permission.CAMERA},ASKING);}

    /** Opens the back camera and starts looking. Quietly does nothing if there is no camera to open. */
    void open() {
        if(!allowed(where))return;
        thread=new HandlerThread("lens");thread.start();hand=new Handler(thread.getLooper());
        CameraManager manager=(CameraManager)where.getSystemService(Context.CAMERA_SERVICE);
        if(manager==null)return;
        try {
            String id=backCamera(manager);
            if(id==null)return;
            Size size=readingSize(manager,id);
            Integer turned=manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensor=turned==null?90:turned;
            using=id;
            android.util.Log.i(TAG,"reading at "+size.getWidth()+"x"+size.getHeight()
                +", sensor "+sensor+", camera "+id);
            // Three, not two: acquireLatestImage drops what it skips past, and it needs one to hand over
            // while it still holds the newest. With two it can find nothing to give on a busy frame.
            reader=ImageReader.newInstance(size.getWidth(),size.getHeight(),ImageFormat.YUV_420_888,3);
            reader.setOnImageAvailableListener(this::look,hand);
            manager.openCamera(id,new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice opened) {camera=opened;preview(size);}
                @Override public void onDisconnected(CameraDevice opened){opened.close();camera=null;}
                @Override public void onError(CameraDevice opened,int error){opened.close();camera=null;}
            },hand);
        } catch(CameraAccessException|SecurityException|IllegalArgumentException e){close();}
    }

    /**
     * The picture, turned the right way up and filled to the box without being stretched.
     *
     * <p>A square window onto a rectangular picture has to lose something: what is lost is the long edges,
     * evenly, so the middle of what the lens sees is the middle of what is drawn. Aiming then means what it
     * looks like it means.
     */
    private void fit(Size picture) {
        if(shown==null||shown.getWidth()==0||shown.getHeight()==0)return;
        int turn=where.getWindowManager().getDefaultDisplay().getRotation();
        int screen=turn==android.view.Surface.ROTATION_90?90
                  :turn==android.view.Surface.ROTATION_180?180
                  :turn==android.view.Surface.ROTATION_270?270:0;
        // How far the sensor is from the way the screen is being held. This says which way round the
        // picture arrives, not which way it has to be turned: the phone has already stood it up for the
        // way it is normally held, and turning it by this much again lays it on its side.
        int degrees=((sensor-screen)+360)%360;
        float wide=shown.getWidth(), high=shown.getHeight();
        // After a quarter turn the picture's long edge runs the other way.
        float pictureWide=(degrees%180==0)?picture.getWidth():picture.getHeight();
        float pictureHigh=(degrees%180==0)?picture.getHeight():picture.getWidth();
        // The buffer is drawn stretched to the view, so the scale here undoes that and fills instead.
        float fill=Math.max(wide/pictureWide,high/pictureHigh);
        android.graphics.Matrix matrix=new android.graphics.Matrix();
        float cx=wide/2f, cy=high/2f;
        // Undo the stretch the view applied, then scale the true picture up until it covers the box.
        matrix.postScale((pictureWide*fill)/wide,(pictureHigh*fill)/high,cx,cy);
        // Only the screen being turned needs undoing. Held the usual way up that is nothing at all.
        if(screen!=0)matrix.postRotate(-screen,cx,cy);
        shown.setTransform(matrix);
    }

    private int sensor=90;

    /**
     * The frame rate range with the highest floor this camera offers, or null if it says nothing.
     *
     * <p>The floor is what matters: it is the rate the sensor is allowed to fall to when the light goes,
     * and falling is what makes a scanner stop working in the evening.
     */
    /** Which camera was opened, so its own frame-rate ranges can be asked for later. */
    private String using;

    private android.util.Range<Integer> quickest(String id) {
        try {
            android.util.Range<Integer>[] could=((CameraManager)where.getSystemService(Context.CAMERA_SERVICE))
                .getCameraCharacteristics(id)
                .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            if(could==null)return null;
            android.util.Range<Integer> best=null;
            for(android.util.Range<Integer> one:could) {
                if(one==null)continue;
                if(best==null||one.getLower()>best.getLower()
                    ||(one.getLower().equals(best.getLower())&&one.getUpper()>best.getUpper()))best=one;
            }
            if(best!=null)android.util.Log.i(TAG,"frame rate held at "+best);
            return best;
        } catch(Exception none){return null;}
    }

    private String backCamera(CameraManager manager) throws CameraAccessException {
        String any=null;
        for(String id:manager.getCameraIdList()) {
            Integer facing=manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if(any==null)any=id;
            if(facing!=null&&facing==CameraCharacteristics.LENS_FACING_BACK)return id;
        }
        return any;
    }

    /** Big enough to read a dense code from a hand's distance, small enough to look at every frame. */
    private Size readingSize(CameraManager manager,String id) throws CameraAccessException {
        StreamConfigurationMap map=manager.getCameraCharacteristics(id)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if(map==null)return new Size(1280,720);
        List<Size> could=new ArrayList<>(Arrays.asList(map.getOutputSizes(ImageFormat.YUV_420_888)));
        Size best=null;
        for(Size size:could) {
            if(size.getWidth()>1600||size.getHeight()>1200)continue;
            if(best==null||size.getWidth()*size.getHeight()>best.getWidth()*best.getHeight())best=size;
        }
        return best==null?new Size(1280,720):best;
    }

    private void preview(Size size) {
        try {
            SurfaceTexture texture=shown.getSurfaceTexture();
            List<Surface> surfaces=new ArrayList<>();
            Surface look=reader.getSurface();
            surfaces.add(look);
            Surface screen=null;
            if(texture!=null) {
                texture.setDefaultBufferSize(size.getWidth(),size.getHeight());
                // The camera hands over a landscape picture and the box on screen is a square, so without
                // this the picture is squashed sideways and turned a quarter over. It still decodes - the
                // reading is done on the frames, not on what is drawn - but you aim by what you can see,
                // and what could be seen was not the shape of the thing in front of the lens.
                shown.post(()->fit(size));
                screen=new Surface(texture);
                surfaces.add(screen);
            }
            final Surface showing=screen;
            camera.createCaptureSession(surfaces,new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession made) {
                    session=made;
                    try {
                        CaptureRequest.Builder ask=camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        ask.addTarget(look);
                        if(showing!=null)ask.addTarget(showing);
                        ask.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                        // Kept quick even in a dim room. Left alone, the exposure stretches until the
                        // frames themselves slow to a crawl - and a long exposure on something held in a
                        // hand is a smeared picture, so the few frames that do arrive are the ones least
                        // likely to be readable. A photograph would rather be clean than quick; a scanner
                        // is the other way round, and this is a scanner.
                        android.util.Range<Integer> quick=quickest(using);
                        if(quick!=null)ask.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,quick);
                        made.setRepeatingRequest(ask.build(),null,hand);
                        android.util.Log.i(TAG,"session is running");
                    } catch(CameraAccessException|IllegalStateException e){close();}
                }
                @Override public void onConfigureFailed(CameraCaptureSession made){close();}
            },hand);
        } catch(CameraAccessException|IllegalStateException e){close();}
    }

    /** One frame, looked at and dropped. The brightness plane is all a code needs. */
    /** What the reader has been given and what it made of it, so a scanner that sees nothing can say so. */
    private int frames, decoded;
    private long since, copying, reading;

    /** One row of the frame, borrowed each time rather than made again thirty times a second. */
    private byte[] line;

    /**
     * How wide the piece handed to the reader is, at most. Seven hundred across leaves a code filling half
     * the window about four pixels a square, where two is enough to read one.
     */
    private static final int ACROSS=720;

    /** Kept between frames: a scanner allocating two megabytes thirty times a second is a scanner that stops. */
    private byte[] patch;
    static final String TAG="Mininotes.Lens";

    private void look(ImageReader from) {
        Image frame=null;
        try {
            frame=from.acquireLatestImage();
            if(frame==null||done)return;
            // Every second or so at thirty frames. Enough to tell a camera that is handing nothing over
            // from one that is handing over pictures nothing can be read in.
            frames++;
            long began=android.os.SystemClock.uptimeMillis();
            if(since==0)since=began;
            Image.Plane plane=frame.getPlanes()[0];
            int wide=frame.getWidth(), high=frame.getHeight();
            // The middle of the frame, at every second or third pixel.
            //
            // It used to copy the whole of it - one and a nine-tenths of a million pixels, a fresh two
            // megabytes a frame - and hand all of that to the reader. On the phone that came to three
            // frames a second, and a code is only readable in the frames where the hand happened to be
            // still and the lens in focus: at three a second the scanner is mostly not looking, which is
            // exactly what it felt like. The square window on screen was only ever showing the middle
            // anyway, so the middle is what is read, and a code needs two pixels a square to be read at
            // all - so there is nothing to be had from handing over ten.
            int side=Math.min(wide,high);
            // Rounded up, or a frame a shade under twice the target subsamples by one - which is to say
            // not at all - and the whole of it is copied after all.
            int every=Math.max(1,(side+ACROSS-1)/ACROSS);
            int across=side/every;
            if(patch==null||patch.length!=across*across)patch=new byte[across*across];
            ByteBuffer buffer=plane.getBuffer();
            int stride=plane.getRowStride(), pixel=plane.getPixelStride(), have=buffer.limit();
            int left=(wide-side)/2, top=(high-side)/2;
            int need=side*pixel;
            if(line==null||line.length<need)line=new byte[need];
            // A row at a time, in one go. Asking a direct buffer for one byte at a time costs about three
            // quarters of a second a frame - measured, on the phone - which is the whole budget spent
            // fetching pixels most of which are then thrown away.
            for(int y=0;y<across;y++) {
                int at=(top+y*every)*stride+left*pixel;
                if(at<0||at+need>have)break;
                buffer.position(at);
                buffer.get(line,0,need);
                int out=y*across;
                for(int x=0;x<across;x++)patch[out+x]=line[x*every*pixel];
            }
            long copied=android.os.SystemClock.uptimeMillis();
            String said=Qr.inside(patch,across,across);
            long ended=android.os.SystemClock.uptimeMillis();
            copying+=copied-began; reading+=ended-copied;
            // One line, once, a few seconds in: enough to tell a scanner that is looking from one that
            // is not, without a log full of it. A scanner that has stopped looking is invisible otherwise
            // - the picture on screen goes on moving either way.
            if(frames==60)android.util.Log.i(TAG,frames+" frames in "+(ended-since)+"ms ("+across+"x"+across
                +"), copying "+copying/frames+"ms and reading "+reading/frames+"ms a frame");
            if(said==null)return;
            decoded++;
            android.util.Log.i(TAG,"read a code of "+said.length()+" characters at frame "+frames);
            done=true;
            where.runOnUiThread(()->found.accept(said));
        } catch(IllegalStateException e){/* the camera is closing */}
        finally{if(frame!=null)frame.close();}
    }

    @Override public void close() {
        done=true;
        try{if(session!=null)session.close();}catch(Exception ignored){}
        try{if(camera!=null)camera.close();}catch(Exception ignored){}
        try{if(reader!=null)reader.close();}catch(Exception ignored){}
        session=null;camera=null;reader=null;
        if(thread!=null){thread.quitSafely();thread=null;hand=null;}
    }
}
