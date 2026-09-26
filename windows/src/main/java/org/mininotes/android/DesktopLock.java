package org.mininotes.android;

import java.awt.*;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.List;
import javax.swing.*;
import org.mininotes.desktop.platform.AtomicFile;

/**
 * The password lock on this PC's notebook: switching it on, opening it, a new password, switching it off.
 *
 * <p>What is on disk is the notebook, encrypted, and {@code vault.key}: the notebook's key sealed once with
 * the password and once with the twelve recovery words (see {@link Vault}). Nothing leaves this PC.
 * While a lock is being put on, the sealed key waits as {@code vault.key.new}; it becomes the lock only
 * once the notebook has been encrypted, so a lock can never be left on a notebook it does not open.
 */
final class DesktopLock {
    static final String KEPT="vault.key",PENDING="vault.key.new";
    /** Said wherever the lock is offered, in these words. */
    static final String WARNING="If you lose both the backup password and the 12 recovery words, nobody can open this notebook. Not you, and not the people who make Mininotes. There is no email reset and no copy anywhere else.";
    static final int SHORTEST=8;
    static final String[] AFTER_NAMES={"Never","After 1 minute","After 5 minutes","After 15 minutes","After 1 hour"};
    static final int[] AFTER_MINUTES={0,1,5,15,60};
    /** Minutes without use before a locked notebook locks again; 0 is never. Five until chosen. */
    static int minutes(org.mininotes.desktop.platform.content.Context c) {
        try{return Integer.parseInt(c.getSharedPreferences("settings",0).getString("autoLock","5"));}catch(NumberFormatException e){return 5;}
    }
    private static int afterIndex(int m){for(int i=0;i<AFTER_MINUTES.length;i++)if(AFTER_MINUTES[i]==m)return i;return 2;}

    private DesktopLock(){}

    static boolean locked(Path folder){return Files.isRegularFile(folder.resolve(KEPT))||Files.isRegularFile(folder.resolve(PENDING));}
    private static Path lockFile(Path folder){return Files.isRegularFile(folder.resolve(KEPT))?folder.resolve(KEPT):folder.resolve(PENDING);}

    // ---- at start --------------------------------------------------------------------------------------

    /**
     * The key, asked for before anything of the notebook is opened; null if they closed the window.
     * A half-finished lock (the notebook never got encrypted) is taken off here, and the notebook opens as it was.
     */
    static byte[] askAtStart(Path folder) throws Exception {
        if(!Files.isRegularFile(folder.resolve(KEPT))&&Files.isRegularFile(folder.resolve(PENDING))&&plain(folder)) {
            Files.delete(folder.resolve(PENDING));return new byte[0];
        }
        byte[] kept=Files.readAllBytes(lockFile(folder));
        byte[][] key={null};JDialog[] box={null};
        JPasswordField password=new JPasswordField(24);password.setName("unlockPassword");
        DesktopUi.Text wrong=DesktopUi.quiet(" ");wrong.setForeground(DesktopUi.WARN);
        JPanel body=DesktopUi.column();
        JLabel brand=new JLabel(new ImageIcon(DesktopIcon.image(48)));DesktopUi.add(body,brand);DesktopUi.gap(body,DesktopUi.M);
        boolean helloSet=DesktopHello.has(folder);
        DesktopUi.add(body,DesktopUi.body(helloSet?"Use Windows Hello, or type your backup password.":"Type your password to open your notes."));DesktopUi.gap(body,DesktopUi.S);
        if(helloSet)password.putClientProperty(com.formdev.flatlaf.FlatClientProperties.PLACEHOLDER_TEXT,"Backup password");
        DesktopUi.add(body,password);DesktopUi.gap(body,6);DesktopUi.add(body,wrong);
        Runnable tryIt=()->{
            // Opening is quiet: only a wrong password is said in the warning colour.
            wrong.setForeground(DesktopUi.QUIET);wrong.setText("Opening…");wrong.paintImmediately(wrong.getVisibleRect());
            try{key[0]=Vault.open(kept,password.getPassword());box[0].dispose();}
            catch(Vault.Refused no){wrong.setForeground(DesktopUi.WARN);wrong.setText("That password did not open it.");password.selectAll();password.requestFocusInWindow();}
        };
        JButton open=DesktopUi.primary("Unlock",tryIt);password.addActionListener(e->tryIt.run());
        JButton forgot=DesktopUi.button("Use recovery words…",()->{
            byte[] recovered=recover(box[0],folder,kept);
            if(recovered!=null){key[0]=recovered;box[0].dispose();}
        });
        JPanel foot=new JPanel(new BorderLayout());foot.setOpaque(false);foot.add(DesktopUi.actions(forgot),BorderLayout.WEST);foot.add(DesktopUi.footer(open),BorderLayout.EAST);
        if(DesktopHello.has(folder)) {
            // Windows Hello, asked straight away; the password stays there for when Hello is not answered.
            JButton hello=new JButton("Use Windows Hello");hello.setFocusPainted(false);hello.setName("unlockHello");
            Runnable askHello=()->{
                hello.setEnabled(false);wrong.setForeground(DesktopUi.QUIET);wrong.setText("Waiting for Windows Hello…");
                new SwingWorker<byte[],Void>(){
                    protected byte[] doInBackground() throws Exception{return DesktopHello.open(folder);}
                    protected void done(){
                        hello.setEnabled(true);
                        try{key[0]=get();box[0].dispose();}
                        catch(Exception e){Throwable why=e.getCause()!=null?e.getCause():e;wrong.setForeground(DesktopUi.QUIET);wrong.setText(why.getMessage()+" You can type your password.");password.requestFocusInWindow();}
                    }
                }.execute();
            };
            hello.addActionListener(e->askHello.run());
            DesktopUi.gap(body,DesktopUi.S);DesktopUi.add(body,DesktopUi.actions(hello));
            SwingUtilities.invokeLater(askHello);
        }
        box[0]=DesktopUi.sheet(null,"Mininotes is locked",body,foot,true);
        box[0].setIconImages(List.of(DesktopIcon.image(16),DesktopIcon.image(32)));
        box[0].getRootPane().setDefaultButton(open);box[0].getRootPane().putClientProperty("focus",password);
        DesktopUi.show(box[0],420,460);
        return key[0];
    }

    /** Whether the notebook opens without a key: a lock that never finished being put on. */
    private static boolean plain(Path folder) {
        try(var c=java.sql.DriverManager.getConnection("jdbc:sqlite:"+folder.resolve("mininotes.db").toAbsolutePath());var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM sqlite_master")){return r.next();}
        catch(Exception encrypted){return false;}
    }

    /** The twelve words, then a new password: the key, sealed again, and the lock file replaced. */
    private static byte[] recover(Window owner,Path folder,byte[] kept) {
        JDialog[] box={null};byte[][] key={null};
        JTextArea words=new JTextArea(3,34);words.setLineWrap(true);words.setWrapStyleWord(true);words.setName("recoveryWords");
        words.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(DesktopUi.LINE),BorderFactory.createEmptyBorder(8,10,8,10)));
        JPasswordField first=new JPasswordField(24),again=new JPasswordField(24);
        DesktopUi.Text wrong=DesktopUi.quiet(" ");wrong.setForeground(DesktopUi.WARN);
        JPanel body=DesktopUi.column();
        DesktopUi.add(body,DesktopUi.note("Type the 12 recovery words you wrote down when the lock was set, in order. Then choose a new password.",380,DesktopUi.INK,DesktopUi.BODY));
        DesktopUi.gap(body,12);DesktopUi.add(body,words);DesktopUi.gap(body,DesktopUi.M);
        DesktopUi.add(body,DesktopUi.quiet("New password"));DesktopUi.gap(body,4);DesktopUi.add(body,first);DesktopUi.gap(body,DesktopUi.S);
        DesktopUi.add(body,DesktopUi.quiet("The same again"));DesktopUi.gap(body,4);DesktopUi.add(body,again);DesktopUi.gap(body,6);DesktopUi.add(body,wrong);
        JButton go=DesktopUi.primary("Unlock and set password",()->{
            String problem=problem(first.getPassword(),again.getPassword());
            if(problem!=null){wrong.setText(problem);return;}
            try {
                byte[] opened=Vault.recover(kept,words.getText());
                AtomicFile.write(lockFile(folder),Vault.newPassword(kept,opened,first.getPassword()));
                key[0]=opened;box[0].dispose();
            } catch(Vault.Refused no){wrong.setText(no.getMessage());}
            catch(Exception e){wrong.setText("The new password could not be saved. The words still open it.");}
        });
        box[0]=DesktopUi.sheet(owner,"Recovery words",body,DesktopUi.footer(go),true);
        box[0].getRootPane().setDefaultButton(go);box[0].getRootPane().putClientProperty("focus",words);DesktopUi.show(box[0],460,600);
        return key[0];
    }

    static String problem(char[] first,char[] again) {
        if(first.length<SHORTEST)return "Use at least "+SHORTEST+" characters.";
        if(!Arrays.equals(first,again))return "The two passwords are not the same.";
        return null;
    }

    // ---- switching it on -------------------------------------------------------------------------------

    /** Password, the words shown once, three of them asked back, then the notebook encrypted. */
    static void turnOn(Desktop app,Runnable after) {
        // Every way out before the end puts the switch back to what is true.
        char[] password=choosePassword(app.frame,Boolean.TRUE.equals(DesktopHello.known()));if(password==null){after.run();return;}
        Vault.Made made;
        try{made=Vault.make(password);}catch(Exception e){app.failed(e);after.run();return;}
        finally{Arrays.fill(password,'\0');}
        if(!showWords(app.frame,made.words)||!checkWords(app.frame,made.words)){after.run();return;}
        app.status.setText("Locking the notebook…");
        Path folder=app.context.getFilesDir().toPath();
        app.disk.submit(()->{
            AtomicFile.write(folder.resolve(PENDING),made.kept);
            app.store.getWritableDatabase().rekey(made.key);
            Files.move(folder.resolve(PENDING),folder.resolve(KEPT),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            app.context.unlock(made.key);
            // And every attachment, sealed with the same key.
            DesktopFiles.every(app.store,made.key,true);
            return null;
        },done->{app.status.setText("Mininotes is locked.");after.run();app.securityShown();},
            e->{try{Files.deleteIfExists(folder.resolve(PENDING));}catch(Exception ignored){/* taken off at the next start */}app.failed(e);after.run();});
    }

    static char[] choosePassword(Window owner){return choosePassword(owner,false);}

    /** @param hello whether Windows Hello will be how it opens, which makes this the backup password */
    static char[] choosePassword(Window owner,boolean hello) {
        JDialog[] box={null};char[][] chosen={null};
        JPasswordField first=new JPasswordField(24),again=new JPasswordField(24);
        DesktopUi.Text wrong=DesktopUi.quiet(" ");wrong.setForeground(DesktopUi.WARN);
        JPanel body=DesktopUi.column();
        DesktopUi.add(body,DesktopUi.note(hello
            ?"Your notes on this PC will be encrypted, and Mininotes will open with Windows Hello - your PIN, face or fingerprint - which you set up right after this. First choose a backup password, for when Windows Hello cannot be used. You will also get 12 recovery words, for if you forget it."
            :"Your notes on this PC will be encrypted. Mininotes will ask for this password each time it opens. You will also get 12 recovery words, for if you forget the password.",380,DesktopUi.INK,DesktopUi.BODY));
        DesktopUi.gap(body,12);DesktopUi.add(body,warning());DesktopUi.gap(body,DesktopUi.M);
        DesktopUi.add(body,DesktopUi.quiet(hello?"Backup password":"Password"));DesktopUi.gap(body,4);DesktopUi.add(body,first);DesktopUi.gap(body,DesktopUi.S);
        DesktopUi.add(body,DesktopUi.quiet("The same again"));DesktopUi.gap(body,4);DesktopUi.add(body,again);DesktopUi.gap(body,6);DesktopUi.add(body,wrong);
        JButton go=DesktopUi.primary("Continue",()->{
            String problem=problem(first.getPassword(),again.getPassword());
            if(problem!=null){wrong.setText(problem);return;}
            chosen[0]=first.getPassword();box[0].dispose();
        });
        box[0]=DesktopUi.sheet(owner,"Lock Mininotes",body,DesktopUi.footer(go),true);
        box[0].getRootPane().setDefaultButton(go);box[0].getRootPane().putClientProperty("focus",first);DesktopUi.show(box[0],460,620);
        return chosen[0];
    }

    /** The warning, where it cannot be missed: its own card, in the warning colour. */
    static JComponent warning() {
        DesktopUi.Text words=DesktopUi.note(WARNING,360,DesktopUi.WARN,DesktopUi.BODY.deriveFont(Font.BOLD,13f));
        JPanel card=new JPanel(new BorderLayout());card.setBackground(new Color(251,238,234));
        card.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(233,196,187)),BorderFactory.createEmptyBorder(10,12,10,12)));
        card.add(words);card.setAlignmentX(Component.LEFT_ALIGNMENT);
        return card;
    }

    static boolean showWords(Window owner,List<String> words){return showWords(owner,words,true);}

    /**
     * The twelve words, in one block that can be selected whole, with a button to copy them. The first
     * time, the lock waits for "I have written them down"; shown again later, there is nothing to press.
     */
    static boolean showWords(Window owner,List<String> words,boolean first) {
        JDialog[] box={null};boolean[] done={false};
        StringBuilder laid=new StringBuilder();
        for(int i=0;i<words.size();i++){String one=String.format("%2d. %-10s",i+1,words.get(i));laid.append(one);laid.append(i%3==2?"\n":"   ");}
        DesktopUi.Text block=new DesktopUi.Text(laid.toString().stripTrailing(),new Font("Consolas",Font.BOLD,16),DesktopUi.INK,0);
        block.setName("recoveryWordsShown");block.getAccessibleContext().setAccessibleName("Your 12 recovery words");
        DesktopUi.Text copied=DesktopUi.quiet(" ");
        JButton copy=DesktopUi.button("Copy the words",()->{
            String plain=String.join(" ",words);
            java.awt.datatransfer.StringSelection held=new java.awt.datatransfer.StringSelection(plain);
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(held,null);
            copied.setText("Copied. Paste them somewhere safe now: the clipboard is emptied in one minute.");
            // Not left on the clipboard, where any program can read it, for longer than it takes to paste.
            javax.swing.Timer clear=new javax.swing.Timer(60_000,e->{
                try{var board=Toolkit.getDefaultToolkit().getSystemClipboard();
                    if(plain.equals(board.getData(java.awt.datatransfer.DataFlavor.stringFlavor)))board.setContents(new java.awt.datatransfer.StringSelection(""),null);}
                catch(Exception gone){/* something else is on it now */}
            });clear.setRepeats(false);clear.start();
        });
        JPanel body=DesktopUi.column();
        DesktopUi.add(body,DesktopUi.note(first
            ?"Write these 12 words on paper, in this order, and keep them in a safe place, away from this PC. With them you can open your notes if you forget the password."
            :"Your 12 recovery words, in order. Keep them in a safe place, away from this PC.",400,DesktopUi.INK,DesktopUi.BODY));
        DesktopUi.gap(body,DesktopUi.M);DesktopUi.add(body,DesktopUi.card(null,block));DesktopUi.gap(body,DesktopUi.S);
        DesktopUi.add(body,DesktopUi.actions(copy));DesktopUi.gap(body,4);DesktopUi.add(body,copied);DesktopUi.gap(body,DesktopUi.M);
        DesktopUi.add(body,warning());
        JButton go=DesktopUi.primary("I have written them down",()->{done[0]=true;box[0].dispose();});
        box[0]=DesktopUi.sheet(owner,first?"Your recovery words":"Recovery words",body,first?DesktopUi.footer(go):null,true);
        if(first)box[0].getRootPane().setDefaultButton(go);
        DesktopUi.show(box[0],500,680);
        return done[0];
    }

    /** The words again, for the owner: the password asked once more, since the words open everything. */
    static void showAgain(Desktop app) {
        Path folder=app.context.getFilesDir().toPath();
        char[] password=askPassword(app.frame,"Show recovery words","Type your password to see your recovery words.","Show the words");
        if(password==null)return;
        try{byte[] kept=Files.readAllBytes(folder.resolve(KEPT));showWords(app.frame,Vault.words(kept,Vault.open(kept,password)),false);}
        catch(Vault.Refused no){DesktopUi.tell(app.frame,"Show recovery words",DesktopUi.note(no.getMessage().startsWith("That did not")?"That password is not right.":no.getMessage(),360,DesktopUi.INK,DesktopUi.BODY));}
        catch(Exception e){app.failed(e);}
        finally{Arrays.fill(password,'\0');}
    }

    /**
     * The key of a locked backup: the password of the notebook it came from, or its 12 recovery words, in
     * the one field. Null if the box was closed.
     */
    static byte[] openBackup(Window owner,byte[] lock) {
        JDialog[] box={null};byte[][] key={null};
        JTextArea said=new JTextArea(2,32);said.setLineWrap(true);said.setWrapStyleWord(true);
        said.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(DesktopUi.LINE),BorderFactory.createEmptyBorder(8,10,8,10)));
        DesktopUi.Text wrong=DesktopUi.quiet(" ");
        JPanel body=DesktopUi.column();
        DesktopUi.add(body,DesktopUi.note("This backup is locked. Type the password of the notebook it came from, or its 12 recovery words.",380,DesktopUi.INK,DesktopUi.BODY));
        DesktopUi.gap(body,12);DesktopUi.add(body,said);DesktopUi.gap(body,6);DesktopUi.add(body,wrong);
        JButton go=DesktopUi.primary("Open the backup",()->{
            wrong.setForeground(DesktopUi.QUIET);wrong.setText("Opening…");wrong.paintImmediately(wrong.getVisibleRect());
            String typed=said.getText();
            try{key[0]=typed.trim().split("\\s+").length==Vault.WORDS?Vault.recover(lock,typed):Vault.open(lock,typed.toCharArray());box[0].dispose();}
            catch(Vault.Refused no){wrong.setForeground(DesktopUi.WARN);wrong.setText("That does not open this backup.");}
        });
        box[0]=DesktopUi.sheet(owner,"Locked backup",body,DesktopUi.footer(go),true);
        box[0].getRootPane().setDefaultButton(go);box[0].getRootPane().putClientProperty("focus",said);DesktopUi.show(box[0],440,420);
        return key[0];
    }
    /** One password, asked for. Null if the box was closed. */
    static char[] askPassword(Window owner,String title,String message,String yes) {
        JDialog[] box={null};char[][] said={null};
        JPasswordField field=new JPasswordField(24);
        JPanel body=DesktopUi.column();DesktopUi.add(body,DesktopUi.note(message,360,DesktopUi.INK,DesktopUi.BODY));DesktopUi.gap(body,12);DesktopUi.add(body,field);
        JButton go=DesktopUi.primary(yes,()->{said[0]=field.getPassword();box[0].dispose();});field.addActionListener(e->go.doClick());
        box[0]=DesktopUi.sheet(owner,title,body,DesktopUi.footer(go),true);
        box[0].getRootPane().setDefaultButton(go);box[0].getRootPane().putClientProperty("focus",field);DesktopUi.show(box[0],420,360);
        return said[0];
    }

    // ---- where it is all kept together --------------------------------------------------------------------

    /** Whether it is on, what that means, and every thing that can be done about it, in one window. */
    static void settings(Desktop app) {
        Path folder=app.context.getFilesDir().toPath();
        JDialog[] box={null};
        JLabel state=new JLabel();state.setFont(DesktopUi.BODY.deriveFont(Font.BOLD,15f));state.setIconTextGap(10);
        DesktopUi.Text means=DesktopUi.note(" ",420,DesktopUi.QUIET,DesktopUi.BODY.deriveFont(13f));
        JCheckBox on=DesktopUi.toggle("Lock Mininotes",locked(folder));
        JButton words=app.button("Show…",()->showAgain(app)),change=app.button("Change…",()->changePassword(app));
        JPanel passwordRow=DesktopUi.row(DesktopUi.body("Backup password"),holder(change)),wordsRow=DesktopUi.row(DesktopUi.body("12 recovery words"),holder(words));
        DesktopUi.Text ways=DesktopUi.quiet("Ways to open it");
        // Locking again when Mininotes has not been used for a while: a choice, and Never is one of them.
        JComboBox<String> after=new JComboBox<>(AFTER_NAMES);after.setSelectedIndex(afterIndex(minutes(app.context)));
        after.addActionListener(e->{int m=AFTER_MINUTES[after.getSelectedIndex()];app.context.getSharedPreferences("settings",0).edit().putString("autoLock",Integer.toString(m)).apply();});
        JPanel afterHolder=new JPanel(new GridBagLayout());afterHolder.setOpaque(false);afterHolder.add(after);
        JPanel afterRow=DesktopUi.row(DesktopUi.body("Lock again when not used"),afterHolder);
        // Windows Hello: offered only once this PC has said it has it (asking takes a moment, so it is asked aside).
        JCheckBox hello=DesktopUi.toggle("Windows Hello: PIN, face or fingerprint",DesktopHello.has(folder));hello.setName("helloSwitch");
        JPanel helloRow=DesktopUi.switchRow("Windows Hello: PIN, face or fingerprint",hello);helloRow.setVisible(false);
        // What happened, said under the switch itself: the bar behind this window is not where anybody looks.
        DesktopUi.Text helloSaid=DesktopUi.quiet(" ");helloSaid.setName("helloSaid");helloSaid.setVisible(false);
        boolean[] helloHere={Boolean.TRUE.equals(DesktopHello.known())};JPanel[] whileOnRef={null};Runnable[] shownRef={()->{}};
        hello.addActionListener(e->{
            if(!hello.isSelected()){DesktopHello.forget(folder);helloSaid.setForeground(DesktopUi.QUIET);helloSaid.setText("Windows Hello no longer opens Mininotes.");helloSaid.setVisible(true);return;}
            byte[] key=app.context.databaseKey();if(key==null){hello.setSelected(false);return;}
            hello.setEnabled(false);helloSaid.setForeground(DesktopUi.QUIET);helloSaid.setText("Waiting for Windows Hello…");helloSaid.setVisible(true);
            // Not on the disk thread: Hello waits for the person, and saving must not wait with it.
            new SwingWorker<Void,Void>(){
                protected Void doInBackground() throws Exception{DesktopHello.enable(folder,key);return null;}
                protected void done(){
                    hello.setEnabled(true);
                    try{get();helloSaid.setForeground(DesktopUi.QUIET);helloSaid.setText("Windows Hello now opens Mininotes. The backup password still works.");shownRef[0].run();}
                    catch(Exception e){Throwable why=e.getCause()!=null?e.getCause():e;hello.setSelected(false);helloSaid.setForeground(DesktopUi.WARN);helloSaid.setText(why.getMessage()!=null?why.getMessage():"Windows Hello could not be set up.");}
                }
            }.execute();
        });
        new SwingWorker<Boolean,Void>(){
            protected Boolean doInBackground(){return DesktopHello.supported();}
            protected void done(){try{helloHere[0]=get();}catch(Exception no){helloHere[0]=false;}shownRef[0].run();if(box[0]!=null){box[0].validate();box[0].setSize(box[0].getWidth(),Math.min(640,box[0].getPreferredSize().height));}}
        }.execute();
        Runnable shown=()->{boolean yes=locked(folder),viaHello=DesktopHello.has(folder);on.setSelected(yes);
            if(whileOnRef[0]!=null)whileOnRef[0].setVisible(yes);
            hello.setSelected(viaHello);helloRow.setVisible(yes&&helloHere[0]);
            state.setIcon(padlock(yes,18));state.setText(yes?"Locked and encrypted":"Not locked");state.setForeground(yes?DesktopUi.ACCENT:DesktopUi.INK);
            means.setText(!yes?"Anyone who can open this PC's files can read your notes. Lock Mininotes to encrypt them"+(helloHere[0]?"; it then opens with Windows Hello.":" with a password.")
                :viaHello?"Mininotes opens with Windows Hello. Your backup password and your 12 recovery words open it too. Your notes, their attachments and the backups you export are encrypted."
                :"Mininotes asks for your backup password when it opens"+(helloHere[0]?" - switch on Windows Hello below and you will rarely need it":"")+". Your notes, their attachments and the backups you export are encrypted.");
            app.securityShown();};
        shownRef[0]=shown;shown.run();
        // Once the lock is on, Windows Hello is set up straight away where this PC has it: that is how it is meant to open.
        on.addActionListener(e->{if(on.isSelected())turnOn(app,()->{shown.run();if(locked(folder)&&helloHere[0]&&!DesktopHello.has(folder))hello.doClick();});else turnOff(app,shown);});
        JPanel body=DesktopUi.column();
        DesktopUi.add(body,state);DesktopUi.gap(body,6);DesktopUi.add(body,means);DesktopUi.gap(body,DesktopUi.M);
        JPanel card=DesktopUi.column();card.add(DesktopUi.switchRow("Lock Mininotes",on));
        // Everything that only means something while it is locked, shown and hidden as one.
        JPanel whileOn=DesktopUi.column();whileOn.setOpaque(false);
        DesktopUi.gap(whileOn,DesktopUi.S);DesktopUi.add(whileOn,ways);whileOn.add(helloRow);DesktopUi.add(whileOn,helloSaid);whileOn.add(passwordRow);whileOn.add(wordsRow);
        DesktopUi.gap(whileOn,DesktopUi.S);whileOn.add(afterRow);DesktopUi.add(card,whileOn);whileOnRef[0]=whileOn;shown.run();
        DesktopUi.add(body,DesktopUi.card(null,card));DesktopUi.gap(body,DesktopUi.M);DesktopUi.add(body,warning());
        box[0]=DesktopUi.sheet(app.frame,"Security",body,null,true);DesktopUi.show(box[0],500,640);
    }

    /** A button kept its own size at the end of a row. */
    private static JComponent holder(JComponent inside){JPanel h=new JPanel(new GridBagLayout());h.setOpaque(false);h.add(inside);return h;}

    /** A small padlock, closed and green when the notebook is encrypted, open and quiet when it is not. */
    static Icon padlock(boolean closed,int size) {
        return new Icon(){
            public int getIconWidth(){return size;}public int getIconHeight(){return size;}
            public void paintIcon(Component c,Graphics g0,int x,int y) {
                Graphics2D g=(Graphics2D)g0.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(closed?new Color(48,99,72):new Color(150,146,136));
                float s=size/18f;g.setStroke(new BasicStroke(2f*s,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
                int bodyTop=y+Math.round(8*s);
                g.fillRoundRect(x+Math.round(3*s),bodyTop,Math.round(12*s),Math.round(9*s),Math.round(3*s),Math.round(3*s));
                int left=x+Math.round(5.5f*s),w=Math.round(7*s);
                if(closed)g.drawArc(left,y+Math.round(2*s),w,Math.round(10*s),0,180);
                else g.drawArc(left+Math.round(4*s),y+Math.round(1*s),w,Math.round(10*s),0,180);
                g.drawLine(closed?left:left+Math.round(4*s)+w,y+Math.round(7*s),closed?left:left+Math.round(4*s)+w,bodyTop);
                if(closed)g.drawLine(left+w,y+Math.round(7*s),left+w,bodyTop);
                g.dispose();
            }
        };
    }
    /** Three of the words, asked back, so the lock is not put on until they are really written down. */
    static boolean checkWords(Window owner,List<String> words) {
        List<Integer> order=new ArrayList<>();for(int i=0;i<words.size();i++)order.add(i);
        Collections.shuffle(order,new SecureRandom());List<Integer> asked=new ArrayList<>(order.subList(0,3));Collections.sort(asked);
        JDialog[] box={null};boolean[] ok={false};
        JTextField[] fields=new JTextField[3];DesktopUi.Text wrong=DesktopUi.quiet(" ");wrong.setForeground(DesktopUi.WARN);
        JPanel body=DesktopUi.column();
        DesktopUi.add(body,DesktopUi.note("To be sure they are written down, type these three of your words.",380,DesktopUi.INK,DesktopUi.BODY));DesktopUi.gap(body,12);
        for(int i=0;i<3;i++){fields[i]=new JTextField(18);fields[i].setName("word"+(asked.get(i)+1));body.add(DesktopUi.row(DesktopUi.body("Word "+(asked.get(i)+1)),fields[i]));}
        DesktopUi.gap(body,6);DesktopUi.add(body,wrong);
        JButton go=DesktopUi.primary("Lock the notebook",()->{
            for(int i=0;i<3;i++)if(!fields[i].getText().trim().equalsIgnoreCase(words.get(asked.get(i)))){wrong.setText("Word "+(asked.get(i)+1)+" is not right. Check what you wrote down.");return;}
            ok[0]=true;box[0].dispose();
        });
        JButton back=DesktopUi.button("Show the words again",()->showWords(box[0],words));
        JPanel foot=new JPanel(new BorderLayout());foot.setOpaque(false);foot.add(DesktopUi.actions(back),BorderLayout.WEST);foot.add(DesktopUi.footer(go),BorderLayout.EAST);
        box[0]=DesktopUi.sheet(owner,"Check your words",body,foot,true);
        box[0].getRootPane().setDefaultButton(go);box[0].getRootPane().putClientProperty("focus",fields[0]);DesktopUi.show(box[0],460,480);
        return ok[0];
    }

    // ---- a new password, and switching it off -----------------------------------------------------------

    static void changePassword(Desktop app) {
        Path folder=app.context.getFilesDir().toPath();
        JDialog[] box={null};
        JPasswordField now=new JPasswordField(24),first=new JPasswordField(24),again=new JPasswordField(24);
        DesktopUi.Text wrong=DesktopUi.quiet(" ");wrong.setForeground(DesktopUi.WARN);
        JPanel body=DesktopUi.column();
        DesktopUi.add(body,DesktopUi.quiet("Current password"));DesktopUi.gap(body,4);DesktopUi.add(body,now);DesktopUi.gap(body,DesktopUi.M);
        DesktopUi.add(body,DesktopUi.quiet("New password"));DesktopUi.gap(body,4);DesktopUi.add(body,first);DesktopUi.gap(body,DesktopUi.S);
        DesktopUi.add(body,DesktopUi.quiet("The same again"));DesktopUi.gap(body,4);DesktopUi.add(body,again);DesktopUi.gap(body,6);DesktopUi.add(body,wrong);
        DesktopUi.add(body,DesktopUi.note("Your recovery words stay the same.",360,DesktopUi.QUIET,DesktopUi.BODY.deriveFont(13f)));
        JButton go=DesktopUi.primary("Change password",()->{
            String problem=problem(first.getPassword(),again.getPassword());
            if(problem!=null){wrong.setText(problem);return;}
            try {
                byte[] kept=Files.readAllBytes(folder.resolve(KEPT));
                byte[] key=Vault.open(kept,now.getPassword());
                AtomicFile.write(folder.resolve(KEPT),Vault.newPassword(kept,key,first.getPassword()));
                box[0].dispose();app.status.setText("Password changed");
            } catch(Vault.Refused no){wrong.setText("The current password is not right.");}
            catch(Exception e){wrong.setText("The password could not be changed. The old one still works.");}
        });
        box[0]=DesktopUi.sheet(app.frame,"Change backup password",body,DesktopUi.footer(go),true);
        box[0].getRootPane().setDefaultButton(go);box[0].getRootPane().putClientProperty("focus",now);DesktopUi.show(box[0],440,520);
    }

    static void turnOff(Desktop app,Runnable after) {
        Path folder=app.context.getFilesDir().toPath();
        JDialog[] box={null};boolean[] go={false};
        JPasswordField now=new JPasswordField(24);DesktopUi.Text wrong=DesktopUi.quiet(" ");wrong.setForeground(DesktopUi.WARN);
        JPanel body=DesktopUi.column();
        DesktopUi.add(body,DesktopUi.note("Your notes on this PC will no longer be encrypted, and Mininotes will open without a password.",380,DesktopUi.INK,DesktopUi.BODY));
        DesktopUi.gap(body,12);DesktopUi.add(body,DesktopUi.quiet("Password"));DesktopUi.gap(body,4);DesktopUi.add(body,now);DesktopUi.gap(body,6);DesktopUi.add(body,wrong);
        JButton off=DesktopUi.danger("Turn off the lock",()->{
            try{Vault.open(Files.readAllBytes(folder.resolve(KEPT)),now.getPassword());go[0]=true;box[0].dispose();}
            catch(Vault.Refused no){wrong.setText("That password is not right.");}
            catch(Exception e){wrong.setText("The lock could not be read.");}
        });
        box[0]=DesktopUi.sheet(app.frame,"Turn off the lock?",body,DesktopUi.footer(off),true);
        box[0].getRootPane().setDefaultButton(off);box[0].getRootPane().putClientProperty("focus",now);DesktopUi.show(box[0],440,400);
        if(!go[0]){after.run();return;}
        app.status.setText("Turning off the lock…");
        app.disk.submit(()->{
            byte[] key=app.context.databaseKey();
            if(key!=null)DesktopFiles.every(app.store,key,false);
            app.store.getWritableDatabase().rekey(null);
            Files.deleteIfExists(folder.resolve(KEPT));DesktopHello.forget(folder);app.context.unlock(null);
            return null;
        },done->{app.status.setText("Lock turned off");after.run();app.securityShown();},e->{app.failed(e);after.run();});
    }
}
