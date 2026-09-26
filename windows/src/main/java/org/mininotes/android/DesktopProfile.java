package org.mininotes.android;

import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.*;

final class DesktopProfile {
    record Connection(String name,String address,String permanent,int relays,String pairing) {}
    static void open(Desktop app) {
        JTextField name=new JTextField(24);name.setName("profileName");
        DesktopUi.Text outcome=DesktopUi.quiet(" "),counts=DesktopUi.quiet(" ");JLabel state=new Desktop.Dot(app.offline?"Offline session":"Connecting…");
        state.setFont(DesktopUi.BODY);state.setIconTextGap(8);
        JLabel qr=new JLabel("<html><div style='width:150px'>Your code appears here once this PC is connected.</div></html>");qr.setFont(DesktopUi.BODY.deriveFont(13f));qr.setForeground(DesktopUi.QUIET);qr.setName("profileQr");qr.setHorizontalAlignment(SwingConstants.CENTER);
        qr.setPreferredSize(new Dimension(220,220));qr.setMinimumSize(new Dimension(220,220));
        JTextArea address=readonly(3),permanent=readonly(2);address.setName("maximaAddress");permanent.setName("permanentAddress");state.setName("connectionState");
        Connection[] current={null};
        name.setEnabled(false);
        DesktopUi.Text nameHelp=DesktopUi.quiet("Other devices see this name. Changes save as you type.");
        Runnable saveName=()->{
            String value=name.getText().trim();if(value.isEmpty()||value.length()>80){nameHelp.setForeground(DesktopUi.WARN);nameHelp.setText("Use a name between 1 and 80 characters.");return;}
            nameHelp.setForeground(DesktopUi.QUIET);nameHelp.setText("Saving…");app.disk.submit(()->{
                app.context.getSharedPreferences("settings",0).edit().putString("me",value).apply();app.store.myName=value;return null;
            },done->{if(value.equals(name.getText().trim()))nameHelp.setText("Saved. Other devices see this name.");app.connectivity.submit(()->{Node.called(app.context,value);return null;},v->{},app::failed);},error->{nameHelp.setForeground(DesktopUi.WARN);nameHelp.setText("Name could not be saved. Edit it to try again.");app.failed(error);});
        };
        JPanel you=DesktopUi.column();DesktopUi.add(you,name);DesktopUi.gap(you,6);DesktopUi.add(you,nameHelp);

        // Your code: the picture another device scans, and the same thing as text for when it cannot.
        JButton link=app.button("Copy pairing link",()->{if(current[0]!=null&&!current[0].pairing.isEmpty()){clipboard(Pairing.link(current[0].pairing));outcome.setText("Pairing link copied");}});link.setEnabled(false);
        JButton copy=app.button("Copy address",()->{if(current[0]!=null&&!current[0].address.isEmpty()){clipboard(current[0].address);outcome.setText("Address copied");}});copy.setEnabled(false);
        JButton raw=app.button("Address QR…",()->{if(current[0]!=null&&!current[0].address.isEmpty())try{JLabel big=new JLabel(new ImageIcon(DesktopQr.draw(current[0].address,320)));DesktopUi.tell(SwingUtilities.getWindowAncestor(qr),"Maxima address",big);}catch(Exception e){app.failed(e);}});raw.setEnabled(false);
        JPanel side=DesktopUi.column();
        DesktopUi.add(side,DesktopUi.note("Scan this with Mininotes on another device to pair with this PC.",200,DesktopUi.QUIET,DesktopUi.BODY.deriveFont(13f)));DesktopUi.gap(side,12);
        for(JButton b:new JButton[]{link,copy,raw}){DesktopUi.add(side,b);DesktopUi.gap(side,DesktopUi.S);}
        JPanel code=new JPanel(new BorderLayout(DesktopUi.M,0));code.setOpaque(false);
        JPanel qrHolder=new JPanel(new BorderLayout());qrHolder.setBackground(Color.WHITE);qrHolder.setBorder(BorderFactory.createLineBorder(DesktopUi.LINE));qrHolder.add(qr);
        code.add(qrHolder,BorderLayout.WEST);code.add(side);
        JPanel codeCard=DesktopUi.column();DesktopUi.add(codeCard,code);DesktopUi.gap(codeCard,12);DesktopUi.add(codeCard,DesktopUi.quiet("Your Maxima address"));DesktopUi.gap(codeCard,4);DesktopUi.add(codeCard,address);

        // Connection: whether it is, and the one thing to do if it is not.
        JButton reconnect=app.button("Reconnect",()->{
            if(app.offline){outcome.setText("Restart without --offline to connect.");return;}
            outcome.setText("Finding relays and refreshing contacts…");app.connectivity.submit(()->{
                Node.retryStart();var node=Node.node(app.context);if(node==null)throw new IllegalStateException("Could not start the Maxima node");
                node.maintain(30000);return Node.attached();
            },n->{outcome.setText(n>0?"Connected":"No relay answered. Check your internet connection and Windows firewall.");app.startNode();},app::failed);
        });reconnect.setEnabled(!app.offline);
        JPanel stateRow=new JPanel(new BorderLayout(DesktopUi.M,0));stateRow.setOpaque(false);JPanel stateWords=DesktopUi.column();DesktopUi.add(stateWords,state);DesktopUi.gap(stateWords,2);DesktopUi.add(stateWords,counts);
        stateRow.add(stateWords);JPanel rc=new JPanel(new GridBagLayout());rc.setOpaque(false);rc.add(reconnect);stateRow.add(rc,BorderLayout.EAST);
        JCheckBox background=DesktopUi.toggle("Listen while the pad is closed",app.listenInTray);background.setEnabled(SystemTray.isSupported()&&!app.offline);
        background.addActionListener(e->app.setTrayListening(background.isSelected(),outcome));
        JPanel connection=DesktopUi.column();DesktopUi.add(connection,stateRow);DesktopUi.gap(connection,12);
        connection.add(DesktopUi.switchRow("Listen while the pad is closed",background));
        DesktopUi.add(connection,DesktopUi.note(SystemTray.isSupported()?"Closing the window keeps Mininotes in the system tray, so notes can arrive.":"The system tray is not available here. Keep the window open or minimised."));
        DesktopUi.gap(connection,12);DesktopUi.add(connection,DesktopUi.quiet("Permanent address"));DesktopUi.gap(connection,4);DesktopUi.add(connection,permanent);DesktopUi.gap(connection,DesktopUi.S);
        DesktopUi.add(connection,DesktopUi.actions(app.button("Copy permanent address",()->{if(current[0]!=null&&!current[0].permanent.isEmpty()){clipboard(current[0].permanent);outcome.setText("Permanent address copied");}})));

        JPanel people=DesktopUi.column();
        DesktopUi.add(people,DesktopUi.actions(app.button("Connect my other device…",()->app.showCode(Desktop.library())),app.button("From another device…",()->app.scanCode(null)),app.button("People…",()->app.people(null))));
        // The lock lives in Security, which the bar shows at all times; here, where it stands and the way there.
        JPanel lock=DesktopUi.column();
        boolean isLocked=DesktopLock.locked(app.context.getFilesDir().toPath());
        JLabel lockState=new JLabel(isLocked?"Encrypted with a password":"Not encrypted",DesktopLock.padlock(isLocked,16),SwingConstants.LEFT);lockState.setIconTextGap(8);lockState.setFont(DesktopUi.BODY);
        lock.add(DesktopUi.row(lockState,app.button("Security…",()->DesktopLock.settings(app))));
        JPanel backup=DesktopUi.column();
        DesktopUi.add(backup,DesktopUi.note("One file holding every collection, book, note and attachment on this PC."));DesktopUi.gap(backup,12);
        DesktopUi.add(backup,DesktopUi.actions(app.button("Export…",()->app.save(app::backup)),app.button("Add from a backup…",()->app.save(app::importBackup))));

        JPanel body=DesktopUi.column();
        for(JPanel card:new JPanel[]{DesktopUi.card("Your name",you),DesktopUi.card("Your code",codeCard),DesktopUi.card("Connection",connection),DesktopUi.card("People and devices",people),DesktopUi.card("Security",lock),DesktopUi.card("Backup",backup)}){DesktopUi.add(body,card);DesktopUi.gap(body,12);}
        JDialog[] box={null};
        JPanel foot=new JPanel(new BorderLayout(DesktopUi.M,0));foot.setOpaque(false);foot.add(outcome);
        JScrollPane scroll=DesktopUi.scrolling(body);scroll.setName("profileScroll");
        JDialog dialog=DesktopUi.sheet(app.frame,"Profile",scroll,foot,false);box[0]=dialog;
        AtomicBoolean busy=new AtomicBoolean();
        Runnable refresh=()->{
            app.disk.submit(()->new int[]{app.store.addresses().size(),app.store.owed(NoteStore.Branch.Kind.LIBRARY,Sharing.EVERYTHING).size()},values->counts.setText((values[0]==1?"1 paired device":values[0]+" paired devices")+"  ·  "+(values[1]==0?"nothing waiting to send":values[1]==1?"1 delivery waiting":values[1]+" deliveries waiting")),app::failed);
            if(app.offline||!busy.compareAndSet(false,true))return;
            app.connectivity.submit(()->{
                String called=Node.nameHere(app.context);var addresses=Node.addresses(app.context);String live=addresses.isEmpty()?"":addresses.get(0);
                app.store.myAddress=live;
                return new Connection(called,live,Node.permanent(app.context),addresses.size(),live.isEmpty()?"":app.keys.line(called,live,"",false,"",""));
            },value->{busy.set(false);if(!dialog.isDisplayable())return;render(value,qr,address,permanent,state);current[0]=value;boolean ready=!value.address.isEmpty();copy.setEnabled(ready);link.setEnabled(ready);raw.setEnabled(ready);},e->{busy.set(false);state.setText("Not connected — try Reconnect");});
        };
        app.disk.submit(()->Node.nameHere(app.context),value->{
            name.setText(value);name.setEnabled(true);outcome.setText(" ");
            name.getDocument().addDocumentListener(new javax.swing.event.DocumentListener(){
                public void insertUpdate(javax.swing.event.DocumentEvent e){saveName.run();}
                public void removeUpdate(javax.swing.event.DocumentEvent e){saveName.run();}
                public void changedUpdate(javax.swing.event.DocumentEvent e){saveName.run();}
            });
        },app::failed);
        javax.swing.Timer timer=new javax.swing.Timer(5000,e->refresh.run());
        dialog.addWindowListener(new java.awt.event.WindowAdapter(){public void windowClosed(java.awt.event.WindowEvent e){timer.stop();}});
        dialog.pack();dialog.setSize(600,Math.min(780,dialog.getHeight()));dialog.setLocationRelativeTo(app.frame);
        DesktopUi.shade(dialog);dialog.setVisible(true);refresh.run();timer.start();
    }    static void render(Connection value,JLabel qr,JTextArea address,JTextArea permanent,JLabel state) {
        if(!address.getText().equals(value.address))address.setText(value.address);
        String permanentText=value.permanent.isEmpty()?"Not published yet":value.permanent;
        if(!permanent.getText().equals(permanentText))permanent.setText(permanentText);
        state.setText(value.relays>0?"Connected · "+value.relays+(value.relays==1?" relay":" relays"):"Not connected — no relay has answered");
        if(!value.pairing.equals(qr.getClientProperty("pairing"))) {
            if(value.pairing.isEmpty()){qr.setIcon(null);qr.setText("<html><div style='width:150px'>Your code appears here once this PC is connected.</div></html>");}
            else try{qr.setText("");qr.setIcon(new ImageIcon(DesktopQr.draw(Pairing.link(value.pairing),216)));}catch(Exception e){qr.setText("Could not draw QR code");return;}
            qr.putClientProperty("pairing",value.pairing);
        }
    }
    static JPanel column(){JPanel panel=new JPanel();panel.setLayout(new BoxLayout(panel,BoxLayout.Y_AXIS));panel.setBackground(Desktop.PAPER);panel.setBorder(BorderFactory.createEmptyBorder(20,24,20,24));return panel;}
    static void space(JPanel panel){panel.add(Box.createVerticalStrut(16));}
    static JTextArea readonly(int rows){JTextArea area=new JTextArea(rows,35);area.setEditable(false);area.setLineWrap(true);area.setWrapStyleWord(false);area.setBackground(new Color(246,245,239));area.setForeground(Desktop.QUIET);area.setFont(new Font("Consolas",Font.PLAIN,12));area.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(DesktopUi.LINE),BorderFactory.createEmptyBorder(8,10,8,10)));((javax.swing.text.DefaultCaret)area.getCaret()).setUpdatePolicy(javax.swing.text.DefaultCaret.NEVER_UPDATE);return area;}
    static void finish(JDialog dialog,JPanel body,int width,int height){
        for(Component child:body.getComponents())if(child instanceof JComponent component)component.setAlignmentX(Component.LEFT_ALIGNMENT);
        JScrollPane scroll=new JScrollPane(body);scroll.setBorder(BorderFactory.createEmptyBorder());scroll.getVerticalScrollBar().setUnitIncrement(20);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);dialog.setContentPane(scroll);dialog.setSize(width,height);dialog.setLocationRelativeTo(dialog.getOwner());
    }
    static void clipboard(String text){Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text),null);}
}
