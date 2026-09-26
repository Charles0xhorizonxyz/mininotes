package org.mininotes.android;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import java.awt.*;
import java.awt.event.*;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import javax.swing.*;
import javax.swing.border.Border;

/**
 * One way to build every screen, so no screen has to decide spacing, type or buttons again.
 *
 * <p>Three text roles (title, body, quiet), one primary button per window, switches for on and off,
 * dropdowns for choices, and space on an eight-point grid. Nothing touches: every group sits inside
 * padding, and every row of buttons has a gap between them.
 */
final class DesktopUi {
    static final Color PAPER=Desktop.PAPER,INK=Desktop.INK,QUIET=Desktop.QUIET,ACCENT=Desktop.ACCENT;
    /** The side bar and bars around the paper: a shade under it. */
    static final Color SHELF=new Color(243,241,234);
    /** Cards: a shade above the paper. */
    static final Color CARD=new Color(255,255,252);
    static final Color LINE=new Color(226,223,213);
    static final Color WARN=new Color(166,68,52);
    static final int S=8,M=16,L=24;
    /**
     * Segoe UI with the system's fallbacks behind it - emoji included, through the font configuration the
     * app starts with - so a note written on a phone reads the same here, not as a row of boxes.
     */
    static final Font BODY=javax.swing.text.StyleContext.getDefaultStyleContext().getFont("Segoe UI",Font.PLAIN,14);
    private static boolean installed;

    private DesktopUi(){}

    /** The look, once, before anything is drawn. */
    static synchronized void install() {
        if(installed)return;installed=true;
        FlatLaf.setGlobalExtraDefaults(Map.of(
            "@accentColor","#306348","@background","#FAF9F4","@foreground","#2B302B",
            "@selectionBackground","#DCE8DF","@selectionForeground","#2B302B",
            "@selectionInactiveBackground","#E7ECE6","@selectionInactiveForeground","#2B302B"));
        FlatLightLaf.setup();
        UIManager.put("defaultFont",BODY);
        UIManager.put("Component.arc",10);UIManager.put("Button.arc",10);UIManager.put("TextComponent.arc",10);
        UIManager.put("CheckBox.arc",6);UIManager.put("ProgressBar.arc",10);
        UIManager.put("Component.focusWidth",1);UIManager.put("Component.innerFocusWidth",0);
        UIManager.put("Button.margin",new Insets(6,14,6,14));
        UIManager.put("Button.background",CARD);UIManager.put("Button.borderColor",LINE);
        UIManager.put("Button.hoverBackground",new Color(244,242,235));
        UIManager.put("TextField.margin",new Insets(6,10,6,10));UIManager.put("ComboBox.padding",new Insets(4,8,4,8));
        UIManager.put("Component.borderColor",LINE);UIManager.put("Component.disabledBorderColor",LINE);
        UIManager.put("ScrollBar.width",10);UIManager.put("ScrollBar.thumbArc",999);UIManager.put("ScrollBar.thumbInsets",new Insets(2,2,2,2));
        UIManager.put("ScrollBar.track",PAPER);UIManager.put("ScrollBar.showButtons",false);
        UIManager.put("Tree.rowHeight",30);UIManager.put("Tree.paintLines",false);UIManager.put("Tree.selectionArc",8);
        UIManager.put("Tree.selectionInsets",new Insets(0,4,0,4));UIManager.put("Tree.background",SHELF);
        UIManager.put("List.selectionArc",8);UIManager.put("List.selectionInsets",new Insets(0,4,0,4));
        UIManager.put("PopupMenu.borderCornerRadius",10);UIManager.put("MenuItem.selectionArc",6);
        UIManager.put("MenuItem.selectionInsets",new Insets(0,4,0,4));UIManager.put("MenuItem.margin",new Insets(6,12,6,12));
        // No menu here has icons, so no room is kept for one: the words start at the edge, not a thumb's width in.
        UIManager.put("MenuItem.minimumIconSize",new Dimension(0,0));UIManager.put("MenuItem.textIconGap",0);
        UIManager.put("CheckBoxMenuItem.minimumIconSize",new Dimension(0,0));
        UIManager.put("TitlePane.unifiedBackground",true);UIManager.put("TitlePane.background",PAPER);
        UIManager.put("SplitPaneDivider.style","plain");UIManager.put("SplitPane.dividerSize",1);
        UIManager.put("Separator.foreground",LINE);UIManager.put("ToolTip.background",CARD);
        UIManager.put("OptionPane.background",PAPER);UIManager.put("Panel.background",PAPER);
        // Every password field: an eye to show what was typed, and a sign when Caps Lock is on.
        UIManager.put("PasswordField.showRevealButton",true);UIManager.put("PasswordField.showCapsLock",true);
    }

    // ---- text ------------------------------------------------------------------------------------------

    static Text title(String text){return new Text(text,BODY.deriveFont(Font.BOLD,20f),INK,0);}
    /** A group's name: the heading inside a window. */
    static Text heading(String text){return new Text(text,BODY.deriveFont(Font.BOLD,15f),INK,0);}
    static Text body(String text){return new Text(text,BODY,INK,0);}
    static Text quiet(String text){return new Text(text,BODY.deriveFont(13f),QUIET,0);}
    /** Quiet text that wraps at a width, measured so it is never cut off or left on one line. */
    static Text note(String text){return note(text,360,QUIET,BODY.deriveFont(13f));}
    static Text note(String text,int width,Color colour,Font font){return new Text(text,font,colour,width);}

    /**
     * Words that can be selected and copied, like any text on a page - an address, a time, a sentence to
     * pass on - but not typed in, and passed over by Tab, which goes from one control to the next.
     */
    static final class Text extends JTextArea {
        private final int wrap;
        Text(String text,Font font,Color colour,int wrap) {
            super(text);this.wrap=wrap;
            setEditable(false);setOpaque(false);setBorder(null);setMargin(new Insets(0,0,0,0));
            setFont(font);setForeground(colour);setLineWrap(wrap>0);setWrapStyleWord(true);
            setSelectionColor(new Color(0xDC,0xE8,0xDF));setSelectedTextColor(INK);
            // No blinking caret in something that cannot be typed in; selecting still works.
            setCaret(new javax.swing.text.DefaultCaret(){
                @Override public void setVisible(boolean shown){super.setVisible(false);}
                {setUpdatePolicy(javax.swing.text.DefaultCaret.NEVER_UPDATE);}
            });
            getAccessibleContext().setAccessibleName(text);
        }
        @Override public void setText(String text){super.setText(text);if(getAccessibleContext()!=null)getAccessibleContext().setAccessibleName(text);}
        /** Emoji in colour, painted over what is behind this text: a card, a bar or the paper. */
        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            ColourEmoji colour=ColourEmoji.get();if(colour==null)return;
            Color behind=ground(this);Color was=getBackground();setBackground(behind);
            try{colour.paint(this,g);}finally{setBackground(was);}
        }
        @Override public Dimension getPreferredSize() {
            if(wrap<=0)return super.getPreferredSize();
            // Laid out at its width first, so the height is that of the lines it really wraps into.
            if(getWidth()!=wrap)setSize(wrap,Short.MAX_VALUE);
            return new Dimension(wrap,super.getPreferredSize().height);
        }
        @Override public Dimension getMaximumSize(){Dimension d=getPreferredSize();return wrap>0?d:new Dimension(Integer.MAX_VALUE,d.height);}
    }
    /** The colour actually behind a component: the nearest that says so, or the nearest that paints itself. */
    static Color ground(Component c) {
        for(Component up=c.getParent();up!=null;up=up.getParent()) {
            if(up instanceof JComponent j&&j.getClientProperty("ground") instanceof Color said)return said;
            if(up.isOpaque())return up.getBackground();
        }
        return PAPER;
    }
    /** Tab from control to control, not onto every sentence on the way. */
    static FocusTraversalPolicy skippingText() {
        return new LayoutFocusTraversalPolicy(){
            @Override protected boolean accept(Component c){return !(c instanceof Text)&&super.accept(c);}
        };
    }
    // ---- buttons ---------------------------------------------------------------------------------------

    static JButton button(String text,Runnable action){JButton b=new JButton(text);b.setFocusPainted(false);b.addActionListener(e->action.run());return b;}
    /** The one thing a window is for. Filled, and only ever one per window. */
    static JButton primary(String text,Runnable action) {
        JButton b=button(text,action);
        b.putClientProperty(FlatClientProperties.STYLE,"background:#306348;foreground:#FFFFFF;hoverBackground:#2A5840;pressedBackground:#224A35;"
            +"borderColor:#306348;hoverBorderColor:#2A5840;focusedBorderColor:#8FB59E;disabledBackground:#A9BCAE;font:bold");
        return b;
    }
    /** Something that cannot be taken back. */
    static JButton danger(String text,Runnable action) {
        JButton b=button(text,action);b.putClientProperty(FlatClientProperties.STYLE,"foreground:#A64434;borderColor:#E3C4BD");return b;
    }
    /** Buttons in a row, with space between them, starting at the left. */
    static JPanel actions(JComponent... items){return flow(FlowLayout.LEFT,items);}
    /** A window's closing row: to the right, the primary last, where the eye ends. */
    static JPanel footer(JComponent... items){return flow(FlowLayout.RIGHT,items);}
    private static JPanel flow(int align,JComponent... items) {
        JPanel p=new JPanel(new FlowLayout(align,S,0));p.setOpaque(false);
        // FlowLayout puts its gap before the first item too; this takes it back so rows line up with text.
        p.setBorder(BorderFactory.createEmptyBorder(0,align==FlowLayout.LEFT?-S:0,0,align==FlowLayout.RIGHT?-S:0));
        for(JComponent item:items)if(item!=null)p.add(item);
        return p;
    }

    // ---- on and off ------------------------------------------------------------------------------------

    /** A switch, as on the phone: a track and a thumb. Keyboard, focus and screen readers as a check box. */
    static JCheckBox toggle(String accessibleName,boolean on) {
        JCheckBox box=new JCheckBox("",on);box.setOpaque(false);box.setFocusPainted(false);
        box.setIcon(new SwitchIcon());box.setSelectedIcon(new SwitchIcon());box.setDisabledIcon(new SwitchIcon());box.setDisabledSelectedIcon(new SwitchIcon());
        box.getAccessibleContext().setAccessibleName(accessibleName);box.setToolTipText(null);
        box.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));return box;
    }
    private static final class SwitchIcon implements Icon {
        public int getIconWidth(){return 38;}
        public int getIconHeight(){return 22;}
        public void paintIcon(Component c,Graphics g0,int x,int y) {
            AbstractButton b=(AbstractButton)c;boolean on=b.isSelected(),enabled=b.isEnabled();
            Graphics2D g=(Graphics2D)g0.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            Color track=on?ACCENT:new Color(205,203,194);if(!enabled)track=mix(track,PAPER,0.55f);
            g.setColor(track);g.fillRoundRect(x,y,38,22,22,22);
            g.setColor(Color.WHITE);int knob=16,kx=on?x+38-3-knob:x+3;g.fillOval(kx,y+3,knob,knob);
            if(b.isFocusOwner()){g.setColor(mix(ACCENT,Color.WHITE,0.45f));g.setStroke(new BasicStroke(2f));g.drawRoundRect(x-1,y-1,39,23,24,24);}
            g.dispose();
        }
    }
    static Color mix(Color a,Color b,float t){return new Color(Math.round(a.getRed()+(b.getRed()-a.getRed())*t),Math.round(a.getGreen()+(b.getGreen()-a.getGreen())*t),Math.round(a.getBlue()+(b.getBlue()-a.getBlue())*t));}

    // ---- layout ----------------------------------------------------------------------------------------

    /** A column that lets each row be as wide as the column, and no taller than it needs. */
    static JPanel column() {
        JPanel p=new JPanel(){
            @Override public Dimension getMaximumSize(){return new Dimension(Integer.MAX_VALUE,getPreferredSize().height);}
        };p.setLayout(new BoxLayout(p,BoxLayout.Y_AXIS));p.setOpaque(false);return p;
    }
    static void add(JPanel column,JComponent item){item.setAlignmentX(Component.LEFT_ALIGNMENT);column.add(item);}
    static void gap(JPanel column,int size){column.add(Box.createVerticalStrut(size));}
    /** A thing on the left, and what it does or says on the right, sharing one line. */
    static JPanel row(JComponent left,JComponent right) {
        JPanel p=new JPanel(new BorderLayout(M,0)){
            @Override public Dimension getMaximumSize(){return new Dimension(Integer.MAX_VALUE,getPreferredSize().height);}
        };p.setOpaque(false);
        JPanel leftMiddle=new JPanel(new GridBagLayout());leftMiddle.setOpaque(false);
        GridBagConstraints at=new GridBagConstraints();at.anchor=GridBagConstraints.WEST;at.weightx=1;at.fill=GridBagConstraints.HORIZONTAL;leftMiddle.add(left,at);p.add(leftMiddle);
        // Whatever is on the right sits in the middle of the row's height, whether a word or a control.
        if(right!=null){JPanel middle=new JPanel(new GridBagLayout());middle.setOpaque(false);middle.add(right);p.add(middle,BorderLayout.EAST);}
        p.setBorder(BorderFactory.createEmptyBorder(6,0,6,0));p.setAlignmentX(Component.LEFT_ALIGNMENT);return p;
    }
    /** A label with its switch at the right; the label turns it too. */
    static JPanel switchRow(String label,JCheckBox toggle) {
        Text said=body(label);
        said.addMouseListener(new MouseAdapter(){public void mouseClicked(MouseEvent e){if(toggle.isEnabled())toggle.doClick();}});
        return row(said,toggle);
    }
    /** A round with a person's first letter: people are shown as people, never as addresses. */
    static JComponent avatar(String name) {
        String letter=name==null||name.isBlank()?"?":name.trim().substring(0,1).toUpperCase(java.util.Locale.ROOT);
        return new JComponent(){
            {setPreferredSize(new Dimension(32,32));setMinimumSize(getPreferredSize());}
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g=(Graphics2D)g0.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
                g.setColor(new Color(221,232,224));g.fillOval(0,0,32,32);g.setColor(ACCENT);g.setFont(BODY.deriveFont(Font.BOLD,14f));
                FontMetrics m=g.getFontMetrics();g.drawString(letter,(32-m.stringWidth(letter))/2,(32-m.getHeight())/2+m.getAscent());g.dispose();
            }
        };
    }
    /** A person: their round, their name, and a quiet line under it. */
    static JPanel person(String name,String under) {
        JPanel words=column();add(words,body(name));if(under!=null&&!under.isEmpty())add(words,quiet(under));
        JPanel p=new JPanel(new BorderLayout(12,0));p.setOpaque(false);JPanel round=new JPanel(new GridBagLayout());round.setOpaque(false);round.add(avatar(name));
        p.add(round,BorderLayout.WEST);JPanel middle=new JPanel(new GridBagLayout());middle.setOpaque(false);
        GridBagConstraints at=new GridBagConstraints();at.anchor=GridBagConstraints.WEST;at.weightx=1;at.fill=GridBagConstraints.HORIZONTAL;middle.add(words,at);p.add(middle);
        return p;
    }

    /** A group of things that belong together, on a card of its own. */
    static JPanel card(String heading,JComponent... items) {
        JPanel inside=column();
        if(heading!=null){add(inside,heading(heading));gap(inside,12);}
        for(JComponent item:items)if(item!=null)add(inside,item);
        JPanel card=new JPanel(new BorderLayout()){
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g=(Graphics2D)g0.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(CARD);g.fillRoundRect(0,0,getWidth()-1,getHeight()-1,14,14);
                g.setColor(LINE);g.drawRoundRect(0,0,getWidth()-1,getHeight()-1,14,14);g.dispose();
            }
            @Override public Dimension getMaximumSize(){return new Dimension(Integer.MAX_VALUE,getPreferredSize().height);}
        };
        card.setOpaque(false);card.putClientProperty("ground",CARD);card.setBorder(BorderFactory.createEmptyBorder(M,M+4,M,M+4));card.add(inside);card.setAlignmentX(Component.LEFT_ALIGNMENT);
        return card;
    }
    static Border padding(int top,int side,int bottom){return BorderFactory.createEmptyBorder(top,side,bottom,side);}

    // ---- windows ---------------------------------------------------------------------------------------

    /**
     * A window of the pad's own: its title at the top of the page, the body, and the closing row on a
     * bar of its own. Escape closes it. Pass the primary button as the last of the footer's items.
     */
    static JDialog sheet(Window owner,String title,JComponent body,JComponent footer,boolean modal) {
        JDialog dialog=new JDialog(owner,title,modal?Dialog.ModalityType.APPLICATION_MODAL:Dialog.ModalityType.MODELESS);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        JPanel page=new JPanel(new BorderLayout());page.setBackground(PAPER);
        JPanel top=new JPanel(new BorderLayout());top.setOpaque(false);top.setBorder(padding(L-4,L,M));top.add(title(title));page.add(top,BorderLayout.NORTH);
        JPanel middle=new JPanel(new BorderLayout());middle.setOpaque(false);middle.setBorder(padding(0,L,M));middle.add(body);page.add(middle);
        if(footer!=null) {
            JPanel bottom=new JPanel(new BorderLayout());bottom.setBackground(SHELF);
            bottom.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1,0,0,0,LINE),padding(12,L,12)));
            bottom.add(footer);page.add(bottom,BorderLayout.SOUTH);
        }
        dialog.setContentPane(page);dialog.setFocusTraversalPolicy(skippingText());
        // Opening puts the ring on the window's own button, not on whatever control happens to come first.
        dialog.addWindowListener(new WindowAdapter(){public void windowOpened(WindowEvent e){
            JButton main=dialog.getRootPane().getDefaultButton();
            JComponent wants=(JComponent)dialog.getRootPane().getClientProperty("focus");
            if(wants!=null)wants.requestFocusInWindow();else if(main!=null)main.requestFocusInWindow();else page.requestFocusInWindow();
        }});
        dialog.getRootPane().registerKeyboardAction(e->dialog.dispose(),KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE,0),JComponent.WHEN_IN_FOCUSED_WINDOW);
        return dialog;
    }
    /** Sized to what it holds, within a floor and a ceiling, over its owner. */
    static void show(JDialog dialog,int width,int maxHeight) {
        dialog.pack();
        dialog.setSize(Math.max(width,Math.min(dialog.getWidth(),width+160)),Math.min(maxHeight,dialog.getHeight()));
        dialog.setLocationRelativeTo(dialog.getOwner());
        if(!dialog.isModal()||!(dialog.getOwner() instanceof RootPaneContainer)){dialog.setVisible(true);return;}
        // Windows lets nothing reach a window behind a modal one, so a click beside the box could not close
        // it. So it is shown as an ordinary window over a shade that covers the one behind - the shade takes
        // that click - and this still waits here until it closes, as a modal window would.
        dialog.setModalityType(Dialog.ModalityType.MODELESS);
        SecondaryLoop waiting=Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
        dialog.addWindowListener(new WindowAdapter(){public void windowClosed(WindowEvent e){waiting.exit();}});
        shade(dialog);dialog.setVisible(true);
        if(dialog.isDisplayable())waiting.enter();
    }

    /**
     * A faint shade over the window behind a box: it says which window is in front, and a click on it
     * closes the box. It goes when the box goes. The window behind stays behind while the box is open.
     */
    static void shade(JDialog dialog) {
        if(!(dialog.getOwner() instanceof RootPaneContainer behind))return;
        Component before=behind.getGlassPane();
        JComponent shade=new JComponent(){
            @Override protected void paintComponent(Graphics g){g.setColor(new Color(20,24,20,38));g.fillRect(0,0,getWidth(),getHeight());}
        };
        MouseAdapter closes=new MouseAdapter(){public void mousePressed(MouseEvent e){dialog.dispose();}};
        shade.addMouseListener(closes);shade.addMouseMotionListener(new MouseMotionAdapter(){});shade.addMouseWheelListener(e->{});
        shade.setCursor(Cursor.getDefaultCursor());
        behind.setGlassPane(shade);shade.setVisible(true);
        WindowAdapter front=new WindowAdapter(){public void windowActivated(WindowEvent e){if(dialog.isShowing())dialog.toFront();}};
        ((Window)behind).addWindowListener(front);
        dialog.addWindowListener(new WindowAdapter(){public void windowClosed(WindowEvent e){
            shade.setVisible(false);behind.setGlassPane(before);before.setVisible(false);((Window)behind).removeWindowListener(front);
        }});
    }
    /** A body that scrolls if it must, without a frame drawn round it. */
    static JScrollPane scrolling(JComponent inside) {
        JPanel fits=new Fitting();fits.add(inside);fits.setBorder(BorderFactory.createEmptyBorder(0,0,0,4));
        JScrollPane s=new JScrollPane(fits);s.setBorder(null);s.setOpaque(false);s.getViewport().setOpaque(false);
        s.getVerticalScrollBar().setUnitIncrement(20);s.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);return s;
    }

    /** As wide as the window and no wider; as tall as it needs, scrolling beyond that. */
    private static final class Fitting extends JPanel implements Scrollable {
        Fitting(){super(new BorderLayout());setOpaque(false);}
        public Dimension getPreferredScrollableViewportSize(){return getPreferredSize();}
        public int getScrollableUnitIncrement(Rectangle r,int o,int d){return 20;}
        public int getScrollableBlockIncrement(Rectangle r,int o,int d){return Math.max(20,r.height-40);}
        public boolean getScrollableTracksViewportWidth(){return true;}
        public boolean getScrollableTracksViewportHeight(){return getParent() instanceof JViewport v&&v.getHeight()>getPreferredSize().height;}
    }
    /** Rows in a list with room around the words. */
    static ListCellRenderer<Object> roomy() {
        DefaultListCellRenderer r=new DefaultListCellRenderer(){
            public Component getListCellRendererComponent(JList<?> l,Object v,int i,boolean s,boolean f){super.getListCellRendererComponent(l,v,i,s,f);setBorder(BorderFactory.createEmptyBorder(0,12,0,12));return this;}
        };return r;
    }
    /** A question with one answer that does something. True if it was given. */
    static boolean confirm(Window owner,String title,String message,String yes,boolean dangerous) {
        boolean[] said={false};
        JDialog[] box={null};
        JButton go=dangerous?danger(yes,()->{said[0]=true;box[0].dispose();}):primary(yes,()->{said[0]=true;box[0].dispose();});
        Text words=note(message,360,INK,BODY);
        box[0]=sheet(owner,title,words,footer(go),true);
        box[0].getRootPane().setDefaultButton(go);show(box[0],440,400);return said[0];
    }
    /** A line of text, asked for. Null if they changed their mind. */
    static String ask(Window owner,String title,String label,String initial) {
        String[] said={null};JDialog[] box={null};
        JTextField field=new JTextField(initial==null?"":initial,28);
        Runnable done=()->{if(!field.getText().isBlank()){said[0]=field.getText().trim();box[0].dispose();}};
        JPanel body=column();add(body,body(label));gap(body,S);add(body,field);
        JButton go=primary(initial==null?"Create":"Rename",done);field.addActionListener(e->done.run());field.selectAll();
        box[0]=sheet(owner,title,body,footer(go),true);
        box[0].getRootPane().setDefaultButton(go);box[0].getRootPane().putClientProperty("focus",field);show(box[0],420,300);return said[0];
    }
    /** One of a few, from a dropdown. The index, or -1. */
    static int choose(Window owner,String title,String message,String[] options,int initial,String yes) {
        int[] said={-1};JDialog[] box={null};
        JComboBox<String> pick=new JComboBox<>(options);pick.setSelectedIndex(Math.max(0,initial));
        JPanel body=column();Text words=note(message,360,INK,BODY);
        add(body,words);gap(body,12);add(body,pick);
        JButton go=primary(yes,()->{said[0]=pick.getSelectedIndex();box[0].dispose();});
        box[0]=sheet(owner,title,body,footer(go),true);
        box[0].getRootPane().setDefaultButton(go);show(box[0],420,340);return said[0];
    }
    /** One item from a list. Null if none was taken. */
    static <T> T pick(Window owner,String title,String message,List<T> items,Function<T,String> label,String yes) {
        @SuppressWarnings("unchecked") T[] said=(T[])new Object[1];JDialog[] box={null};
        DefaultListModel<String> model=new DefaultListModel<>();for(T item:items)model.addElement(label.apply(item));
        JList<String> list=new JList<>(model);list.setSelectedIndex(0);list.setVisibleRowCount(Math.min(10,Math.max(4,items.size())));
        list.setFixedCellHeight(36);list.setBackground(CARD);list.setCellRenderer(roomy());
        JScrollPane scroll=new JScrollPane(list);scroll.setBorder(BorderFactory.createLineBorder(LINE));
        JPanel body=new JPanel(new BorderLayout(0,12));body.setOpaque(false);if(message!=null)body.add(quiet(message),BorderLayout.NORTH);body.add(scroll);
        Runnable take=()->{int i=list.getSelectedIndex();if(i>=0){said[0]=items.get(i);box[0].dispose();}};
        list.addMouseListener(new MouseAdapter(){public void mouseClicked(MouseEvent e){if(e.getClickCount()==2)take.run();}});
        JButton go=primary(yes,take);
        box[0]=sheet(owner,title,body,footer(go),true);
        box[0].getRootPane().setDefaultButton(go);show(box[0],460,560);return said[0];
    }
    /** Something to read, and one button to close it. */
    static void tell(Window owner,String title,JComponent body) {
        // Nothing to decide, so nothing to press: the cross, Escape, or a click beside it closes it.
        JDialog box=sheet(owner,title,body,null,true);show(box,420,720);
    }
}
