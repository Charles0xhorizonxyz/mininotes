package org.mininotes.android;

import org.mininotes.desktop.platform.content.Context;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.tree.*;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;

/** Windows UI. Shared Java classes retain their package to avoid widening their APIs. */
public final class Desktop {
    static final Color PAPER=new Color(250,249,244),INK=new Color(43,48,43),QUIET=new Color(111,117,108),ACCENT=new Color(48,99,72);
    // The look is set before a single component is made, fields included: a tree made first keeps the old one.
    static{DesktopUi.install();}
    final JFrame frame=new JFrame("Mininotes");
    final JTextField title=new JTextField();
    final Paper page=new Paper();
    final JLabel connection=new Dot("Offline");
    /** Whether this PC's notebook is encrypted, always in sight; a click opens Security. */
    final JLabel security=new JLabel();
    /** This build, beside the name in the bar - and, when a newer one is out, the way to it. */
    static final String VERSION="0.0.021";
    final JLabel version=new JLabel();
    /** Beside the version, only while a newer one is out: an outlined button, so it reads as one to press. */
    final JButton updateButton=new JButton();
    String newestKnown="";
    /** The paperclip at the foot of the note, with how many files the note holds. */
    final JButton clip=new JButton("📎");
    final DesktopUi.Text status=DesktopUi.quiet("Opening your pad…"),standing=DesktopUi.quiet(" "),syncDetails=DesktopUi.quiet(" ");
    final JTree tree=new JTree(new DefaultMutableTreeNode("All collections"));
    /** The main area: the note that is open, or cards. */
    private final JPanel mainArea=new JPanel(new CardLayout());
    final DesktopShelves shelves=new DesktopShelves(this::openBranch,(branch,e)->shelfMenu(branch,e.getComponent(),e.getX(),e.getY()));
    /** Everything, as the tree last drew it: what the cards are made from. */
    private List<NoteStore.Branch> everything=new ArrayList<>();
    private JPanel side;private JSplitPane split;
    /** The button that folds the side panel away and back. */
    private final JButton sideToggle=new JButton();private volatile boolean treeWanted=true;
    final Context context;
    final NoteStore store;
    final Keys keys;
    final Background disk=new Background(SwingUtilities::invokeLater),network=new Background(SwingUtilities::invokeLater);
    final Background connectivity=new Background(SwingUtilities::invokeLater);
    final javax.swing.Timer autosave,syncLater;
    private NoteStore.Note base;
    private boolean drawing,dirty,saving;
    private volatile boolean closing;
    private Runnable afterSave;
    private int treeRequest,noteRequest;
    final boolean offline;
    volatile boolean listenInTray;
    private TrayIcon tray;
    private boolean nodeStarting;
    private String currentAddress="";
    private NoteStore.Branch selected;
    private final Set<String> pendingOffers=new HashSet<>();
    private final Map<String,Boolean> offers=new HashMap<>();
    private final Map<String,Long> offeredAt=new HashMap<>();
    private final Map<String,Long> due=new HashMap<>();
    private FileChannel instanceChannel;private FileLock instanceLock;

    public static void main(String[] args) {
        try {
            DesktopUi.install();
            Path data=Path.of(System.getenv().getOrDefault("LOCALAPPDATA",System.getProperty("user.home")),"Mininotes");
            boolean offline=false;
            for(int i=0;i<args.length;i++) {
                if(args[i].equals("--data-dir")&&i+1<args.length)data=Path.of(args[++i]);
                else if(args[i].equals("--offline"))offline=true;
                else throw new IllegalArgumentException("Unknown launch argument");
            }
            Path home=data;boolean local=offline;
            SwingUtilities.invokeLater(()->{
                try {
                    // A locked notebook is asked for before anything of it is opened. Closing the question closes Mininotes.
                    byte[] key=null;
                    if(DesktopLock.locked(home)){byte[] given=DesktopLock.askAtStart(home);if(given==null){System.exit(0);return;}key=given.length==0?null:given;}
                    new Desktop(home,local,key).show();
                }
                catch(Exception e){JOptionPane.showMessageDialog(null,e.getMessage(),"Mininotes could not open",JOptionPane.ERROR_MESSAGE);}
            });
        } catch(Exception e){JOptionPane.showMessageDialog(null,e.getMessage(),"Mininotes",JOptionPane.ERROR_MESSAGE);}
    }
    Desktop(Path folder,boolean offline) throws Exception{this(folder,offline,null);}
    Desktop(Path folder,boolean offline,byte[] key) throws Exception {
        this.offline=offline;context=new Context(folder.toFile());
        context.unlock(key);
        if(key==null&&DesktopLock.locked(folder))throw new IllegalStateException("This notebook is locked. Open Mininotes again to type its password.");
        instanceChannel=FileChannel.open(folder.resolve("notebook.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        try{instanceLock=instanceChannel.tryLock();}catch(OverlappingFileLockException e){instanceLock=null;}
        if(instanceLock==null){instanceChannel.close();throw new IllegalStateException("This pad is already open in another window.");}
        store=new NoteStore(context);keys=new Keys(context);
        autosave=new javax.swing.Timer(350,e->save(null));autosave.setRepeats(false);
        syncLater=new javax.swing.Timer(1000,e->sendDue());syncLater.start();
        Toolkit.getDefaultToolkit().addAWTEventListener(using,AWTEvent.KEY_EVENT_MASK|AWTEvent.MOUSE_EVENT_MASK|AWTEvent.MOUSE_WHEEL_EVENT_MASK);
        idle=new javax.swing.Timer(20_000,e->relockIfIdle());idle.start();
        build();
        disk.submit(()->{
            listenInTray="true".equals(context.getSharedPreferences("settings",0).getString("listenInTray","false"));
            treeWanted=!"false".equals(context.getSharedPreferences("settings",0).getString("tree","true"));
            store.getWritableDatabase();
            // A locked notebook seals any attachment still plain: one kept before the lock covered attachments.
            byte[] opened=context.databaseKey();if(opened!=null)DesktopFiles.every(store,opened,true);
            NoteStore.Note latest=store.latest();
            if(latest==null){latest=new NoteStore.Note();latest.book=store.someBook();store.save(latest);}
            return latest;
        },note->{display(note);refresh();status.setText(" ");if(!treeWanted)showTree(false);if(!offline){startNode();lookForUpdate(false);}
            // Whether this PC has Windows Hello, asked now so Security can show it the moment it opens.
            connectivity.submit(DesktopHello::supported,yes->{},e->{});},this::failed);
    }
    void show(){frame.setVisible(true);Node.near(true);page.requestFocusInWindow();}
    private void build() {
        DesktopUi.install();
        Font body=DesktopUi.BODY;
        frame.setIconImages(List.of(DesktopIcon.image(16),DesktopIcon.image(32),DesktopIcon.image(48),DesktopIcon.image(256)));
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.setMinimumSize(new Dimension(820,520));frame.setSize(1120,760);frame.setLocationRelativeTo(null);
        frame.addWindowListener(new WindowAdapter(){public void windowClosing(WindowEvent e){save(()->{if(listenInTray&&ensureTray()){frame.setVisible(false);Node.near(false);}else shutdown(true);});}});
        JPanel shell=new JPanel(new BorderLayout());shell.setBackground(PAPER);

        // The bar: what this is on the left; on the right, whether it is connected, then what can be done,
        // the one thing done most (a new note) filled and last.
        JPanel bar=new JPanel(new BorderLayout(16,0));bar.setBackground(DesktopUi.SHELF);
        bar.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0,0,1,0,DesktopUi.LINE),BorderFactory.createEmptyBorder(10,16,10,16)));
        JLabel brand=new JLabel("Mininotes",new ImageIcon(DesktopIcon.image(28)),SwingConstants.LEFT);brand.setIconTextGap(10);brand.setFont(body.deriveFont(Font.BOLD,18f));brand.setForeground(INK);
        // The side panel folds away, so the note or the cards have the whole window; the same button brings it back.
        sideToggle.putClientProperty(com.formdev.flatlaf.FlatClientProperties.BUTTON_TYPE,com.formdev.flatlaf.FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        sideToggle.setIcon(arrowIcon());sideToggle.setFocusPainted(false);sideToggle.setToolTipText("Hide the side panel (Ctrl+B)");sideToggle.getAccessibleContext().setAccessibleName("Hide the side panel");
        sideToggle.setMargin(new Insets(6,2,6,2));sideToggle.addActionListener(e->showTree(!side.isVisible()));
        JPanel left=new JPanel(new FlowLayout(FlowLayout.LEFT,0,0));left.setOpaque(false);left.add(brand);left.add(Box.createHorizontalStrut(12));left.add(version);left.add(Box.createHorizontalStrut(12));left.add(updateButton);
        updateButton.setFocusPainted(false);updateButton.setFont(DesktopUi.BODY.deriveFont(Font.BOLD,12.5f));updateButton.setMargin(new Insets(3,12,3,12));
        updateButton.putClientProperty(com.formdev.flatlaf.FlatClientProperties.STYLE,"arc:8;foreground:#FFFFFF;background:#306348;hoverBackground:#2A5840;pressedBackground:#224A35;borderWidth:0;focusWidth:0");
        updateButton.addActionListener(e->{if(staged!=null)restartToUpdate();else updatesBox(null);});
        version.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));version.setIconTextGap(6);
        version.addMouseListener(new MouseAdapter(){public void mouseClicked(MouseEvent e){lookForUpdate(true);}});
        updateShown();
        JPanel leftMiddle=new JPanel(new GridBagLayout());leftMiddle.setOpaque(false);leftMiddle.add(left);bar.add(leftMiddle,BorderLayout.WEST);
        connection.setForeground(QUIET);connection.setFont(body.deriveFont(13f));connection.setIconTextGap(6);
        connection.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));connection.setToolTipText("Connection details are in Profile");
        connection.addMouseListener(new MouseAdapter(){public void mouseClicked(MouseEvent e){profile();}});
        JButton menu=button("",()->{});menu.setIcon(new Icon(){
            public int getIconWidth(){return 16;}public int getIconHeight(){return 16;}
            public void paintIcon(Component c,Graphics g0,int x,int y){Graphics2D g=(Graphics2D)g0.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setColor(INK);for(int i=0;i<3;i++)g.fillOval(x+1+i*5+(i>0?i:0),y+7,3,3);g.dispose();}
        });menu.setToolTipText("More");menu.getAccessibleContext().setAccessibleName("More");menu.addActionListener(e->menu(menu));
        security.setFont(body.deriveFont(13f));security.setIconTextGap(6);security.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        security.addMouseListener(new MouseAdapter(){public void mouseClicked(MouseEvent e){DesktopLock.settings(Desktop.this);}});
        securityShown();
        JPanel actions=DesktopUi.footer(security,connection,button("Profile",this::profile),button("Share",()->save(this::share)),menu,DesktopUi.primary("New note",()->save(this::newNote)));
        JPanel middle=new JPanel(new GridBagLayout());middle.setOpaque(false);middle.add(actions);bar.add(middle,BorderLayout.EAST);
        shell.add(bar,BorderLayout.NORTH);

        // The shelves: search at the top, then everything, on a shade of its own.
        JPanel side=new JPanel(new BorderLayout(0,10));side.setBackground(DesktopUi.SHELF);side.setBorder(BorderFactory.createEmptyBorder(14,12,12,8));
        JTextField search=new JTextField();search.putClientProperty(com.formdev.flatlaf.FlatClientProperties.PLACEHOLDER_TEXT,"Search notes");
        search.putClientProperty(com.formdev.flatlaf.FlatClientProperties.TEXT_FIELD_LEADING_ICON,new com.formdev.flatlaf.icons.FlatSearchIcon());
        search.putClientProperty(com.formdev.flatlaf.FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON,true);
        search.setToolTipText("Search all notes (Ctrl+F)");search.getAccessibleContext().setAccessibleName("Search all notes");
        JPanel searchRow=new JPanel(new BorderLayout());searchRow.setOpaque(false);searchRow.setBorder(BorderFactory.createEmptyBorder(0,4,0,4));searchRow.add(search);side.add(searchRow,BorderLayout.NORTH);
        tree.setRootVisible(true);tree.setShowsRootHandles(true);tree.setRowHeight(30);tree.setBackground(DesktopUi.SHELF);tree.setBorder(BorderFactory.createEmptyBorder(2,0,4,0));
        tree.setCellRenderer(new DefaultTreeCellRenderer(){
            public Component getTreeCellRendererComponent(JTree t,Object v,boolean s,boolean ex,boolean leaf,int row,boolean focus) {
                super.getTreeCellRendererComponent(t,v,s,ex,leaf,row,focus);setIcon(null);setBorder(BorderFactory.createEmptyBorder(0,6,0,6));
                Object said=v instanceof DefaultMutableTreeNode node?node.getUserObject():v;
                boolean page=said instanceof Item item&&item.branch.kind==NoteStore.Branch.Kind.PAGE;
                setFont(page?DesktopUi.BODY:DesktopUi.BODY.deriveFont(Font.BOLD));
                // The colour it was given, on this PC or on the phone: a dot before its name.
                int colour=said instanceof Item item?item.branch.colour:Tint.NONE;
                if(Tint.known(colour)){setIcon(swatch(colour,9));setIconTextGap(8);}
                setBackgroundNonSelectionColor(DesktopUi.SHELF);setTextNonSelectionColor(INK);return this;
            }
        });
        tree.addTreeSelectionListener(e->{if(drawing)return;Object value=((DefaultMutableTreeNode)tree.getLastSelectedPathComponent());
            if(value instanceof DefaultMutableTreeNode node&&node.getUserObject() instanceof Item item){
                selected=item.branch;if(selected.kind==NoteStore.Branch.Kind.PAGE)save(()->open(item.branch.id));else save(()->showShelf(item.branch));
            }else if(value instanceof DefaultMutableTreeNode node&&node.isRoot()){selected=library();save(()->showShelf(library()));}});
        JScrollPane shelves=DesktopUi.scrolling(tree);side.add(shelves);
        // Everything in the tree can be renamed and coloured where it stands: right-click it, or F2 to rename.
        tree.addMouseListener(new MouseAdapter(){
            public void mousePressed(MouseEvent e){if(e.isPopupTrigger())shelfMenu(e);}
            public void mouseReleased(MouseEvent e){if(e.isPopupTrigger())shelfMenu(e);}
        });
        tree.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("F2"),"rename");
        tree.getActionMap().put("rename",new AbstractAction(){public void actionPerformed(ActionEvent e){if(selected!=null)rename(selected);}});
        search.getDocument().addDocumentListener(watch(()->search(search.getText())));

        // The page: its title, one quiet line for where it lives and whether the others have it, then paper.
        JPanel editor=new JPanel(new BorderLayout());editor.setBackground(PAPER);
        JPanel heading=new JPanel(new BorderLayout(0,6));heading.setOpaque(false);heading.setBorder(BorderFactory.createEmptyBorder(22,42,10,38));
        title.setFont(body.deriveFont(Font.BOLD,24f));title.setBorder(BorderFactory.createEmptyBorder());title.setBackground(PAPER);title.setForeground(INK);title.setToolTipText("Note title (optional)");
        title.putClientProperty(com.formdev.flatlaf.FlatClientProperties.PLACEHOLDER_TEXT,"Untitled");
        title.setEditable(false);title.getAccessibleContext().setAccessibleName("Note title");heading.add(title);standing.setForeground(QUIET);standing.setFont(body.deriveFont(13f));
        // One quiet row: where the note lives on the left, whether the others have it on the right. The exact times wait in the tooltip.
        JPanel details=new JPanel(new BorderLayout(18,0));details.setOpaque(false);details.add(standing);
        syncDetails.setForeground(QUIET);syncDetails.setFont(body.deriveFont(13f));syncDetails.setBorder(BorderFactory.createEmptyBorder(0,0,0,2));details.add(syncDetails,BorderLayout.EAST);
        heading.add(details,BorderLayout.SOUTH);editor.add(heading,BorderLayout.NORTH);
        page.setFont(body.deriveFont(18f));page.setForeground(INK);page.setBackground(PAPER);page.setLineWrap(true);page.setWrapStyleWord(true);page.setBorder(BorderFactory.createEmptyBorder(12,42,40,38));
        page.setEditable(false);page.getAccessibleContext().setAccessibleName("Note text");page.setTabSize(4);
        JScrollPane paper=DesktopUi.scrolling(page);editor.add(paper);
        // The paperclip, always at the foot of the note as on the phone: what is attached, and a way to add more.
        clip.setToolTipText("Attachments");clip.getAccessibleContext().setAccessibleName("Attachments");
        clip.putClientProperty(com.formdev.flatlaf.FlatClientProperties.BUTTON_TYPE,com.formdev.flatlaf.FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        clip.setFont(body.deriveFont(18f));clip.setForeground(QUIET);clip.addActionListener(e->clipMenu());
        JPanel foot=new JPanel(new FlowLayout(FlowLayout.RIGHT,0,0));foot.setOpaque(false);foot.setBorder(BorderFactory.createEmptyBorder(4,16,10,22));foot.add(clip);
        editor.add(foot,BorderLayout.SOUTH);
        // The main area is the open note, or the cards of whatever is chosen in the tree.
        mainArea.add(editor,"page");mainArea.add(this.shelves,"shelf");
        standing.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));standing.setToolTipText("Show this book as cards");
        standing.addMouseListener(new MouseAdapter(){public void mouseClicked(MouseEvent e){if(base!=null){NoteStore.Branch book=find(base.book);if(book!=null)save(()->showShelf(book));}}});
        this.side=side;
        // The arrow on the dividing line: at the page's left edge, level with the title, whether the panel is open or folded.
        JPanel edge=new JPanel(new BorderLayout());edge.setOpaque(false);edge.setBorder(BorderFactory.createEmptyBorder(24,2,0,0));edge.add(sideToggle,BorderLayout.NORTH);
        JPanel pageSide=new JPanel(new BorderLayout());pageSide.setBackground(PAPER);pageSide.add(edge,BorderLayout.WEST);pageSide.add(mainArea);
        JSplitPane split=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,side,pageSide);this.split=split;split.setDividerLocation(280);split.setDividerSize(1);split.setBorder(BorderFactory.createEmptyBorder());
        split.putClientProperty(com.formdev.flatlaf.FlatClientProperties.STYLE,"dividerSize:1");shell.add(split);

        // What just happened, on a quiet bar of its own at the foot.
        status.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1,0,0,0,DesktopUi.LINE),BorderFactory.createEmptyBorder(7,16,7,16)));
        status.setForeground(QUIET);status.setFont(body.deriveFont(13f));status.setOpaque(true);status.setBackground(DesktopUi.SHELF);shell.add(status,BorderLayout.SOUTH);frame.setContentPane(shell);frame.setFocusTraversalPolicy(DesktopUi.skippingText());
        title.getDocument().addDocumentListener(watch(this::edited));page.getDocument().addDocumentListener(watch(this::edited));
        bind("control B",()->showTree(!side.isVisible()));
        bind("control N",()->save(this::newNote));bind("control S",()->save(null));bind("control F",()->search.requestFocusInWindow());
        javax.swing.undo.UndoManager undo=new javax.swing.undo.UndoManager();page.getDocument().addUndoableEditListener(e->{if(!drawing)undo.addEdit(e.getEdit());});
        page.putClientProperty("undo",undo);bind("control Z",()->{if(page.isEditable()&&undo.canUndo())undo.undo();});bind("control Y",()->{if(page.isEditable()&&undo.canRedo())undo.redo();});
    }    JButton button(String text,Runnable action){return DesktopUi.button(text,action);}
    private void bind(String key,Runnable action){frame.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key),key);frame.getRootPane().getActionMap().put(key,new AbstractAction(){public void actionPerformed(ActionEvent e){action.run();}});}
    private static DocumentListener watch(Runnable action){return new DocumentListener(){public void insertUpdate(DocumentEvent e){action.run();}public void removeUpdate(DocumentEvent e){action.run();}public void changedUpdate(DocumentEvent e){action.run();}};}
    private void edited(){if(drawing||base==null)return;dirty=true;due.remove(base.id);status.setText("Saving…");syncDetails.setText("Saving…");autosave.restart();}
    void save(Runnable next) {
        if(next!=null)afterSave=next;
        autosave.stop();if(saving)return;
        if(!dirty||base==null){Runnable go=afterSave;afterSave=null;if(go!=null)go.run();return;}
        saving=true;String body=page.getText(),name=title.getText();NoteStore.Note seen=base.copy();
        disk.submit(()->DesktopEdits.save(store,seen,name,body),saved->{
            saving=false;String now=page.getText(),nowTitle=title.getText();
            boolean changed=!now.equals(body)||!nowTitle.equals(name);
            drawing=true;int caret=page.getCaretPosition();
            String merged=changed?Merge.merge(body,now,saved.body).text:saved.body;
            String mergedTitle=changed?Merge.merge(name,nowTitle,saved.title).text:saved.title;
            if(!page.getText().equals(merged)){page.setText(merged);((javax.swing.undo.UndoManager)page.getClientProperty("undo")).discardAllEdits();}
            if(!title.getText().equals(mergedTitle))title.setText(mergedTitle);
            page.setCaretPosition(Math.min(caret,page.getDocument().getLength()));drawing=false;
            base=saved;dirty=!page.getText().equals(saved.body)||!title.getText().equals(saved.title);
            status.setText(" ");refresh();
            if(dirty){save(null);return;}scheduleSync();
            Runnable go=afterSave;afterSave=null;if(go!=null)go.run();
        },error->{saving=false;afterSave=null;failed(error);});
    }
    private void display(NoteStore.Note note) {
        ((CardLayout)mainArea.getLayout()).show(mainArea,"page");
        syncDetails.setText(" ");
        selected=null;
        page.setEditable(false);title.setEditable(false);
        drawing=true;base=note;dirty=false;title.setText(note.title);page.setText(note.body);page.setCaretPosition(0);drawing=false;
        ((javax.swing.undo.UndoManager)page.getClientProperty("undo")).discardAllEdits();
        disk.submit(()->Boolean.TRUE.equals(store.readOnlyHere(note.id)[0]),readOnly->{if(base==null||!base.id.equals(note.id))return;title.setEditable(!readOnly);page.setEditable(!readOnly);updateStanding();},this::failed);
        page.requestFocusInWindow();
    }
    private void updateStanding() {
        if(base==null)return;String id=base.id,book=base.book;
        disk.submit(()->{
            String shelf=store.collectionName(store.collectionOfBook(book))+"  /  "+store.bookName(book);
            var state=SyncStatus.read(store,id);int files=store.filesOf(NoteStore.Branch.Kind.PAGE,id).size();SwingUtilities.invokeLater(()->{if(base!=null&&base.id.equals(id))clip.setText(files==0?"📎":"📎 "+files);});
            return new Object[]{shelf+(Boolean.TRUE.equals(store.readOnlyHere(id)[0])?"  ·  Read only":""),state};
        },text->{if(base!=null&&base.id.equals(id)){
            SyncStatus.State state=(SyncStatus.State)text[1];standing.setText((String)text[0]);
            syncDetails.setText(dirty||saving?"Saving…":state.brief("this PC"));
            syncDetails.setToolTipText("<html>"+state.detail("this PC").replace("\n","<br>")+"</html>");}},this::failed);
    }
    void open(String id){
        int request=++noteRequest;
        disk.submit(()->store.get(id),note->{
            if(request!=noteRequest||note==null)return;
            if(dirty||saving){if(afterSave==null)save(()->open(id));return;}
            display(note);
        },this::failed);
    }
    private void newNote() {
        int request=++noteRequest;
        NoteStore.Branch picked=selected;String current=base==null?null:base.book;
        disk.submit(()->{
            NoteStore.Note note=new NoteStore.Note();
            note.book=picked!=null&&picked.kind==NoteStore.Branch.Kind.BOOK?picked.id:current==null?store.someBook():current;
            if(Boolean.FALSE.equals(store.mayWriteIn(NoteStore.Branch.Kind.BOOK,note.book)))throw new IllegalStateException("This book is read only. Choose one of your books first.");
            store.save(note);return note;
        },note->{if(request!=noteRequest)return;if(dirty||saving){if(afterSave==null)save(()->open(note.id));return;}display(note);refresh();},this::failed);
    }
    void refresh(){search("");updateStanding();}
    private void search(String term) {
        int request=++treeRequest;
        disk.submit(()->term.isBlank()?store.wholeTree():store.looking(term),rows->{
            if(request!=treeRequest)return;
            if(term.isBlank())everything=rows;
            drawing=true;DefaultMutableTreeNode root=new DefaultMutableTreeNode("All collections");Map<String,DefaultMutableTreeNode> nodes=new HashMap<>();
            nodes.put(Sharing.EVERYTHING,root);
            for(NoteStore.Branch branch:rows) {
                if(branch.kind==NoteStore.Branch.Kind.LIBRARY)continue;
                DefaultMutableTreeNode node=new DefaultMutableTreeNode(new Item(branch));nodes.put(branch.id,node);
                nodes.getOrDefault(branch.parent,root).add(node);
            }
            tree.setModel(new DefaultTreeModel(root));for(int i=0;i<tree.getRowCount();i++)tree.expandRow(i);drawing=false;
        },this::failed);
    }
    /** A round of one of the phone's eight colours. */
    static Icon swatch(int colour,int size) {
        Color c=new Color(Tint.of(colour,false),true);
        return new Icon(){
            public int getIconWidth(){return size;}public int getIconHeight(){return size;}
            public void paintIcon(Component x,Graphics g0,int left,int top){Graphics2D g=(Graphics2D)g0.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setColor(c);g.fillOval(left,top,size,size);g.dispose();}
        };
    }
    private static final String[] COLOUR_NAMES={"No colour","Red","Orange","Yellow","Green","Teal","Blue","Purple","Pink"};
    /** What can be done to one thing in the tree, where it is. */
    private void shelfMenu(MouseEvent e) {
        TreePath path=tree.getPathForLocation(e.getX(),e.getY());if(path==null)return;
        tree.setSelectionPath(path);
        if(!(((DefaultMutableTreeNode)path.getLastPathComponent()).getUserObject() instanceof Item item))return;
        shelfMenu(item.branch,tree,e.getX(),e.getY());
    }
    private void shelfMenu(NoteStore.Branch branch,Component where,int x,int y) {
        selected=branch;
        JPopupMenu menu=new JPopupMenu();
        JMenuItem rename=new JMenuItem(branch.kind==NoteStore.Branch.Kind.PAGE?"Rename…":"Rename…");rename.setAccelerator(KeyStroke.getKeyStroke("F2"));rename.addActionListener(a->rename(branch));menu.add(rename);
        JMenu colours=new JMenu("Colour");
        for(int c=0;c<Tint.count();c++){final int colour=c;JMenuItem one=new JMenuItem(COLOUR_NAMES[Math.min(c,COLOUR_NAMES.length-1)],c==Tint.NONE?null:swatch(c,12));
            if(c==branch.colour||c==Tint.NONE&&!Tint.known(branch.colour))one.setFont(one.getFont().deriveFont(Font.BOLD));
            one.addActionListener(a->disk.submit(()->{store.paint(branch.kind,branch.id,colour);return null;},done->refresh(),this::failed));colours.add(one);}
        menu.add(colours);
        menu.addSeparator();
        JMenuItem share=new JMenuItem("Share…");share.addActionListener(a->save(this::share));menu.add(share);
        menu.show(where,x,y);
    }
    /** What a collection or a book holds, as cards; the top of everything for the library. */
    void showShelf(NoteStore.Branch here) {
        List<NoteStore.Branch> path=new ArrayList<>();
        if(here.kind!=NoteStore.Branch.Kind.LIBRARY) {
            path.add(0,library());
            for(NoteStore.Branch up=find(here.parent);up!=null;up=find(up.parent))path.add(1,up);
        }
        NoteStore.Branch shown=here.kind==NoteStore.Branch.Kind.LIBRARY?new NoteStore.Branch(NoteStore.Branch.Kind.LIBRARY,Sharing.EVERYTHING,"","All collections","",0,0,true):here;
        shelves.show(shown,path,everything);
        ((CardLayout)mainArea.getLayout()).show(mainArea,"shelf");
    }
    /** A card or a step of the trail, chosen: a note opens; anything else shows its cards. */
    private void openBranch(NoteStore.Branch branch) {
        selected=branch;
        if(branch.kind==NoteStore.Branch.Kind.PAGE)save(()->open(branch.id));else save(()->showShelf(branch));
    }
    private NoteStore.Branch find(String id) {
        if(id==null)return null;for(NoteStore.Branch one:everything)if(one.id.equals(id))return one;return null;
    }
    /** A new name for a collection or a book; a note's name is its title, typed where it is. */
    private void rename(NoteStore.Branch branch) {
        if(branch.kind==NoteStore.Branch.Kind.PAGE){if(base!=null&&base.id.equals(branch.id)){title.requestFocusInWindow();title.selectAll();}else{save(()->open(branch.id));}return;}
        if(branch.kind!=NoteStore.Branch.Kind.COLLECTION&&branch.kind!=NoteStore.Branch.Kind.BOOK)return;
        String name=DesktopUi.ask(frame,branch.kind==NoteStore.Branch.Kind.COLLECTION?"Rename collection":"Rename book","Name",branch.name);
        if(name==null||name.isBlank()||name.trim().equals(branch.name))return;
        disk.submit(()->{if(branch.kind==NoteStore.Branch.Kind.COLLECTION)store.renameCollection(branch.id,name.trim());else store.renameBook(branch.id,name.trim());return null;},done->{status.setText("Renamed to "+name.trim());refresh();},this::failed);
    }
    private record Item(NoteStore.Branch branch){public String toString(){return branch.name+(branch.waiting>0?"  ↑":branch.shared>0?"  ✓":"");}}
    void menu(JButton anchor) {
        JPopupMenu menu=new JPopupMenu();
        item(menu,"New collection",()->save(()->addShelf(true)));item(menu,"New book",()->save(()->addShelf(false)));
        item(menu,"From another device…",()->scanCode(null));item(menu,"People…",()->people(null));menu.addSeparator();
        item(menu,"Versions…",()->save(this::versions));item(menu,"Attach a file…",()->save(this::attach));item(menu,"Attachments…",this::attachments);
        item(menu,"Move note to bin",()->save(()->putAway(true)));item(menu,"Archive note",()->save(()->putAway(false)));
        item(menu,"Bin…",()->restore(true));item(menu,"Archive…",()->restore(false));menu.addSeparator();
        item(menu,"Export backup…",()->save(this::backup));item(menu,"Add from backup…",()->save(this::importBackup));
        // A plain item saying what it will do: a tick box alone in the menu set it apart from every other line.
        boolean treeShown=side.isVisible();item(menu,treeShown?"Hide the tree":"Show the tree",()->showTree(!treeShown));menu.addSeparator();
        item(menu,"Share Mininotes…",this::shareApp);
        item(menu,"Profile…",this::profile);item(menu,"Security…",()->DesktopLock.settings(this));item(menu,"About",this::about);item(menu,"Updates…",()->lookForUpdate(true));
        item(menu,"Exit Mininotes",()->save(()->shutdown(true)));
        menu.show(anchor,0,anchor.getHeight());
    }
    /** The badge in the bar, said again whenever the lock changes. */
    /** The tree beside the page, or not: without it, the cards are the way round, as on the phone. */
    /** A chevron: pointing left to fold the side panel away, right to bring it back. */
    private Icon arrowIcon() {
        return new Icon(){
            public int getIconWidth(){return 12;}public int getIconHeight(){return 16;}
            public void paintIcon(Component c,Graphics g0,int x,int y) {
                Graphics2D g=(Graphics2D)g0.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(QUIET);g.setStroke(new BasicStroke(2f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
                boolean open=side==null||side.isVisible();
                if(open)g.drawPolyline(new int[]{x+8,x+3,x+8},new int[]{y+3,y+8,y+13},3);
                else g.drawPolyline(new int[]{x+4,x+9,x+4},new int[]{y+3,y+8,y+13},3);
                g.dispose();
            }
        };
    }
    /** A small window with its left pane: filled while the side panel is shown, empty when it is folded away. */
    private Icon panelIcon() {
        return new Icon(){
            public int getIconWidth(){return 18;}public int getIconHeight(){return 16;}
            public void paintIcon(Component c,Graphics g0,int x,int y) {
                Graphics2D g=(Graphics2D)g0.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(INK);g.setStroke(new BasicStroke(1.6f));g.drawRoundRect(x+1,y+1,16,14,4,4);g.drawLine(x+7,y+1,x+7,y+15);
                if(side!=null&&side.isVisible()){g.setColor(DesktopUi.mix(INK,Color.WHITE,0.55f));g.fillRect(x+2,y+2,5,12);}
                g.dispose();
            }
        };
    }
    void showTree(boolean shown) {
        side.setVisible(shown);split.setDividerSize(shown?1:0);if(shown)split.setDividerLocation(280);
        split.revalidate();sideToggle.repaint();
        sideToggle.setToolTipText(shown?"Hide the side panel (Ctrl+B)":"Show the side panel (Ctrl+B)");
        sideToggle.getAccessibleContext().setAccessibleName(sideToggle.getToolTipText());
        disk.submit(()->{context.getSharedPreferences("settings",0).edit().putString("tree",Boolean.toString(shown)).apply();return null;},done->{},this::failed);
    }
    static final String DOWNLOAD="https://github.com/mininotesorg/mininotes/releases/latest";
    /** Three lines to send with the link: what it is, what it is for, where to get it. The same words as the phone's. */
    static final String INVITE="I use Mininotes to keep notes and lists with the people close to me: a private paper pad, sealed from phone to phone, nothing to sign up for.\nAndroid: open the link and install the .apk file.\n";

    /** Mininotes, handed on: a code for a phone's camera, and the three lines with the link to paste anywhere. */
    void shareApp() {
        JPanel body=DesktopUi.column();
        DesktopUi.add(body,DesktopUi.note("Somebody with their phone here can scan the code with its camera. For anybody else, copy the message and send it.",400,DesktopUi.INK,DesktopUi.BODY));
        DesktopUi.gap(body,DesktopUi.M);
        try {
            JLabel code=new JLabel(new ImageIcon(DesktopQr.draw(DOWNLOAD,220)));JPanel centred=new JPanel(new GridBagLayout());centred.setOpaque(false);centred.add(DesktopUi.card(null,code));
            DesktopUi.add(body,centred);DesktopUi.gap(body,DesktopUi.M);
        } catch(Exception noCode){/* the message is enough */}
        DesktopUi.Text message=DesktopUi.note(INVITE+DOWNLOAD,400,DesktopUi.QUIET,DesktopUi.BODY.deriveFont(13f));
        DesktopUi.add(body,message);DesktopUi.gap(body,DesktopUi.S);
        DesktopUi.Text copied=DesktopUi.quiet(" ");
        DesktopUi.add(body,DesktopUi.actions(DesktopUi.primary("Copy the message",()->{Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(INVITE+DOWNLOAD),null);copied.setText("Copied. Paste it into a message or an email.");})));
        DesktopUi.gap(body,4);DesktopUi.add(body,copied);
        DesktopUi.tell(frame,"Share Mininotes",body);
    }

    // ---- a newer version --------------------------------------------------------------------------------------

    /** A newer version already downloaded and checked, waiting beside this app to take its place; or null. */
    private Path staged;private String stagedVersion="";
    private boolean fetching;

    /** Whether new versions are fetched by themselves. On unless it was switched off. */
    boolean autoUpdate(){return !"false".equals(context.getSharedPreferences("settings",0).getString("autoUpdate","true"));}

    /**
     * The version in the bar, and beside it - only when there is something to do - one filled button, as an
     * editor shows it: "Restart to update" once a newer version is downloaded, "Update" while it is only known.
     */
    void updateShown() {
        boolean behind=Update.newer(newestKnown,VERSION);
        version.setFont(DesktopUi.BODY.deriveFont(13f));version.setForeground(QUIET);version.setText("v"+VERSION);
        version.setToolTipText("Mininotes for Windows v"+VERSION+". Click for updates.");
        version.getAccessibleContext().setAccessibleName(version.getToolTipText());
        updateButton.setText(staged!=null?"Restart to update":"Update");updateButton.setVisible(staged!=null||behind);
        updateButton.setToolTipText(staged!=null?"Mininotes v"+stagedVersion+" is downloaded and checked. Restart to use it."
            :"Mininotes v"+newestKnown+" is out. Click to get it.");
        updateButton.getAccessibleContext().setAccessibleName(updateButton.getToolTipText());
    }

    /**
     * Whether a newer version is out: once a day by itself, or now when asked. What was last found is kept,
     * so the bar says so from the moment Mininotes opens. Found by itself, and updating by itself is on, it
     * is downloaded straight away and waits for a restart.
     */
    void lookForUpdate(boolean asked) {
        var kept=context.getSharedPreferences("settings",0);
        newestKnown=Update.read(kept.getString("updateNewest",""));updateShown();
        long looked;try{looked=Long.parseLong(kept.getString("updateLooked","0"));}catch(NumberFormatException e){looked=0;}
        if(!asked&&!Update.due(System.currentTimeMillis(),looked)){if(Update.newer(newestKnown,VERSION)&&autoUpdate())fetchQuietly(newestKnown);return;}
        if(asked){version.setText("Checking…");version.setForeground(ACCENT);}
        connectivity.submit(DesktopUpdate::latest,newest->{
            kept.edit().putString("updateNewest",newest).putString("updateLooked",Long.toString(System.currentTimeMillis())).apply();
            newestKnown=newest;updateShown();
            if(Update.newer(newest,VERSION)&&autoUpdate()&&!asked)fetchQuietly(newest);
            if(asked)updatesBox(null);
        },e->{updateShown();if(asked)updatesBox(e.getMessage());});
    }

    /** Downloaded and checked in the background; the bar then offers the restart. Failing is quiet: it is tried again. */
    private void fetchQuietly(String newest) {
        Path app=DesktopUpdate.appFolder();
        if(app==null||fetching||(staged!=null&&stagedVersion.equals(newest)))return;
        fetching=true;
        connectivity.submit(()->DesktopUpdate.fetch(newest,app,p->{}),opened->{fetching=false;staged=opened;stagedVersion=newest;updateShown();
            status.setText("Mininotes v"+newest+" is ready. Restart to update, or it is put in place when you close Mininotes.");},
            e->fetching=false);
    }

    /** The newer version put in place of this one, and Mininotes opened again on it. */
    private void restartToUpdate() {
        Path app=DesktopUpdate.appFolder();
        if(app==null||staged==null)return;
        try{DesktopUpdate.replaceAfterExit(app,staged);staged=null;save(()->shutdown(true));}
        catch(Exception e){failed(e);}
    }

    /**
     * Everything about updates in one box, opened from the version: which version this is, whether a newer
     * one is out - or why that could not be known - the one thing to do about it, and the switch for doing it
     * by itself.
     */
    void updatesBox(String problem) {
        Path app=DesktopUpdate.appFolder();
        boolean behind=Update.newer(newestKnown,VERSION);
        JDialog[] box={null};
        JPanel body=DesktopUi.column();
        String said=staged!=null?"Mininotes v"+stagedVersion+" is downloaded and checked. Restart to use it; your notes stay as they are."
            :problem!=null?problem
            :behind?"Mininotes v"+newestKnown+" is available. You have v"+VERSION+"."
            :"You have the newest version, v"+VERSION+".";
        DesktopUi.add(body,DesktopUi.note(said,400,problem!=null?DesktopUi.WARN:DesktopUi.INK,DesktopUi.BODY));
        if(behind&&staged==null&&app==null){DesktopUi.gap(body,DesktopUi.S);DesktopUi.add(body,DesktopUi.note("This copy is not the packaged app, so it cannot replace itself: get the new version from the releases page.",400,DesktopUi.QUIET,DesktopUi.BODY.deriveFont(13f)));}
        DesktopUi.gap(body,DesktopUi.M);
        DesktopUi.Text progress=DesktopUi.quiet(" ");DesktopUi.add(body,progress);
        JCheckBox auto=DesktopUi.toggle("Update automatically",autoUpdate());
        auto.addActionListener(e->{context.getSharedPreferences("settings",0).edit().putString("autoUpdate",Boolean.toString(auto.isSelected())).apply();
            if(auto.isSelected()&&Update.newer(newestKnown,VERSION))fetchQuietly(newestKnown);});
        JPanel card=DesktopUi.column();card.add(DesktopUi.switchRow("Update automatically",auto));
        DesktopUi.add(card,DesktopUi.note("New versions are downloaded from the Mininotes releases on GitHub, checked, and put in place when you restart or close Mininotes.",380,DesktopUi.QUIET,DesktopUi.BODY.deriveFont(12.5f)));
        DesktopUi.add(body,DesktopUi.card(null,card));
        JButton go=null;
        if(staged!=null)go=DesktopUi.primary("Restart to update",()->{box[0].dispose();restartToUpdate();});
        else if(behind&&app==null)go=DesktopUi.primary("Open the releases page",()->{try{java.awt.Desktop.getDesktop().browse(java.net.URI.create(DesktopUpdate.RELEASES));}catch(Exception e){failed(e);}box[0].dispose();});
        else if(behind) {
            JButton[] self={null};
            go=DesktopUi.primary("Update now",()->{
                self[0].setEnabled(false);progress.setForeground(DesktopUi.QUIET);progress.setText("Downloading…");
                String newest=newestKnown;
                new SwingWorker<Path,Integer>(){
                    protected Path doInBackground() throws Exception{return DesktopUpdate.fetch(newest,app,p->publish(p));}
                    protected void process(java.util.List<Integer> got){int p=got.get(got.size()-1);progress.setText(p<0?"Downloading…":"Downloading… "+p+"%");}
                    protected void done(){
                        try{staged=get();stagedVersion=newest;updateShown();progress.setText("Checked. Mininotes closes and opens again with v"+newest+"…");box[0].dispose();restartToUpdate();}
                        catch(Exception e){Throwable why=e.getCause()!=null?e.getCause():e;progress.setForeground(DesktopUi.WARN);
                            progress.setText(why.getMessage()!=null?why.getMessage():"The update could not be installed. This version is unchanged.");self[0].setEnabled(true);}
                    }
                }.execute();
            });
            self[0]=go;
        } else if(problem!=null)go=DesktopUi.button("Try again",()->{box[0].dispose();lookForUpdate(true);});
        box[0]=DesktopUi.sheet(frame,"Updates",body,go==null?null:DesktopUi.footer(go),true);
        if(go!=null)box[0].getRootPane().setDefaultButton(go);
        DesktopUi.show(box[0],460,460);
    }

    /** Kept for the gallery and anything that already knows a newer version: the same box. */
    void updateBox(String newest){newestKnown=newest;updateShown();updatesBox(null);}

    /** Closing with a newer version waiting: it is put in place as Mininotes goes, and not opened. */
    void updateOnExit() {
        Path app=DesktopUpdate.appFolder();
        if(app==null||staged==null)return;
        try{DesktopUpdate.replaceAfterExit(app,staged,ProcessHandle.current().pid(),false);staged=null;}catch(Exception e){/* the next start downloads it again */}
    }

    void securityShown() {
        boolean on=DesktopLock.locked(context.getFilesDir().toPath());
        security.setIcon(DesktopLock.padlock(on,15));security.setText(on?"Encrypted":"Not encrypted");
        security.setForeground(on?ACCENT:QUIET);security.setToolTipText(on?"Your notes on this PC are encrypted with a password. Click for Security.":"Your notes on this PC are not encrypted. Click to lock them with a password.");
        security.getAccessibleContext().setAccessibleName(security.getText());
    }
    void about() {
        JPanel body=DesktopUi.column();
        JLabel brand=new JLabel("Mininotes",new ImageIcon(DesktopIcon.image(48)),SwingConstants.LEFT);brand.setIconTextGap(14);brand.setFont(DesktopUi.BODY.deriveFont(Font.BOLD,20f));brand.setForeground(INK);
        DesktopUi.add(body,brand);DesktopUi.gap(body,DesktopUi.S);DesktopUi.add(body,DesktopUi.quiet("Windows v"+VERSION+" · preview"));DesktopUi.gap(body,DesktopUi.M);
        DesktopUi.Text words=DesktopUi.note("A paper pad, shared over Maxima, the communication layer of Minima.\n\n"+(DesktopLock.locked(context.getFilesDir().toPath())?"Notes on this PC are encrypted with your password.":"Notes on this PC are not encrypted. Security, in the bar at the top, can lock them with a password.")+" Attachments stay on the device they were added on; backups include them. Listening from the system tray is in Profile.");
        DesktopUi.add(body,words);
        DesktopUi.tell(frame,"About",body);
    }
    private void item(JPopupMenu menu,String label,Runnable go){JMenuItem item=new JMenuItem(label);item.addActionListener(e->go.run());menu.add(item);}
    private void addShelf(boolean collection) {
        String name=DesktopUi.ask(frame,collection?"New collection":"New book",collection?"Name":"Name of the book",null);if(name==null||name.isBlank())return;
        String parent=selected!=null&&selected.kind==NoteStore.Branch.Kind.COLLECTION?selected.id:null;
        disk.submit(()->{if(collection)store.addCollection(name.trim());else store.addBook(parent==null?store.collectionOfBook(base.book):parent,name.trim());return null;},done->refresh(),this::failed);
    }
    private void putAway(boolean bin) {
        if(base==null)return;String id=base.id;
        disk.submit(()->{store.putAway(NoteStore.Branch.Kind.PAGE,id,bin,true);return store.latest();},latest->{if(latest!=null)display(latest);else newNote();refresh();},this::failed);
    }
    void restore(boolean bin) {
        disk.submit(()->store.heldIn(bin),rows->{
            if(rows.isEmpty()){status.setText(bin?"The bin is empty":"The archive is empty");return;}
            NoteStore.Branch chosen=DesktopUi.pick(frame,bin?"Bin":"Archive","Put something back where it was.",rows,
                row->row.name+"   ·   "+(row.kind==NoteStore.Branch.Kind.PAGE?"note":row.kind==NoteStore.Branch.Kind.BOOK?"book":"collection"),"Put back");
            if(chosen!=null){Item item=new Item(chosen);disk.submit(()->{store.restore(item.branch.kind,item.branch.id);return null;},done->{status.setText("Put back");refresh();},this::failed);}
        },this::failed);
    }
    void versions() {
        if(base==null)return;String id=base.id;
        disk.submit(()->store.versions(id),all->{
            if(all.isEmpty()){status.setText("No earlier versions yet");return;}
            // Each version by when it was kept and who it came from; what it said, in full, beside it.
            java.time.format.DateTimeFormatter when=java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm:ss");
            String[] labels=all.stream().map(v->when.format(java.time.Instant.ofEpochMilli(v.at).atZone(java.time.ZoneId.systemDefault()))).toArray(String[]::new);
            JList<String> list=new JList<>(labels);list.setSelectedIndex(0);list.setFixedCellHeight(36);list.setBackground(DesktopUi.CARD);list.setCellRenderer(DesktopUi.roomy());
            JTextArea preview=new JTextArea(16,40);preview.setLineWrap(true);preview.setWrapStyleWord(true);preview.setEditable(false);preview.setText(all.get(0).body);preview.setCaretPosition(0);
            preview.setFont(DesktopUi.BODY.deriveFont(15f));preview.setBackground(DesktopUi.CARD);preview.setMargin(new Insets(12,14,12,14));
            list.addListSelectionListener(e->{if(list.getSelectedIndex()>=0){preview.setText(all.get(list.getSelectedIndex()).body);preview.setCaretPosition(0);}});
            JScrollPane left=new JScrollPane(list);left.setBorder(BorderFactory.createLineBorder(DesktopUi.LINE));left.setPreferredSize(new Dimension(210,360));
            JScrollPane right=new JScrollPane(preview);right.setBorder(BorderFactory.createLineBorder(DesktopUi.LINE));
            JPanel content=new JPanel(new BorderLayout(12,0));content.setOpaque(false);content.add(left,BorderLayout.WEST);content.add(right);
            JDialog[] box={null};boolean[] restore={false};
            JButton back=DesktopUi.primary("Put this version back",()->{restore[0]=true;box[0].dispose();});back.setEnabled(page.isEditable());
            box[0]=DesktopUi.sheet(frame,"Versions",content,DesktopUi.footer(back),true);
            DesktopUi.show(box[0],760,640);
            if(restore[0]&&page.isEditable()){var old=all.get(list.getSelectedIndex());title.setText(old.title);page.setText(old.body);save(null);status.setText("Version put back. The text it replaced is kept as a version.");}
        },this::failed);
    }
    void backup() {
        JFileChooser pick=new JFileChooser();pick.setSelectedFile(new java.io.File(context.databaseKey()!=null?"mininotes-backup-locked.mnbackup":"mininotes-backup.zip"));
        if(pick.showSaveDialog(frame)!=JFileChooser.APPROVE_OPTION)return;Path target=pick.getSelectedFile().toPath();
        if(Files.exists(target)&&!DesktopUi.confirm(frame,"Replace backup?","A file called "+target.getFileName()+" is already there. Replace it with a new backup?","Replace",true))return;
        status.setText("Writing backup…");disk.submit(()->{
            byte[] key=context.databaseKey();Path lock=context.getFilesDir().toPath().resolve(DesktopLock.KEPT);
            DesktopBackup.write(store,target,key,key==null?null:Files.readAllBytes(lock));return key!=null;
        },locked->status.setText(locked?"Backup saved, locked with your password":"Backup saved"),this::failed);
    }
    void importBackup() {
        JFileChooser pick=new JFileChooser();if(pick.showOpenDialog(frame)!=JFileChooser.APPROVE_OPTION)return;
        Path source=pick.getSelectedFile().toPath();byte[][] opener={null};
        // A locked backup is opened here, on this thread, before any work starts: its password or its words.
        if(DesktopBackup.locked(source)) {
            try {
                byte[] lock;try(var in=new DataInputStream(new BufferedInputStream(Files.newInputStream(source)))){in.readFully(new byte[4]);lock=new byte[in.readUnsignedShort()];in.readFully(lock);}
                opener[0]=DesktopLock.openBackup(frame,lock);if(opener[0]==null)return;
            } catch(Exception e){failed(e);return;}
        }
        status.setText("Adding notes from backup…");disk.submit(()->{
            int count=DesktopBackup.add(store,source,lock->opener[0]);
            byte[] opened=context.databaseKey();if(opened!=null)DesktopFiles.every(store,opened,true);
            return count;
        },count->{status.setText("Added "+count+" notes");refresh();},this::failed);
    }
    private void attach() {
        if(base==null||!page.isEditable())return;JFileChooser pick=new JFileChooser();if(pick.showOpenDialog(frame)!=JFileChooser.APPROVE_OPTION)return;
        String id=base.id;Path source=pick.getSelectedFile().toPath();status.setText("Keeping attachment…");
        disk.submit(()->{
            long size=Files.size(source);if(size>Attachment.LIMIT||store.weight()+size>Attachment.PLENTY)throw new IllegalArgumentException("That file is too large");
            String key=UUID.randomUUID().toString();Path dest=store.fileFor(key).toPath();DesktopFiles.keep(context,source,dest);
            try{store.keep(new NoteStore.Held(key,id,Attachment.named(source.getFileName().toString()),Attachment.kind(Files.probeContentType(source)),size,System.currentTimeMillis()));}
            catch(Exception failure){Files.deleteIfExists(dest);throw failure;}return null;
        },done->{status.setText("Attachment saved on this PC");updateStanding();},this::failed);
    }
    /** What the paperclip offers: to attach something, and each file already attached. */
    private void clipMenu() {
        if(base==null)return;String id=base.id;
        disk.submit(()->store.filesOf(NoteStore.Branch.Kind.PAGE,id),files->{
            JPopupMenu menu=new JPopupMenu();
            JMenuItem add=new JMenuItem("Attach a file…");add.setEnabled(page.isEditable());add.addActionListener(e->save(this::attach));menu.add(add);
            if(!files.isEmpty())menu.addSeparator();
            for(NoteStore.Held file:files){JMenuItem one=new JMenuItem(file.name+"   ·   "+Attachment.size(file.bytes));one.setToolTipText("Save a copy");one.addActionListener(e->saveCopy(file));menu.add(one);}
            menu.show(clip,clip.getWidth()-menu.getPreferredSize().width,-menu.getPreferredSize().height-4);
        },this::failed);
    }
    private void saveCopy(NoteStore.Held file) {
        JFileChooser pick=new JFileChooser();pick.setSelectedFile(new java.io.File(file.name));
        if(pick.showSaveDialog(frame)!=JFileChooser.APPROVE_OPTION)return;Path target=pick.getSelectedFile().toPath();
        if(Files.exists(target)&&!DesktopUi.confirm(frame,"Replace file?","A file called "+target.getFileName()+" is already there. Replace it?","Replace",true))return;
        disk.submit(()->{ByteArrayOutputStream bytes=new ByteArrayOutputStream();DesktopFiles.copyOut(context,store.fileFor(file.id).toPath(),bytes);org.mininotes.desktop.platform.AtomicFile.write(target,bytes.toByteArray());return null;},done->status.setText("Copy saved"),this::failed);
    }
    void attachments() {
        if(base==null)return;String id=base.id;
        disk.submit(()->store.filesOf(NoteStore.Branch.Kind.PAGE,id),files->{
            if(files.isEmpty()){status.setText("No attachments on this note");return;}
            NoteStore.Held file=DesktopUi.pick(frame,"Attachments","Kept on this PC with this note. Save a copy to open it elsewhere.",files,
                f->f.name+"   ·   "+Attachment.size(f.bytes),"Save a copy…");
            if(file==null)return;JFileChooser pick=new JFileChooser();pick.setSelectedFile(new java.io.File(file.name));
            if(pick.showSaveDialog(frame)!=JFileChooser.APPROVE_OPTION)return;Path target=pick.getSelectedFile().toPath();
            if(Files.exists(target)&&!DesktopUi.confirm(frame,"Replace file?","A file called "+target.getFileName()+" is already there. Replace it?","Replace",true))return;
            disk.submit(()->{ByteArrayOutputStream bytes=new ByteArrayOutputStream();DesktopFiles.copyOut(context,store.fileFor(file.id).toPath(),bytes);org.mininotes.desktop.platform.AtomicFile.write(target,bytes.toByteArray());return null;},done->status.setText("Copy saved"),this::failed);
        },this::failed);
    }
    private void profile() {
        DesktopProfile.open(this);
    }
    void failed(Exception error){status.setText(error.getMessage()==null?"That did not finish. Please try again.":error.getMessage());status.setToolTipText(status.getText());}

    // Network work never queues in front of a keystroke being committed to disk.
    void startNode() {
        if(offline||nodeStarting||closing)return;nodeStarting=true;
        connection.setText("Connecting…");
        network.submit(()->{
            store.mySigningKey=Base64.getEncoder().encodeToString(keys.signing().getPublic().getEncoded());store.myName=Node.nameHere(context);
            if(!Node.listen(context,bytes->disk.submit(()->Post.arrived(context,store,keys,bytes),this::arrived,this::failed)))throw new IllegalStateException("Could not start sharing");
            List<String> addresses=Node.addresses(context);String address=addresses.isEmpty()?"":addresses.get(0);store.myAddress=address;
            Node.everyBeat(()->{if(!closing){Post.again(context,store,keys);Post.leftAgain(context,store,keys);Post.removedAgain(context,store,keys);SwingUtilities.invokeLater(this::queuePending);}});
            return address;
        },address->{nodeStarting=false;currentAddress=address;connection.setText(address.isEmpty()?"No relay — writing works offline":"Connected");queuePending();
            network.submit(()->{Node.tellEverybody(context);retryAccepting();return null;},done->{},e->{});
            // Whatever arrived while the notebook was locked again, taken in now it is open.
            disk.submit(()->DesktopFiles.takeIn(context,store,keys),landed->{if(landed>0)refresh();},this::failed);
        },e->{nodeStarting=false;connection.setText("Offline");failed(e);});
        Object previous=frame.getRootPane().getClientProperty("health");if(previous instanceof javax.swing.Timer old)old.stop();
        javax.swing.Timer health=new javax.swing.Timer(15000,e->{if(!closing)network.submit(Node::attached,count->{if(!closing)connection.setText(count>0?"Connected":"Offline — retrying");},error->{});});health.start();frame.getRootPane().putClientProperty("health",health);
    }
    private String address() throws Exception {
        List<String> all=Node.addresses(context);if(all.isEmpty())throw new IllegalStateException("No relay connection yet. Try sharing again in a moment.");
        currentAddress=all.get(0);store.myAddress=currentAddress;return currentAddress;
    }
    private void retryAccepting() throws Exception {
        String mine=address();for(NoteStore.Accepting pending:store.waitingToAccept())try{Post.sayAgain(context,store,keys,pending,Node.nameHere(context),mine);store.triedAgain(pending.address);}catch(Exception unavailable){/* Kept for next start. */}
    }
    private void scheduleSync() {
        if(offline||base==null)return;String id=base.id;
        disk.submit(()->store.pauseFor(NoteStore.Branch.Kind.PAGE,id),seconds->{if(seconds>=0)due.put(id,System.currentTimeMillis()+Math.max(1,seconds)*1000L);},this::failed);
    }
    private void sendDue() {
        if(offline||closing)return;
        List<String> ready=new ArrayList<>();long now=System.currentTimeMillis();
        due.forEach((id,at)->{if(at<=now)ready.add(id);});
        for(String id:ready){due.remove(id);network.submit(()->{
            if(store.pauseFor(NoteStore.Branch.Kind.PAGE,id)<0)return new Post.Done(0,0,"");
            return Post.send(context,store,keys,NoteStore.Branch.Kind.PAGE,id);
        },done->{if(done.failed>0)due.putIfAbsent(id,System.currentTimeMillis()+60000);refresh();},e->{due.putIfAbsent(id,System.currentTimeMillis()+60000);failed(e);});}
    }
    private void queuePending() {
        if(closing||offline)return;
        disk.submit(()->{
            Map<String,Integer> waiting=new HashMap<>();
            for(Outbox.Wait one:store.owed(NoteStore.Branch.Kind.LIBRARY,Sharing.EVERYTHING)) {
                int seconds=store.pauseFor(NoteStore.Branch.Kind.PAGE,one.page);if(seconds>=0)waiting.put(one.page,seconds);
            }return waiting;
        },waiting->{if(closing)return;waiting.forEach((id,seconds)->{if(!(dirty&&base!=null&&base.id.equals(id)))due.putIfAbsent(id,System.currentTimeMillis()+Math.max(1,seconds)*1000L);});},this::failed);
    }
    private void sync(boolean explicit) {
        if(offline){if(explicit)status.setText("This session was started offline");return;}
        if(explicit)status.setText("Syncing…");
        NoteStore.Branch picked=explicit&&base!=null?target():null;
        NoteStore.Branch.Kind kind=picked!=null?picked.kind:NoteStore.Branch.Kind.LIBRARY;
        String id=picked!=null?picked.id:Sharing.EVERYTHING;
        if(kind!=NoteStore.Branch.Kind.COLLECTION&&kind!=NoteStore.Branch.Kind.BOOK&&kind!=NoteStore.Branch.Kind.PAGE){kind=NoteStore.Branch.Kind.LIBRARY;id=Sharing.EVERYTHING;}
        final NoteStore.Branch.Kind targetKind=kind;final String target=id;
        network.submit(()->{if(explicit)Post.ask(context,store,keys,targetKind,target);return Post.send(context,store,keys,targetKind,target,null,explicit);},done->{
            refresh();if(explicit)status.setText(done.failed>0?"Some notes could not be sent. They remain waiting.":done.sent>0?"Sent — waiting for delivery confirmation":"No outgoing changes. Asked for updates.");
        },this::failed);
    }
    private void arrived(Post.Landed landed) {
        if(!frame.isVisible()&&tray!=null&&landed.said!=null)tray.displayMessage("Mininotes",landed.said,TrayIcon.MessageType.INFO);
        if(landed.accepted!=null){accepted(landed.accepted);return;}
        if(landed.said!=null)status.setText(landed.said);refresh();
        if(base!=null&&base.id.equals(landed.note)) {
            // Saving merges against the row just received. Never redraw over unsaved text.
            if(dirty||saving)save(()->open(landed.note));else open(landed.note);
        }
    }
    private NoteStore.Branch target() {
        if(selected!=null&&(selected.kind==NoteStore.Branch.Kind.LIBRARY||selected.kind==NoteStore.Branch.Kind.COLLECTION||selected.kind==NoteStore.Branch.Kind.BOOK||selected.kind==NoteStore.Branch.Kind.PAGE))return selected;
        return new NoteStore.Branch(NoteStore.Branch.Kind.PAGE,base.id,base.book,base.title.isBlank()?"this note":base.title,"",0,0,false);
    }
    static NoteStore.Branch library(){return new NoteStore.Branch(NoteStore.Branch.Kind.LIBRARY,Sharing.EVERYTHING,"","all notes on this PC","",0,0,true);}
    void share() {
        if(base==null)return;NoteStore.Branch target=target();Sharing.Scope scope=Sharing.Scope.valueOf(target.kind.name());
        disk.submit(()->{
            var rules=new ArrayList<Sharing.Rule>();String book=target.kind==NoteStore.Branch.Kind.PAGE?store.bookOf(target.id):target.kind==NoteStore.Branch.Kind.BOOK?target.id:"";
            String collection=target.kind==NoteStore.Branch.Kind.COLLECTION?target.id:store.collectionOfBook(book);
            for(Sharing.Rule rule:store.shares())if(rule.scope==Sharing.Scope.LIBRARY||rule.scope==scope&&rule.target.equals(target.id)
                ||rule.scope==Sharing.Scope.BOOK&&rule.target.equals(book)||rule.scope==Sharing.Scope.COLLECTION&&rule.target.equals(collection))rules.add(rule);
            // Names looked up now, so each person is drawn once, as themselves.
            Map<String,String> names=new HashMap<>();for(Sharing.Rule rule:rules){NoteStore.Contact who=store.address(rule.address);names.put(rule.address,who==null?"Paired device":who.name);}
            return new Object[]{rules,store.myLevel(scope,target.id),store.cameFrom(target.kind,target.id),store.pauseFor(target.kind,target.id),names};
        },data->{
            @SuppressWarnings("unchecked") List<Sharing.Rule> rules=(List<Sharing.Rule>)data[0];
            boolean owner=((String)data[2]).isEmpty(),admin=owner||data[1]==Sharing.Level.ADMIN;
            JDialog[] box={null};
            // Who has access: you first, then everybody else with their role beside them, as Drive does.
            JPanel people=DesktopUi.column();
            people.add(DesktopUi.row(DesktopUi.person(Node.nameHere(context)+" (you)",null),DesktopUi.quiet(owner?"Owner":((Sharing.Level)data[1]).words())));
            for(Sharing.Rule rule:rules) {
                boolean inherited=rule.scope!=scope||!rule.target.equals(target.id);
                // Addresses are never shown in place of a person's name.
                @SuppressWarnings("unchecked") Map<String,String> named=(Map<String,String>)data[4];
                JPanel who=DesktopUi.person(named.getOrDefault(rule.address,"Paired device"),inherited?"Through the "+rule.scope.name().toLowerCase(Locale.ROOT)+" it is in":null);
                JComboBox<String> role=new JComboBox<>(new String[]{"Can read","Can write","Admin","Remove"});role.setSelectedIndex(Math.max(0,rule.level.ordinal()-1));role.setEnabled(admin&&!inherited);
                role.setPreferredSize(new Dimension(130,role.getPreferredSize().height));
                if(inherited)role.setToolTipText("Change this where it was shared");
                role.addActionListener(e->{int choice=role.getSelectedIndex();status.setText("Updating access…");disk.submit(()->{
                    if(!store.saysWhoHas(rule.scope,rule.target))throw new IllegalStateException("Only an owner or admin can change access");
                    if(choice==3)store.removeShare(rule);else store.setLevel(rule.scope,rule.target,rule.address,Sharing.Level.values()[choice+1],null);return null;
                },done->{box[0].dispose();network.submit(()->{Post.changed(context,store,keys,target.kind,target.id);Post.removedAgain(context,store,keys);return null;},v->{status.setText("Access updated");refresh();},this::failed);},this::failed);});
                JPanel right=new JPanel(new GridBagLayout());right.setOpaque(false);right.add(role);
                people.add(DesktopUi.row(who,right));
            }
            JButton add=button("Add someone…",()->{box[0].dispose();people(target);});add.setEnabled(admin);
            JButton code=button("Show my code…",()->{box[0].dispose();showCode(target);});code.setEnabled(admin);
            if(!admin)people.add(DesktopUi.quiet("Only the owner or an admin can add people."));
            DesktopUi.gap(people,DesktopUi.S);DesktopUi.add(people,DesktopUi.actions(add,code));

            // Syncing: when changes go, and whether this PC takes what arrives.
            JComboBox<String> delay=new JComboBox<>(new String[]{"After 3 seconds","After 10 seconds","After 30 seconds","After 2 minutes","When I ask"});
            int[] waits={3,10,30,120,-1};int current=(int)data[3];for(int i=0;i<waits.length;i++)if(waits[i]==current)delay.setSelectedIndex(i);
            delay.setEnabled(scope!=Sharing.Scope.LIBRARY);
            delay.addActionListener(e->{int seconds=waits[delay.getSelectedIndex()];disk.submit(()->{store.setPause(target.kind,target.id,seconds);return null;},done->{if(seconds<0)due.remove(target.id);},this::failed);});
            JCheckBox receiving=DesktopUi.toggle("Receive changes",true);receiving.setEnabled(scope!=Sharing.Scope.LIBRARY);
            disk.submit(()->store.pausedHere(target.kind,target.id),paused->receiving.setSelected(!paused),this::failed);
            receiving.addActionListener(e->{boolean pause=!receiving.isSelected();disk.submit(()->{
                if(pause)for(String peer:store.everybodyIn(target.kind,target.id))store.refuse(peer,target.id,target.kind);
                else store.resume(target.kind,target.id);return null;
            },done->refresh(),this::failed);});
            JPanel wait=new JPanel(new GridBagLayout());wait.setOpaque(false);wait.add(delay);
            JPanel syncing=DesktopUi.column();
            syncing.add(DesktopUi.row(DesktopUi.body("Send my changes"),wait));
            syncing.add(DesktopUi.switchRow("Receive their changes",receiving));

            JPanel body=DesktopUi.column();
            DesktopUi.add(body,DesktopUi.card("Who has access",people));DesktopUi.gap(body,12);DesktopUi.add(body,DesktopUi.card("Syncing",syncing));
            JButton leave=owner?null:DesktopUi.danger("Unfollow…",()->{
                if(!DesktopUi.confirm(box[0],"Unfollow?","Stop receiving "+target.name+"? Your copy stays on this PC.","Unfollow",true))return;
                box[0].dispose();status.setText("Unfollowing…");network.submit(()->{Post.leave(context,store,keys,target.kind,target.id);return null;},done->{status.setText("Unfollowed — your copy stays here");refresh();},this::failed);
            });
            JPanel foot=new JPanel(new BorderLayout());foot.setOpaque(false);
            if(leave!=null)foot.add(DesktopUi.actions(leave),BorderLayout.WEST);
            foot.add(DesktopUi.footer(button("Sync now",()->{box[0].dispose();sync(true);})),BorderLayout.EAST);
            box[0]=DesktopUi.sheet(frame,"Share “"+target.name+"”",DesktopUi.scrolling(body),foot,true);
            DesktopUi.show(box[0],520,720);
        },this::failed);
    }
    void showCode(NoteStore.Branch target) {
        if(offline){status.setText("This session was started offline");return;}
        int role=DesktopUi.choose(frame,"Share “"+target.name+"”","What may the other device do with it?",new String[]{"Read and write","Read only"},0,"Show my code");if(role<0)return;
        Sharing.Scope scope=Sharing.Scope.valueOf(target.kind.name());status.setText("Preparing sharing code…");
        network.submit(()->keys.line(Node.nameHere(context),address(),Sharing.travelling(scope,target.name),role==0,scope.name(),target.id),line->{
            offers.put(scope.name()+":"+target.id,role==0);offeredAt.put(scope.name()+":"+target.id,System.currentTimeMillis());status.setText("Scan this code with Mininotes on your phone");
            try {
                BitMatrix matrix=new MultiFormatWriter().encode(Pairing.link(line),BarcodeFormat.QR_CODE,340,340,Map.of(EncodeHintType.MARGIN,2));
                BufferedImage image=new BufferedImage(matrix.getWidth(),matrix.getHeight(),BufferedImage.TYPE_INT_RGB);
                for(int y=0;y<matrix.getHeight();y++)for(int x=0;x<matrix.getWidth();x++)image.setRGB(x,y,matrix.get(x,y)?0xff17261b:0xffffffff);
                JLabel picture=new JLabel(new ImageIcon(image));picture.setAlignmentX(Component.CENTER_ALIGNMENT);
                JPanel body=DesktopUi.column();
                DesktopUi.Text how=DesktopUi.note("On the phone, open Mininotes and scan this code. They will get "+(role==0?"read and write":"read-only")+" access to "+target.name+" once you approve.");
                DesktopUi.add(body,how);DesktopUi.gap(body,DesktopUi.M);
                JPanel framed=new JPanel(new GridBagLayout());framed.setOpaque(false);framed.add(DesktopUi.card(null,picture));DesktopUi.add(body,framed);
                DesktopUi.Text copied=DesktopUi.quiet(" ");
                JButton link=button("Copy link",()->{Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(Pairing.link(line)),null);copied.setText("Link copied. Send it to them another way if they cannot scan.");});
                DesktopUi.gap(body,DesktopUi.M);DesktopUi.add(body,DesktopUi.actions(link));DesktopUi.gap(body,DesktopUi.S);DesktopUi.add(body,copied);
                DesktopUi.tell(frame,"Share “"+target.name+"”",body);
            } catch(Exception e){failed(e);}
        },this::failed);
    }    void scanCode(NoteStore.Branch shareTarget) {
        if(offline){status.setText("This session was started offline");return;}
        DesktopScanner.open(frame,text->receiveCode(text,shareTarget));
    }
    private void receiveCode(DesktopScanner.Code code,NoteStore.Branch shareTarget) {
        try {
            Pairing.Said said=Pairing.read(Pairing.line(code.text().trim()));
            disk.submit(()->code.camera()?"":Envelope.code(keys.signing().getPublic(),Keys.publicKey(said.signing)),digits->{
            JPanel body=DesktopUi.column();
            DesktopUi.add(body,DesktopUi.person(said.name,said.offer.isEmpty()?"Wants to pair with this PC":said.offer+"  ·  "+(said.writes?"Read and write":"Read only")));
            if(!digits.isEmpty()) {
                // A code read from a picture or a link, not a camera: the two devices compare digits first.
                DesktopUi.gap(body,DesktopUi.M);DesktopUi.add(body,DesktopUi.note("Check that the other device shows the same code. Accept only if it matches."));
                DesktopUi.gap(body,DesktopUi.S);JLabel shown=new JLabel(digits);shown.setFont(DesktopUi.BODY.deriveFont(Font.BOLD,28f));shown.setForeground(INK);DesktopUi.add(body,shown);
            }
            boolean[] yes={false};JDialog[] box={null};
            JButton accept=DesktopUi.primary(digits.isEmpty()?"Accept":"The codes match — accept",()->{yes[0]=true;box[0].dispose();});
            box[0]=DesktopUi.sheet(frame,said.offer.isEmpty()?"Pair with "+said.name+"?":"Accept from "+said.name+"?",body,DesktopUi.footer(accept),true);
            DesktopUi.show(box[0],440,480);if(!yes[0])return;
            status.setText("Pairing with "+said.name+"…");network.submit(()->{
                store.pairedWith(said.address,said.name,true,said.agreement,said.signing);
                try{String key=Node.introduce(context,said.address);if(!key.isEmpty())store.knownAs(said.address,key);}catch(Exception unreachable){/* The code's address remains usable. */}
                // A plain code too: the device that showed it has to hear about this PC, or it drops as a
                // stranger's everything this PC shares with it. Kept, and said again at each start until answered.
                store.accepting(said.address,said.name,said.scope,said.target,said.writes);
                try{Post.accept(context,keys,said,Node.nameHere(context),address());}catch(Exception notNow){return false;}
                return true;
            },told->{status.setText(said.target.isEmpty()?(told?"Paired. "+said.name+" is asked to pair back":"Paired. "+said.name+" is told when it is next reachable"):"Paired — waiting for "+said.name+" to approve and send");refresh();if(shareTarget!=null)people(shareTarget);},e->{status.setText("Pairing has not finished. Reopen the app to retry, or try the link again.");});
            },this::failed);
        } catch(Exception e){failed(e);}
    }
    private void accepted(Hello.Said said) {
        if(said.target.isEmpty()){pairBack(said);return;}
        String key=said.scope+":"+said.target;Boolean writes=offers.get(key);
        // A reply cannot upgrade its offer, refer to an unoffered item, or bypass consent.
        if(writes==null||said.writes&&!writes||!pendingOffers.add(key+said.address))return;
        if(!frame.isVisible()){frame.setVisible(true);Node.near(true);frame.toFront();}
        // Showing the code was the decision: scanned within a quarter of an hour, it is handed over without asking again.
        Long shown=offeredAt.get(key);long age=shown==null?Long.MAX_VALUE:System.currentTimeMillis()-shown;
        boolean give=age>=0&&age<15*60_000L||DesktopUi.confirm(frame,said.name+" scanned your code",said.name+" accepted what you offered. Give them "+(writes?"read and write":"read-only")+" access?","Give access",false);
        pendingOffers.remove(key+said.address);if(!give)return;
        Sharing.Scope scope=Sharing.Scope.valueOf(said.scope);NoteStore.Branch.Kind kind=NoteStore.Branch.Kind.valueOf(said.scope);
        status.setText("Sharing with "+said.name+"…");network.submit(()->{
            if(!store.saysWhoHas(scope,said.target))throw new IllegalStateException("Only an owner or admin can share this");
            store.pairedWith(said.address,said.name,false,said.agreement,said.signing);
            try{String contact=Node.introduce(context,said.address);if(!contact.isEmpty())store.knownAs(said.address,contact);}catch(Exception unreachable){/* Fallback to the current address. */}
            store.addShare(new Sharing.Rule(scope,said.target,said.address,writes));
            return Post.send(context,store,keys,kind,said.target);
        },done->{status.setText(done.failed>0?"Shared — waiting to send":"Sent — waiting for delivery confirmation");refresh();},this::failed);
    }
    /** Somebody scanned this PC's code with nothing offered: asked, then saved and answered. */
    private void pairBack(Hello.Said said) {
        if(!pendingOffers.add("pair:"+said.address))return;
        // Somebody scanned the code this PC showed: showing it was the decision, so they are paired back without a question.
        pendingOffers.remove("pair:"+said.address);
        status.setText("Pairing with "+said.name+"…");network.submit(()->{
            store.pairedWith(said.address,said.name,false,said.agreement,said.signing);
            try{String contact=Node.introduce(context,said.address);if(!contact.isEmpty())store.knownAs(said.address,contact);}catch(Exception unreachable){/* the address they sent still works */}
            NoteStore.Contact saved=store.address(said.address);
            try{Post.helloBack(context,keys,said,Node.nameHere(context),address(),saved==null?null:saved.contact);}catch(Exception notNow){/* they say hello again until they hear */}
            return null;
        },done->{status.setText(said.name+" paired with this PC");if(tray!=null&&!frame.isVisible())tray.displayMessage("Mininotes",said.name+" paired with this PC",TrayIcon.MessageType.INFO);refresh();},this::failed);
    }
    /** When somebody last typed or clicked in Mininotes; the notebook locks again after the time chosen in Security. */
    private volatile long lastUse=System.currentTimeMillis();
    private final AWTEventListener using=e->lastUse=System.currentTimeMillis();
    private javax.swing.Timer idle;
    private boolean relocking;
    /** What closing the unlock window after a re-lock does: leaves Mininotes. Tests put something quieter in. */
    Runnable leave=()->System.exit(0);

    private void relockIfIdle() {
        if(closing||relocking)return;
        java.nio.file.Path folder=context.getFilesDir().toPath();
        int minutes=DesktopLock.minutes(context);
        if(minutes<=0||!DesktopLock.locked(folder))return;
        if(System.currentTimeMillis()-lastUse<minutes*60_000L)return;
        relock();
    }

    /**
     * Locked again: writing saved, the notebook closed and its key let go, and the password asked for as at
     * start. What arrives meanwhile waits sealed in the inbox, and is taken in when it is opened.
     */
    void relock() {
        relocking=true;
        java.nio.file.Path folder=context.getFilesDir().toPath();boolean wasOffline=offline;
        save(()->{
            if(!wasOffline)Node.listen(context,bytes->DesktopFiles.keepArriving(folder,bytes));
            shutdown(false,()->{
                try {
                    byte[] key=DesktopLock.askAtStart(folder);
                    if(key==null){leave.run();return;}
                    new Desktop(folder,wasOffline,key.length==0?null:key).show();
                } catch(Exception e){JOptionPane.showMessageDialog(null,e.getMessage(),"Mininotes could not open",JOptionPane.ERROR_MESSAGE);System.exit(1);}
            });
        });
    }

    void shutdown(boolean exit){shutdown(exit,null);}
    void shutdown(boolean exit,Runnable after) {
        closing=true;
        // A newer version downloaded and waiting takes this one's place as Mininotes goes.
        if(exit)updateOnExit();if(idle!=null)idle.stop();Toolkit.getDefaultToolkit().removeAWTEventListener(using);autosave.stop();syncLater.stop();Object timer=frame.getRootPane().getClientProperty("health");if(timer instanceof javax.swing.Timer health)health.stop();
        frame.setEnabled(false);status.setText("Closing…");
        Node.everyBeat(null);
        disk.submit(()->{if(!exit){store.close();instanceLock.release();instanceChannel.close();}return null;},done->{
            if(tray!=null)SystemTray.getSystemTray().remove(tray);
            for(Window owned:frame.getOwnedWindows())owned.dispose();frame.dispose();disk.abandon();network.abandon();connectivity.abandon();
            context.unlock(null);
            if(exit)System.exit(0);
            if(after!=null)SwingUtilities.invokeLater(after);
        },e->{closing=false;frame.setEnabled(true);failed(e);});
    }
    void people(NoteStore.Branch target) {
        disk.submit(store::addresses,contacts->{
            JDialog[] box={null};JPanel list=DesktopUi.column();
            if(contacts.isEmpty())DesktopUi.add(list,DesktopUi.note("No devices paired yet. Scan another device's code, or show them yours."));
            for(NoteStore.Contact contact:contacts) {
                JComponent right;
                if(target!=null) {
                    JButton give=button("Share…",()->{
                        int chosen=DesktopUi.choose(box[0],"Share “"+target.name+"”","What may "+contact.name+" do with it?",new String[]{"Can read","Can write","Admin"},1,"Share");if(chosen<0)return;
                        Sharing.Scope scope=Sharing.Scope.valueOf(target.kind.name());status.setText("Sharing with "+contact.name+"…");
                        disk.submit(()->{if(!store.saysWhoHas(scope,target.id))throw new IllegalStateException("Only an owner or admin can share this");store.setLevel(scope,target.id,contact.address,Sharing.Level.values()[chosen+1],null);return null;},done->{box[0].dispose();network.submit(()->Post.changed(context,store,keys,target.kind,target.id),sent->{status.setText("Access saved — waiting for delivery confirmation");refresh();},this::failed);},this::failed);
                    });give.setEnabled(contact.paired());right=give;
                }else {
                    JCheckBox mine=DesktopUi.toggle("My device",contact.mine);mine.addActionListener(e->{boolean yes=mine.isSelected();disk.submit(()->{store.setMine(contact.address,yes);return null;},done->{},this::failed);});
                    JButton address=button("Address",()->{JTextArea text=DesktopProfile.readonly(5);text.setText(contact.address);DesktopUi.tell(box[0],contact.name,text);});
                    JPanel both=DesktopUi.actions(DesktopUi.quiet("My device"),mine,address);right=both;
                }
                JPanel holder=new JPanel(new GridBagLayout());holder.setOpaque(false);holder.add(right);
                list.add(DesktopUi.row(DesktopUi.person(contact.name,contact.paired()?null:"Not paired yet"),holder));
            }
            JPanel body=DesktopUi.column();DesktopUi.add(body,DesktopUi.card(contacts.isEmpty()?null:"Paired devices",list));
            JPanel foot=new JPanel(new BorderLayout());foot.setOpaque(false);
            foot.add(DesktopUi.actions(button("Scan a code or paste a link…",()->{box[0].dispose();scanCode(target);}),target!=null?button("Show my code…",()->{box[0].dispose();showCode(target);}):null),BorderLayout.WEST);
            box[0]=DesktopUi.sheet(frame,target==null?"People and devices":"Add someone to “"+target.name+"”",DesktopUi.scrolling(body),foot,true);
            DesktopUi.show(box[0],600,640);
        },this::failed);
    }    void setTrayListening(boolean enabled,javax.swing.text.JTextComponent outcome) {
        if(enabled&&!ensureTray()){outcome.setText("Could not add Mininotes to the system tray.");return;}
        disk.submit(()->{context.getSharedPreferences("settings",0).edit().putString("listenInTray",Boolean.toString(enabled)).apply();return null;},done->{listenInTray=enabled;outcome.setText(enabled?"Closing the window keeps Mininotes listening in the tray.":"Closing the window exits Mininotes.");if(!enabled&&tray!=null){SystemTray.getSystemTray().remove(tray);tray=null;}},this::failed);
    }
    private boolean ensureTray() {
        if(offline||!SystemTray.isSupported())return false;if(tray!=null)return true;
        BufferedImage icon=DesktopIcon.image(32);
        PopupMenu menu=new PopupMenu();MenuItem open=new MenuItem("Open Mininotes"),quit=new MenuItem("Exit Mininotes");menu.add(open);menu.add(quit);
        Runnable reveal=()->{frame.setVisible(true);Node.near(true);frame.setState(Frame.NORMAL);frame.toFront();};open.addActionListener(e->SwingUtilities.invokeLater(reveal));quit.addActionListener(e->SwingUtilities.invokeLater(()->save(()->shutdown(true))));
        tray=new TrayIcon(icon,"Mininotes — listening",menu);tray.setImageAutoSize(true);tray.addActionListener(e->SwingUtilities.invokeLater(reveal));
        try{SystemTray.getSystemTray().add(tray);return true;}catch(AWTException e){tray=null;return false;}
    }
    /** Connected, connecting or not: a coloured dot says which before the words are read. */
    static final class Dot extends JLabel {
        Dot(String text){super();setText(text);}
        @Override public void setText(String text) {
            super.setText(text);
            Color colour=text==null?QUIET:text.startsWith("Connected")?new Color(64,145,94):text.startsWith("Connecting")||text.contains("retrying")?new Color(207,150,48):new Color(168,165,156);
            setIcon(new Icon(){
                public int getIconWidth(){return 8;}public int getIconHeight(){return 8;}
                public void paintIcon(Component c,Graphics g0,int x,int y){Graphics2D g=(Graphics2D)g0.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setColor(colour);g.fillOval(x,y,8,8);g.dispose();}
            });
        }
    }
    static final class Paper extends JTextArea {
        protected void paintComponent(Graphics original) {
            Graphics2D g=(Graphics2D)original.create();g.setColor(getBackground());g.fillRect(0,0,getWidth(),getHeight());
            int height=getFontMetrics(getFont()).getHeight();g.setColor(new Color(222,226,215));
            for(int y=getInsets().top+height;y<getHeight();y+=height)g.drawLine(28,y,getWidth()-22,y);
            g.setColor(new Color(221,192,179));g.drawLine(30,0,30,getHeight());g.dispose();
            setOpaque(false);super.paintComponent(original);
            ColourEmoji colour=ColourEmoji.get();if(colour!=null)colour.paint(this,original);
        }
    }
}

