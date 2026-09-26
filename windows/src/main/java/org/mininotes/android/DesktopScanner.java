package org.mininotes.android;

import com.github.sarxos.webcam.Webcam;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.swing.*;

/** Camera access happens only after Start camera, and ends when the dialog closes. */
final class DesktopScanner {
    record Code(String text,boolean camera) {}
    static void open(JFrame owner,Consumer<Code> accepted) {
        // The camera's picture in a dark frame of its own, what to do under it, and the other two ways in -
        // a picture of the code, or a link - as buttons beside the camera's own.
        JLabel preview=new JLabel("Show the other device's Mininotes code to your camera",SwingConstants.CENTER);
        preview.setPreferredSize(new Dimension(560,315));preview.setForeground(new Color(214,218,210));preview.setFont(DesktopUi.BODY);
        JPanel screen=new JPanel(new BorderLayout()){
            @Override protected void paintComponent(Graphics g0){Graphics2D g=(Graphics2D)g0.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setColor(new Color(32,36,33));g.fillRoundRect(0,0,getWidth(),getHeight(),14,14);g.dispose();}
        };screen.setOpaque(false);screen.setBorder(BorderFactory.createEmptyBorder(6,6,6,6));screen.add(preview);
        DesktopUi.Text progress=DesktopUi.quiet("Start the camera, open a picture of the code, or paste a link.");
        JComboBox<String> cameras=new JComboBox<>(new String[]{"Default camera"});cameras.setPreferredSize(new Dimension(220,cameras.getPreferredSize().height));cameras.getAccessibleContext().setAccessibleName("Camera");
        JButton camera=DesktopUi.primary("Start camera",()->{}),image=DesktopUi.button("Open a picture…",()->{}),paste=DesktopUi.button("Paste a link or picture",()->{});
        JPanel top=new JPanel(new BorderLayout(DesktopUi.S,0));top.setOpaque(false);top.add(DesktopUi.actions(cameras,camera),BorderLayout.WEST);top.add(DesktopUi.footer(image,paste),BorderLayout.EAST);
        JPanel panel=new JPanel(new BorderLayout(0,12));panel.setOpaque(false);panel.add(top,BorderLayout.NORTH);panel.add(screen);panel.add(progress,BorderLayout.SOUTH);
        JDialog[] box={null};
        JDialog dialog=DesktopUi.sheet(owner,"From another device",panel,null,true);box[0]=dialog;
        ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"mininotes-qr");t.setDaemon(true);return t;});
        AtomicBoolean closed=new AtomicBoolean(),scanning=new AtomicBoolean(),painting=new AtomicBoolean();
        java.util.List<Webcam> devices=new java.util.ArrayList<>();
        Consumer<Code> found=text->{if(closed.compareAndSet(false,true)){dialog.dispose();worker.shutdown();SwingUtilities.invokeLater(()->accepted.accept(text));}};
        dialog.addWindowListener(new WindowAdapter(){public void windowClosed(WindowEvent e){closed.set(true);scanning.set(false);worker.shutdown();}});
        camera.addActionListener(e->{
            if(scanning.get()){scanning.set(false);camera.setText("Stopping…");camera.setEnabled(false);return;}
            int choice=cameras.getSelectedIndex();scanning.set(true);camera.setText("Stop camera");progress.setText("Opening camera…");
            worker.submit(()->{
                Webcam opened=null;
                try {
                    if(devices.isEmpty())devices.addAll(Webcam.getWebcams(10,TimeUnit.SECONDS));
                    if(devices.isEmpty())throw new IllegalStateException("No webcam found. Connect one, or use Open QR image / Paste.");
                    SwingUtilities.invokeLater(()->{if(cameras.getItemCount()==1&&"Default camera".equals(cameras.getItemAt(0))){cameras.removeAllItems();for(Webcam device:devices)cameras.addItem(device.getName());}});
                    if(closed.get()||!scanning.get())return;
                    opened=devices.get(Math.max(0,Math.min(choice,devices.size()-1)));sharpest(opened);
                    Dimension size=opened.getViewSize();
                    SwingUtilities.invokeLater(()->progress.setText("Hold the whole QR code steady inside the camera view. Camera at "+size.width+"×"+size.height+"."));
                    while(!closed.get()&&scanning.get()) {
                        BufferedImage frame=opened.getImage();
                        if(frame!=null) {
                            if(painting.compareAndSet(false,true))SwingUtilities.invokeLater(()->{if(!closed.get()){preview.setText("");preview.setIcon(new ImageIcon(fitted(frame,preview.getWidth()>0?preview.getWidth():560,preview.getHeight()>0?preview.getHeight():315)));}painting.set(false);});
                            try{String text=DesktopQr.read(frame);SwingUtilities.invokeLater(()->found.accept(new Code(text,true)));break;}catch(java.io.IOException noCode){/* Next frame. */}
                        }
                        Thread.sleep(150);
                    }
                }catch(Exception|LinkageError failure){SwingUtilities.invokeLater(()->progress.setText("Camera unavailable. Check Windows camera permissions, or open a QR image."));}
                finally{try{if(opened!=null)opened.close();}finally{scanning.set(false);SwingUtilities.invokeLater(()->{camera.setEnabled(true);camera.setText("Start camera");preview.setIcon(null);preview.setText("Camera stopped");});}}
            });
        });
        image.addActionListener(e->{
            JFileChooser pick=new JFileChooser();if(pick.showOpenDialog(dialog)!=JFileChooser.APPROVE_OPTION)return;
            scanning.set(false);progress.setText("Reading QR image…");worker.submit(()->{try{String text=DesktopQr.read(pick.getSelectedFile().toPath());SwingUtilities.invokeLater(()->found.accept(new Code(text,false)));}catch(Exception failure){SwingUtilities.invokeLater(()->progress.setText(failure.getMessage()));}});
        });
        paste.addActionListener(e->{
            try {
                var board=Toolkit.getDefaultToolkit().getSystemClipboard();
                if(board.isDataFlavorAvailable(DataFlavor.imageFlavor)) {
                    Image raw=(Image)board.getData(DataFlavor.imageFlavor);int w=raw.getWidth(null),h=raw.getHeight(null);
                    if(w<=0||h<=0||(long)w*h>16_000_000)throw new IllegalArgumentException("Clipboard image is too large");
                    BufferedImage copy=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);var g=copy.createGraphics();g.drawImage(raw,0,0,null);g.dispose();
                    scanning.set(false);progress.setText("Reading clipboard image…");worker.submit(()->{try{String text=DesktopQr.read(copy);SwingUtilities.invokeLater(()->found.accept(new Code(text,false)));}catch(Exception failure){SwingUtilities.invokeLater(()->progress.setText(failure.getMessage()));}});
                }else {
                    String current=board.isDataFlavorAvailable(DataFlavor.stringFlavor)?(String)board.getData(DataFlavor.stringFlavor):"";
                    JTextArea input=new JTextArea(current,5,42);input.setLineWrap(true);input.setWrapStyleWord(true);
                    if(JOptionPane.showConfirmDialog(dialog,new JScrollPane(input),"Paste sharing link",JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION)found.accept(new Code(input.getText().trim(),false));
                }
            }catch(Exception failure){progress.setText("Could not read clipboard: "+failure.getMessage());}
        });
        DesktopUi.show(dialog,700,720);
    }

    /**
     * Opened at the most detail the camera gives. The driver's default is 320x240, where the dense code a
     * phone shows for pairing is a grey blur. Asking for more than the camera has gets its nearest size,
     * so the largest ask is made first and the list only falls back on a camera that refuses outright.
     */
    static void sharpest(Webcam camera) {
        Dimension[] wanted={new Dimension(1920,1080),new Dimension(1280,720),new Dimension(640,480)};
        camera.setCustomViewSizes(wanted);
        for(Dimension size:wanted) {
            try{camera.setViewSize(size);if(camera.open())return;}
            catch(RuntimeException refused){try{camera.close();}catch(RuntimeException ignored){/* next size */}}
        }
        Dimension[] listed=camera.getViewSizes();
        camera.setViewSize(listed[listed.length-1]);camera.open();
    }

    /** The whole frame inside the box, its shape kept, smoothly: a squashed preview is hard to aim with. */
    static Image fitted(BufferedImage frame,int width,int height) {
        double scale=Math.min((double)width/frame.getWidth(),(double)height/frame.getHeight());
        return frame.getScaledInstance(Math.max(1,(int)(frame.getWidth()*scale)),Math.max(1,(int)(frame.getHeight()*scale)),Image.SCALE_SMOOTH);
    }
}
