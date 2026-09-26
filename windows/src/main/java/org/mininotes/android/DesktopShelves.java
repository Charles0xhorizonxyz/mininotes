package org.mininotes.android;

import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.swing.*;

/**
 * The shelves as cards, as on the phone: what one collection or book holds, each thing on a card of its own,
 * its colour along the top, what it holds or how it begins underneath. The tree beside it shows where
 * everything is; this shows what is here.
 */
final class DesktopShelves extends JPanel {
    static final int WIDE=210,HIGH=128,GAP=16;
    private final JPanel trail=new JPanel(new FlowLayout(FlowLayout.LEFT,0,0));
    private final DesktopUi.Text title=DesktopUi.title(" ");
    private final Grid grid=new Grid();
    private final Consumer<NoteStore.Branch> open;
    private final BiConsumer<NoteStore.Branch,MouseEvent> menu;

    DesktopShelves(Consumer<NoteStore.Branch> open,BiConsumer<NoteStore.Branch,MouseEvent> menu) {
        super(new BorderLayout());this.open=open;this.menu=menu;
        setBackground(DesktopUi.PAPER);
        JPanel top=DesktopUi.column();top.setBorder(BorderFactory.createEmptyBorder(22,42,12,38));
        trail.setOpaque(false);trail.setAlignmentX(LEFT_ALIGNMENT);DesktopUi.add(top,trail);DesktopUi.gap(top,6);title.setFont(DesktopUi.BODY.deriveFont(Font.BOLD,24f));DesktopUi.add(top,title);
        add(top,BorderLayout.NORTH);
        grid.setBorder(BorderFactory.createEmptyBorder(8,42,32,38));
        JScrollPane scroll=new JScrollPane(grid);scroll.setBorder(null);scroll.getViewport().setBackground(DesktopUi.PAPER);scroll.getVerticalScrollBar().setUnitIncrement(24);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        add(scroll);
    }

    /**
     * What {@code here} holds, from the whole tree as the notebook gave it. {@code path} is how one gets
     * there from the top, each step a link back.
     */
    void show(NoteStore.Branch here,List<NoteStore.Branch> path,List<NoteStore.Branch> tree) {
        trail.removeAll();
        for(int i=0;i<path.size();i++) {
            NoteStore.Branch step=path.get(i);
            JLabel link=new JLabel(step.kind==NoteStore.Branch.Kind.LIBRARY?"All collections":step.name);link.setFont(DesktopUi.BODY.deriveFont(13f));link.setForeground(DesktopUi.QUIET);
            link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            link.addMouseListener(new MouseAdapter(){public void mouseClicked(MouseEvent e){open.accept(step);}
                public void mouseEntered(MouseEvent e){link.setForeground(DesktopUi.ACCENT);}public void mouseExited(MouseEvent e){link.setForeground(DesktopUi.QUIET);}});
            trail.add(link);if(i==path.size()-1)break;
            JLabel sep=new JLabel("   /   ");sep.setFont(DesktopUi.BODY.deriveFont(13f));sep.setForeground(DesktopUi.LINE.darker());trail.add(sep);
        }
        if(path.isEmpty())trail.add(new JLabel(" "));
        title.setText(here.name);
        grid.removeAll();
        List<NoteStore.Branch> inside=new ArrayList<>();
        for(NoteStore.Branch one:tree)if(one.kind!=NoteStore.Branch.Kind.LIBRARY&&here.id.equals(one.parent))inside.add(one);
        if(inside.isEmpty()) {
            DesktopUi.Text empty=DesktopUi.note(here.kind==NoteStore.Branch.Kind.BOOK?"No notes in this book yet. New note, at the top, starts one here."
                :here.kind==NoteStore.Branch.Kind.COLLECTION?"No books in this collection yet.":"No collections yet.",360,DesktopUi.QUIET,DesktopUi.BODY);
            grid.add(empty);
        }
        for(NoteStore.Branch one:inside)grid.add(card(one));
        grid.revalidate();grid.repaint();revalidate();repaint();
    }

    private JComponent card(NoteStore.Branch one) {
        boolean page=one.kind==NoteStore.Branch.Kind.PAGE;
        Color band=Tint.known(one.colour)?new Color(Tint.of(one.colour,false),true):null;
        boolean[] over={false};
        JPanel card=new JPanel(new BorderLayout(0,6)){
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g=(Graphics2D)g0.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(DesktopUi.CARD);g.fillRoundRect(0,0,getWidth()-1,getHeight()-1,14,14);
                if(band!=null){g.setClip(new java.awt.geom.RoundRectangle2D.Float(0,0,getWidth()-1,getHeight()-1,14,14));g.setColor(band);g.fillRect(0,0,getWidth(),6);g.setClip(null);}
                g.setColor(over[0]?DesktopUi.ACCENT:DesktopUi.LINE);g.drawRoundRect(0,0,getWidth()-1,getHeight()-1,14,14);g.dispose();
            }
        };
        card.setOpaque(false);card.putClientProperty("ground",DesktopUi.CARD);card.setBorder(BorderFactory.createEmptyBorder(16,16,14,16));card.setPreferredSize(new Dimension(WIDE,HIGH));
        String mark=one.waiting>0?"  ↑":one.shared>0?"  ✓":"";
        DesktopUi.Text name=new DesktopUi.Text(one.name+mark,DesktopUi.BODY.deriveFont(Font.BOLD,15f),DesktopUi.INK,0);name.setFocusable(false);
        String said=one.detail==null?"":one.detail.trim();
        DesktopUi.Text under=new DesktopUi.Text(page?said:(said.isEmpty()?(one.kind==NoteStore.Branch.Kind.BOOK?"Book":"Collection"):said),
            DesktopUi.BODY.deriveFont(13f),DesktopUi.QUIET,WIDE-32);
        under.setFocusable(false);under.setRows(page?3:1);
        JLabel kind=new JLabel(page?"Note":one.kind==NoteStore.Branch.Kind.BOOK?"Book":"Collection");kind.setFont(DesktopUi.BODY.deriveFont(11.5f));kind.setForeground(DesktopUi.QUIET);
        card.add(name,BorderLayout.NORTH);card.add(under);card.add(kind,BorderLayout.SOUTH);
        card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        card.getAccessibleContext().setAccessibleName(kind.getText()+": "+one.name);card.setToolTipText(one.name);
        MouseAdapter hand=new MouseAdapter(){
            public void mouseClicked(MouseEvent e){if(SwingUtilities.isLeftMouseButton(e))open.accept(one);}
            public void mousePressed(MouseEvent e){if(e.isPopupTrigger())menu.accept(one,e);}
            public void mouseReleased(MouseEvent e){if(e.isPopupTrigger())menu.accept(one,e);}
            public void mouseEntered(MouseEvent e){over[0]=true;card.repaint();}
            public void mouseExited(MouseEvent e){over[0]=false;card.repaint();}
        };
        // The words on it take the same clicks as the card, so a card is one thing to point at.
        for(JComponent part:new JComponent[]{card,name,under,kind}){part.addMouseListener(hand);if(part!=card)part.setCursor(card.getCursor());}
        return card;
    }

    /** Cards in rows, as many to a row as the width allows, and as tall as the rows make it. */
    private static final class Grid extends JPanel implements Scrollable {
        Grid() {
            super(null);setOpaque(false);
            // Wider or narrower can mean a different number to a row, and so a different height.
            addComponentListener(new ComponentAdapter(){int last;public void componentResized(ComponentEvent e){int now=columns();if(now!=last){last=now;revalidate();}}});
        }
        private int columns(){int inner=Math.max(WIDE,getWidth()-getInsets().left-getInsets().right);return Math.max(1,(inner+GAP)/(WIDE+GAP));}
        @Override public void doLayout() {
            Insets in=getInsets();int cols=columns();int i=0;
            for(Component c:getComponents()) {
                if(!(c instanceof JPanel)){Dimension d=c.getPreferredSize();c.setBounds(in.left,in.top,d.width,d.height);continue;}
                c.setBounds(in.left+(i%cols)*(WIDE+GAP),in.top+(i/cols)*(HIGH+GAP),WIDE,HIGH);i++;
            }
        }
        @Override public Dimension getPreferredSize() {
            Insets in=getInsets();int n=0;for(Component c:getComponents())if(c instanceof JPanel)n++;
            int cols=columns(),rows=n==0?1:(n+cols-1)/cols;
            return new Dimension(in.left+in.right+cols*(WIDE+GAP),in.top+in.bottom+rows*(HIGH+GAP));
        }
        public Dimension getPreferredScrollableViewportSize(){return getPreferredSize();}
        public int getScrollableUnitIncrement(Rectangle r,int o,int d){return 24;}
        public int getScrollableBlockIncrement(Rectangle r,int o,int d){return Math.max(24,r.height-48);}
        public boolean getScrollableTracksViewportWidth(){return true;}
        public boolean getScrollableTracksViewportHeight(){return false;}
    }
}
