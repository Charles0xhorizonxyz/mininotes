// SPDX-License-Identifier: GPL-3.0-or-later
// Mininotes is free software: GNU General Public License, version 3 or later. See LICENSE.
package org.mininotes.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.text.InputFilter;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.URLSpan;
import android.text.util.Linkify;
import android.util.TypedValue;
import android.view.DragEvent;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.Gravity;
import android.view.View;
import android.view.TextureView;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A paper pad. It opens on the page you last wrote, ruled, with the cursor in it and the keyboard up.
 *
 * <p>The shelves go one level at a time — All collections, then a collection's books, then a book's pages —
 * with the trail across the top saying where you are, <b>+</b> adding whatever that level holds, and an
 * arrow on every line and on the level itself for the addresses it is shared with. Picking a line up turns
 * the screen into the places it could go, because a page and the book it would move to are never both on
 * screen; dropping it there, or tapping one, asks before any move changes who can read it.
 */
public final class MainActivity extends Activity {
    /**
     * Ten papers, from the brightest white through cream and kraft to a dark page, each a whole set rather
     * than a tint: the ink, the rules, the cards and the accent are chosen for that paper, so the pad reads
     * the same way on every rung. Row 3 is the paper the app has always had.
     */
    private static final int[][] PAPERS={
        // paper     ink       muted     rules     card      accent    warning   menu sheet
        {0xFFFFFFFF,0xFF1E2422,0xFF6E7674,0xFFE8E8E4,0xFFF3F3F0,0xFF1F6B4F,0xFF9C3A2A,0xFFFFFFFF},
        {0xFFFDFCFA,0xFF22302B,0xFF767F7B,0xFFE7E4DC,0xFFF2F1EC,0xFF24614A,0xFF973B2C,0xFFFEFEFD},
        {0xFFFBFAF6,0xFF273A34,0xFF7C8A84,0xFFE4DFD1,0xFFF2EFE5,0xFF285646,0xFF8C3B2E,0xFFFCFCFA},
        {0xFFF8F4E9,0xFF2A3A33,0xFF7D8880,0xFFDFD7C3,0xFFEEE8D8,0xFF2A5A46,0xFF8A3C2E,0xFFFAF7EF},
        {0xFFF2EBDB,0xFF2C3A32,0xFF7B857C,0xFFD4C9B1,0xFFE7DDC7,0xFF2D5B44,0xFF883D2F,0xFFF6F1E4},
        {0xFFE9E0CA,0xFF2E3A31,0xFF78826F,0xFFC9BC9F,0xFFDDD1B5,0xFF34604A,0xFF8A4030,0xFFEFE8D6},
        {0xFFDACEB3,0xFF2B342B,0xFF6E7865,0xFFB9A989,0xFFCDBD9D,0xFF38614C,0xFF8B4433,0xFFE2D9C3},
        {0xFF4A4A43,0xFFEFECE2,0xFFA9A99D,0xFF5C5C54,0xFF55554E,0xFF8FBCA6,0xFFD98A7A,0xFF55554E},
        {0xFF262B28,0xFFE6E5DE,0xFF9AA39B,0xFF3B413B,0xFF30352F,0xFF8FBCA6,0xFFD98A7A,0xFF30352F},
        {0xFF121413,0xFFE8E8E2,0xFF8D948C,0xFF272B28,0xFF1C1F1D,0xFF93C3A9,0xFFDC8E7E,0xFF1C1F1D},
    };
    /**
     * Two of these papers are used: a light one and a dark one. Which is in use is the phone's own setting,
     * the way every other app decides it — there is no scale for it, because a scale of greys is not a
     * choice anybody wants to make twice. The rest of the table stays: it is what the two are cut from.
     */
    private static final int FIRST_PAPER=2, DARK_PAPER=8;
    private int paper=FIRST_PAPER;
    /** Laid out across the screen as cards, or one under the other as a list. Remembered either way. */
    private boolean cards=true;
    private int PAPER,INK,MUTED,LINE,CARD,ACCENT,WARN,SHEET;
    private static final long SAVE_DELAY=500, FLUSH_TIMEOUT=2000;
    private static final int EXPORT=10, IMPORT=11, ATTACH=12, SAYING_SO=13, IMPORT_BYTES=10000000;
    /** The text of a backup, inside the zip that carries it and whatever the notes hold. */
    private static final String BACKUP_TEXT="mininotes-backup.json";
    private static final String READ_FAILED="Could not read your notes. Nothing was changed.";
    private static final String BACKUP_FAILED="Backup failed. Nothing was partly imported. Check the file and free space.";
    /**
     * Where to send something, for anybody who wants to. Empty until there is an address to put here; the
     * line is drawn either way, saying plainly that one is coming, rather than quietly not existing.
     */
    private static final String DONATE="MxG087BNANGRNYJAAKU73AHE0YSUE9NYQU1PJCFK4E5J6010W5U68ABKKGHS7AQ";
    /**
     * Where the source lives, and where a newer build would be announced. Empty until there is a repository:
     * while it is, nothing is fetched and the network is never touched.
     */
    private static final String SOURCE="https://github.com/mininotesorg/mininotes";
    /** The one file the update check reads: a line of text holding the newest version's name. */
    private static final String LATEST=SOURCE.isEmpty()?"":SOURCE.replace("github.com","raw.githubusercontent.com")
        +"/main/dist/latest.txt";
    /** Where a newer build is fetched from by whoever wants it. The app sends them there and no further. */
    private static final String DOWNLOAD=SOURCE.isEmpty()?"":SOURCE+"/releases/latest";
    private static final String TOO_BIG="That file is larger than 25 MB, which is more than a note will keep. Nothing was changed.";
    private static final String TOO_MUCH="This pad is already holding 500 MB of files. Remove some before keeping more.";
    private NoteStore store;
    private Background background;
    /**
     * A second worker, for anything that waits on the network.
     *
     * <p>Everything used to share one. That worker is serialized so that a read sees the writes before it,
     * which is right for a notebook and wrong for a relay: starting the node, telling contacts where this
     * phone is and sending a note can each take as long as the network takes, and while one of them did,
     * nothing else ran. The note you opened the app to read was queued behind the node finding a relay —
     * a blank page for minutes — every word typed waited to be saved behind every note being sent, and
     * leaving the app stalled for two seconds waiting on both. The notebook's worker now does the
     * notebook's work and nothing else.
     */
    private Background network;
    /** And a third, for what a node wants done when it has just come up. Nobody is waiting on any of it. */
    private Background chores;
    /**
     * And one for the daily look for a newer build, which shares with nothing. With no connection it waits
     * eight seconds to find that out, and on the network's worker a note being sent would wait behind it.
     */
    private Background lookout;
    /** Built on the worker thread on first use, because pairing identifiers are written to disk. */
    private volatile CoreConnection core;
    private volatile MaximaConnection transport;
    /** This device's own two keys, made on first use and kept sealed. */
    private volatile Keys deviceKeys;
    /** Where this device can be reached, once its own node has said so. Empty until then. */
    private volatile String myAddress="";
    /** True while the phone is being asked for the camera, so the scanner opens once it answers. */
    private boolean waitingToScan;
    /** What the scan was for, kept while the phone is being asked for the camera. */
    private Consumer<String> waitingFor;
    /** Kept with it, so the way out of a camera survives being asked for the camera first. */
    private Runnable waitingPaste;
    private FrameLayout stage;
    private LinearLayout root,rows,topBar;
    /** The level's things, laid out across the screen, when this level is one you arrange. */
    private GridLayout tiles;
    private boolean desktop;
    /** The colour of the level being stood in, so the room shows it while you are inside it. */
    private int levelColour=Tint.NONE;
    /** How loudly every colour lands, from pastel to the colour itself. One setting for the whole pad. */
    private int tone=Tint.FIRST_TONE;
    private Pad page;
    /** The strip of what this note keeps, under the writing. Empty and out of the way when it keeps nothing. */
    private LinearLayout attached;
    /** A thing just made, whose name is waiting to be typed over the one it was made with. */
    private String nameNext="";
    /** A blank note is waiting to be written on, and the keyboard is still owed to it. */
    private boolean wantKeyboard;
    /** On the unlock page: the notebook is locked and not open yet, and nothing else of the screen exists. */
    private boolean lockedOut;
    /** Profile's "Keep listening while the phone sleeps", while Profile is open; and what it says under it. */
    private android.widget.Switch sleepSwitch;
    private TextView sleepSays;
    private boolean quietSwitch;
    /** The blank page's own ask for the keyboard, kept so a note that arrives to be read can take it back. */
    private final Runnable raise=this::writeOn;
    /** The mark beside the dots, and how to ask again what it should say. */
    private View owedMark;
    private Runnable owedAsk;
    /** What a file is being attached to, settled when the picker opens rather than when it comes back. */
    private NoteStore.Branch.Kind attachingTo=NoteStore.Branch.Kind.PAGE;
    private String attachingToId="";
    private TextView status;
    private TextView syncTimes;
    private SyncStatus.State syncState;
    /** The open note's name, in the bar. */
    private TextView named;
    private NoteStore.Note active;
    /**
     * What the notebook holds of the open note, as far as this page knows: what it said when it was opened,
     * or when it was last written down. It is what the page is compared against when the note turns out to
     * have been written in from somewhere else — see {@link Arriving#onThePage}.
     */
    private String kept="";
    /** The note's title as the notebook has it, for the same reason: to know when it was changed here. */
    private String keptTitle="";
    /** What holds the title in the bar, so the word can be put back where the field was. */
    private LinearLayout nameHolder;
    /** Whether the open note is shared at all, so the mark can ask to be pressed the moment a word is typed. */
    private boolean pageShared;
    /**
     * Whether the open note is somebody's, shared to be read: the page takes no writing, so nothing is ever
     * sent back to be refused. Who it came from, for the one time the page says so out loud.
     */
    private boolean readOnly, saidReadOnly;
    private String readOwner="";
    /**
     * The revision of the notebook's copy that {@link #kept} is. Kept apart from the note's own revision,
     * which a writing counts up before it knows whether it will be allowed: a writing that was refused
     * had already moved that number on, the page then looked no older than the notebook, and the words
     * that had just been refused were left on the screen and nowhere else.
     */
    private long keptRevision;
    /** The pad has been off the screen since it was last drawn, so what it shows may be behind. */
    private boolean beenAway;

    /** What a cold start needs: the reading size, and the page last written. */
    private static final class Opening {
        final int size; final NoteStore.Note note; final String book;
        Opening(int size,NoteStore.Note note,String book){this.size=size;this.note=note;this.book=book;}
    }

    /** One step of the trail across the top: where you are, and how you got there. */
    private static final class Step {
        final NoteStore.Branch.Kind kind; final String id,name;
        Step(NoteStore.Branch.Kind kind,String id,String name){this.kind=kind;this.id=id;this.name=name;}
    }
    private final List<Step> trail=new ArrayList<>();
    /** The line being carried to somewhere else, or null when simply looking. */
    private NoteStore.Branch carrying;
    /** The line being dragged into a new place among the lines beside it, and the gap it left behind. */
    /** How many addresses have not been given the open page as it stands. */
    private int owedNow;
    /** The menu on screen, if one is, so a ladder inside it can repaint it while it stays open. */
    private Sheet showing;
    /** Something was given a colour while the menu was over it, so the level is redrawn when it closes. */
    private boolean painted;
    private NoteStore.Branch dragging;
    private View lifted;
    /** The list's own scroller, so a long level keeps moving under a finger held at its edge. */
    private ScrollView scroller;
    private boolean shelves;
    /** The page the reader left to look at the shelves, so the way back is where they were. */
    private String writing;

    private final Handler handler=new Handler(Looper.getMainLooper());
    /** The open page is unsaved while edits differs from saved; both only ever count up. */
    private long edits,saved;
    private int textSize=SIZES[FIRST_SIZE];
    private boolean failed,loading;
    private final Runnable autoSave=this::save;

    /**
     * What the open note owes, sent once the writing has stopped for as long as that note asks for.
     *
     * <p>Long enough that a sentence is not sent a word at a time, short enough that the other end is
     * looking at what you wrote rather than what you wrote a while ago. Ten seconds unless the thing says
     * otherwise, and it can say otherwise — see {@link NoteStore#pauseFor}.
     */
    private final Runnable sendSoon=this::sendOpenNote;
    /** The wait for the note that is open, in milliseconds, or 0 while nobody has asked the notebook. */
    private long sendDelay;

    private int dp(int n){return (int)(getResources().getDisplayMetrics().density*n);}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}

    /**
     * A word the app drew, remembering the size it was asked for. The reading ladder sets one size for the
     * whole pad — a name under a tile is as hard to read as the writing when the writing is too small — so
     * every piece of text keeps its own proportion and is scaled from it, rather than being rebuilt.
     */
    @android.annotation.SuppressLint("ViewConstructor")
    private final class Words extends TextView {
        private final int base;
        Words(int base){super(MainActivity.this);this.base=base;resize();}
        void resize(){setTextSize(Math.max(8,Math.round(base*reading())));}
    }

    /** How much bigger or smaller than the pad's own size everything is drawn, from the reading ladder. */
    private float reading(){return textSize/(float)SIZES[FIRST_SIZE];}

    /** Takes a new reading size through everything on screen, without rebuilding what is on it. */
    private void resize(View from) {
        if(from instanceof Words)((Words)from).resize();
        else if(from instanceof android.view.ViewGroup) {
            android.view.ViewGroup group=(android.view.ViewGroup)from;
            for(int at=0;at<group.getChildCount();at++)resize(group.getChildAt(at));
        }
    }

    private TextView label(String s,int size,int colour){TextView t=new Words(size);t.setText(s);t.setTextColor(colour);return t;}
    private TextView line(String s,int size,int colour){TextView t=label(s,size,colour);t.setSingleLine(true);t.setEllipsize(TextUtils.TruncateAt.END);return t;}
    private int touchFeedback(){TypedValue v=new TypedValue();getTheme().resolveAttribute(android.R.attr.selectableItemBackground,v,true);return v.resourceId;}
    private int borderlessFeedback(){TypedValue v=new TypedValue();getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless,v,true);return v.resourceId;}
    private void alert(String message){new Box().setMessage(message).show();}
    private void toast(String message){Toast.makeText(this,message,Toast.LENGTH_SHORT).show();}
    private LinearLayout bar(){LinearLayout b=new LinearLayout(this);b.setGravity(Gravity.CENTER_VERTICAL);b.setPadding(dp(8),dp(2),dp(8),dp(2));topBar=b;return b;}
    /** The same, drawn heavy: for the one control that is reached for more than any other. */
    private TextView heavy(String face,String name,int size,int colour,View.OnClickListener action) {
        TextView made=tap(face,name,size,colour,action);
        made.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return made;
    }

    private TextView tap(String face,String name,int size,int colour,View.OnClickListener action) {
        TextView t=label(face,size,colour);t.setContentDescription(name);t.setGravity(Gravity.CENTER);
        t.setMinWidth(dp(48));t.setMinimumHeight(dp(48));t.setPadding(dp(8),0,dp(8),0);
        t.setBackgroundResource(borderlessFeedback());t.setOnClickListener(action);return t;
    }
    /**
     * A line inside a box that opens something else. It sits where the words it follows on from are, rather
     * than becoming a fourth button a box has no room for.
     */
    private View tapRow(String said,Runnable go){return row(said,"",go);}

    /**
     * A line in a box: what it is on the left, what it is set to on the right, and an arrow-head at the
     * end where tapping it goes somewhere.
     *
     * <p>Every box is written in three ways and no more - its title, words to read, and quieter words
     * beside them. A box had grown ten: a bold title, a big sentence, a bold button, words, grey words
     * under them, a word in a bordered chip, green words that were links, capitals spaced out as headings,
     * and grey words on the right. Each was reasonable where it was put, and together they were a page
     * that had to be studied before it could be used. What can be tapped is said by the arrow-head, the
     * same one everywhere, and not by a colour somebody has to have learnt.
     */
    private View row(String left,String right,Runnable go) {
        LinearLayout entry=new LinearLayout(this);
        entry.setGravity(Gravity.CENTER_VERTICAL);
        entry.setMinimumHeight(dp(52));
        entry.setPadding(0,dp(6),0,dp(6));
        TextView what=label(left,READING,INK);
        entry.addView(what,new LinearLayout.LayoutParams(0,-2,1));
        if(right!=null&&!right.isEmpty()) {
            TextView set=label(right,QUIET,MUTED);
            set.setPadding(dp(12),0,0,0);set.setGravity(Gravity.END);
            entry.addView(set);
        }
        if(go!=null) {
            TextView on=label("\u203a",READING,MUTED);
            on.setPadding(dp(10),0,0,dp(2));
            entry.addView(on);
            entry.setBackgroundResource(touchFeedback());
            entry.setOnClickListener(v->go.run());
        }
        entry.setContentDescription(right==null||right.isEmpty()?left:left+", "+right);
        return entry;
    }

    /** Something that is on or off, as the switch everybody already knows how to use. */
    private View switchRow(String said,boolean on,final Consumer<Boolean> changed) {
        LinearLayout entry=new LinearLayout(this);
        entry.setGravity(Gravity.CENTER_VERTICAL);
        entry.setMinimumHeight(dp(52));
        entry.setPadding(0,dp(6),0,dp(6));
        entry.addView(label(said,READING,INK),new LinearLayout.LayoutParams(0,-2,1));
        final android.widget.Switch flick=new android.widget.Switch(this);
        flick.setChecked(on);
        flick.setThumbTintList(new android.content.res.ColorStateList(
            new int[][]{{android.R.attr.state_checked},{}},new int[]{ACCENT,MUTED}));
        flick.setTrackTintList(new android.content.res.ColorStateList(
            new int[][]{{android.R.attr.state_checked},{}},new int[]{mix(ACCENT,PAPER,0.5f),LINE}));
        flick.setOnCheckedChangeListener((v,now)->changed.accept(now));
        entry.addView(flick);
        entry.setBackgroundResource(touchFeedback());
        entry.setOnClickListener(v->flick.toggle());
        entry.setContentDescription(said);
        return entry;
    }

    // ---- something is going on ----------------------------------------------------------------------------

    /**
     * Whatever is going on, said for as long as it is going on.
     *
     * <p>Anything that has to cross the network takes as long as the network takes: finding another phone
     * can be a minute and a half. All of that used to happen behind a screen that had not changed, so that
     * somebody who had just pressed Accept was left looking at a note, wondering whether they had. The
     * rule is that nothing is ever going on in silence: what starts says so here at once, says what it has
     * got to as it goes, and ends by saying how it ended. A strip at the foot of the window rather than a
     * box, because none of this is a reason to stop anybody reading or writing while it happens.
     */
    private LinearLayout busyStrip;
    private TextView busyWords;
    private android.widget.ProgressBar busyTurning;
    /** Which piece of work the strip is speaking for. A later one takes it over; an earlier one's last words are dropped. */
    private int busyJob, busyJobs;
    private final Runnable busyAway=()->{
        busyJob=0;
        if(busyStrip!=null&&busyStrip.getParent()!=null)((android.view.ViewGroup)busyStrip.getParent()).removeView(busyStrip);
    };

    /** Something has started. What comes back is what it is known by when it says more, or ends. */
    private int busy(String what) {
        busyJob=++busyJobs;
        busyShow(what,true);
        return busyJob;
    }

    /** How far it has got. From any thread: it is the worker that knows. */
    private void busySay(final int job,final String what) {
        handler.post(()->{if(job==busyJob)busyShow(what,true);});
    }

    /** It has ended, and how - or with null, that something else is about to say so. From any thread. */
    private void busyDone(final int job,final String how) {
        handler.post(()->{
            if(job!=busyJob)return;
            if(how==null||how.isEmpty()){handler.removeCallbacks(busyAway);busyAway.run();return;}
            busyShow(how,false);
            handler.postDelayed(busyAway,2800);
        });
    }

    private void busyShow(String what,boolean going) {
        handler.removeCallbacks(busyAway);
        if(busyStrip==null) {
            busyStrip=new LinearLayout(this);
            busyStrip.setGravity(Gravity.CENTER_VERTICAL);
            busyStrip.setPadding(dp(16),dp(12),dp(18),dp(12));
            busyStrip.setElevation(dp(6));
            busyStrip.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            busyTurning=new android.widget.ProgressBar(this,null,android.R.attr.progressBarStyleSmall);
            LinearLayout.LayoutParams turning=new LinearLayout.LayoutParams(dp(18),dp(18));
            turning.setMargins(0,0,dp(12),0);
            busyStrip.addView(busyTurning,turning);
            busyWords=label("",QUIET,INK);
            busyStrip.addView(busyWords);
        }
        // Drawn again each time, because the paper can have been changed since it was last up.
        GradientDrawable card=new GradientDrawable();
        card.setColor(CARD);card.setCornerRadius(dp(14));card.setStroke(Math.max(1,dp(1)),LINE);
        busyStrip.setBackground(card);
        busyWords.setTextColor(INK);
        busyTurning.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(MUTED));
        busyTurning.setVisibility(going?View.VISIBLE:View.GONE);
        busyWords.setText(what);
        busyStrip.setContentDescription(what);
        // On the window rather than on the screen that is showing: screens are rebuilt as somebody moves
        // about, and what is going on goes on whichever of them they are looking at.
        android.view.ViewGroup window=(android.view.ViewGroup)getWindow().getDecorView();
        int foot=0;
        WindowInsets insets=window.getRootWindowInsets();
        if(insets!=null)foot=android.os.Build.VERSION.SDK_INT>=30
            ?Math.max(insets.getInsets(WindowInsets.Type.systemBars()).bottom,insets.getInsets(WindowInsets.Type.ime()).bottom)
            :insets.getSystemWindowInsetBottom();
        FrameLayout.LayoutParams place=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);
        place.setMargins(dp(16),0,dp(16),foot+dp(24));
        if(busyStrip.getParent()==null)window.addView(busyStrip,place);else busyStrip.setLayoutParams(place);
    }

    /** The one thing to do in a box: a button across it, in the accent, saying what it does. One a box. */
    private View primary(String words,Runnable does) {
        TextView button=label(words,READING,PAPER);
        button.setGravity(Gravity.CENTER);
        button.setMinimumHeight(dp(52));
        button.setPadding(dp(12),dp(12),dp(12),dp(12));
        GradientDrawable filled=new GradientDrawable();
        filled.setColor(ACCENT);filled.setCornerRadius(dp(12));
        button.setBackground(filled);
        button.setOnClickListener(v->does.run());
        LinearLayout.LayoutParams wide=new LinearLayout.LayoutParams(-1,-2);
        wide.setMargins(0,dp(12),0,dp(10));
        button.setLayoutParams(wide);
        return button;
    }

    private void keyboard(boolean wanted){getWindow().setSoftInputMode((wanted?WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE:WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)|WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);}
    /** The keyboard, asked for rather than arriving: a tap or a double tap on the page, and nothing else. */
    private void writeOn() {
        // Not on a page that takes no writing. Quietly: the keyboard can be asked for by a page that has
        // since been filled with somebody's note, and that is nobody tapping.
        if(page==null||readOnly)return;
        page.requestFocus();
        // Asked for two ways. SHOW_IMPLICIT is a request the system is free to ignore, and it does ignore
        // one made before the window has focus - which is exactly when a brand new note asks. The insets
        // controller is not a request, so where there is one it is the one that answers.
        if(android.os.Build.VERSION.SDK_INT>=30) {
            android.view.WindowInsetsController asking=page.getWindowInsetsController();
            if(asking!=null)asking.show(android.view.WindowInsets.Type.ime());
        }
        InputMethodManager keys=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
        // Not SHOW_IMPLICIT: an implicit request is one the system may decline, and it declines this one
        // on a page that has been told not to raise a keyboard when it is focused.
        if(keys!=null)keys.showSoftInput(page,0);
    }

    private void focus(){page.requestFocus();page.post(raise);}
    /** Takes back a keyboard nobody tapped for: the asks still waiting, and the one already up. */
    private void quiet() {
        wantKeyboard=false;keyboard(false);
        if(page==null)return;
        page.removeCallbacks(raise);
        InputMethodManager keys=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
        if(keys!=null)keys.hideSoftInputFromWindow(page.getWindowToken(),0);
    }

    /** The last three rungs are dark papers, where every element colour takes its lighter strength. */
    private boolean darkPaper(){return paper>=PAPERS.length-3;}

    /** Light or dark, as the phone is set. Changing it restarts the screen, which draws it again from here. */
    private int paperNow() {
        int night=getResources().getConfiguration().uiMode&android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return night==android.content.res.Configuration.UI_MODE_NIGHT_YES?DARK_PAPER:FIRST_PAPER;
    }

    /** Takes one paper into use. Drawing from these fields is what makes the app follow it. */
    // ---- the password lock ---------------------------------------------------------------------------------

    /**
     * The page Mininotes opens on while its notebook is locked: the password, and nothing of the notebook
     * behind it. Opening is quiet; only a wrong password is said in the warning colour.
     */
    private void unlockScreen() {
        LinearLayout body=column();body.setPadding(dp(28),dp(72),dp(28),dp(28));body.setBackgroundColor(PAPER);
        android.widget.ImageView mark=new android.widget.ImageView(this);mark.setImageResource(R.drawable.ic_note);
        // Gaps given their height outright: this page fills the screen, and a plain View would take all of it.
        body.addView(mark,new LinearLayout.LayoutParams(dp(56),dp(56)));body.addView(gap(18),new LinearLayout.LayoutParams(-1,dp(18)));
        TextView title=label("Mininotes is locked",Math.round(READING*1.4f),INK);title.setTypeface(null,android.graphics.Typeface.BOLD);body.addView(title);
        final boolean bio=android.os.Build.VERSION.SDK_INT>=30&&PhoneLock.hasBio(this);
        body.addView(gap(6),new LinearLayout.LayoutParams(-1,dp(6)));
        body.addView(label(bio?"Use your fingerprint or screen lock, or type your backup password.":"Type your password to open your notes.",READING,INK));
        final EditText password=secret(body,bio?"Backup password":"Password");
        final TextView said=label(" ",QUIET,MUTED);body.addView(said);
        final Runnable tryIt=()->{
            said.setTextColor(MUTED);said.setText("Opening…");
            final char[] typed=password.getText().toString().toCharArray();
            new Thread(()->{
                byte[] key=null;long began=System.nanoTime();
                try{key=Vault.open(PhoneLock.kept(this),typed);}catch(Exception wrong){/* said below */}
                // How long a password takes to check on this phone: the number to watch if the rounds ever rise.
                android.util.Log.i("Mininotes/Lock","password checked in "+(System.nanoTime()-began)/1_000_000+" ms");
                final byte[] opened=key;
                runOnUiThread(()->{
                    if(opened==null){said.setTextColor(WARN);said.setText("That password did not open it.");password.selectAll();return;}
                    NoteStore.unlock(opened);recreate();
                });
            },"mininotes-unlock").start();
        };
        password.setOnEditorActionListener((v,action,event)->{tryIt.run();return true;});
        body.addView(primary("Unlock",tryIt));
        // The phone's own unlock, where it is set up: asked for at once, and a tap away if it was put aside.
        if(bio) {
            TextView viaPhone=tap("Use fingerprint or screen lock","Use fingerprint or screen lock",READING,ACCENT,v->unlockWithPhone(said));
            viaPhone.setGravity(Gravity.START);viaPhone.setPadding(0,dp(8),0,dp(4));body.addView(viaPhone);
            handler.post(()->unlockWithPhone(said));
        }
        TextView forgot=tap("Use recovery words","Use recovery words",READING,ACCENT,v->recoverAtStart());
        forgot.setGravity(Gravity.START);forgot.setPadding(0,dp(8),0,dp(8));body.addView(forgot);
        ScrollView page=new ScrollView(this);page.setFillViewport(true);page.setBackgroundColor(PAPER);page.addView(body);
        setContentView(page);
    }

    /** A password field with a Show button beside it: what was typed can be seen before it is sent. */
    private EditText secret(LinearLayout into,String hint) {
        final EditText field=field(hint,200);
        final int hidden=android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD;
        final int shown=android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD;
        field.setInputType(hidden);field.setTypeface(android.graphics.Typeface.DEFAULT);
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);
        field.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));row.addView(field);
        final TextView show=label("Show",QUIET,ACCENT);show.setPadding(dp(14),dp(18),dp(4),dp(10));
        show.setContentDescription("Show the password");
        show.setOnClickListener(v->{
            boolean now=field.getInputType()==hidden;int at=field.getSelectionEnd();
            field.setInputType(now?shown:hidden);field.setTypeface(android.graphics.Typeface.DEFAULT);field.setSelection(Math.max(0,at));
            show.setText(now?"Hide":"Show");show.setContentDescription(now?"Hide the password":"Show the password");
        });
        row.addView(show);into.addView(row);
        return field;
    }

    /** The twelve words, then a new password: the lock file sealed again, and the notebook opened. */
    private void recoverAtStart() {
        LinearLayout body=inside();
        body.addView(label("Type the 12 recovery words you wrote down when the lock was set, in order. Then choose a new password.",READING,INK));
        final EditText words=field("The 12 words",400);words.setSingleLine(false);words.setMinLines(3);
        words.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        body.addView(words);
        final EditText first=secret(body,"New password"),again=secret(body,"The same again");
        final TextView said=label(" ",QUIET,WARN);body.addView(said);
        final AlertDialog[] box={null};
        body.addView(primary("Unlock and set password",()->{
            String problem=passwordProblem(first,again);if(problem!=null){said.setText(problem);return;}
            said.setTextColor(MUTED);said.setText("Opening…");
            final String typed=words.getText().toString();final char[] chosen=first.getText().toString().toCharArray();
            new Thread(()->{
                String failed=null;byte[] key=null;
                try{byte[] kept=PhoneLock.kept(this);key=Vault.recover(kept,typed);PhoneLock.write(PhoneLock.file(this,PhoneLock.KEPT),Vault.newPassword(kept,key,chosen));}
                catch(Vault.Refused no){failed=no.getMessage();}
                catch(Exception e){failed="The new password could not be saved. The words still open it.";}
                final String problemNow=failed;final byte[] opened=key;
                runOnUiThread(()->{
                    if(problemNow!=null){said.setTextColor(WARN);said.setText(problemNow);return;}
                    // The unlock page stays what it is while it goes; the screen that replaces it starts fresh.
                    box[0].dismiss();NoteStore.unlock(opened);recreate();
                });
            },"mininotes-recover").start();
        }));
        box[0]=new Box().setTitle("Recovery words").setView(scrolling(body)).create();box[0].show();
    }

    /**
     * Android's own prompt - fingerprint, face, or the phone's PIN, pattern or password - over a cipher on the
     * key that lives in the phone's secure hardware. What the prompt lets through is handed on; nothing else.
     */
    private void askPhone(String title,javax.crypto.Cipher cipher,Consumer<javax.crypto.Cipher> allowed,Consumer<String> refused) {
        if(android.os.Build.VERSION.SDK_INT<30){refused.accept("This phone's Android is too old for this.");return;}
        android.hardware.biometrics.BiometricPrompt prompt=new android.hardware.biometrics.BiometricPrompt.Builder(this)
            .setTitle(title).setSubtitle("Mininotes")
            .setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
                |android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build();
        prompt.authenticate(new android.hardware.biometrics.BiometricPrompt.CryptoObject(cipher),new android.os.CancellationSignal(),getMainExecutor(),
            new android.hardware.biometrics.BiometricPrompt.AuthenticationCallback(){
                @Override public void onAuthenticationSucceeded(android.hardware.biometrics.BiometricPrompt.AuthenticationResult result){allowed.accept(result.getCryptoObject().getCipher());}
                @Override public void onAuthenticationError(int code,CharSequence why){refused.accept(why==null?"":why.toString());}
            });
    }

    /** The notebook opened with the phone's own unlock, or told why not - quietly, the password is right there. */
    private void unlockWithPhone(final TextView said) {
        javax.crypto.Cipher cipher;
        try{cipher=PhoneLock.bioCipher(this,false);}
        catch(android.security.keystore.KeyPermanentlyInvalidatedException changed) {
            // A new fingerprint was added to the phone since: the hardware key is gone, by design. Password, then set it up again.
            PhoneLock.forgetBio(this);said.setTextColor(MUTED);said.setText("The phone's fingerprints changed, so unlocking with them was switched off. Use your password, then switch it on again in Security.");return;
        } catch(Exception e){said.setTextColor(MUTED);said.setText("Use your password.");return;}
        askPhone("Unlock your notes",cipher,allowed->{
            try{NoteStore.unlock(PhoneLock.openBio(this,allowed));recreate();}
            catch(Exception e){said.setTextColor(WARN);said.setText("That did not open it. Use your password.");}
        },why->{/* put aside: the password is there */});
    }
    private static String passwordProblem(EditText first,EditText again) {
        String a=first.getText().toString(),b=again.getText().toString();
        if(a.length()<8)return "Use at least 8 characters.";
        if(!a.equals(b))return "The two passwords are not the same.";
        return null;
    }

    /** The warning, where it cannot be missed. */
    private TextView lockWarning() {
        TextView words=label(PhoneLock.WARNING,QUIET,WARN);words.setTypeface(null,android.graphics.Typeface.BOLD);
        GradientDrawable pale=new GradientDrawable();pale.setColor(mix(WARN,PAPER,0.9f));pale.setCornerRadius(dp(10));
        words.setBackground(pale);words.setPadding(dp(14),dp(12),dp(14),dp(12));words.setTextIsSelectable(true);
        LinearLayout.LayoutParams place=new LinearLayout.LayoutParams(-1,-2);place.setMargins(0,dp(14),0,dp(6));words.setLayoutParams(place);
        return words;
    }

    /** Whether it is on, what that means, and everything that can be done about it. */
    private void security() {
        final boolean on=PhoneLock.locked(this);
        LinearLayout body=inside();
        // The lock is one thing; the ways to open it are several, and the phone's own unlock comes first.
        // A password typed every time is the worst of them, so it is the backup, not the front door.
        final boolean phone=android.os.Build.VERSION.SDK_INT>=30,bio=phone&&PhoneLock.hasBio(this);
        TextView state=label(on?"🔒  Locked and encrypted":"Not locked",READING,on?ACCENT:INK);
        state.setTypeface(null,android.graphics.Typeface.BOLD);body.addView(state);
        body.addView(under(!on?"Anyone who can read this phone's storage can read your notes. Lock Mininotes to encrypt them"
                +(phone?"; it then opens with your fingerprint or screen lock.":" with a password.")
            :bio?"Mininotes opens with your fingerprint or screen lock. Your backup password and your 12 recovery words open it too. Your notes, their files and the backups you export are encrypted."
            :"Mininotes asks for your backup password when it opens"+(phone?" - switch on fingerprint or screen lock below and you will rarely need it":"")
                +". Your notes, their files and the backups you export are encrypted."));
        final AlertDialog[] box={null};
        body.addView(switchRow("Lock Mininotes",on,want->{box[0].dismiss();if(want)lockOn();else lockOff();}));
        if(on) {
            TextView ways=label("Ways to open it",QUIET,MUTED);ways.setPadding(0,dp(14),0,dp(2));body.addView(ways);
            if(phone)body.addView(switchRow("Fingerprint or screen lock",bio,want->{box[0].dismiss();if(want)phoneUnlockOn();else{PhoneLock.forgetBio(this);toast("Mininotes no longer opens with the phone's unlock");}}));
            body.addView(tapRow("Backup password: change",()->{box[0].dismiss();changePassword();}));
            body.addView(tapRow("12 recovery words: show",()->{box[0].dismiss();wordsAgain();}));
            // Locking again when not used, and Never is one of the choices.
            final List<String> afterNames=java.util.Arrays.asList(PhoneLock.AFTER_NAMES);
            int now=0;for(int i=0;i<PhoneLock.AFTER_MINUTES.length;i++)if(PhoneLock.AFTER_MINUTES[i]==PhoneLock.minutes(this))now=i;
            body.addView(dropRow("Lock again when not used",afterNames,null,now,PhoneLock.AFTER_NAMES[now],picked->
                getSharedPreferences("settings",MODE_PRIVATE).edit().putInt("autoLock",PhoneLock.AFTER_MINUTES[picked]).apply()));
        }
        body.addView(lockWarning());
        box[0]=new Box().setTitle("Security").setView(scrolling(body)).create();box[0].show();
    }

    /** Set when the lock has just gone on: the screen drawn again after it asks for the phone's own unlock. */
    private static boolean offerPhoneUnlock;

    /** Password twice, the words shown once, three asked back, then the notebook encrypted where it is. */
    private void lockOn() {
        LinearLayout body=inside();
        final boolean phone=android.os.Build.VERSION.SDK_INT>=30;
        body.addView(label(phone
            ?"Your notes on this phone will be encrypted, and Mininotes will open with your fingerprint or screen lock - you set that up right after this. First choose a backup password, for when the phone's unlock cannot be used. You will also get 12 recovery words, for if you forget it."
            :"Your notes on this phone will be encrypted. Mininotes will ask for this password each time it opens. You will also get 12 recovery words, for if you forget the password.",READING,INK));
        body.addView(lockWarning());
        final EditText first=secret(body,phone?"Backup password":"Password"),again=secret(body,"The same again");
        final TextView said=label(" ",QUIET,WARN);body.addView(said);
        final AlertDialog[] box={null};
        body.addView(primary("Continue",()->{
            String problem=passwordProblem(first,again);if(problem!=null){said.setText(problem);return;}
            final char[] chosen=first.getText().toString().toCharArray();box[0].dismiss();
            final int job=busy("Making your recovery words…");
            new Thread(()->{
                Vault.Made made=null;try{made=Vault.make(chosen);}catch(Exception e){/* said below */}
                final Vault.Made ready=made;
                runOnUiThread(()->{
                    busyDone(job,null);
                    if(ready==null){alert("The lock could not be made. Nothing was changed.");return;}
                    showWords(ready.words,true,()->checkWords(ready.words,()->encryptNow(ready)));
                });
            },"mininotes-lock").start();
        }));
        box[0]=new Box().setTitle("Lock Mininotes").setView(scrolling(body)).create();box[0].show();
    }

    /** The notebook swapped for its encrypted copy; the screen drawn again on it, with no restart. */
    private void encryptNow(final Vault.Made made) {
        final int job=busy("Locking the notebook…");
        background.submit(()->{PhoneLock.encrypt(this,made);return null;},done->{
            Listening.rehear(this);busyDone(job,"Notebook locked");
            offerPhoneUnlock=android.os.Build.VERSION.SDK_INT>=30;recreate();
        },e->{busyDone(job,null);alert("The notebook could not be locked: "+(e.getMessage()==null?"something went wrong":e.getMessage())+". Your notes are as they were.");});
    }

    /**
     * The twelve words in one block that can be selected whole, and a button to copy them. The first time,
     * nothing goes on until "I have written them down"; shown again later, there is nothing to press.
     */
    private void showWords(final List<String> words,final boolean first,final Runnable then) {
        LinearLayout body=inside();
        body.addView(label(first?"Write these 12 words on paper, in this order, and keep them in a safe place, away from this phone. With them you can open your notes if you forget the password."
            :"Your 12 recovery words, in order. Keep them in a safe place, away from this phone.",READING,INK));
        StringBuilder laid=new StringBuilder();
        for(int i=0;i<words.size();i++){laid.append(String.format(java.util.Locale.ROOT,"%2d. %-9s",i+1,words.get(i)));laid.append(i%2==1?"\n":"  ");}
        TextView block=label(laid.toString().trim(),READING,INK);block.setTypeface(android.graphics.Typeface.MONOSPACE,android.graphics.Typeface.BOLD);
        block.setTextIsSelectable(true);block.setContentDescription("Your 12 recovery words");
        GradientDrawable card=new GradientDrawable();card.setColor(CARD);card.setCornerRadius(dp(12));card.setStroke(Math.max(1,dp(1)),LINE);
        block.setBackground(card);block.setPadding(dp(16),dp(14),dp(16),dp(14));
        LinearLayout.LayoutParams place=new LinearLayout.LayoutParams(-1,-2);place.setMargins(0,dp(14),0,dp(8));body.addView(block,place);
        body.addView(pill("Copy the words",()->{
            final String plain=String.join(" ",words);
            ClipboardManager board=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);if(board==null)return;
            ClipData clip=ClipData.newPlainText("Recovery words",plain);
            // Marked sensitive, so the phone does not show them in its copy preview.
            if(android.os.Build.VERSION.SDK_INT>=33){android.os.PersistableBundle extra=new android.os.PersistableBundle();extra.putBoolean("android.content.extra.IS_SENSITIVE",true);clip.getDescription().setExtras(extra);}
            board.setPrimaryClip(clip);
            toast("Copied. Paste them somewhere safe now: the clipboard is emptied in one minute.");
            handler.postDelayed(()->{try{ClipData now=board.getPrimaryClip();if(now!=null&&now.getItemCount()>0&&plain.contentEquals(now.getItemAt(0).coerceToText(this)))board.clearPrimaryClip();}catch(RuntimeException gone){/* something else is there now */}},60_000);
        }));
        body.addView(lockWarning());
        final AlertDialog[] box={null};
        if(first)body.addView(primary("I have written them down",()->{box[0].dismiss();then.run();}));
        box[0]=new Box().setTitle(first?"Your recovery words":"Recovery words").setView(scrolling(body)).create();box[0].show();
    }

    /** Three of the words asked back, so the lock is not put on before they are really written down. */
    private void checkWords(final List<String> words,final Runnable then) {
        List<Integer> order=new ArrayList<>();for(int i=0;i<words.size();i++)order.add(i);
        java.util.Collections.shuffle(order,new java.security.SecureRandom());
        final List<Integer> asked=new ArrayList<>(order.subList(0,3));java.util.Collections.sort(asked);
        LinearLayout body=inside();
        body.addView(label("To be sure they are written down, type these three of your words.",READING,INK));
        final EditText[] fields=new EditText[3];
        for(int i=0;i<3;i++){fields[i]=field("Word "+(asked.get(i)+1),20);fields[i].setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);body.addView(fields[i]);}
        final TextView said=label(" ",QUIET,WARN);body.addView(said);
        final AlertDialog[] box={null};
        body.addView(primary("Lock the notebook",()->{
            for(int i=0;i<3;i++)if(!fields[i].getText().toString().trim().equalsIgnoreCase(words.get(asked.get(i)))){said.setText("Word "+(asked.get(i)+1)+" is not right. Check what you wrote down.");return;}
            box[0].dismiss();then.run();
        }));
        TextView again=tap("Show the words again","Show the words again",READING,ACCENT,v->showWords(words,false,()->{}));
        again.setGravity(Gravity.START);again.setPadding(0,dp(8),0,dp(8));body.addView(again);
        box[0]=new Box().setTitle("Check your words").setView(scrolling(body)).create();box[0].show();
    }

    /** A password asked for once, checked, and the key it opens handed on. */
    private void withPassword(String title,String message,String yes,final Consumer<byte[]> then) {
        LinearLayout body=inside();body.addView(label(message,READING,INK));
        final EditText password=secret(body,"Password");
        final TextView said=label(" ",QUIET,MUTED);body.addView(said);
        final AlertDialog[] box={null};
        body.addView(primary(yes,()->{
            said.setTextColor(MUTED);said.setText("Opening…");
            final char[] typed=password.getText().toString().toCharArray();
            new Thread(()->{
                byte[] key=null;try{key=Vault.open(PhoneLock.kept(this),typed);}catch(Exception wrong){/* said below */}
                final byte[] opened=key;
                runOnUiThread(()->{
                    if(opened==null){said.setTextColor(WARN);said.setText("That password is not right.");return;}
                    box[0].dismiss();then.accept(opened);
                });
            },"mininotes-password").start();
        }));
        box[0]=new Box().setTitle(title).setView(scrolling(body)).create();box[0].show();
    }

    /** The notebook's key sealed once more, by the phone's secure hardware, after Android's own prompt. */
    private void phoneUnlockOn() {
        final byte[] key=NoteStore.key();
        if(key==null){alert("Open the notebook first.");return;}
        javax.crypto.Cipher cipher;
        try{cipher=PhoneLock.bioCipher(this,true);}
        catch(Exception e){alert("This phone cannot keep a key for its own unlock. Set a screen lock in Android's settings first.");return;}
        askPhone("Unlock Mininotes with this phone",cipher,allowed->{
            try{PhoneLock.keepBio(this,allowed,key);toast("Mininotes now unlocks with your fingerprint or screen lock");}
            catch(Exception e){PhoneLock.forgetBio(this);alert("It could not be set up. Your backup password still works.");}
        },why->{PhoneLock.forgetBio(this);if(why!=null&&!why.isEmpty())toast(why);});
    }

    /** Three lines to send with the link: what it is, what it is for, where to get it. */
    static final String INVITE="I use Mininotes to keep notes and lists with the people close to me: a private paper pad, sealed from phone to phone, nothing to sign up for.\nAndroid: open the link and install the .apk file.\n";

    /**
     * Mininotes, handed on: the download page as a code to scan for somebody standing here, and the same
     * link with three lines around it for anybody further away, through whatever app sends messages.
     */
    private void shareApp() {
        if(DOWNLOAD.isEmpty()){alert("There is no public download yet.");return;}
        LinearLayout body=inside();
        body.addView(label("Somebody next to you can scan this with their phone's camera. For anybody else, send the link.",READING,INK));
        try {
            ImageView code=new ImageView(this);
            android.graphics.drawable.BitmapDrawable drawn=new android.graphics.drawable.BitmapDrawable(getResources(),Qr.of(DOWNLOAD,1));
            drawn.setFilterBitmap(false);code.setImageDrawable(drawn);code.setContentDescription("The download link as a code");
            code.setAdjustViewBounds(true);code.setPadding(0,dp(16),0,dp(8));
            body.addView(code,new LinearLayout.LayoutParams(-1,dp(240)));
        } catch(Exception noCode){/* the link below is enough */}
        TextView link=label(DOWNLOAD,QUIET,MUTED);link.setTextIsSelectable(true);link.setGravity(Gravity.CENTER);body.addView(link);
        final AlertDialog[] box={null};
        body.addView(primary("Send the link",()->{
            Intent send=new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT,"Mininotes").putExtra(Intent.EXTRA_TEXT,INVITE+DOWNLOAD);
            started(Intent.createChooser(send,"Share Mininotes"));
        }));
        box[0]=new Box().setTitle("Share Mininotes").setView(scrolling(body)).create();box[0].show();
    }

    private void wordsAgain() {
        withPassword("Show recovery words","Type your password to see your recovery words.","Show the words",key->{
            try{showWords(Vault.words(PhoneLock.kept(this),key),false,()->{});}
            catch(Exception e){alert("The words could not be read: "+e.getMessage());}
        });
    }

    private void changePassword() {
        withPassword("Change backup password","Type your current backup password.","Continue",key->{
            LinearLayout body=inside();
            final EditText first=secret(body,"New password"),again=secret(body,"The same again");
            body.addView(under("Your recovery words stay the same."));
            final TextView said=label(" ",QUIET,WARN);body.addView(said);
            final AlertDialog[] box={null};
            body.addView(primary("Change password",()->{
                String problem=passwordProblem(first,again);if(problem!=null){said.setText(problem);return;}
                final char[] chosen=first.getText().toString().toCharArray();box[0].dismiss();
                final int job=busy("Changing the password…");
                new Thread(()->{
                    boolean done=false;
                    try{byte[] kept=PhoneLock.kept(this);PhoneLock.write(PhoneLock.file(this,PhoneLock.KEPT),Vault.newPassword(kept,key,chosen));done=true;}catch(Exception e){/* said below */}
                    final boolean changed=done;
                    runOnUiThread(()->busyDone(job,changed?"Password changed":"The password could not be changed. The old one still works."));
                },"mininotes-password").start();
            }));
            box[0]=new Box().setTitle("New backup password").setView(scrolling(body)).create();box[0].show();
        });
    }

    private void lockOff() {
        withPassword("Turn off the lock?","Your notes on this phone will no longer be encrypted, and Mininotes will open without a password. Type your password to turn it off.","Turn off the lock",key->{
            final int job=busy("Turning off the lock…");
            background.submit(()->{PhoneLock.decrypt(this,key);return null;},done->{
                Listening.rehear(this);busyDone(job,"Lock turned off");recreate();
            },e->{busyDone(job,null);alert("The lock could not be turned off: "+(e.getMessage()==null?"something went wrong":e.getMessage())+". Your notes are as they were.");});
        });
    }

    /** The key of a locked backup: the password of the notebook it came from, or its 12 words, in one field. */
    private void backupKey(final byte[] lock,final Consumer<byte[]> then) {
        LinearLayout body=inside();
        body.addView(label("This backup is locked. Type the password of the notebook it came from, or its 12 recovery words.",READING,INK));
        final EditText said=field("Password or recovery words",400);said.setSingleLine(false);
        said.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS|android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        body.addView(said);
        final TextView wrong=label(" ",QUIET,MUTED);body.addView(wrong);
        final AlertDialog[] box={null};
        body.addView(primary("Open the backup",()->{
            wrong.setTextColor(MUTED);wrong.setText("Opening…");
            final String typed=said.getText().toString();
            new Thread(()->{
                byte[] key=null;
                try{key=typed.trim().split("\\s+").length==Vault.WORDS?Vault.recover(lock,typed):Vault.open(lock,typed.toCharArray());}catch(Exception no){/* said below */}
                final byte[] opened=key;
                runOnUiThread(()->{
                    if(opened==null){wrong.setTextColor(WARN);wrong.setText("That does not open this backup.");return;}
                    box[0].dismiss();then.accept(opened);
                });
            },"mininotes-backup-key").start();
        }));
        box[0]=new Box().setTitle("Locked backup").setView(scrolling(body)).create();box[0].show();
    }

    private void usePaper(int rung) {
        paper=Math.max(0,Math.min(PAPERS.length-1,rung));
        int[] set=PAPERS[paper];
        PAPER=set[0];INK=set[1];MUTED=set[2];LINE=set[3];CARD=set[4];ACCENT=set[5];WARN=set[6];SHEET=set[7];
    }

    /**
     * The page under an open menu, repainted where it stands. The shelves are rebuilt the next time they are
     * drawn, which is the moment you leave the page, so nothing has to be walked but what is on screen.
     */
    /** Whether the open page is owed to anybody, asked again whenever it is opened or written to. */
    private void askWhatIsOwed() {
        if(active==null){owedNow=0;return;}
        final String id=active.id;
        background.submit(()->new Object[]{store.owed(NoteStore.Branch.Kind.PAGE,id).size(),SyncStatus.read(store,id)},
            result->{if(active!=null&&active.id.equals(id)){owedNow=(Integer)result[0];syncState=(SyncStatus.State)result[1];saidState();}},e->{});
    }

    /**
     * Whether the open page takes writing.
     *
     * <p>A note somebody shares to be read is read here: the keyboard does not come, the title is not for
     * changing, an old version is to look at, and nothing this page could do writes the note - so nothing
     * is ever sent back to be refused. It was a word in the sharing box and no more: a reader could type,
     * their phone sent it, and the owner's phone took it in. Asked when the page opens and again whenever
     * who may do what arrives, because the person it came from can change their mind while it is open;
     * given writing back, the page is simply built again as one that writes.
     */
    private void askWritable() {
        if(active==null||shelves||page==null)return;
        final String id=active.id;
        background.submit(()->store.readOnlyHere(id),said->{
            if(active==null||shelves||page==null||!active.id.equals(id))return;
            boolean now=(Boolean)said[0];
            readOwner=(String)said[1];
            if(now==readOnly)return;
            if(!now){closeNote();open(id);return;}
            readOnly=true;
            handler.removeCallbacks(autoSave);
            // No keys, and the words still there to be held and copied. Making the text selectable sets
            // it again, which the page must not take for typing.
            loading=true;
            page.setKeyListener(null);
            page.setTextIsSelectable(true);
            loading=false;
            keyboard(false);
            InputMethodManager keys=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
            if(keys!=null)keys.hideSoftInputFromWindow(page.getWindowToken(),0);
            saidState();
        },e->{});
    }

    /** The same line, borrowed for as long as something is happening. */
    private void status(String words) {
        if(status!=null){status.setTextColor(MUTED);status.setText(words);status.setVisibility(View.VISIBLE);}
    }

    /** One line for both things it can say: a save that failed, or an address still waiting. */
    private void saidState() {
        if(syncTimes!=null&&active!=null&&!shelves) {
            syncTimes.setTextColor(MUTED);
            syncTimes.setText(edits!=saved?"Saving…":syncState==null||syncState.saved()!=active.updated?" ":syncState.brief("this phone"));
        }
        if(status==null)return;
        if(failed){status.setTextColor(WARN);status.setText(R.string.save_failed);status.setVisibility(View.VISIBLE);return;}
        // A copy shared to be read says so, in the place the state of the page is said, for as long as it is one.
        if(readOnly){status("Read only");return;}
        status.setTextColor(MUTED);
        status.setText(owedNow==0?"":owedNow==1?"Not sent yet":"Not sent to "+owedNow+" addresses");
        // Gone rather than empty, so that a name with nothing under it sits in the middle of the bar.
        status.setVisibility(owedNow==0?View.GONE:View.VISIBLE);
    }

    private void repaint() {
        // A thing given a colour is written on paper of that colour, washed enough to leave the writing
        // alone. On the shelves that thing is the level you are standing in — you cannot see its own tile
        // from inside it, so the room itself is what shows the colour you just gave it.
        int own=shelves?levelColour:(active!=null?active.colour:Tint.NONE);
        if(stage!=null)stage.setBackgroundColor(Tint.over(own,PAPER,wash(0.12f,0.72f),darkPaper()));
        if(page!=null){page.setTextColor(INK);page.rules(Tint.over(own,LINE,wash(0.35f,1f),darkPaper()));page.invalidate();}
        if(topBar!=null)
            for(int at=0;at<topBar.getChildCount();at++) {
                View child=topBar.getChildAt(at);
                if(child instanceof TextView)((TextView)child).setTextColor(child==status?MUTED:INK);
            }
        // The note's name sits a level further in, beside the line under it, so it is reached by name.
        if(named!=null)named.setTextColor(INK);
        if(showing!=null)showing.repaint();
    }

    private void shell() {
        rows=null;topBar=null;versionLine=null;stage=new FrameLayout(this);stage.setBackgroundColor(PAPER);root=column();
        stage.addView(root,new FrameLayout.LayoutParams(-1,-1));
        // A new screen is not a new app: whatever you were looking closely at, you go on looking closely
        // at. The zoom is put back once the new stage has a size to put it back against.
        if(zoom!=1f)stage.post(this::settle);
        // The keyboard is one more edge of the screen. Newer Android no longer shrinks the window for it,
        // so the app makes room itself: whichever is deeper, the navigation bar or the keyboard, is kept
        // clear at the bottom. Where the window is still shrunk, the keyboard's own inset is nothing and
        // this is simply the navigation bar again.
        stage.setOnApplyWindowInsetsListener((v,insets)->{
            int top,bottom;
            if(android.os.Build.VERSION.SDK_INT>=30) {
                android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());
                top=bars.top;bottom=Math.max(bars.bottom,insets.getInsets(WindowInsets.Type.ime()).bottom);
            } else {top=insets.getSystemWindowInsetTop();bottom=insets.getSystemWindowInsetBottom();}
            root.setPadding(0,top,0,bottom);
            // A menu that is open was fitted to the room there was. There is a different amount now.
            if(showing!=null)showing.fit();
            return insets;
        });
        setContentView(stage);stage.requestApplyInsets();
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        PhoneLock.tidy(this);
        // Locked, and not opened since the app started: the password first, and nothing of the notebook before it.
        if(!PhoneLock.open(this)){lockedOut=true;usePaper(paperNow());unlockScreen();return;}
        store=NoteStore.of(this);background=new Background(handler::post);
        if(offerPhoneUnlock){offerPhoneUnlock=false;handler.postDelayed(this::phoneUnlockOn,600);}
        network=new Background(handler::post);chores=new Background(handler::post);
        lookout=new Background(handler::post);
        // A build that was handed to Android at the last opening is, if it took, the one running now.
        afterUpdate();
        // Listening before anything is sent, so a reply to the first thing this app sends is not the one
        // message that lands with nobody there to hear it.
        listenForNotes();
        // Whatever arrived while the notebook was locked and nobody had opened it, taken in now it is open.
        background.submit(()->PhoneLock.takeIn(this,store,keys()).size(),taken->{if(taken>0)refresh();},e->{});
        usePaper(paperNow());
        cards=getSharedPreferences("settings",MODE_PRIVATE).getBoolean("cards",true);
        tone=getSharedPreferences("settings",MODE_PRIVATE).getInt("tone",Tint.FIRST_TONE);
        final String where=getSharedPreferences("settings",MODE_PRIVATE).getString("where",null);
        final List<Step> back=whereTrail(where);
        // Rotation says where to go before anything remembered does: it is the same moment, not a later one.
        // And a notification about one note says where to go before either: it was tapped for that note.
        final String tapped=state==null&&getIntent()!=null?getIntent().getStringExtra(Listening.NOTE):null;
        // Opened by a code, from the phone's own camera. After the first screen is up: what it asks is a box.
        if(state==null){final Intent with=getIntent();handler.post(()->opened(with));}
        // Whether a newer build is out, once a day at most and after the first screen is up. Not on a
        // rotation: that is the same opening, not another one.
        if(state==null)handler.post(this::lookQuietly);
        final String id=tapped!=null&&!tapped.isEmpty()?tapped:state!=null?state.getString("note")
            :where!=null&&where.startsWith("note\n")?where.substring(5):null;
        // The size is settled before anything is drawn, so the first frame is the size the last one was
        // rather than the default caught changing.
        textSize=onRung(getSharedPreferences("settings",MODE_PRIVATE).getInt("rung",FIRST_SIZE));
        // Which screen this is comes out of the same file, on this thread, so it is known before a single
        // frame is drawn. It used to draw a blank page and then swap to the shelves a moment later if the
        // shelves were where you had been, and a page nobody asked for flashing past is the app telling you
        // it did not know where it was.
        final boolean toShelves=!back.isEmpty()&&(state==null||state.getString("note")==null)
            &&(tapped==null||tapped.isEmpty());
        if(toShelves){trail.clear();trail.addAll(back);browse();}
        // A blank page exists before the window takes focus, so the app draws at once rather than after a
        // disk read. The stored page is dropped into it a moment later — and because that stored page is
        // usually one you are coming back to read, it arrives without the keyboard.
        else write(new NoteStore.Note());
        background.submit(()->new Opening(textSize,
                toShelves?null:(id!=null?store.get(id):store.latest()),store.someBook()),
            opening->{
                if(toShelves)return;
                repaint();if(page!=null)page.setTextSize(textSize);
                if(opening.note!=null)load(opening.note);
                else if(active!=null)active.book=opening.book;},
            e->alert(READ_FAILED));
    }

    // ---- writing -----------------------------------------------------------------------------------------

    /** The whole app, most of the time: one ruled page. */
    private void write(NoteStore.Note note) {
        active=note;shelves=false;carrying=null;saved=edits;failed=false;pageShared=false;syncState=null;
        readOnly=false;saidReadOnly=false;readOwner="";shell();
        LinearLayout top=bar();
        top.addView(heavy("←","Back to this book",30,INK,v->{if(active!=null)openBookOf(active.book);}));
        // The note's title, where every other level has its name: at the top, and only there. It used to
        // be the first line of the page, which tied two things together that are not one thing - the first
        // thing somebody writes is often not what the note is called, and renaming a note meant rewriting
        // its first sentence. It is a word of its own now: tap it to change it, and the page underneath is
        // all writing. One too long for the bar rolls past rather than being cut off, twice when the note
        // opens - not for ever, because something moving at the top of a page is hard to read under.
        named=label("",READING,INK);
        named.setHint("Title");named.setHintTextColor(MUTED);
        named.setTypeface(named.getTypeface(),android.graphics.Typeface.BOLD);
        named.setSingleLine(true);named.setGravity(Gravity.CENTER);
        named.setEllipsize(TextUtils.TruncateAt.MARQUEE);named.setMarqueeRepeatLimit(2);
        named.setHorizontalFadingEdgeEnabled(true);named.setSelected(true);
        named.setContentDescription("This note's title. Tap to change it.");
        named.setMinimumHeight(dp(36));
        named.setOnClickListener(v->renameNote());
        // Silence means saved, and given to whoever receives it. Anything else is said in one quiet line
        // under the name, which is not there at all when there is nothing to say.
        status=label("",QUIET,MUTED);status.setGravity(Gravity.CENTER);status.setSingleLine(true);status.setOnClickListener(v->retry());
        status.setVisibility(View.GONE);
        LinearLayout middle=column();middle.setGravity(Gravity.CENTER);nameHolder=middle;
        middle.addView(named,new LinearLayout.LayoutParams(-1,-2));
        middle.addView(status,new LinearLayout.LayoutParams(-1,-2));
        askWhatIsOwed();
        askPause(note.id);
        top.addView(middle,new LinearLayout.LayoutParams(0,-2,1));
        top.addView(syncMark(new NoteStore.Branch(NoteStore.Branch.Kind.PAGE,note.id,note.book,
            "this note","",0,0,false,note.colour)));
        top.addView(tap("⋮","This note",22,INK,this::pageMenu));
        root.addView(top);
        // One quiet line; the exact times, to the second, when it is tapped.
        syncTimes=label(" ",QUIET,MUTED);syncTimes.setSingleLine(true);syncTimes.setEllipsize(android.text.TextUtils.TruncateAt.END);
        syncTimes.setPadding(dp(20),0,dp(20),dp(6));
        syncTimes.setOnClickListener(v->{if(syncState!=null)alert(syncState.detail("this phone"));});
        root.addView(syncTimes,new LinearLayout.LayoutParams(-1,-2));
        page=new Pad(this,LINE);page.setGravity(Gravity.TOP);page.setTextSize(textSize);page.setTextColor(INK);
        // A little paper under the last line, and no more: a page that kept a keyboard's worth of it
        // scrolled that emptiness into view as you wrote, and the writing was pushed off the top.
        page.setLineSpacing(dp(6),1f);page.setPadding(0,dp(2),0,dp(24));page.setContentDescription("Note");
        page.setFilters(new InputFilter[]{new InputFilter.LengthFilter(24000)});
        // A page has no name of its own: it is known by its first line. An empty one says so, and from the
        // first word on that line is drawn as the heading it is — in weight only, so the rules still fit it.
        page.setHint("Write.");page.setHintTextColor(MUTED);
        page.setLinkTextColor(ACCENT);
        // A tap on a web address opens it; a tap anywhere else is left alone, so the cursor still goes where
        // it was put. Nothing is opened without a tap on the address itself.
        // The page never raises the keyboard by itself. Holding a word is how you select and copy it, and
        // a keyboard sliding up over the thing you are selecting is in the way; a tap or a double tap says
        // you mean to write, and those are the only two things that bring it up.
        page.setShowSoftInputOnFocus(false);
        final android.view.GestureDetector taps=new android.view.GestureDetector(this,
            new android.view.GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onSingleTapUp(MotionEvent e){tapped(e.getY());return false;}
                @Override public boolean onDoubleTap(MotionEvent e){tapped(e.getY());return false;}
            });
        page.setOnTouchListener((v,event)->{
            taps.onTouchEvent(event);
            if(event.getActionMasked()!=MotionEvent.ACTION_UP)return false;
            if(!followLink(event.getX(),event.getY()))return false;
            v.performClick();
            return true;
        });
        page.post(this::repaint);
        LinearLayout.LayoutParams sheet=new LinearLayout.LayoutParams(-1,0,1);sheet.setMargins(dp(20),0,dp(20),dp(12));
        root.addView(page,sheet);
        root.addView(fileStrip());
        page.addTextChangedListener(watch(()->{
            if(loading)return;
            // A shared note that has just been written in is waiting to go, from this keystroke and not
            // from whenever the notebook next gets round to saying so. The mark is what sends it, so it
            // has to be ready to be pressed the moment there is something to send.
            if(pageShared)wantsSending();
            unlinkAround(page.getSelectionStart());
            // Typing is use: the keyboard's letters never reach the screen as touches.
            lastTouch=System.currentTimeMillis();
            edits++;saidState();handler.removeCallbacks(autoSave);handler.postDelayed(autoSave,SAVE_DELAY);}));
        fill(note);
        // A note you open is a note you are reading. The keyboard comes up when you tap the page, not
        // before — except on a blank one, where there is nothing to read and writing is the only reason
        // you are there.
        boolean blank=note.body.trim().isEmpty()&&note.title.trim().isEmpty();
        // The page refuses the keyboard on being focused - that is what keeps it down when you open
        // something to read, and what lets you hold a word without one sliding over it. So a blank note
        // has to ask for it in so many words, once the page is on the screen to ask with.
        // A keyboard cannot be raised on a window that has not been given focus yet, and a note made a
        // moment ago is on one that is still arriving. So it is asked for now, and asked for again the
        // moment the window is actually listening.
        // Not on a copy this phone may only read, where there is nothing to write with.
        if(note.theirs&&!note.writes)blank=false;
        wantKeyboard=blank;
        if(blank){keyboard(true);focus();page.postDelayed(raise,300);}
        else keyboard(false);
        askWritable();
    }

    /**
     * A tap on the page: the keyboard, on the line that was tapped. On a page that takes no writing, the
     * one thing a tap gets is told why, once an opening - the line under the title says it the rest of
     * the time.
     */
    private void tapped(float y) {
        if(readOnly) {
            if(!saidReadOnly) {
                saidReadOnly=true;
                toast(readOwner.isEmpty()?"Read only":"Read only. Ask "+readOwner+" to let you write in it.");
            }
            return;
        }
        reachLine(y);writeOn();
    }

    /**
     * A rule below the writing is a rule you can write on.
     *
     * <p>The page is ruled to the bottom whether or not there is text that far down, and a pad you can see
     * lines on is a pad you expect to be able to point at. A text box cannot put a cursor where there is no
     * text, so the lines are made: tap the fourth empty rule and the note gains three blank lines and the
     * cursor sits on the fourth. That is what skipping down a paper page does — the lines were always there,
     * you simply had not written on them.
     *
     * <p>Only downward, and only into blank space. A tap among the writing means what it has always meant.
     */
    private void reachLine(float y) {
        if(page==null||readOnly)return;
        android.text.Layout out=page.getLayout();
        if(out==null)return;
        // Not on a page with nothing on it. A note is named by its first line, and a tap near the foot of
        // an empty page would push that name thirty rules down and leave the note without one.
        if(page.getText().toString().trim().isEmpty())return;
        int last=out.getLineCount()-1;
        int bottom=out.getLineBottom(last);
        float at=y+page.getScrollY()-page.getPaddingTop();
        if(at<=bottom)return;
        int height=out.getLineBottom(last)-out.getLineTop(last);
        if(height<=0)return;
        int down=(int)((at-bottom)/height)+1;
        // A tap far below the last line on a nearly empty page should not make a hundred of them.
        down=Math.min(down,40);
        StringBuilder blank=new StringBuilder();
        for(int i=0;i<down;i++)blank.append('\n');
        Editable text=page.getText();
        text.append(blank);
        page.setSelection(text.length());
    }

    /** Web and mail addresses in the note, marked so they can be seen and opened. */
    private void linkify() {
        if(page==null)return;
        Editable text=page.getText();
        for(URLSpan was:text.getSpans(0,text.length(),URLSpan.class))text.removeSpan(was);
        Linkify.addLinks(text,Linkify.WEB_URLS|Linkify.EMAIL_ADDRESSES);
    }

    /**
     * A link being edited stops being a link, at once.
     *
     * <p>Marking addresses happens when the typing stops, which is the right moment to find new ones and
     * the wrong moment to let go of an old one: backspace into the end of an address and it went on
     * looking like an address, underlined and openable, until the pause. So the first keystroke inside one
     * takes the mark off, and the next settle puts it back if what is left is still an address.
     */
    private void unlinkAround(int at) {
        if(page==null)return;
        Editable text=page.getText();
        int from=Math.max(0,at-1), to=Math.min(text.length(),at+1);
        for(URLSpan was:text.getSpans(from,to,URLSpan.class))text.removeSpan(was);
    }

    /** Opens whatever address was tapped, or says so plainly when nothing on the phone can open it. */
    private boolean followLink(float x,float y) {
        android.text.Layout out=page==null?null:page.getLayout();
        if(out==null)return false;
        int line=out.getLineForVertical((int)(y+page.getScrollY()));
        int at=out.getOffsetForHorizontal(line,x+page.getScrollX());
        URLSpan[] links=page.getText().getSpans(at,at,URLSpan.class);
        if(links.length==0)return false;
        openAddress(links[0].getURL());
        return true;
    }

    private void load(NoteStore.Note note) {
        if(edits!=saved)return;
        active=note;saved=edits;fill(note);
        // The blank start page asked for the keyboard; a note with writing on it arrived to be read instead.
        if(note.body!=null&&!note.body.trim().isEmpty())quiet();
        // The bar was drawn before this note existed on it, so the mark is asked again now that it does -
        // and so is whether the page takes writing, which the blank page it was dropped into did.
        askWhatIsOwed();refreshOwed();
        askWritable();
    }
    private void fill(NoteStore.Note note) {
        kept=note.body==null?"":note.body;keptRevision=note.revision;
        if(note.title==null)note.title="";
        keptTitle=note.title;
        loading=true;page.setText(note.body);loading=false;
        linkify();
        page.setSelection(page.getText().length());
        showTitle();
    }

    /** The open note's title, in the bar. Left alone while it is being typed in. */
    private void showTitle() {
        if(named==null||active==null||named.getParent()==null)return;
        String said=active.title==null?"":active.title.trim();
        if(!said.contentEquals(named.getText())){named.setText(said);named.setSelected(true);}
    }

    /**
     * The title, changed where it is. Where a note has none yet, the field opens holding the note's first
     * line, selected: most notes written before titles existed were named by that line, so taking it is one
     * tap, and typing anything else replaces it. The page itself is not touched either way.
     */
    private void renameNote() {
        if(active==null||named==null||nameHolder==null||named.getParent()==null)return;
        if(readOnly){toast("Read only");return;}
        final NoteStore.Note note=active;
        String offered=note.title.trim();
        if(offered.isEmpty()&&page!=null) {
            String text=page.getText().toString();
            int line=text.indexOf('\n');
            offered=(line<0?text:text.substring(0,line)).trim();
            if(offered.length()>TITLE_MOST)offered=offered.substring(0,TITLE_MOST);
        }
        editInPlace(named,said->{
            // The word goes back where the field was, whatever was typed.
            if(named.getParent()==null) {
                if(nameHolder.getChildCount()>0)nameHolder.removeViewAt(0);
                nameHolder.addView(named,0,new LinearLayout.LayoutParams(-1,-2));
            }
            if(active!=note)return;
            String now=said==null?"":said.trim();
            if(!now.equals(note.title)){note.title=now;edits++;save();}
            showTitle();
        },TITLE_MOST,offered);
    }

    /** As long as a title may be. Long enough to be a sentence; it rolls in the bar if it has to. */
    private static final int TITLE_MOST=120;

    private boolean blank(){return active!=null&&page.getText().toString().trim().isEmpty()&&active.title.trim().isEmpty();}
    /** A page nobody wrote on is thrown away rather than kept as an empty page. */
    private void closeNote() {
        if(active==null)return;
        // Not a copy somebody shares to be read: an empty one is theirs to fill, and would only come back.
        if(blank()&&!readOnly){final String id=active.id;handler.removeCallbacks(autoSave);active=null;background.submit(()->{store.remove(id);return null;},done->{},e->{});}
        else save();
    }
    private void blankPage(String book){NoteStore.Note note=new NoteStore.Note();note.book=book;write(note);}
    private void back() {
        final String was=writing;
        background.submit(()->{
            NoteStore.Note note=was==null?null:store.get(was);
            if(note==null)note=store.latest();
            if(note==null){note=new NoteStore.Note();note.book=store.someBook();}
            return note;},
            this::write,e->alert(READ_FAILED));
    }
    private void open(String id){background.submit(()->store.get(id),note->{if(note!=null)write(note);else back();},e->alert(READ_FAILED));}

    /** Hands the worker its own copy, so text typed while a write is in flight cannot change what is written. */
    private void save() {
        handler.removeCallbacks(autoSave);if(active==null||edits==saved||readOnly)return;
        // Addresses are marked when the typing stops rather than at every keystroke: the same moment the
        // note is written down, and cheap even on a long one.
        linkify();
        final NoteStore.Note note=active;note.body=page.getText().toString();note.updated=System.currentTimeMillis();
        // Each writing is counted. A count says what a clock cannot: whether a version that arrives from
        // somewhere else came after this one or beside it.
        final long seen=keptRevision;
        note.revision=Math.max(note.revision,seen)+1;
        final NoteStore.Note written=note.copy();final long attempt=edits;
        background.submit(()->store.saveFrom(written,seen),
            wrote->{
                   // The notebook had something newer than this page had seen, so nothing was written.
                   // The two are put together and the page is written again from there.
                   if(!wrote){changedUnderneath(written.id);return;}
                   if(active!=note)return;kept=written.body;keptRevision=written.revision;keptTitle=written.title;
                   saved=Math.max(saved,attempt);failed=false;saidState();
                   askWhatIsOwed();refreshOwed();
                   // And it goes, once the writing has stopped for as long as this note asks for.
                   // Sharing something once and then leaving every later word of it sitting here until
                   // somebody presses a button is a copy that silently drifts out of date, which is worse
                   // than no copy at all.
                   handler.removeCallbacks(sendSoon);
                   if(sendDelay>0)handler.postDelayed(sendSoon,sendDelay);},
            error->{if(active==note)warn();
                else if(active==null&&!shelves)recover(written);
                else alert("A note could not be saved and its last edit was not stored. Open it again to retype that change.");});
    }
    /**
     * What the note said, kept where its history can be read. Done when an editing session ends rather than
     * at every save, so the list reads as the note's history and not as a keystroke log.
     */
    private void keepVersion() {
        if(active==null)return;
        final String note=active.id;
        background.submit(()->{store.keepVersion(note,"");return null;},done->{},e->{});
    }

    /**
     * The open note, sent to whoever it reaches.
     *
     * <p>Quietly: this happens while somebody is writing, and a box over the page saying a relay was busy
     * would be worse than the delay it is reporting. What did not go is still owed, the mark beside the
     * name goes on saying so, and Sync now still says it in full.
     */
    private void sendOpenNote() {
        if(active==null||shelves)return;
        final String id=active.id;
        network.submit(()->Post.send(this,store,keys(),NoteStore.Branch.Kind.PAGE,id),
            // Asked again rather than assumed. The line was redrawn from the count it already had, so a
            // note that had just gone went on saying "Not sent yet" until it was closed and opened.
            done->{if(done.sent>0){askWhatIsOwed();refreshOwed();}},e->{});
    }

    /**
     * Everything this phone owes anybody, sent when it opens.
     *
     * <p>A phone that was off while somebody wrote comes back owing whatever was written, and the person
     * who wrote it has put their phone away. So the catching up is done by the one that was away.
     */
    private void sendWhatIsOwed() {
        network.submit(()->Post.send(this,store,keys(),NoteStore.Branch.Kind.LIBRARY,Sharing.EVERYTHING),
            done->{if(done.sent>0){askWhatIsOwed();refresh();refreshOwed();}},e->{});
    }

    private void warn(){edits++;failed=true;saidState();}
    private void retry(){if(failed&&active!=null)save();}
    private void recover(NoteStore.Note note){write(note);warn();alert("That note could not be saved. It is open again so you can retry.");}
    private TextWatcher watch(Runnable r){return new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){}public void afterTextChanged(Editable e){r.run();}};}

    // ---- what can be done with the open page --------------------------------------------------------------

    /**
     * The reading ladder, ten rungs of it, the steps growing as they go up because a size you can already
     * read needs a bigger push to be noticeably bigger. The ruled lines are drawn from the text's own line
     * height, so they close up and open out with the writing rather than staying a fixed pattern behind it.
     */
    private static final int[] SIZES={11,12,14,15,17,19,21,24,26,29};
    /**
     * The middle rung, and the one a pad starts on: 17, which is what everything else on the phone writes
     * in. An app that opens at its own idea of a size is an app you have to set up before you can use it,
     * so this one opens at the ordinary one and the ladder is a way to leave it, five rungs either way.
     * Sizes are kept in points and drawn in sp, so the phone's own text setting still moves them all.
     */
    private static final int FIRST_SIZE=4;
    /**
     * The one size things are read at. The writing, the name of a thing under its box, and every line of
     * the menu are the same size, because they are all words you read rather than furniture: a name you
     * cannot read is no better than writing you cannot read. Given as a rung, so that it moves with the
     * ladder like everything else.
     */
    private static final int READING=SIZES[FIRST_SIZE];

    /**
     * The quiet line: what a thing is, what state it is in, the sentence under a heading saying what the
     * part below is for. One rung down from the reading size, not four. These were written at 11 and 12,
     * which is smaller than a phone's own smallest setting and reads as small print — and small print is
     * what a page puts the words it would rather you did not read in. Everything here is meant to be read.
     */
    private static final int QUIET=SIZES[FIRST_SIZE-1];

    /**
     * A heading over a part of a box. Two rungs down and set in capitals with a little space between the
     * letters, which is what makes it a heading; being tiny never was.
     */
    private static final int HEADING=SIZES[FIRST_SIZE-2];

    /**
     * What a saved rung is worth. The rung is what is kept rather than the size, so that re-cutting the
     * ladder moves everybody with it instead of stranding them: a pad kept in the middle stays in the
     * middle. A size saved by an older ladder is not read at all, and that pad opens at the ordinary size.
     */
    private int onRung(int rung){return SIZES[Math.max(0,Math.min(SIZES.length-1,rung))];}

    /** The rung the page is standing on, the nearest one for a pad saved before the ladder had ten. */
    private int step(int size) {
        int nearest=0;
        for(int at=0;at<SIZES.length;at++)if(Math.abs(SIZES[at]-size)<Math.abs(SIZES[nearest]-size))nearest=at;
        return nearest;
    }

    /** One rung up or down, straight onto the page under the open menu. False at either end of the ladder. */
    private boolean stepBy(int by) {
        int at=step(textSize)+by;
        if(at<0||at>=SIZES.length)return false;
        setSize(SIZES[at]);
        return true;
    }


    private void setSize(int size) {
        textSize=size;
        if(page!=null){page.setTextSize(textSize);page.setLineSpacing(dp(6),1f);}
        // The pad is the measure: everything else is drawn in proportion to it, so it all moves together.
        if(stage!=null)resize(stage);
        if(showing!=null)resize(showing.body());
        final int kept=size;
        background.submit(()->{getSharedPreferences("settings",MODE_PRIVATE).edit().putInt("rung",step(kept)).apply();return null;},
            done->{},e->{});
    }

    /**
     * The text of whatever the menu was opened on — a page, a book, a collection, the whole pad. The page
     * being written on is taken from the screen, so what leaves is what can be seen, including the words
     * typed a moment ago; anything larger is read from the notebook, where all of it is already saved.
     */
    private void withText(NoteStore.Branch thing,String nothing,Consumer<String> then) {
        if(thing.kind==NoteStore.Branch.Kind.PAGE&&active!=null&&active.id.equals(thing.id)) {
            String text=page.getText().toString();
            if(text.trim().isEmpty()&&active.title.trim().isEmpty()){toast(nothing);return;}
            then.accept(active.title.trim().isEmpty()?text:active.title.trim()+"\n\n"+text);
            return;
        }
        final NoteStore.Branch.Kind kind=thing.kind;final String id=thing.id;
        background.submit(()->store.gather(kind,id),
            text->{if(text.trim().isEmpty())toast(nothing);else then.accept(text);},e->alert(READ_FAILED));
    }

    /**
     * Hands it to whatever the reader picks — a messenger, mail, anything. It leaves Mininotes in plain
     * text through that app, which is nothing to do with sharing over Minima, and is their choice.
     */
    private void sendElsewhere(NoteStore.Branch thing) {
        withText(thing,"Nothing to send",text->{
            save();
            Intent send=new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,text);
            startActivity(Intent.createChooser(send,"Send"));
        });
    }

    // ---- the shelves, one level at a time ----------------------------------------------------------------

    private Step here(){return trail.get(trail.size()-1);}

    /** Opens the shelves at the book being written in, with the trail above it already filled in. */
    /** Leaving the page ends an editing session, so what it says now goes into its history. */
    private void openBookOf(String book) {
        save();keepVersion();
        if(active!=null)writing=active.id;
        background.submit(()->{
            String collection=store.collectionOf(book);
            return new String[]{collection,store.collectionName(collection),store.bookName(book)};
        },at->{
            trail.clear();
            trail.add(new Step(NoteStore.Branch.Kind.LIBRARY,Sharing.EVERYTHING,"All collections"));
            trail.add(new Step(NoteStore.Branch.Kind.COLLECTION,at[0],at[1]));
            trail.add(new Step(NoteStore.Branch.Kind.BOOK,book,at[2]));
            browse();
        },e->alert(READ_FAILED));
    }
    private void openLibrary() {
        trail.clear();trail.add(new Step(NoteStore.Branch.Kind.LIBRARY,Sharing.EVERYTHING,"All collections"));browse();
    }
    private void enter(NoteStore.Branch branch) {
        trail.add(new Step(branch.kind,branch.id,branch.name));browse();
    }
    private void climb(int step) {
        while(trail.size()>step+1)trail.remove(trail.size()-1);
        browse();
    }

    private void browse() {
        closeNote();active=null;shelves=true;keyboard(false);shell();
        if(trail.isEmpty())trail.add(new Step(NoteStore.Branch.Kind.LIBRARY,Sharing.EVERYTHING,"All collections"));
        Step step=here();
        LinearLayout top=bar();
        // Every level's bar is built to the same measure — one place on the left, one in the middle, two on
        // the right — so the name never moves as you go in and out. The arrow exists only where there is a
        // level above; where there is not, its place is kept empty rather than closed up.
        if(carrying!=null)top.addView(heavy("←","Stop moving",30,INK,v->{carrying=null;browse();}));
        else if(trail.size()>1)top.addView(heavy("←","Up to "+trail.get(trail.size()-2).name,30,INK,v->climb(trail.size()-2)));
        else top.addView(new View(this),new LinearLayout.LayoutParams(dp(48),dp(48)));
        // The shelves are the app's own rooms, so they say whose they are; the note screen stays bare, its
        // one line being kept for what is not saved or not sent.
        TextView called=label(here().kind==NoteStore.Branch.Kind.LIBRARY
            ?getString(R.string.app_name):here().name,READING,MUTED);
        called.setGravity(Gravity.CENTER);called.setSingleLine(true);
        called.setEllipsize(TextUtils.TruncateAt.END);
        if(here().kind==NoteStore.Branch.Kind.LIBRARY) {
            // At the top of the app, which build this is - and, when a newer one is out, the way to it.
            LinearLayout named=column();named.setGravity(Gravity.CENTER);
            named.addView(called,new LinearLayout.LayoutParams(-1,-2));
            versionLine=label("",QUIET,MUTED);versionLine.setGravity(Gravity.CENTER);versionLine.setSingleLine(true);
            named.addView(versionLine,new LinearLayout.LayoutParams(-1,-2));
            versionShown();
            top.addView(named,new LinearLayout.LayoutParams(0,-2,1));
        } else {versionLine=null;top.addView(called,new LinearLayout.LayoutParams(0,-2,1));}
        // What others share with you is not kept apart: it stands among your own things, saying on itself
        // that it came from somebody else. So there is nothing up here for it.
        // One menu: this level, then the app. Everything about a thing is managed from inside it.
        // A place is not a thing that is shared, so it wears no mark: not the archive, the bin, or favourites.
        if(carrying==null&&here().kind!=NoteStore.Branch.Kind.ARCHIVE&&here().kind!=NoteStore.Branch.Kind.BIN
            &&!amongFavourites())top.addView(syncMark(standingIn()));
        if(carrying==null)top.addView(tap("⋮",here().name,22,INK,this::levelMenu));
        else top.addView(new View(this),new LinearLayout.LayoutParams(dp(48),dp(48)));
        root.addView(top);
        if(carrying!=null)root.addView(carryingBar());
        ScrollView scroll=new ScrollView(this);scroll.setClipToPadding(false);scroller=scroll;
        // A level is a desktop: its things are laid out across it, not stacked down it. Destinations, the
        // archive and the bin stay lists, because those are read rather than arranged.
        desktop=cards&&carrying==null&&(here().kind==NoteStore.Branch.Kind.LIBRARY
            ||here().kind==NoteStore.Branch.Kind.COLLECTION||here().kind==NoteStore.Branch.Kind.BOOK
            ||amongFavourites());
        if(desktop) {
            GridLayout grid=new GridLayout(this);grid.setColumnCount(columns());
            grid.setPadding(dp(12),dp(4),dp(12),dp(28));
            tiles=grid;rows=null;scroll.addView(grid);
            grid.setOnDragListener(this::dragOver);
        } else {
            tiles=null;rows=column();rows.setPadding(0,0,0,dp(28));scroll.addView(rows);
            rows.setOnDragListener(this::dragOver);
        }
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        // A collection and a book keep files the same way a note does - a deed with the house, a receipt
        // with the order - so the strip is at the foot of every screen, not only the one it started on.
        // Not at the top: a file belongs with a collection, a book or a note, and All collections is none
        // of those. A clip there would be a control that refuses, which is worse than no control.
        if(carrying==null&&(here().kind==NoteStore.Branch.Kind.COLLECTION||here().kind==NoteStore.Branch.Kind.BOOK))
            root.addView(fileStrip());
        refresh();
    }

    /**
     * The foot of the screen: a paperclip, and beside it everything this thing can reach - what is kept
     * here, and then what the book and the collection around it keep, because a file put on a collection is
     * meant for everything in it. It is a clip rather than a menu row because attaching something is done
     * while looking at the thing, and a menu is somewhere you go.
     */
    private View fileStrip() {
        LinearLayout strip=new LinearLayout(this);
        strip.setGravity(Gravity.CENTER_VERTICAL);strip.setPadding(dp(6),0,dp(6),dp(4));
        // The clip sits in the corner the thumb reaches, and what is already kept scrolls away from it,
        // so the control stays put however many files there are.
        HorizontalScrollView along=new HorizontalScrollView(this);
        along.setHorizontalScrollBarEnabled(false);
        attached=new LinearLayout(this);attached.setGravity(Gravity.CENTER_VERTICAL);
        along.addView(attached,new FrameLayout.LayoutParams(-2,-2));
        strip.addView(along,new LinearLayout.LayoutParams(0,-2,1));
        strip.addView(heavy("\uD83D\uDCCE","Attach a file",20,MUTED,v->attach()));
        showFiles();
        return strip;
    }

    /**
     * Whether the screen is the place that gathers favourites.
     *
     * <p>It looks like a collection and is not one. Nothing lives in it: what it lists is where it always
     * was, so nothing is added here, nothing is dragged about here, and opening a thing goes to where the
     * thing really is - with the way back being the way back from there. Starring something was never
     * going to be allowed to move it: a note moved out of a shared book is a note taken away from
     * everybody it was shared with.
     */
    private boolean amongFavourites() {
        return shelves&&!trail.isEmpty()&&here().kind==NoteStore.Branch.Kind.FAVOURITES;
    }

    /** What the things of one kind are called, over them, where several kinds are listed together. */
    private static String manyOf(NoteStore.Branch.Kind kind) {
        return kind==NoteStore.Branch.Kind.COLLECTION?"Collections":kind==NoteStore.Branch.Kind.BOOK?"Books":"Notes";
    }

    /** A heading over part of a level, the whole way across whether the level is tiles or lines. */
    private View over(String words) {
        TextView head=part(words);
        head.setPadding(dp(desktop?10:22),dp(14),dp(12),dp(4));
        if(desktop) {
            GridLayout.LayoutParams across=new GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED),GridLayout.spec(0,columns()));
            across.width=tileSize()*columns();
            head.setLayoutParams(across);
        }
        return head;
    }

    /** Held down, where things are not picked up: what can be done to it, and no lifting. */
    private void menuOnly(View on,final NoteStore.Branch branch) {
        on.setTag(branch);
        on.setOnLongClickListener(v->{menuFor(v,branch);return true;});
    }

    /** A small star, for the corner or the end of whatever is a favourite. */
    private TextView star(int px) {
        TextView star=label("\u2605",QUIET,INK);
        star.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,px*0.62f);
        star.setGravity(Gravity.CENTER);star.setIncludeFontPadding(false);
        GradientDrawable disc=new GradientDrawable();
        disc.setShape(GradientDrawable.OVAL);disc.setColor(PAPER);
        star.setBackground(disc);
        star.setContentDescription("A favourite");
        return star;
    }

    /** Whatever the screen is about: the open note, or the level of the shelves being looked at. */
    private NoteStore.Branch.Kind holding() {
        return shelves?here().kind:NoteStore.Branch.Kind.PAGE;
    }
    private String holdingId() {
        return shelves?here().id:(active==null?"":active.id);
    }

    private String adding(NoteStore.Branch.Kind kind) {
        return kind==NoteStore.Branch.Kind.LIBRARY?"New collection":kind==NoteStore.Branch.Kind.COLLECTION?"New book":"New note";
    }
    private Sharing.Scope scopeOf(NoteStore.Branch.Kind kind) {
        return kind==NoteStore.Branch.Kind.LIBRARY?Sharing.Scope.LIBRARY
            :kind==NoteStore.Branch.Kind.COLLECTION?Sharing.Scope.COLLECTION:Sharing.Scope.BOOK;
    }

    /** The level you are standing in, as something that can be renamed, moved, shared and deleted. */
    private NoteStore.Branch standingIn() {
        Step step=here();
        String parent=trail.size()>1?trail.get(trail.size()-2).id:"";
        return new NoteStore.Branch(step.kind,step.id,parent,step.name,"",0,0,true);
    }

    /**
     * One menu, wherever you open it. It always says the same things in the same order: what the app is set
     * to, then the colour of whatever you are standing in, then what can be done to that thing, then what
     * can be done with its text, then the app itself. A menu that changes shape from screen to screen has to
     * be read each time; this one is learnt once.
     */
    private void menuFor(View anchor,NoteStore.Branch thing) {
        // The keyboard goes first. A menu opened while writing was drawn into whatever was left above the
        // keys - which on a note being typed in is about half the screen - so everything past the middle
        // of it was off the bottom: Sync now, Search, the archive, the bin, Profile, About. It scrolls, so
        // nothing was unreachable in principle; it was unreachable in the way that matters, which is that
        // the rows were not there and there was no sign of any more.
        InputMethodManager keys=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
        if(keys!=null&&anchor!=null)keys.hideSoftInputFromWindow(anchor.getWindowToken(),0);
        keyboard(false);
        final NoteStore.Branch here=thing;
        Sheet sheet=new Sheet();
        sheet.ladder();
        sheet.palette(here);
        sheet.tones(here);
        sheet.line();
        // First, because a thing you did not mean is looked for straight away and not hunted for. And
        // only there when there is something to put back: it used to sit greyed at the top of every menu
        // saying "Nothing to undo", which is a row spent telling somebody about a thing they cannot do.
        if(undoHow!=null){sheet.row("Undo "+undoWhat,this::undo);sheet.line();}
        if(here.kind==NoteStore.Branch.Kind.LIBRARY) {
            // Nothing is shared from here. Who receives what is decided on a collection, a book or a note —
            // the things people actually mean — and "everything" was a way to mean all of them at once
            // without ever saying which. Sending is different: it is about what is owed, not about who.
            sheet.row("Sync now",()->syncNow(here.kind,here.id,here.name));
        }
        else if(here.kind==NoteStore.Branch.Kind.FAVOURITES) {
            // A place, and one with nothing to do to it: what is in it is changed from the thing itself.
        }
        else if(here.kind==NoteStore.Branch.Kind.ARCHIVE||here.kind==NoteStore.Branch.Kind.BIN) {
            // A place is not a thing: it cannot be renamed, moved, shared or put away, only emptied.
            if(here.kind==NoteStore.Branch.Kind.BIN)sheet.row("Empty the bin",this::askEmptyBin);
            else sheet.row("Nothing to put away",()->toast("Archive a collection, book or note from its own menu"));
        } else {
            // Nothing here renames anything: a note is named by its first line, and a collection or a book
            // by its own name where it is written, under its tile or on its card.
            if(here.kind==NoteStore.Branch.Kind.PAGE)sheet.row("Versions",()->versions(here));
            if(here.kind!=NoteStore.Branch.Kind.COLLECTION)sheet.row("Move somewhere else",()->{carrying=here;browse();});
            // One row. There were three - Share, Shared with, Sync now - and each opened a different
            // thing, so sharing a note meant knowing which of three words was the one. They all lead to
            // the same box now, which is the one the mark on the thing opens: who has it, the button
            // that syncs it, and how to add somebody.
            sheet.row("Sharing",()->aboutSharing(here));
            final TextView hand=sheet.dimRow("\u2606 Favourite");
            background.submit(()->store.favourite(here.kind,here.id),
                already->sheet.wakeRow(hand,Boolean.TRUE.equals(already)?"\u2605 Favourite":"\u2606 Favourite",
                    ()->keepToHand(here,!Boolean.TRUE.equals(already))),e->{});
            sheet.row("Archive",()->putAway(here,false));
            sheet.row("Delete",()->putAway(here,true));
        }
        // A place is not a thing: what is waiting in the archive or the bin is not copied out or sent on
        // from here. Whatever is in them can be put back first, and then it is a thing again.
        if(here.kind!=NoteStore.Branch.Kind.ARCHIVE&&here.kind!=NoteStore.Branch.Kind.BIN
                &&here.kind!=NoteStore.Branch.Kind.FAVOURITES) {
            sheet.line();
            // One way out, not two: a share sheet already offers the clipboard among everywhere else it can
            // go, and "copy all" at a collection never said what all of it would look like when it landed.
            sheet.row("Send to another app",()->sendElsewhere(here));
        }
        appRows(sheet);
        sheet.show(anchor);
    }

    /**
     * Beside the dots: whether what you are looking at is up to date with everybody it reaches, and a tap
     * to deal with it. A thing that reaches nobody shows nothing - a mark that is always there says nothing
     * by being there. It is asked for in the background, so opening a screen never waits on it.
     *
     * @param thing what the screen is about, which is what the tap will sync
     */
    private View syncMark(final NoteStore.Branch thing) {
        final boolean followsThePage=thing.kind==NoteStore.Branch.Kind.PAGE;
        // A picture and, where there is one, a number beside it.
        final LinearLayout mark=new LinearLayout(this);
        mark.setGravity(Gravity.CENTER);
        mark.setMinimumWidth(dp(48));mark.setMinimumHeight(dp(48));
        mark.setBackgroundResource(borderlessFeedback());
        mark.setPadding(dp(4),0,dp(4),0);
        ImageView ring=new ImageView(this);
        mark.addView(ring,new LinearLayout.LayoutParams(dp(20),dp(20)));
        TextView many=label("",HEADING,MUTED);
        many.setPadding(dp(3),0,0,0);
        mark.addView(many);
        // The tap has to mean the note that is open, for the same reason the mark does. And what it does
        // is what the mark is showing: an arrow is something waiting to go, so one touch sends it; anything
        // else is a question about who has it, so one touch answers it. Held, it always answers.
        mark.setOnClickListener(v->{
            NoteStore.Branch what=followsThePage?openNoteAsThing(thing):thing;
            if(v.getTag()==Mark.What.WAITING)syncNow(what.kind,what.id,what.name);
            else aboutSharing(what);
        });
        mark.setOnLongClickListener(v->{aboutSharing(followsThePage?openNoteAsThing(thing):thing);return true;});
        mark.setVisibility(View.GONE);
        owedMark=mark;
        owedAsk=()->askOwed(mark,followsThePage?openNoteAsThing(thing):thing);
        askOwed(mark,followsThePage?openNoteAsThing(thing):thing);
        return mark;
    }

    /** How long this note waits, asked of the notebook once when it is opened and once when it changes. */
    private void askPause(final String id) {
        background.submit(()->store.pauseFor(NoteStore.Branch.Kind.PAGE,id),
            seconds->{
                if(active==null||!active.id.equals(id))return;
                int said=(Integer)seconds;
                sendDelay=said<=0?0L:said*1000L;
            },e->{sendDelay=NoteStore.USUALLY*1000L;});
    }

    /** The note that is open now, or the one the bar was built with if there is none. */
    private NoteStore.Branch openNoteAsThing(NoteStore.Branch built) {
        if(shelves||active==null)return built;
        String said=named==null?"":named.getText().toString().trim();
        return new NoteStore.Branch(NoteStore.Branch.Kind.PAGE,active.id,active.book,
            said.isEmpty()?"this note":said,"",0,0,false,active.colour);
    }

    /**
     * The one mark, tapped: everything about who else has this thing, in one box.
     *
     * <p>There were two marks side by side - which way a thing was shared, and whether it had gone - and
     * each opened a box of its own, so the answer to "what is the state of this?" was in two places and a
     * person had to know which circle to ask. One mark now says the three things a thing can be: only
     * here, shared and everybody has it, shared and somebody is waiting. One tap says who, what each of
     * them may do, and sends what is waiting.
     */
    private void aboutSharing(final NoteStore.Branch thing) {
        // The keyboard goes first, as it does for the menu: a box drawn into the half of the screen the
        // keys have left is a box with no room round it to tap.
        InputMethodManager keys=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
        if(keys!=null&&stage!=null)keys.hideSoftInputFromWindow(stage.getWindowToken(),0);
        final Sharing.Scope scope=thing.scope();
        // Everything at once has no list of people of its own: what it can do is send what is owed.
        if(scope==null||scope==Sharing.Scope.LIBRARY){syncNow(thing.kind,thing.id,thing.name);return;}
        background.submit(()->store.cameFrom(thing.kind,thing.id),origin->{
            if(origin==null||origin.isEmpty()){sharedWith(scope,thing.id,thing.name);return;}
            sharedWithMe(new NoteStore.Branch(thing.kind,thing.id,thing.parent,thing.name,"",0,0,false,
                thing.colour,0,Sharing.State.THEIRS,origin));
        },e->alert(READ_FAILED));
    }

    /** The mark in the bar turned to the arrow at once, because the page has just been written in. */
    private void wantsSending() {
        if(owedMark==null||owedMark.getParent()==null||owedMark.getTag()==Mark.What.WAITING)return;
        owedMark.setTag(Mark.What.WAITING);
        wear(owedMark,Mark.What.WAITING,ACCENT,"");
        owedMark.setContentDescription("Shared, and waiting to go. Tap to sync now. Hold to see who.");
    }

    /** What this thing owes, asked again. Off the interface thread, and dropped if the screen has moved on. */
    private void askOwed(final View mark,final NoteStore.Branch thing) {
        final NoteStore.Branch.Kind kind=thing.kind;final String id=thing.id;
        // The thing's own scope, not one worked out from its kind: scopeOf answers for the three levels a
        // share is *made* at and calls a note a book, which asked the wrong question about every note.
        final Sharing.Scope scope=thing.scope();
        background.submit(()->{
            int owed=store.owed(kind,id).size();
            // Shared itself, or holding anything that is: the same question its tile answers.
            boolean shared=scope==null||scope==Sharing.Scope.LIBRARY
                ?!store.shares().isEmpty()
                :store.sharedAtAll(kind,id);
            return new int[]{owed,shared?1:0,store.pausedHere(kind,id)?1:0};
        },said->{
            if(mark.getParent()==null)return;
            int owed=said[0];boolean shared=said[1]==1;final boolean stopped=said[2]==1;
            mark.setVisibility(View.VISIBLE);
            // Three states, always one of them showing. A thing that reaches nobody says so quietly rather
            // than by not being there: an indicator you have to remember the absence of is not one.
            Mark.What first=!shared?Mark.What.HERE:owed>0?Mark.What.WAITING:Mark.What.GONE;
            Mark.What which=first;
            if(mark==owedMark&&kind==NoteStore.Branch.Kind.PAGE)pageShared=shared;
            // Unless the page holds words the notebook has not seen yet: then it is waiting whatever the
            // notebook says, and stays an arrow until those words have been written down and asked about.
            if(mark==owedMark&&kind==NoteStore.Branch.Kind.PAGE&&shared&&edits!=saved)which=Mark.What.WAITING;
            // Stopped taking it in outranks the rest: it is the one thing here that wants putting right.
            if(stopped)which=Mark.What.PAUSED;
            mark.setTag(which);
            wear(mark,which,which==Mark.What.WAITING?ACCENT:MUTED,
                // The number only where there is more than one: a ring with a 1 beside it says the same
                // thing twice, and the mark is meant to be read at a glance rather than counted.
                which==Mark.What.WAITING&&owed>1?(owed>9?"9+":String.valueOf(owed)):"");
            mark.setContentDescription(stopped?"Paused. This phone is not receiving it. Tap to resume."
                :!shared?"Only on this phone. Tap to share it."
                :which!=Mark.What.WAITING?"Shared, and everybody has it. Tap to see who."
                :owed<=1?"Shared, and waiting to go. Tap to sync now. Hold to see who."
                :"Shared, and "+owed+" notes are waiting to go. Tap to sync now. Hold to see who.");
        },e->{});
    }

    /**
     * The mark drawn on a line of the bar: the ring on the left, and a count beside it where there is one.
     * The drawing takes its size from the text, so it steps with the reading ladder like everything else.
     */
    private void wear(View holder,Mark.What what,int ink,String count) {
        Mark.PAPER_HOLE=PAPER;
        LinearLayout row=(LinearLayout)holder;
        ImageView ring=(ImageView)row.getChildAt(0);
        TextView many=(TextView)row.getChildAt(1);
        int side=Math.round(READING*reading()*1.3f*getResources().getDisplayMetrics().scaledDensity);
        Mark drawn=new Mark(what,ink);
        drawn.sized(side);
        ring.setImageDrawable(drawn);
        android.view.ViewGroup.LayoutParams size=ring.getLayoutParams();
        size.width=side;size.height=side;ring.setLayoutParams(size);
        many.setText(count);
        many.setTextColor(ink);
        many.setVisibility(count.isEmpty()?View.GONE:View.VISIBLE);
    }

    /** The mark, worked out again, after something happened that could have changed what is owed. */
    private void refreshOwed() {
        if(owedAsk!=null&&owedMark!=null&&owedMark.getParent()!=null)owedAsk.run();
    }

    /** The level you are standing in, as the thing this menu is about. */
    private void levelMenu(View anchor) { menuFor(anchor,standingIn()); }

    /** The open page, as the thing its menu is about. */
    private void pageMenu(View anchor) {
        if(active==null)return;
        String text=page.getText().toString().trim();
        int first=text.indexOf('\n');
        String name=(first<0?text:text.substring(0,first)).trim();
        menuFor(anchor,new NoteStore.Branch(NoteStore.Branch.Kind.PAGE,active.id,active.book,
            name.isEmpty()?"This note":name,"",0,0,false,active.colour));
    }

    private void appRows(Sheet sheet) {
        sheet.line();
        if(shelves)sheet.row(cards?"Show as a list":"Show as cards",()->{
            cards=!cards;
            final boolean kept=cards;
            background.submit(()->{getSharedPreferences("settings",MODE_PRIVATE).edit().putBoolean("cards",kept).apply();return null;},
                done->{},e->{});
            if(shelves)browse();
        });
        // Where things go when they leave the shelves: one archive and one bin for the whole pad, holding
        // collections, books and notes together. They are near the foot of the menu because they are where
        // you go looking for something afterwards, not part of what you are doing now.
        sheet.row("Search",this::searching);
        sheet.row("Tree view",this::wholeTree);
        sheet.row("Open the archive",()->enter(new NoteStore.Branch(NoteStore.Branch.Kind.ARCHIVE,
            NoteStore.ARCHIVE,"","Archive","",0,0,true)));
        sheet.row("Open the bin",()->enter(new NoteStore.Branch(NoteStore.Branch.Kind.BIN,
            NoteStore.BIN,"","Bin","",0,0,true)));
        // Where anybody would look for it: with the app's own things, and next to the one other row that
        // is about this pad rather than about what is on it.
        // A newer build, for as long as this phone has heard of one and is not it. First among the rows
        // about the pad itself, and absent the rest of the time: a row that is always there saying
        // "no update" is a row everybody learns not to read.
        final String newer=newerKnown();
        if(!newer.isEmpty())sheet.row("Update to v"+newer,()->announce(newer));
        // One place for everything about this pad rather than about what is on it: what you are called,
        // how anybody reaches you, and whether the node behind that is working.
        // Whether the notebook is encrypted, in the menu that is always a tap away, not only inside Profile.
        sheet.row(PhoneLock.locked(this)?"🔒 Encrypted":"Not encrypted · Security",this::security);
        sheet.row("Profile",this::profile);
        // Above About, because About is where you go when you have finished with the app and this is where
        // you go when you have not.
        sheet.row("Feedback",this::feedback);
        sheet.row("Share Mininotes",this::shareApp);
        sheet.row("About",this::about);
    }

    /**
     * The menu under a ⋮, and the one that comes up when a tile is held. Drawn into the app's own window
     * rather than a popup one, because a phone's desktop lets you keep moving: the finger that opened this
     * still belongs to the tile underneath, so sliding away from it picks the tile up instead.
     */
    private final class Sheet {
        private final LinearLayout body=column();
        /** The menu can be longer than the room left for it, so it is a thing you can scroll to the end of. */
        private final ScrollView holder=new ScrollView(MainActivity.this);
        private final FrameLayout scrim=new FrameLayout(MainActivity.this);
        /** How each ladder redraws itself: the menu is repainted without being rebuilt under the finger. */
        private final List<Runnable> marks=new ArrayList<>();

        Sheet() {
            holder.addView(body,new FrameLayout.LayoutParams(-1,-2));
            holder.setBackground(shape(SHEET,0,false));
            holder.setElevation(dp(12));
            scrim.setBackgroundColor(0x22000000);
            scrim.setOnClickListener(v->close());
            scrim.setOnTouchListener((v,event)->{
                if(event.getActionMasked()!=MotionEvent.ACTION_MOVE)return false;
                if(heldTile==null||dragging!=null)return false;
                float moved=Math.max(Math.abs(event.getRawX()-heldFrom[0]),
                                     Math.abs(event.getRawY()-heldFrom[1]));
                if(moved<=ViewConfiguration.get(MainActivity.this).getScaledTouchSlop())return false;
                View tile=heldTile;NoteStore.Branch what=heldBranch;
                heldTile=null;heldBranch=null;
                close();
                lift(tile,what);
                return true;
            });
        }

        LinearLayout body(){return body;}

        void close() {
            if(showing==this)showing=null;
            if(scrim.getParent()!=null)stage.removeView(scrim);
            if(painted){painted=false;if(shelves)refresh();}
        }

        void repaint() {
            holder.setBackground(shape(SHEET,0,false));
            for(int at=0;at<body.getChildCount();at++) {
                View child=body.getChildAt(at);
                if(child instanceof TextView)((TextView)child).setTextColor(INK);
                else if(!(child instanceof LinearLayout))child.setBackgroundColor(LINE);
            }
            for(Runnable mark:marks)mark.run();
        }

        void row(String words,Runnable does) {
            TextView row=label(words,READING,INK);
            row.setPadding(dp(20),dp(14),dp(20),dp(14));
            row.setBackgroundResource(touchFeedback());
            row.setOnClickListener(v->{close();does.run();});
            body.addView(row,new LinearLayout.LayoutParams(-1,-2));
        }

        /**
         * A row whose words are asked for in the background, because the count in them is a question for
         * the notebook. Until the answer comes it reads greyed and does nothing, which is what it will go
         * on doing if the answer is nobody: the row is there either way, so the menu keeps its shape.
         */
        TextView dimRow(String words) {
            TextView row=label(words,READING,MUTED);
            row.setPadding(dp(20),dp(14),dp(20),dp(14));
            body.addView(row,new LinearLayout.LayoutParams(-1,-2));
            return row;
        }

        /** The same row, once there turns out to be something behind it. */
        void wakeRow(TextView row,String words,Runnable does) {
            row.setText(words);row.setTextColor(INK);
            row.setBackgroundResource(touchFeedback());
            row.setOnClickListener(v->{close();does.run();});
        }

        void line() {
            View rule=new View(MainActivity.this);rule.setBackgroundColor(LINE);
            LinearLayout.LayoutParams place=new LinearLayout.LayoutParams(-1,Math.max(1,dp(1)));
            place.setMargins(0,dp(6),0,dp(6));
            body.addView(rule,place);
        }

        /** The reading ladder: A− and A+ either side of the rungs, with the page standing on one of them. */
        void ladder() {
            LinearLayout row=new LinearLayout(MainActivity.this);
            row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(6),dp(4),dp(6),dp(4));
            final LinearLayout rungs=new LinearLayout(MainActivity.this);
            rungs.setGravity(Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);
            // Each rung is worth reaching for, so the bar is drawn inside a square you can actually hit.
            for(int step=0;step<SIZES.length;step++) {
                final int which=step;
                FrameLayout reach=new FrameLayout(MainActivity.this);
                reach.setPadding(dp(3),dp(10),dp(3),dp(10));
                View rung=new View(MainActivity.this);
                reach.addView(rung,new FrameLayout.LayoutParams(dp(3),dp(7)+step*dp(2),Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL));
                reach.setContentDescription("Text size "+(step+1)+" of "+SIZES.length);
                reach.setOnClickListener(v->setSize(SIZES[which]));
                rungs.addView(reach,new LinearLayout.LayoutParams(0,-2,1));
            }
            final TextView smaller=tap("A−","Smaller text",15,INK,null);
            final TextView bigger=tap("A+","Bigger text",22,INK,null);
            final Runnable mark=()->{
                int at=step(textSize);
                for(int rung=0;rung<rungs.getChildCount();rung++)
                    ((FrameLayout)rungs.getChildAt(rung)).getChildAt(0).setBackgroundColor(rung<=at?ACCENT:LINE);
                smaller.setTextColor(at>0?INK:MUTED);
                bigger.setTextColor(at<SIZES.length-1?INK:MUTED);
                rungs.setContentDescription("Text size, step "+(at+1)+" of "+SIZES.length);
            };
            smaller.setOnClickListener(v->{if(stepBy(-1))mark.run();});
            bigger.setOnClickListener(v->{if(stepBy(1))mark.run();});
            marks.add(mark);
            row.addView(smaller);
            row.addView(rungs,new LinearLayout.LayoutParams(0,-2,1));
            row.addView(bigger);
            mark.run();
            body.addView(row,new LinearLayout.LayoutParams(-1,-2));
        }

        /**
         * The colours one thing can be given, drawn as the colours themselves with the one it has ringed.
         * The first is no colour at all, which is the paper, and which is what everything starts as. One
         * scale, and it is for colour: light and dark is the phone's business, not a row of greys in here.
         *
         * <p>Choosing repaints the page behind the menu and the menu with it, so the choice is made by
         * looking at the pad rather than by imagining it.
         */
        void palette(final NoteStore.Branch thing) {
            if(thing==null||thing.scope()==null)return;
            final LinearLayout colours=new LinearLayout(MainActivity.this);
            colours.setGravity(Gravity.CENTER_VERTICAL);colours.setPadding(dp(10),dp(4),dp(10),dp(4));
            final boolean whole=thing.kind==NoteStore.Branch.Kind.LIBRARY;
            chosen[0]=whole?libraryColour():thing.colour;
            for(int colour=0;colour<Tint.count();colour++) {
                final int which=colour;
                FrameLayout reach=new FrameLayout(MainActivity.this);
                reach.setPadding(dp(2),dp(9),dp(2),dp(9));
                reach.addView(new View(MainActivity.this),new FrameLayout.LayoutParams(dp(20),dp(20),Gravity.CENTER));
                reach.setContentDescription(Tint.NAMES[colour]+" for "+thing.name);
                reach.setOnClickListener(v->{
                    if(chosen[0]==which)return;
                    chosen[0]=which;paintThing(thing,which);repaintMarks();
                });
                colours.addView(reach,new LinearLayout.LayoutParams(0,-2,1));
            }
            final Runnable mark=()->{
                for(int colour=0;colour<colours.getChildCount();colour++) {
                    GradientDrawable blob=new GradientDrawable();
                    blob.setShape(GradientDrawable.OVAL);
                    blob.setColor(Tint.known(colour)?Tint.of(colour,darkPaper()):PAPER);
                    blob.setStroke(dp(colour==chosen[0]?3:1),colour==chosen[0]?INK:LINE);
                    ((FrameLayout)colours.getChildAt(colour)).getChildAt(0).setBackground(blob);
                }
            };
            marks.add(mark);
            mark.run();
            body.addView(colours,new LinearLayout.LayoutParams(-1,-2));
            if(whole)return;
            // The trail knows a level's name, not its colour, so the ring is confirmed from the notebook.
            final NoteStore.Branch.Kind kind=thing.kind;final String id=thing.id;
            // The colour arrives after the menu is drawn, and the tone band is drawn in it, so everything
            // in here is repainted rather than only the ring - otherwise the ring says red and the band
            // below it goes on showing the nothing it was built with.
            background.submit(()->store.colourOf(kind,id),
                stored->{if(chosen[0]!=stored){chosen[0]=stored;repaintMarks();}},e->{});
        }

        /**
         * How loudly a colour lands: pastel at one end, the colour itself at the other. Drawn as the wash
         * each tone would actually make, in the colour of the thing you are standing in, so what is being
         * chosen is what you are looking at. One setting for the whole pad, like the reading size.
         */
        void tones(final NoteStore.Branch thing) {
            // A slider with no word on it is a thing to be tried to find out what it does.
            TextView what=label("Colour strength",QUIET,MUTED);
            what.setPadding(dp(20),dp(2),dp(20),0);
            body.addView(what,new LinearLayout.LayoutParams(-1,-2));
            LinearLayout row=new LinearLayout(MainActivity.this);
            row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(16),dp(4),dp(16),dp(10));
            final android.widget.SeekBar slide=new android.widget.SeekBar(MainActivity.this);
            slide.setMax(Tint.TONES.length-1);
            slide.setProgress(Math.max(0,Math.min(Tint.TONES.length-1,tone)));
            slide.setPadding(dp(10),dp(10),dp(10),dp(10));
            slide.setSplitTrack(false);
            row.addView(slide,new LinearLayout.LayoutParams(-1,-2));

            final Runnable mark=()->{
                // The wash each tone would actually make, in the colour that is chosen right now. With no
                // colour chosen there is nothing to show a tone of, so the band runs paper to ink rather
                // than borrowing some colour the thing does not have.
                int shown=chosen[0];
                int[] band=new int[Tint.TONES.length];
                for(int step=0;step<band.length;step++)
                    band[step]=Tint.known(shown)
                        ?Tint.over(shown,CARD,Tint.weigh(0.22f,step,0.92f),darkPaper())
                        :mix(CARD,INK,0.06f+0.5f*step/(band.length-1));
                GradientDrawable track=new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,band);
                track.setCornerRadius(dp(9));
                track.setStroke(Math.max(1,dp(1)),LINE);
                slide.setProgressDrawable(new android.graphics.drawable.InsetDrawable(track,0,dp(4),0,dp(4)){
                    @Override public int getIntrinsicHeight(){return dp(26);}
                });
                GradientDrawable grip=new GradientDrawable();
                grip.setShape(GradientDrawable.OVAL);
                grip.setColor(band[slide.getProgress()]);
                grip.setStroke(Math.max(2,dp(2)),INK);
                grip.setSize(dp(26),dp(26));
                slide.setThumb(grip);
                slide.setThumbOffset(0);
                slide.setContentDescription("Tone, "+(tone+1)+" of "+Tint.TONES.length
                    +". Pastel at the left, the colour itself at the right.");
            };
            slide.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(android.widget.SeekBar bar,int at,boolean byHand) {
                    if(!byHand||at==tone)return;
                    // Repainted as the finger moves, so the tone is chosen by looking at the pad behind
                    // the menu rather than by letting go and finding out.
                    useTone(at);
                }
                @Override public void onStartTrackingTouch(android.widget.SeekBar bar){}
                @Override public void onStopTrackingTouch(android.widget.SeekBar bar){}
            });
            marks.add(mark);
            mark.run();
            body.addView(row,new LinearLayout.LayoutParams(-1,-2));
        }

        /**
         * The colour this menu is about, as it stands now. The palette writes it and the tone slider reads
         * it, so choosing a colour repaints the tone with it instead of leaving it showing the old one.
         */
        private final int[] chosen={Tint.NONE};

        /** Every mark in this menu, redrawn where it stands. */
        private void repaintMarks(){for(Runnable mark:marks)mark.run();}

        void show(View anchor) {
            if(showing!=null)showing.close();
            showing=this;
            // The menu is as wide as its words need: it holds the same lines at any size, so it widens
            // with the reading ladder until there is no more screen to give it.
            int width=Math.min(Math.round(dp(268)*reading()),getResources().getDisplayMetrics().widthPixels-dp(32));
            place=new FrameLayout.LayoutParams(width,-2);
            int[] at=new int[2],mine=new int[2];
            anchor.getLocationOnScreen(at);stage.getLocationOnScreen(mine);
            // Always down the right-hand side, whatever was held. Aligning to the thing itself put the menu
            // under the left thumb for anything in the left column, and a menu that moves about the screen
            // has to be looked for; this one is always in the same place.
            wantedTop=at[1]-mine[1]+anchor.getHeight();
            place.leftMargin=Math.max(dp(8),stage.getWidth()-width-dp(8));
            scrim.addView(holder,place);
            stage.addView(scrim,new FrameLayout.LayoutParams(-1,-1));
            fit();
        }

        /** Where the menu sits, and how far down the thing it was opened from would have it start. */
        private FrameLayout.LayoutParams place;
        private int wantedTop;

        /**
         * Fitted to the room there is — now, and again whenever that changes.
         *
         * <p>It used to be fitted once, as it opened. But a menu opened from a note being written in asks the
         * keyboard to go as it opens, and the keyboard goes when it goes, a moment later: so the room was
         * measured with the keyboard still in it, the menu was cut to the half of the screen above the keys,
         * and then the keys went and left it there, half a menu over an empty half of a screen.
         */
        void fit() {
            if(place==null||scrim.getParent()==null)return;
            // The room a menu has is the screen less what is over it: the status bar above, and below it
            // the navigation bar or the keyboard, whichever is there. Held inside that, the menu can never
            // be measured taller than the room — it keeps what it has and scrolls to the rest.
            scrim.setPadding(0,root.getPaddingTop()+dp(8),0,root.getPaddingBottom()+dp(8));
            place.topMargin=Math.max(0,wantedTop-root.getPaddingTop()-dp(8));
            place.height=-2;
            holder.setLayoutParams(place);
            // Once it is measured, lift it up until all of it is in that room; what is measured is the
            // menu itself rather than the window it was given, which may already have been cut to fit.
            holder.post(()->{
                if(scrim.getParent()==null)return;
                int room=scrim.getHeight()-scrim.getPaddingTop()-scrim.getPaddingBottom();
                int tall=body.getHeight();
                int over=place.topMargin+tall-room;
                if(over>0)place.topMargin=Math.max(0,place.topMargin-over);
                // And then it takes the room it needs, up to all of it. Left to wrap around its content
                // it settled at about half the screen with the rest of the rows below the edge and an
                // expanse of nothing underneath: a menu that can be scrolled but shows no reason to be.
                place.height=tall>room-place.topMargin?room-place.topMargin:-2;
                holder.setLayoutParams(place);
            });
        }
    }

    /** Only a collection or a book is named this way; a page is named by writing its first line. */
    /**
     * The name under a tile, or on a card, is the name: tapping it turns it into the field it already looks
     * like, and tapping away keeps what is in it. A note needs none of this — its first line is its name —
     * and neither needs a menu row, because the name is right there to be changed.
     */
    private void nameable(final TextView shown,final NoteStore.Branch branch) {
        if(branch.kind!=NoteStore.Branch.Kind.COLLECTION&&branch.kind!=NoteStore.Branch.Kind.BOOK)return;
        shown.setContentDescription("Rename "+branch.name);
        // Something just made opens its own name, keyboard and all, with the made-up name selected: the
        // first thing typed replaces it. Nothing has to be pressed to get there, and nothing has to be
        // pressed to leave - tapping away keeps whatever is in it, the same as every other name here.
        if(!nameNext.isEmpty()&&nameNext.equals(branch.id)) {
            nameNext="";
            shown.post(shown::performClick);
        }
        shown.setOnClickListener(v->{
            android.view.ViewGroup holder=(android.view.ViewGroup)shown.getParent();
            int at=holder.indexOfChild(shown);
            final EditText typing=field("Name",40);
            typing.setText(branch.name);typing.setSelection(0,branch.name.length());
            typing.setTextSize(shown.getTextSize()/getResources().getDisplayMetrics().scaledDensity);
            typing.setGravity(shown.getGravity());
            typing.setBackground(null);
            holder.removeView(shown);
            holder.addView(typing,at,shown.getLayoutParams());
            typing.requestFocus();
            InputMethodManager keys=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
            // Not SHOW_IMPLICIT. An implicit request is one the system may decline, and it declines this
            // one often enough that a name box opened with no keyboard was the ordinary case.
            if(keys!=null)keys.showSoftInput(typing,0);
            if(android.os.Build.VERSION.SDK_INT>=30) {
                android.view.WindowInsetsController asking=typing.getWindowInsetsController();
                if(asking!=null)asking.show(android.view.WindowInsets.Type.ime());
            }
            typing.postDelayed(()->{
                if(!typing.isAttachedToWindow())return;
                InputMethodManager again=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
                if(again!=null)again.showSoftInput(typing,0);
            },120);
            final Runnable keep=()->{
                String name=typing.getText().toString().trim();
                if(keys!=null)keys.hideSoftInputFromWindow(typing.getWindowToken(),0);
                if(name.isEmpty()||name.equals(branch.name)){refresh();return;}
                final String was=branch.name;
                background.submit(()->{
                    if(branch.kind==NoteStore.Branch.Kind.COLLECTION)store.renameCollection(branch.id,name);
                    else store.renameBook(branch.id,name);
                    return null;},
                    done->{
                        renamed(branch,name);refresh();
                        // Only where there was a name before: undoing back to the made-up one a thing was
                        // created with would be undoing into nonsense.
                        if(!was.isEmpty()&&!was.equals("New collection")&&!was.equals("New book"))
                            canUndo(was,()->{
                                if(branch.kind==NoteStore.Branch.Kind.COLLECTION)store.renameCollection(branch.id,was);
                                else store.renameBook(branch.id,was);
                            });
                    },
                    e->alert("Could not rename that. Nothing was changed."));
            };
            typing.setOnFocusChangeListener((v2,has)->{if(!has)keep.run();});
            typing.setOnEditorActionListener((v2,action,event)->{keep.run();return true;});
            naming=typing;namingKeep=keep;
        });
    }

    /**
     * Everything this note has said. Nothing is ever written over out of existence: an editing session
     * keeps what the note said, and anything that arrives from somebody else is kept as it arrived, so a
     * note two people wrote at once has both sides here rather than one of them quietly gone.
     */
    private void versions(final NoteStore.Branch thing) {
        final String note=thing.id;
        background.submit(()->store.versions(note),all->{
            LinearLayout body=inside();
            if(all.isEmpty()) {
                TextView none=label("Nothing yet. What this note says is kept each time you leave it.",READING,INK);
                none.setPadding(0,dp(12),0,dp(4));body.addView(none);
            }
            for(final NoteStore.Version version:all) {
                LinearLayout entry=column();entry.setPadding(0,dp(12),0,dp(10));
                entry.setBackgroundResource(touchFeedback());
                entry.addView(line(when(version.at),READING,INK));
                String first=version.body.trim();
                int stop=first.indexOf('\n');
                if(stop>=0)first=first.substring(0,stop);
                if(first.length()>60)first=first.substring(0,60)+"…";
                entry.addView(label(first.isEmpty()?"(empty)":first,READING,MUTED));
                entry.addView(label(version.ours()?"Written here":"Came from "+version.source,READING,MUTED));
                entry.setContentDescription("Look at the version from "+when(version.at));
                entry.setOnClickListener(v->lookAtVersion(thing,version));
                body.addView(entry);
            }
            ScrollView scroll=scrolling(body);
            new Box().setTitle("Versions").setView(scroll).show();
        },e->alert(READ_FAILED));
    }

    /** One version, read whole, with the choice to put it back. Putting back writes a new version. */
    private void lookAtVersion(final NoteStore.Branch thing,final NoteStore.Version version) {
        LinearLayout body=inside();
        body.addView(label(version.ours()?"Written here":"Came from "+version.source,READING,MUTED));
        TextView said=label(version.body.isEmpty()?"(empty)":version.body,READING,INK);
        said.setPadding(0,dp(10),0,0);
        body.addView(said);
        ScrollView scroll=scrolling(body);
        new Box().setTitle(when(version.at))
            .setView(scroll)
            .setPositiveButton("Put this back",(d,w)->putVersionBack(thing,version)).show();
    }

    private void putVersionBack(final NoteStore.Branch thing,final NoteStore.Version version) {
        final String note=thing.id;
        background.submit(()->{
            // A copy this phone may only read says what its owner says; an old version of it is to look at.
            if((Boolean)store.readOnlyHere(note)[0])return false;
            // What it says now is kept first: putting an old version back is another writing, not an undoing.
            store.keepVersion(note,"");
            NoteStore.Note now=store.get(note);
            if(now==null)return false;
            now.title=version.title;now.body=version.body;
            now.updated=System.currentTimeMillis();now.revision++;
            store.save(now);
            return true;
        },done->{
            if(!done){alert("Read only. This note is somebody else's to change.");return;}
            toast("Put back");
            if(active!=null&&active.id.equals(note))open(note);else refresh();
        },e->alert("Could not put that version back. Nothing was changed."));
    }

    /** A time somebody can read, rather than a number. */
    private String when(long at) {
        return android.text.format.DateFormat.getMediumDateFormat(this).format(new java.util.Date(at))
            +" "+android.text.format.DateFormat.getTimeFormat(this).format(new java.util.Date(at));
    }

    /** The box a thing made by a gesture is named in, since a gesture cannot say what it is called. */
    private void askRename(NoteStore.Branch branch) {
        EditText input=field("Name",40);
        // The whole name is selected: renaming usually means replacing it, and a tap still places the cursor.
        input.setText(branch.name);input.setSelection(0,branch.name.length());
        AlertDialog box=new Box().setTitle("Name it")
            .setView(naming(input,"Two things were put together, so the new one needs a name.")).create();
        box.setOnDismissListener(d->{
            String name=input.getText().toString().trim();
            if(name.isEmpty()||name.equals(branch.name))return;
            background.submit(()->{
                if(branch.kind==NoteStore.Branch.Kind.COLLECTION)store.renameCollection(branch.id,name);
                else store.renameBook(branch.id,name);
                return null;},
                done->{renamed(branch,name);
                    if(active!=null&&active.id.equals(branch.id))open(branch.id);else refresh();},
                e->alert("Could not rename that. Nothing was changed."));});
        closeOnEnter(input,box);
        box.show();
    }

    private void addHere() {
        final Step step=here();
        new Box().setTitle(adding(step.kind))
            .setItems(new CharSequence[]{makeOne(step.kind),"From another device"},(d,which)->{
                if(which==0)makeHere();else addFromSomeone();
            }).show();
    }

    /** What the plus makes, said as the thing rather than as the act: the other half is what arrives. */
    private String makeOne(NoteStore.Branch.Kind kind) {
        return kind==NoteStore.Branch.Kind.LIBRARY?"A new collection"
            :kind==NoteStore.Branch.Kind.COLLECTION?"A new book":"A new note";
    }

    private void makeHere() {
        Step step=here();
        if(step.kind==NoteStore.Branch.Kind.BOOK){blankPage(step.id);return;}
        final boolean collection=step.kind==NoteStore.Branch.Kind.LIBRARY;
        final String where=step.id;
        final String called=collection?"New collection":"New book";
        background.submit(()->collection?store.addCollection(called):store.addBook(where,called),
            made->{nameNext=made.id;refresh();},
            e->alert("Could not create that. Nothing was changed."));
    }

    /** The keyboard's own key closes the box, which is what saves it. */
    private void closeOnEnter(EditText input,AlertDialog box) {
        input.setOnEditorActionListener((v,action,event)->{box.dismiss();return true;});
    }

    /** The trail shows names, so a renamed step has to be renamed there too. */
    private void renamed(NoteStore.Branch branch,String name) {
        for(int i=0;i<trail.size();i++)
            if(trail.get(i).id.equals(branch.id))trail.set(i,new Step(trail.get(i).kind,branch.id,name));
    }

    /**
     * Nothing leaves in one tap. Archiving puts a thing away and deleting drops it in the bin; either way it
     * is still there, holding whatever it held, and can be put back. Only the bin asks anything, and only
     * because what it asks about cannot be undone.
     */
    private void putAway(NoteStore.Branch branch,boolean bin) {
        final boolean standingInIt=shelves&&here().id.equals(branch.id);
        final String was=active!=null&&active.id.equals(branch.id)?active.book:null;
        final NoteStore.Branch.Kind kind=branch.kind;final String id=branch.id;
        final String called=branch.name;
        background.submit(()->{store.putAway(kind,id,bin,true);return null;},
            done->{
                carrying=null;toast(bin?"In the bin":"Archived");
                canUndo(called,()->store.restore(kind,id));
                if(was!=null){active=null;openBookOf(was);}
                else if(standingInIt&&trail.size()>1)climb(trail.size()-2);
                else if(shelves)refresh();
                else back();
            },
            e->alert(bin?"Could not put that in the bin. Nothing was changed.":"Could not archive that. Nothing was changed."));
    }

    /**
     * The colour of one collection, book or page. The page under an open menu washes at once; a card or a row
     * is redrawn when the menu closes, since it is not on screen while its own menu is over it.
     */
    /** How loudly a colour lands, at the tone the pad is set to and never past what it can carry. */
    private float wash(float base,float most){return Tint.weigh(base,tone,most);}

    /** Two colours mixed, for the one place a band has to be drawn with no colour to draw it in. */
    private static int mix(int from,int to,float much) {
        float f=much<0?0:much>1?1:much;
        int r=Math.round(((from>>16)&255)+((((to>>16)&255)-((from>>16)&255))*f));
        int g=Math.round(((from>>8)&255)+((((to>>8)&255)-((from>>8)&255))*f));
        int b=Math.round((from&255)+(((to&255)-(from&255))*f));
        return 0xFF000000|(r<<16)|(g<<8)|b;
    }

    /** Straight to one tone, wherever the finger landed on it. */
    private void useTone(int step) {
        if(step==tone)return;
        tone=step;painted=true;repaint();
        if(showing!=null)showing.repaint();
        final int kept=step;
        background.submit(()->{getSharedPreferences("settings",MODE_PRIVATE).edit().putInt("tone",kept).apply();return null;},
            done->{},e->{});
    }

    /**
     * The colour of the whole pad — the room the collections sit in. It belongs to no collection, book or
     * note, so it is kept beside the other things the app remembers about itself rather than in the notebook.
     */
    private int libraryColour(){return getSharedPreferences("settings",MODE_PRIVATE).getInt("colour",Tint.NONE);}

    private void paintThing(NoteStore.Branch thing,int colour) {
        if(thing.kind==NoteStore.Branch.Kind.PAGE&&active!=null&&active.id.equals(thing.id))active.colour=colour;
        if(shelves&&thing.id.equals(here().id))levelColour=colour;
        if(thing.kind==NoteStore.Branch.Kind.LIBRARY) {
            levelColour=colour;painted=true;repaint();
            final int kept=colour;
            background.submit(()->{getSharedPreferences("settings",MODE_PRIVATE).edit().putInt("colour",kept).apply();return null;},
                done->{},e->alert("Could not change that colour. Nothing was changed."));
            return;
        }
        painted=true;repaint();
        final NoteStore.Branch.Kind kind=thing.kind;final String id=thing.id;
        background.submit(()->{store.paint(kind,id,colour);return null;},
            done->{},e->alert("Could not change that colour. Nothing was changed."));
    }

    /**
     * Everything one thing owes, actually sent. Sealed for each recipient, carried by this phone's own
     * node, and a mark cleared only where the other end took it.
     */
    private void sendThem(final NoteStore.Branch.Kind kind,final String id,final String name) {
        status("Sending\u2026");
        final int job=busy("Sending\u2026");
        network.submit(()->Post.send(this,store,keys(),kind,id),done->{
            busyDone(job,null);
            saidState();refresh();refreshOwed();
            if(done.sent>0&&done.failed==0)
                alert(done.sent==1?"Sent.":"Sent "+done.sent+" notes.");
            else if(done.sent>0)
                alert("Sent "+done.sent+", and "+done.failed+" could not go."
                    +(done.why.isEmpty()?"":"\n\n"+done.why));
            else alert("Nothing could be sent."+(done.why.isEmpty()?"":"\n\n"+done.why));
        },e->{busyDone(job,null);saidState();alert("Nothing was sent. "+(e.getMessage()==null?"":e.getMessage()));});
    }

    /**
     * What opening the pad sets going: the node, somebody to hear what it brings, and the housekeeping a
     * node wants when it has just come up.
     *
     * <p>In that order, and the order is the point. Hearing comes first, so nothing sent in the first
     * minute lands with nobody there. What this phone owes goes next, on the worker that sends. The
     * housekeeping comes last and on a worker of its own, because the longest part of it - telling every
     * contact where this phone now is - is given a minute and a half by the transport, and a note somebody
     * has just written should not wait behind a courtesy.
     */
    private void listenForNotes() {
        chores.submit(()->{
            // One row per device first, so everything below is talking about the same people.
            try{store.tidyDevices();}catch(Exception notNow){/* nothing here is worth failing an opening */}
            try{store.tidyShelves();}catch(Exception notNow){/* nor this */}
            try{store.tidyBroken();}catch(Exception notNow){/* nor this */}
            try{store.tidyOrigins();}catch(Exception notNow){/* nor this */}
            // The node is told where to bring what arrives - once for the process, not once a screen, so
            // it goes on being heard after this screen has gone - and whether it stays up after that is
            // settled now, while the pad is on screen, which is the one moment Android allows it.
            Listening.hear(this);
            Listening.settle(this);
            return Listening.switchedOn(this)&&store.anybodyPaired();
        },staying->{
            if(staying)askToSaySo();
            sendWhatIsOwed();
            chores.submit(()->{
                // This node has just been given an address, and it is rarely the one it had yesterday.
                // Anybody who has met it is told, so they send to where it is rather than where it was.
                try{Node.tellEverybody(this);}catch(Exception notNow){/* the next opening tries again */}
                // And anybody whose offer was taken up but who never answered hears it again. A phone that
                // was asleep when somebody accepted would otherwise never find out, and the person who
                // accepted would go on looking at a shelf with nothing on it.
                String mine=getSharedPreferences("settings",MODE_PRIVATE).getString("address","");
                // Once: pairings made with a plain code before a plain code said hello were one-sided, so
                // each of this owner's own devices never heard from is said hello to now. Only their own:
                // somebody else's phone is not sent a question this person did not ask.
                if(!getSharedPreferences("settings",MODE_PRIVATE).getBoolean("helloedOwn",false)) {
                    for(NoteStore.Contact own:store.addresses())
                        if(own.mine&&!Post.heardFrom(this,own))store.accepting(own.address,own.name,"","",false);
                    getSharedPreferences("settings",MODE_PRIVATE).edit().putBoolean("helloedOwn",true).apply();
                }
                for(NoteStore.Accepting again:store.waitingToAccept()) {
                    try{Post.sayAgain(this,store,keys(),again,deviceName(),mine);}
                    catch(Exception notNow){/* the next opening tries again */}
                    store.triedAgain(again.address);
                }
                // And anybody this phone told it had left something, who may have been asleep for it.
                try{Post.leftAgain(this,store,keys());}catch(Exception notNow){/* the next opening says it */}
                // And anybody this phone took off something, for the same reason.
                try{Post.removedAgain(this,store,keys());}catch(Exception notNow){/* the next opening says it */}
                return null;
            },done->{},e->{});
        },e->{});
    }

    /**
     * Asked once, the first time there is anything it would be for.
     *
     * <p>A note that arrives with the pad closed lands on the shelves whether or not the phone is allowed
     * to say so; the permission is only for saying so. Somebody who said no is not asked again — Profile
     * still has the row, for anybody who changes their mind.
     */
    private void askToSaySo() {
        if(android.os.Build.VERSION.SDK_INT<33||maySaySo())return;
        android.content.SharedPreferences kept=getSharedPreferences("settings",MODE_PRIVATE);
        if(kept.getBoolean("askedToSaySo",false))return;
        kept.edit().putBoolean("askedToSaySo",true).apply();
        requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},SAYING_SO);
    }

    private boolean maySaySo() {
        return android.os.Build.VERSION.SDK_INT<33
            ||checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                ==android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Somebody has taken up something this phone offered.
     *
     * <p>Asked rather than done. A code on a screen is an open offer to whoever photographs it, and this
     * app has always said so - but "anybody who sees it can write to you" is a smaller thing than "anybody
     * who sees it is on your list and has your book". One is a consequence of showing a code; the other is
     * a decision, and decisions belong to the person whose notes they are.
     */
    private void somebodyAccepted(final Hello.Said them) {
        // Somebody scanned the code this phone showed. Showing it was the decision, so it is not asked
        // again: they are paired back, and handed what was offered if the code went up a short while ago
        // and they are not claiming more than it offered. An old or unknown offer is still asked about.
        if(them.target.isEmpty()){pairBack(them);return;}
        final Sharing.Scope scope=scopeNamed(them.scope);
        if(scope==null)return;
        String offered=getSharedPreferences("offers",MODE_PRIVATE).getString(them.scope+":"+them.target,"");
        int split=offered.indexOf(':');
        if(split>0)try {
            long at=Long.parseLong(offered.substring(0,split));boolean writes=Boolean.parseBoolean(offered.substring(split+1));
            long age=System.currentTimeMillis()-at;
            if(age>=0&&age<15*60_000L&&(writes||!them.writes)){giveItTo(them,scope);return;}
        } catch(NumberFormatException old){/* asked below */}
        background.submit(()->store.nameOf(them.target,scope==Sharing.Scope.COLLECTION),called->{
            String what=called==null||called.toString().trim().isEmpty()
                ?Sharing.describe(scope,"this"):Sharing.shortly(scope,called.toString());
            new Box().setTitle(them.name+" accepted")
                .setMessage(them.name+" scanned the code offering "+what+", to "
                    +(them.writes?"read and write it":"read it")+".\n\nGive it to them?")
                .setPositiveButton("Give it to them",(d,w)->giveItTo(them,scope))
                .setNeutralButton("Not now",(d,w)->{})
                .show();
        },e->{});
    }

    /** Saved as a device, given what they accepted, and sent it — in that order and in one go. */
    private void giveItTo(final Hello.Said them,final Sharing.Scope scope) {
        final int job=busy("Adding "+them.name+"\u2026");
        network.submit(()->{
            store.pairedWith(them.address,them.name,false,them.agreement,them.signing);
            busySay(job,"Finding "+them.name+" on the network\u2026");
            try {
                String key=Node.introduce(this,them.address);
                if(!key.isEmpty())store.knownAs(them.address,key);
            } catch(Exception notNow){/* the address they sent still works until it does not */}
            store.addShare(new Sharing.Rule(scope,them.target,them.address,them.writes));
            Listening.settle(this);
            return null;
        // It said "has it" here, before a word had been sent. What it says now is what is true: the
        // sending is next, and the strip goes on into it.
        },done->{askToSaySo();refresh();refreshOwed();sendAfterSharing(scope,them.target,job,them.name);},
            e->{busyDone(job,null);alert("Could not give it to them. Nothing was changed.");});
    }

    /** Saved as a device, introduced, and answered, so their phone stops saying hello. */
    private void pairBack(final Hello.Said them) {
        final int job=busy("Pairing with "+them.name+"…");
        network.submit(()->{
            store.pairedWith(them.address,them.name,false,them.agreement,them.signing);
            try {
                String key=Node.introduce(this,them.address);
                if(!key.isEmpty())store.knownAs(them.address,key);
            } catch(Exception notNow){/* the address they sent still works until it does not */}
            Listening.settle(this);
            String mine=getSharedPreferences("settings",MODE_PRIVATE).getString("address","");
            NoteStore.Contact saved=store.address(them.address);
            try{Post.helloBack(this,keys(),them,deviceName(),mine,saved==null?null:saved.contact);}catch(Exception notNow){/* they send again until they hear */}
            return null;
        },done->{askToSaySo();busyDone(job,"Paired with "+them.name);refresh();},
            e->{busyDone(job,null);alert("Could not pair with "+them.name+". Nothing was changed.");});
    }

    /** A level by the name it travelled under, or null for one this build does not know. */
    private static Sharing.Scope scopeNamed(String said) {
        if(said==null)return null;
        for(Sharing.Scope scope:Sharing.Scope.values())if(scope.name().equals(said.trim()))return scope;
        return null;
    }

    /**
     * Looking for a word, anywhere on the pad.
     *
     * <p>The answer appears as it is typed, because searching is a thing you do by narrowing: you write
     * three letters, look, write two more. A button to press between each of those is a button pressed
     * every time.
     */
    private void searching() {
        LinearLayout body=inside();
        final EditText word=field("A word to look for",80);
        body.addView(word);
        final LinearLayout found=column();
        body.addView(found);
        final TextView none=under("");
        body.addView(none);

        final AlertDialog box=new Box().setTitle("Search")
            .setView(scrolling(body)).create();
        android.view.Window window=box.getWindow();
        if(window!=null) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                |WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
        }

        final Runnable look=()->{
            final String term=word.getText().toString().trim();
            found.removeAllViews();
            if(term.isEmpty()){none.setText("");atHand(found,box);return;}
            if(term.length()<2){none.setText("");return;}
            background.submit(()->store.looking(term),got->{
                if(!word.getText().toString().trim().equals(term))return;
                @SuppressWarnings("unchecked") List<NoteStore.Branch> hits=(List<NoteStore.Branch>)got;
                found.removeAllViews();
                // Only where the screen would otherwise be blank. A list that has just told you what it
                // found does not need a line underneath counting it.
                none.setText(hits.isEmpty()?"Nothing found.":"");
                for(final NoteStore.Branch hit:hits)found.addView(hitRow(hit,box));
            },e->none.setText(READ_FAILED));
        };
        atHand(found,box);
        word.addTextChangedListener(watch(()->{
            handler.removeCallbacks(looking);
            looking=look;
            handler.postDelayed(looking,200);
        }));
        box.show();
    }

    /** The last search asked for, so a fast typist asks the notebook once rather than once a letter. */
    private Runnable looking;

    /**
     * What you keep to hand, and what you were just doing.
     *
     * <p>Shown where a search would be, before anything is typed. Opening a box to look for something and
     * being met with an empty field is the app asking a question when it already knows the two likeliest
     * answers: the thing you marked because you keep coming back to it, and the thing you were writing in
     * ten minutes ago. Neither of them needed a row in the menu of its own.
     */
    private void atHand(final LinearLayout into,final AlertDialog box) {
        background.submit(()->new Object[]{store.favourites(),store.lately(12)},got->{
            Object[] both=(Object[])got;
            @SuppressWarnings("unchecked") List<NoteStore.Branch> kept=(List<NoteStore.Branch>)both[0];
            @SuppressWarnings("unchecked") List<NoteStore.Branch> lately=(List<NoteStore.Branch>)both[1];
            into.removeAllViews();
            if(!kept.isEmpty()) {
                into.addView(part("Favourites"));
                for(NoteStore.Branch one:kept)into.addView(hitRow(one,box));
            }
            if(!lately.isEmpty()) {
                into.addView(part("Recent"));
                for(NoteStore.Branch one:lately)into.addView(hitRow(one,box));
            }
            if(kept.isEmpty()&&lately.isEmpty())into.addView(under("Nothing on the shelves yet."));
        },e->{});
    }

    /** One thing a word was found in: what it is, where it is, and the words around it. */
    private View hitRow(final NoteStore.Branch hit,final AlertDialog box) {
        LinearLayout row=new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0,dp(10),0,dp(10));
        row.setBackgroundResource(touchFeedback());
        row.addView(line(hit.name,READING,INK));
        if(!hit.detail.isEmpty()) {
            TextView where=label(hit.detail,QUIET,MUTED);
            where.setMaxLines(2);where.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(where);
        }
        row.setContentDescription("Open "+hit.name);
        row.setOnClickListener(v->{box.dismiss();goTo(hit);});
        return row;
    }

    /** Whatever was picked out of a list that is not the shelves: opened where it actually lives. */
    private void goTo(NoteStore.Branch thing) {
        if(thing.kind==NoteStore.Branch.Kind.PAGE){open(thing.id);return;}
        if(thing.kind==NoteStore.Branch.Kind.BOOK){openBookOf(thing.id);return;}
        openCollection(thing.id,thing.name);
    }

    /**
     * The whole pad at once.
     *
     * <p>Walking in and out shows one room at a time, which is the right way to work and the wrong way to
     * remember where something was put. This is the plan of the building: every collection, the books in
     * each, and the notes on each book, indented by how deep they sit.
     */
    private void wholeTree() {
        background.submit(()->store.wholeTree(),got->{
            @SuppressWarnings("unchecked") List<NoteStore.Branch> all=(List<NoteStore.Branch>)got;
            LinearLayout body=inside();
            if(all.isEmpty())body.addView(under("Nothing on the shelves yet."));
            final AlertDialog box=new Box().setTitle("Tree view")
                .setView(scrolling(body)).create();
            for(final NoteStore.Branch thing:all)body.addView(treeRow(thing,box));
            box.show();
        },e->alert(READ_FAILED));
    }

    /** One line of the tree, set in from the edge by how deep it is. */
    private View treeRow(final NoteStore.Branch thing,final AlertDialog box) {
        LinearLayout row=new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(thing.depth*18),dp(8),0,dp(8));
        row.setBackgroundResource(touchFeedback());
        // A dot in the colour the thing was given, so the tree reads as the shelves do.
        View dot=new View(this);
        GradientDrawable round=new GradientDrawable();
        round.setShape(GradientDrawable.OVAL);
        round.setColor(Tint.known(thing.colour)?Tint.over(thing.colour,CARD,wash(0.6f,1f),darkPaper()):LINE);
        dot.setBackground(round);
        LinearLayout.LayoutParams pip=new LinearLayout.LayoutParams(dp(8),dp(8));
        pip.setMargins(0,0,dp(10),0);
        row.addView(dot,pip);
        LinearLayout words=column();
        words.addView(line(thing.name,thing.depth==2?QUIET:READING,thing.depth==0?INK:INK));
        if(thing.depth<2&&!thing.detail.isEmpty())words.addView(label(thing.detail,QUIET,MUTED));
        row.addView(words,new LinearLayout.LayoutParams(0,-2,1));
        if(thing.kept)row.addView(star(dp(20)),new LinearLayout.LayoutParams(dp(20),dp(20)));
        View mark=shareBadge(thing,dp(20));
        if(mark!=null) {
            LinearLayout.LayoutParams beside=new LinearLayout.LayoutParams(dp(20),dp(20));
            beside.setMargins(dp(8),0,dp(4),0);
            row.addView(mark,beside);
        }
        row.setContentDescription("Open "+thing.name+(thing.kept?", a favourite":""));
        row.setOnClickListener(v->{box.dismiss();goTo(thing);});
        return row;
    }

    /** A row in the archive or the bin: where it can go from here, and nothing else. */
    private void awaySheet(View anchor,NoteStore.Branch thing,boolean bin) {
        Sheet sheet=new Sheet();
        sheet.row("Put back",()->background.submit(()->{store.restore(thing.kind,thing.id);return null;},
            done->{toast("Back on the shelves");refresh();},e->alert("Could not put that back. Nothing was changed.")));
        if(bin)sheet.row("Delete for good",()->askErase(thing));
        else sheet.row("Move to the bin",()->background.submit(()->{
                store.putAway(thing.kind,thing.id,false,false);store.putAway(thing.kind,thing.id,true,true);return null;},
            done->{toast("In the bin");refresh();},e->alert("Could not move that. Nothing was changed.")));
        sheet.show(anchor);
    }

    private void askErase(NoteStore.Branch thing) {
        new Box().setTitle("Delete "+thing.name+" for good?")
            .setMessage(thing.detail+"\n\nThis cannot be undone. Anything inside it goes too.")
            .setPositiveButton("Delete for good",(d,w)->background.submit(()->{store.erase(thing.kind,thing.id);return null;},
                done->{toast("Gone");refresh();},e->alert("Could not delete that. Nothing was changed."))).show();
    }

    private void askEmptyBin() {
        background.submit(()->store.awayCount(true),waiting->{
            if(waiting==0){toast("The bin is already empty");return;}
            new Box().setTitle("Empty the bin?")
                .setMessage(waiting+(waiting==1?" thing":" things")+" in it, and whatever they hold, gone for good.\n\nThis cannot be undone.")
                .setPositiveButton("Empty the bin",(d,w)->background.submit(store::emptyBin,
                    gone->{toast(gone+(gone==1?" thing deleted":" things deleted"));refresh();},
                    e->alert("Could not empty the bin. Nothing was changed."))).show();
        },e->alert(READ_FAILED));
    }

    /** While something is carried, the trail is replaced by what is being moved and how to put it down. */
    private LinearLayout carryingBar() {
        final NoteStore.Branch held=carrying;
        LinearLayout says=new LinearLayout(this);says.setGravity(Gravity.CENTER_VERTICAL);says.setPadding(dp(20),dp(2),dp(6),dp(12));
        LinearLayout words=column();
        words.addView(line("Moving "+held.name,16,INK));
        words.addView(label(held.kind==NoteStore.Branch.Kind.PAGE
            ?"Tap the book to move it into." : "Tap the collection to move it into.",READING,MUTED));
        says.addView(words,new LinearLayout.LayoutParams(0,-2,1));
        return says;
    }

    private void refresh() {
        // Whichever this level is drawn on, a read that comes back for a level you have since left is dropped.
        final android.view.ViewGroup target=desktop?tiles:rows;
        if(target==null)return;
        if(carrying!=null) {
            final boolean forPage=carrying.kind==NoteStore.Branch.Kind.PAGE;
            background.submit(()->store.places(forPage),
                where->{if(target==(desktop?tiles:rows))draw(where,true);},e->alert(READ_FAILED));
            return;
        }
        final Step step=here();
        final boolean whole=step.kind==NoteStore.Branch.Kind.LIBRARY;
        // The trail is remembered across a restart, and what it points at can have been put away since.
        // So how much of it is still there is asked with the level itself, rather than the app drawing the
        // inside of something that is in the bin and calling it where you are.
        final List<Step> path=new ArrayList<>(trail);
        background.submit(()->{
            int keep=path.size();
            for(int at=1;at<path.size();at++)
                if(!store.stillThere(path.get(at).kind,path.get(at).id)){keep=at;break;}
            if(keep<path.size())return new Object[]{null,0,keep};
            return new Object[]{store.inside(step.kind,step.id),
                whole?libraryColour():store.colourOf(step.kind,step.id),keep};
        },read->{
                if(target!=(desktop?tiles:rows))return;
                Object[] got=(Object[])read;
                int keep=(Integer)got[2];
                if(keep<path.size()) {
                    // Out to the last level that is still there, and said out loud: a screen that quietly
                    // becomes a different screen is worse than one that tells you why.
                    trail.clear();trail.addAll(path.subList(0,keep));
                    toast(path.get(keep).name+" is in the bin");
                    browse();
                    return;
                }
                levelColour=(Integer)got[1];
                repaint();
                draw(((NoteStore.Level)got[0]).holds,false);
            },e->alert(READ_FAILED));
    }

    private void draw(List<NoteStore.Branch> lines,boolean destinations) {
        final boolean starred=amongFavourites()&&!destinations;
        if(desktop&&tiles!=null) {
            tiles.removeAllViews();
            // Nothing is added among the favourites: a thing becomes one where it lives.
            if(!starred)tiles.addView(addSquare());
            else if(lines.isEmpty())tiles.addView(over("Nothing is a favourite any more"));
            NoteStore.Branch.Kind last=null;
            for(NoteStore.Branch branch:lines) {
                if(starred&&branch.kind!=last){last=branch.kind;tiles.addView(over(manyOf(last)));}
                // What others send you, and where things go when they leave, are places rather than things.
                if(branch.kind==NoteStore.Branch.Kind.PAGE||branch.kind==NoteStore.Branch.Kind.COLLECTION
                    ||branch.kind==NoteStore.Branch.Kind.BOOK)tiles.addView(tile(branch));
                else tiles.addView(placeTile(branch));
            }
            return;
        }
        rows.removeAllViews();
        // Adding sits with the things it adds to, so even an empty level says what to do next. Nothing is
        // added to the archive or the bin: things arrive there from the shelves.
        final boolean away=here().kind==NoteStore.Branch.Kind.ARCHIVE||here().kind==NoteStore.Branch.Kind.BIN;
        if(!destinations&&!away&&!starred)rows.addView(addTile());
        NoteStore.Branch.Kind last=null;
        for(final NoteStore.Branch branch:lines) {
            boolean itself=destinations&&branch.id.equals(carrying.parent);
            if(away){rows.addView(awayRow(branch));continue;}
            if(starred&&branch.kind!=last){last=branch.kind;rows.addView(over(manyOf(last)));}
            rows.addView(branch.kind==NoteStore.Branch.Kind.PAGE&&!destinations?pageRow(branch):card(branch,destinations,itself));
        }
        if(destinations&&lines.isEmpty())empty("Nowhere else to put it yet");
        if(away&&lines.isEmpty())empty(here().kind==NoteStore.Branch.Kind.BIN?"The bin is empty":"Nothing put away");
        if(starred&&lines.isEmpty())empty("Nothing is a favourite any more");
    }

    /** Something that holds things — a collection, a book, a destination — drawn as a thing you can open. */
    /** A thing waiting in the archive or the bin. It is not a place to go into, so tapping it asks where to. */
    private View awayRow(final NoteStore.Branch thing) {
        final boolean bin=here().kind==NoteStore.Branch.Kind.BIN;
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(22),dp(14),dp(20),dp(14));
        if(Tint.known(thing.colour)) {
            row.setBackgroundColor(Tint.over(thing.colour,PAPER,wash(0.16f,0.82f),darkPaper()));
            row.setForeground(getDrawable(touchFeedback()));
        } else row.setBackgroundResource(touchFeedback());
        LinearLayout text=column();
        text.addView(line(thing.name,READING,INK));
        TextView what=line(thing.detail,READING,MUTED);what.setPadding(0,dp(3),0,0);text.addView(what);
        row.addView(text,new LinearLayout.LayoutParams(0,-2,1));
        row.setContentDescription(thing.name+", "+thing.detail);
        row.setOnClickListener(v->awaySheet(v,thing,bin));
        return row;
    }

    private View card(NoteStore.Branch branch,boolean destination,boolean itself) {
        final boolean favourites=branch.kind==NoteStore.Branch.Kind.FAVOURITES;
        boolean theirs=branch.kind==NoteStore.Branch.Kind.INBOX||favourites
            ||branch.kind==NoteStore.Branch.Kind.ARCHIVE||branch.kind==NoteStore.Branch.Kind.BIN;
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.HORIZONTAL);card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(18),dp(15),dp(8),dp(15));
        // What is yours is filled in; what is only a marker — where it already sits, what others send you — is outlined.
        // A thing given a colour is filled with it, washed so the name on it still reads.
        card.setBackground(itself||theirs
            ?shape(0,LINE,false)
            :edged(Tint.over(branch.colour,CARD,wash(0.20f,0.92f),darkPaper()),branch.colour,false));
        if(!itself)card.setForeground(getDrawable(touchFeedback()));
        LinearLayout.LayoutParams place=new LinearLayout.LayoutParams(-1,-2);place.setMargins(dp(20),dp(5),dp(20),dp(5));
        card.setLayoutParams(place);

        LinearLayout text=column();
        TextView named=line(favourites?"\u2605  "+branch.name:branch.name,19,itself?MUTED:INK);
        if(!destination&&!favourites)nameable(named,branch);
        text.addView(named);
        String under=itself?"where it is now":branch.detail;
        if(!under.isEmpty()){TextView sub=line(under,READING,MUTED);sub.setPadding(0,dp(3),0,0);text.addView(sub);}
        card.addView(text,new LinearLayout.LayoutParams(0,-2,1));

        if(destination) {
            card.setContentDescription(itself?branch.name+", where it is now":"Move into "+branch.name);
            if(!itself)card.setOnClickListener(v->putDown(branch));
            return card;
        }
        if(branch.kind==NoteStore.Branch.Kind.LIBRARY) {
            card.addView(arrow(branch));
            card.setContentDescription("Share everything");
            card.setOnClickListener(v->shareSheet(Sharing.Scope.LIBRARY,Sharing.EVERYTHING,"Everything"));
            return card;
        }
        if(favourites||branch.kind==NoteStore.Branch.Kind.ARCHIVE||branch.kind==NoteStore.Branch.Kind.BIN) {
            card.setContentDescription(favourites?"Open favourites, "+branch.detail
                :"Open the "+branch.name.toLowerCase(java.util.Locale.ROOT));
            card.setOnClickListener(v->enter(branch));
            return card;
        }
        if(theirs) {
            card.setContentDescription("Shared with me");
            card.setOnClickListener(v->alert("Notes another device shares with you arrive here."));
            return card;
        }
        card.setContentDescription("Open "+branch.name+(branch.kept?", a favourite":""));
        // From among the favourites a thing is opened where it really is, not as if it were inside them.
        card.setOnClickListener(v->{if(amongFavourites())goTo(branch);else enter(branch);});
        if(branch.kept&&!amongFavourites())card.addView(star(dp(22)),new LinearLayout.LayoutParams(dp(22),dp(22)));
        View mark=shareBadge(branch,dp(24));
        if(mark!=null) {
            LinearLayout.LayoutParams beside=new LinearLayout.LayoutParams(dp(24),dp(24));
            beside.setMargins(dp(8),0,dp(4),0);
            card.addView(mark,beside);
        }
        if(amongFavourites())menuOnly(card,branch);else orderable(card,branch);
        return card;
    }

    /** A page is writing, not a container, so it is a line of text rather than a card. */
    private View pageRow(NoteStore.Branch branch) {
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(22),dp(14),dp(6),dp(14));
        if(Tint.known(branch.colour)) {
            row.setBackgroundColor(Tint.over(branch.colour,PAPER,wash(0.16f,0.82f),darkPaper()));
            row.setForeground(getDrawable(touchFeedback()));
        } else row.setBackgroundResource(touchFeedback());
        LinearLayout text=column();
        text.addView(line(branch.name,READING,INK));
        if(!branch.detail.isEmpty()){TextView sub=line(branch.detail,14,MUTED);sub.setPadding(0,dp(2),0,0);text.addView(sub);}
        row.addView(text,new LinearLayout.LayoutParams(0,-2,1));
        row.setContentDescription("Open "+branch.name+(branch.kept?", a favourite":""));
        row.setOnClickListener(v->open(branch.id));
        if(branch.kept&&!amongFavourites())row.addView(star(dp(22)),new LinearLayout.LayoutParams(dp(22),dp(22)));
        View mark=shareBadge(branch,dp(24));
        if(mark!=null) {
            LinearLayout.LayoutParams beside=new LinearLayout.LayoutParams(dp(24),dp(24));
            beside.setMargins(dp(8),0,dp(4),0);
            row.addView(mark,beside);
        }
        if(amongFavourites())menuOnly(row,branch);else orderable(row,branch);
        return row;
    }

    private View addTile() {
        Step step=here();
        TextView add=label("+   "+adding(step.kind),16,ACCENT);
        add.setGravity(Gravity.CENTER);add.setPadding(dp(18),dp(17),dp(18),dp(17));
        add.setBackground(shape(0,LINE,true));add.setForeground(getDrawable(touchFeedback()));
        add.setContentDescription(adding(step.kind));
        add.setOnClickListener(v->addHere());
        LinearLayout.LayoutParams place=new LinearLayout.LayoutParams(-1,-2);place.setMargins(dp(20),dp(4),dp(20),dp(10));
        add.setLayoutParams(place);
        return add;
    }

    private GradientDrawable shape(int fill,int stroke,boolean dashed) {
        GradientDrawable shape=new GradientDrawable();
        shape.setColor(fill==0?Color.TRANSPARENT:fill);shape.setCornerRadius(dp(14));
        if(stroke!=0){if(dashed)shape.setStroke(dp(1),stroke,dp(7),dp(6));else shape.setStroke(dp(1),stroke);}
        return shape;
    }

    /**
     * The same shape, edged in the thing's own colour. A wash has to stay light enough to be written on,
     * which makes eight washes look like eight shades of the paper; the edge is the colour itself, at full
     * strength, so two things are told apart at a glance rather than by comparing tints.
     */
    private GradientDrawable edged(int fill,int colour,boolean dashed) {
        GradientDrawable shape=shape(fill,Tint.known(colour)?Tint.of(colour,darkPaper()):LINE,dashed);
        if(Tint.known(colour))shape.setStroke(dp(3),Tint.of(colour,darkPaper()));
        return shape;
    }

    /**
     * Which way a thing is being shared, drawn on the thing itself and worth tapping.
     *
     * <p>Under a tile there is already a line of words saying the same; this is for the glance across a
     * shelf, where reading eight lines to find the one that came from somebody else is not a glance. It is
     * the one mark on the shelves that is also a control, because the question it raises - who else has
     * this? - has an answer, and the answer should be one tap away from the question.
     *
     * @return the mark, or null for a thing that is only on this phone
     */
    private View shareBadge(final NoteStore.Branch branch,int px) {
        if(branch.scope()==null)return null;
        // The same ring the bar wears, saying the same three things: only here, shared and gone, shared
        // and waiting. It used to be a mark of its own that said which way a thing was going - so a note
        // had one drawing on the shelf and two in its bar, and the two in the bar were both circles.
        final boolean here=branch.state==null||branch.state==Sharing.State.HERE;
        final Mark.What which=branch.paused?Mark.What.PAUSED
            :here?Mark.What.HERE:branch.waiting>0?Mark.What.WAITING:Mark.What.GONE;
        Mark.PAPER_HOLE=PAPER;
        Mark drawn=new Mark(which,which==Mark.What.WAITING?ACCENT:which==Mark.What.GONE?INK:MUTED);
        drawn.sized(px);
        ImageView mark=new ImageView(this);
        mark.setImageDrawable(drawn);
        mark.setScaleType(ImageView.ScaleType.FIT_CENTER);
        // A disc of the paper behind it, so that it reads the same over a coloured tile as over the page.
        GradientDrawable disc=new GradientDrawable();
        disc.setShape(GradientDrawable.OVAL);disc.setColor(PAPER);
        mark.setBackground(disc);
        mark.setContentDescription(branch.paused?"Paused. This phone is not receiving it. Tap to resume."
            :here?"Only on this phone. Tap to share it."
            :(branch.state==Sharing.State.THEIRS?"Shared with you by another device":Sharing.describe(branch.state,branch.shared))
                +(branch.waiting==0?", and up to date":branch.waiting==1?", and one note is waiting to go"
                    :", and "+branch.waiting+" notes are waiting to go")
                +(which==Mark.What.WAITING?". Tap to sync now. Hold to see who.":". Tap to see who."));
        mark.setOnClickListener(v->{
            if(which==Mark.What.WAITING)syncNow(branch.kind,branch.id,branch.name);
            else aboutSharing(branch);
        });
        mark.setOnLongClickListener(v->{aboutSharing(branch);return true;});
        return mark;
    }

    /**
     * The mark for something an address has not been given yet. Nothing arrives because the reader wrote it;
     * it arrives because this device sent it, so until it has, the line says so. It is a mark, not a control:
     * what to do about it is in the menu, like everything else about a thing.
     */
    private TextView waitingMark(NoteStore.Branch branch) {
        TextView mark=label(branch.waiting>1?"\u2191 "+branch.waiting:"\u2191",15,ACCENT);
        mark.setGravity(Gravity.CENTER);mark.setMinWidth(dp(40));mark.setPadding(dp(4),0,dp(8),0);
        mark.setContentDescription(branch.waiting==1?"One note not sent yet":branch.waiting+" notes not sent yet");
        return mark;
    }

    /**
     * Where a thing stands, said on the thing itself: on this device, on your own devices, out to somebody
     * else, or in from them. It is a mark and not a control — who receives it is changed from its menu.
     */
    /**
     * Where a thing stands and whether it is up to date, said in words. A thing that is only on this phone
     * says nothing — that is what everything is until it is shared, and a mark on everything marks nothing.
     * Anything that reaches somewhere says where, and whether anything is still waiting to go.
     */
    private TextView standing(NoteStore.Branch branch,boolean brief) {
        String said=Sharing.says(branch.state,branch.waiting,brief);
        if(said==null)return null;
        TextView mark=label(said,brief?11:12,branch.waiting>0?ACCENT:MUTED);
        mark.setGravity(brief?Gravity.CENTER:Gravity.END);
        mark.setSingleLine(true);mark.setEllipsize(TextUtils.TruncateAt.END);
        mark.setContentDescription(Sharing.says(branch.state,branch.waiting,false)
            +", "+Sharing.describe(branch.state,branch.shared));
        return mark;
    }

    private TextView arrow(NoteStore.Branch branch) {
        return tap(branch.shared>0?"↗ "+branch.shared:"↗",
            branch.shared>0?"Shared with "+branch.shared+", change who":"Share "+branch.name,
            15,branch.shared>0?ACCENT:MUTED,
            v->{if(branch.shared>0)sharedWith(branch.scope(),branch.id,branch.name);
                else shareSheet(branch.scope(),branch.id,branch.name);});
    }
    private void empty(String words){TextView empty=label(words,16,MUTED);empty.setGravity(Gravity.CENTER);empty.setPadding(dp(20),dp(72),dp(20),0);rows.addView(empty);}

    // ---- the shelves as a desktop ----------------------------------------------------------------------------

    /** How many tiles fit across, and how big one is. Worked out from the screen, not guessed at. */
    private int columns() {
        int across=Math.max(dp(64),Math.round(dp(112)*reading()));
        return Math.max(2,(getResources().getDisplayMetrics().widthPixels-dp(24))/across);
    }
    private int tileSize(){return (getResources().getDisplayMetrics().widthPixels-dp(24))/columns();}

    /**
     * One thing on the shelves, drawn the way a phone draws an app: a square you can grab, with its name
     * under it. A collection or a book is a bubble showing what is inside it; a page is a sheet of the
     * paper it is written on. Tapping opens it, holding shows what can be done to it, and dragging moves
     * it — onto another thing to put both in a new one, or between two to change the order.
     */
    private View tile(final NoteStore.Branch branch) {
        int size=tileSize();
        LinearLayout tile=column();tile.setGravity(Gravity.CENTER_HORIZONTAL);
        tile.setPadding(dp(4),dp(8),dp(4),dp(8));
        GridLayout.LayoutParams place=new GridLayout.LayoutParams();
        place.width=size;place.height=GridLayout.LayoutParams.WRAP_CONTENT;
        tile.setLayoutParams(place);

        int face=size-dp(26);
        View picture=branch.holds?bubble(branch,face):sheet(branch,face);
        LinearLayout.LayoutParams held=new LinearLayout.LayoutParams(face,face);
        // The mark goes on the corner of the picture rather than beside the name, because the picture is
        // the thing the eye lands on and the corner is the one part of it that is never writing.
        int badge=Math.max(dp(16),face/4);
        View mark=shareBadge(branch,badge);
        if(mark==null)tile.addView(picture,held);
        else {
            android.widget.FrameLayout over=new android.widget.FrameLayout(this);
            over.addView(picture,new android.widget.FrameLayout.LayoutParams(face,face));
            android.widget.FrameLayout.LayoutParams corner=
                new android.widget.FrameLayout.LayoutParams(badge,badge,Gravity.TOP|Gravity.END);
            corner.setMargins(0,-badge/4,-badge/4,0);
            over.addView(mark,corner);
            // And on the other corner, that it is a favourite - said where the thing lives. Not among the
            // favourites themselves, where it would be a star on every tile saying what the room says.
            if(branch.kept&&!amongFavourites()) {
                android.widget.FrameLayout.LayoutParams other=
                    new android.widget.FrameLayout.LayoutParams(badge,badge,Gravity.TOP|Gravity.START);
                other.setMargins(-badge/4,-badge/4,0,0);
                over.addView(star(badge),other);
            }
            over.setClipChildren(false);over.setClipToPadding(false);
            tile.setClipChildren(false);tile.setClipToPadding(false);
            tile.addView(over,held);
        }

        TextView name=label(branch.name,READING,INK);
        name.setGravity(Gravity.CENTER);name.setMaxLines(2);name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(0,dp(6),0,0);
        nameable(name,branch);
        tile.addView(name,new LinearLayout.LayoutParams(-1,-2));


        tile.setBackgroundResource(touchFeedback());
        tile.setContentDescription((branch.holds?"Open "+branch.name:"Open the note "+branch.name)
            +(branch.kept?", a favourite":""));
        tile.setOnClickListener(v->{
            if(amongFavourites())goTo(branch);
            else if(branch.kind==NoteStore.Branch.Kind.PAGE)open(branch.id);
            else enter(branch);
        });
        // Among the favourites nothing is picked up: dropping one thing on another makes a new shelf out of
        // the two, and these are not in the same place to begin with.
        if(amongFavourites())menuOnly(tile,branch);else grabbable(tile,branch);
        return tile;
    }

    /** A collection or a book: a rounded square with the first few things inside it showing through. */
    private View bubble(NoteStore.Branch branch,int face) {
        LinearLayout box=column();
        box.setBackground(edged(Tint.over(branch.colour,CARD,wash(0.22f,0.92f),darkPaper()),branch.colour,false));
        int pad=Math.max(dp(6),face/7);
        box.setPadding(pad,pad,pad,pad);
        int held=Math.max(0,Math.min(4,countIn(branch)));
        for(int row=0;row<2;row++) {
            LinearLayout across=new LinearLayout(this);across.setOrientation(LinearLayout.HORIZONTAL);
            for(int at=0;at<2;at++) {
                View pip=new View(this);
                boolean there=row*2+at<held;
                GradientDrawable shape=new GradientDrawable();
                shape.setCornerRadius(dp(3));
                shape.setColor(there?Tint.over(branch.colour,PAPER,wash(0.10f,0.7f),darkPaper()):0);
                if(there)shape.setStroke(Math.max(1,dp(1)/2),LINE);
                pip.setBackground(shape);
                LinearLayout.LayoutParams cell=new LinearLayout.LayoutParams(0,-1,1);
                cell.setMargins(dp(2),dp(2),dp(2),dp(2));
                across.addView(pip,cell);
            }
            box.addView(across,new LinearLayout.LayoutParams(-1,0,1));
        }
        return box;
    }

    /** A page: the paper it is written on, ruled like the page itself. */
    private View sheet(NoteStore.Branch branch,int face) {
        LinearLayout paper=column();
        paper.setBackground(edged(Tint.over(branch.colour,PAPER,wash(0.14f,0.82f),darkPaper()),branch.colour,false));
        int pad=Math.max(dp(7),face/6);
        paper.setPadding(pad,pad,pad,pad);
        for(int rule=0;rule<3;rule++) {
            View line=new View(this);line.setBackgroundColor(LINE);
            LinearLayout.LayoutParams across=new LinearLayout.LayoutParams(rule==2?face/3:-1,Math.max(1,dp(1)));
            across.setMargins(0,0,0,Math.max(dp(5),face/7));
            paper.addView(line,across);
        }
        return paper;
    }

    /** How many things are inside, so a bubble can show it. The detail line already counted them. */
    private int countIn(NoteStore.Branch branch) {
        int count=0;
        for(int at=0;at<branch.detail.length();at++) {
            char digit=branch.detail.charAt(at);
            if(digit<'0'||digit>'9')break;
            count=count*10+(digit-'0');
        }
        return count;
    }

    /** The tile that adds another one, in the same square as everything else. */
    private View addSquare() {
        Step step=here();
        int size=tileSize(),face=size-dp(26);
        LinearLayout tile=column();tile.setGravity(Gravity.CENTER_HORIZONTAL);
        tile.setPadding(dp(4),dp(8),dp(4),dp(8));
        GridLayout.LayoutParams place=new GridLayout.LayoutParams();
        place.width=size;place.height=GridLayout.LayoutParams.WRAP_CONTENT;
        tile.setLayoutParams(place);
        TextView plus=label("+",30,ACCENT);plus.setGravity(Gravity.CENTER);
        plus.setBackground(shape(0,LINE,true));
        tile.addView(plus,new LinearLayout.LayoutParams(face,face));
        TextView name=label(adding(step.kind).replace("New ",""),READING,ACCENT);
        name.setGravity(Gravity.CENTER);name.setPadding(0,dp(6),0,0);
        tile.addView(name,new LinearLayout.LayoutParams(-1,-2));
        tile.setBackgroundResource(touchFeedback());
        tile.setContentDescription(adding(step.kind));
        tile.setOnClickListener(v->addHere());
        return tile;
    }

    /** A place rather than a thing: the archive, the bin, what other people send you. Outlined, and not grabbed. */
    private View placeTile(final NoteStore.Branch branch) {
        int size=tileSize(),face=size-dp(26);
        LinearLayout tile=column();tile.setGravity(Gravity.CENTER_HORIZONTAL);
        tile.setPadding(dp(4),dp(8),dp(4),dp(8));
        GridLayout.LayoutParams place=new GridLayout.LayoutParams();
        place.width=size;place.height=GridLayout.LayoutParams.WRAP_CONTENT;
        tile.setLayoutParams(place);
        final boolean favourites=branch.kind==NoteStore.Branch.Kind.FAVOURITES;
        LinearLayout box=column();box.setBackground(shape(0,LINE,false));
        if(favourites) {
            // A star where a collection shows what is inside it: the one tile on this screen that is not
            // a thing of yours but a way to the things you keep coming back to.
            box.setGravity(Gravity.CENTER);
            TextView star=label("\u2605",QUIET,INK);
            star.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,face*0.42f);
            star.setIncludeFontPadding(false);star.setGravity(Gravity.CENTER);
            box.addView(star,new LinearLayout.LayoutParams(-1,-1));
        }
        tile.addView(box,new LinearLayout.LayoutParams(face,face));
        TextView name=label(branch.name,READING,favourites?INK:MUTED);
        name.setGravity(Gravity.CENTER);name.setMaxLines(2);name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(0,dp(6),0,0);
        tile.addView(name,new LinearLayout.LayoutParams(-1,-2));
        tile.setBackgroundResource(touchFeedback());
        tile.setContentDescription(branch.name+", "+branch.detail);
        tile.setOnClickListener(v->{
            if(branch.kind==NoteStore.Branch.Kind.INBOX)
                alert("Notes another device shares with you arrive on the shelves, in the collection and "
                    +"book they came out of, marked as having come from it.");
            else enter(branch);
        });
        return tile;
    }

    // ---- putting two things together ---------------------------------------------------------------------

    /** The thing the finger is over, if it is over the middle of one: dropping there puts both in a new one. */
    private View onto;

    private void markOnto(View target) {
        if(onto==target)return;
        if(onto!=null)onto.setBackgroundResource(touchFeedback());
        onto=target;
        if(onto!=null)onto.setBackground(shape(0,ACCENT,false));
    }

    /**
     * Two things dropped together become one that holds them both, one level up: two pages make a book, two
     * books make a collection. It is the desktop's own gesture, and it is the only way to make a container
     * without naming it first — so the name is asked for straight afterwards, with the thing already made.
     */
    private void combine(final NoteStore.Branch one,final NoteStore.Branch two) {
        if(one.kind!=two.kind||one.id.equals(two.id))return;
        final boolean pages=one.kind==NoteStore.Branch.Kind.PAGE;
        if(!pages&&one.kind!=NoteStore.Branch.Kind.BOOK)return;
        final String collection=pages?(trail.size()>1?trail.get(trail.size()-2).id:""):"";
        background.submit(()->{
            List<Sharing.Rule> rules=store.shares();
            Map<String,Boolean> gained=new LinkedHashMap<>(),lost=new LinkedHashMap<>();
            for(NoteStore.Branch thing:new NoteStore.Branch[]{one,two}) {
                Sharing.Change change=pages
                    ? Sharing.moving(rules,collection,thing.parent,collection,"",thing.id)
                    : Sharing.moving(rules,thing.parent,thing.id,"",thing.id,"");
                gained.putAll(change.gained);lost.putAll(change.lost);
            }
            Map<String,String> names=new HashMap<>();
            for(NoteStore.Contact contact:store.addresses())names.put(contact.address,contact.name);
            return new Object[]{new Sharing.Change(gained,lost),names};
        },found->{
            Sharing.Change change=(Sharing.Change)found[0];
            Map<String,String> names=castNames(found[1]);
            if(!change.any()){doCombine(one,two,pages,collection);return;}
            StringBuilder said=new StringBuilder("Putting \"").append(one.name).append("\" and \"").append(two.name)
                .append("\" together changes who receives them.\n");
            if(!change.gained.isEmpty()){said.append("\nStarts reaching:");
                for(Map.Entry<String,Boolean> who:change.gained.entrySet())said.append("\n  • ").append(named(who,names));}
            if(!change.lost.isEmpty()){said.append("\nStops reaching:");
                for(Map.Entry<String,Boolean> who:change.lost.entrySet())said.append("\n  • ").append(named(who,names));}
            // What this used to say - that nothing is sent yet - stopped being true the day sending
            // worked. What is worth saying in its place is the part that cannot be undone.
            said.append("\n\nWhoever starts receiving them gets them now. What has already "
                +"reached somebody stays with them.");
            new Box().setTitle("This changes who can read them").setMessage(said.toString())
                .setPositiveButton("Put them together",(d,w)->doCombine(one,two,pages,collection))
                .setOnCancelListener(d->refresh()).show();
        },e->alert(READ_FAILED));
    }

    private void doCombine(NoteStore.Branch one,NoteStore.Branch two,boolean pages,String collection) {
        background.submit(()->{
            String made=pages?store.addBook(collection,"New book").id:store.addCollection("New collection").id;
            if(pages){store.movePage(one.id,made);store.movePage(two.id,made);}
            else {store.moveBook(one.id,made);store.moveBook(two.id,made);}
            return made;
        },made->{
            dragging=null;lifted=null;
            NoteStore.Branch box=new NoteStore.Branch(
                pages?NoteStore.Branch.Kind.BOOK:NoteStore.Branch.Kind.COLLECTION,made,
                pages?collection:Sharing.EVERYTHING,pages?"New book":"New collection","",0,0,true);
            if(pages)openBookOf(made);else openCollection(made,"New collection");
            sendAfterSharing(pages?Sharing.Scope.BOOK:Sharing.Scope.COLLECTION,made);
            // Made by a gesture, and it takes you inside itself, where there is no tile of its own to type
            // on - so this is the one place a name is still asked for in a box.
            askRename(box);
        },e->alert("Could not put those together. Nothing was changed."));
    }

    private String named(Map.Entry<String,Boolean> who,Map<String,String> names) {
        String name=names.get(who.getKey());
        return (name==null?who.getKey():name)+(Boolean.TRUE.equals(who.getValue())?" (your device)":" (someone else)");
    }

    // ---- putting a level in your own order -----------------------------------------------------------------

    /**
     * Every collection, book and page can be held and dragged into the place you want it, among the things
     * beside it. Holding means only that: a row opens on a tap and moves on a hold, and moving something
     * into a different book or collection stays in the menu, where what it would change is checked first.
     */
    private void orderable(View row,NoteStore.Branch branch) {
        row.setTag(branch);
        row.setOnLongClickListener(v->{lift(v,branch);return true;});
    }

    /**
     * A phone's own desktop: hold a thing and what can be done to it comes up beside it; keep moving and you
     * have picked it up instead. Both come out of the same press, told apart by whether the finger stayed
     * still, which is why the menu is drawn in this window rather than a popup one.
     */
    // The listener never consumes anything: it watches the gesture and returns false, so the tile's own
    // click and long click still run and a screen reader still reaches both.
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private void grabbable(final View tile,final NoteStore.Branch branch) {
        tile.setTag(branch);
        tile.setOnLongClickListener(v->{heldTile=v;heldBranch=branch;menuFor(v,branch);return true;});
        tile.setOnTouchListener((v,event)->{
            switch(event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    heldFrom[0]=event.getRawX();heldFrom[1]=event.getRawY();
                    heldTile=null;heldBranch=null;
                    break;
                // Where the tile is still the one hearing the finger - before the menu is up, or where a
                // long press never happened - the same movement lifts it.
                case MotionEvent.ACTION_MOVE:
                    if(heldTile==null||dragging!=null)break;
                    float moved=Math.max(Math.abs(event.getRawX()-heldFrom[0]),
                                         Math.abs(event.getRawY()-heldFrom[1]));
                    if(moved>ViewConfiguration.get(MainActivity.this).getScaledTouchSlop()) {
                        heldTile=null;heldBranch=null;
                        if(showing!=null)showing.close();
                        lift(v,branch);
                    }
                    break;
                default: heldTile=null;heldBranch=null;break;
            }
            return false;
        });
    }

    /**
     * The last thing done that a person might not have meant, and how to put it back.
     *
     * <p>One deep on purpose. A stack of undos is a thing to navigate; one is a thing to press. What is
     * kept are the four that lose your place — putting something away, binning it, moving it, renaming it —
     * because those are the ones where the pad stops looking how you left it. A colour or a text size is
     * changed back by doing it again, in the place you already are.
     */
    private String undoWhat="";
    private Runnable undoHow;

    /** Remembered, replacing whatever was remembered before: the last thing, not the last few. */
    private void canUndo(String what,Runnable how){undoWhat=what;undoHow=how;}

    private void undo() {
        final Runnable how=undoHow;final String what=undoWhat;
        undoHow=null;undoWhat="";
        if(how==null)return;
        background.submit(()->{how.run();return null;},done->{
            toast(what+" put back");
            if(shelves)refresh();else back();
            refreshOwed();
        },e->alert("Could not put that back. Nothing was changed."));
    }

    /**
     * Every touch passes here first, so a name being typed can be kept by tapping away from it — which is
     * what tapping away means everywhere else in this app.
     */
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        keptNameOnTouchOutside(event);
        // Two fingers zoom the whole pad, the way two fingers zoom a page: everything gets bigger
        // together - the writing, the rules, the tiles, the menu over them - and you move about inside it
        // by dragging with both fingers still down. The reading ladder is a different thing and stays: it
        // sets how big the writing is, which is a decision you keep. This is a look closer, which is not.
        if(pinch==null)pinch=new android.view.ScaleGestureDetector(this,
            new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(android.view.ScaleGestureDetector how) {
                    if(stage==null)return true;
                    float was=zoom;
                    zoom=Math.max(1f,Math.min(4f,zoom*how.getScaleFactor()));
                    // The point between the fingers stays under them, which is what makes a zoom feel
                    // like moving a magnifying glass rather than watching the screen jump.
                    float fx=how.getFocusX(), fy=how.getFocusY();
                    panX=fx-(fx-panX)*(zoom/was);
                    panY=fy-(fy-panY)*(zoom/was);
                    // And dragging both fingers moves what you are looking at.
                    panX+=fx-lastFocusX;panY+=fy-lastFocusY;
                    lastFocusX=fx;lastFocusY=fy;
                    settle();
                    return true;
                }
                @Override public boolean onScaleBegin(android.view.ScaleGestureDetector how) {
                    lastFocusX=how.getFocusX();lastFocusY=how.getFocusY();
                    return true;
                }
            });
        pinch.onTouchEvent(event);
        // A pinch is not a tap: once two fingers are down, nothing underneath should act on them.
        if(event.getPointerCount()>1)return true;
        return super.dispatchTouchEvent(event);
    }

    /**
     * The zoom put on the screen, and kept inside it.
     *
     * <p>Nothing is ever drawn smaller than the window and nothing is ever panned past its own edge, so
     * there is no way to end up looking at a strip of blank next to the pad and wondering where it went.
     * Pinching back to where you started puts it exactly back: one is one.
     */
    private void settle() {
        if(stage==null)return;
        float over=(zoom-1f)*stage.getWidth(), down=(zoom-1f)*stage.getHeight();
        panX=Math.max(-over,Math.min(0f,panX));
        panY=Math.max(-down,Math.min(0f,panY));
        stage.setPivotX(0);stage.setPivotY(0);
        stage.setScaleX(zoom);stage.setScaleY(zoom);
        stage.setTranslationX(panX);stage.setTranslationY(panY);
    }

    private android.view.ScaleGestureDetector pinch;
    /** One is the pad at its own size; four is as close as it will go. */
    private float zoom=1f;
    private float panX, panY, lastFocusX, lastFocusY;

    /**
     * A word becomes the field it already looks like, where it already is.
     *
     * <p>This is how a collection and a book have always been renamed, and it is now the only way anything
     * in this app is renamed. A box that opens over the thing you are changing hides the thing you are
     * changing, and then has to be dismissed, and then has to explain how to dismiss it. Changing a word in
     * the place the word is needs none of that: what is typed is what you can see, and looking away keeps
     * it, the same as looking away from anything else here.
     *
     * @param shown the word on the screen, which is replaced for as long as it is being typed
     * @param keep  what to do with what was typed, or with nothing where it was emptied
     */
    private void editInPlace(final TextView shown,final Consumer<String> keep) {
        editInPlace(shown,keep,40,null);
    }

    /**
     * @param most    how long what is typed may be
     * @param offered what the field opens holding where the word itself is empty, or null for nothing
     */
    private void editInPlace(final TextView shown,final Consumer<String> keep,int most,String offered) {
        final android.view.ViewGroup holder=(android.view.ViewGroup)shown.getParent();
        if(holder==null)return;
        final int at=holder.indexOfChild(shown);
        final String there=shown.getText().toString();
        final String was=there.isEmpty()&&offered!=null?offered:there;
        final EditText typing=field("",most);
        typing.setSingleLine(true);
        typing.setText(was);typing.setSelection(0,was.length());
        typing.setTextSize(shown.getTextSize()/getResources().getDisplayMetrics().scaledDensity);
        typing.setGravity(shown.getGravity());
        typing.setBackground(null);
        holder.removeView(shown);
        holder.addView(typing,at,shown.getLayoutParams());
        typing.requestFocus();
        InputMethodManager keys=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
        if(keys!=null)keys.showSoftInput(typing,0);
        if(android.os.Build.VERSION.SDK_INT>=30) {
            android.view.WindowInsetsController asking=typing.getWindowInsetsController();
            if(asking!=null)asking.show(android.view.WindowInsets.Type.ime());
        }
        // A view added a moment ago is not always one the system will raise a keyboard for yet, and in a
        // box it is the box's window that has to be listening. Asked again once it is.
        typing.postDelayed(()->{
            if(!typing.isAttachedToWindow()||!typing.isFocused())return;
            InputMethodManager again=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
            if(again!=null)again.showSoftInput(typing,0);
        },150);
        final boolean[] done={false};
        final Runnable settle=()->{
            if(done[0])return;
            done[0]=true;
            if(keys!=null)keys.hideSoftInputFromWindow(typing.getWindowToken(),0);
            keep.accept(typing.getText().toString().trim());
        };
        typing.setOnFocusChangeListener((v,has)->{if(!has)settle.run();});
        typing.setOnEditorActionListener((v,action,event)->{settle.run();return true;});
        naming=typing;namingKeep=settle;
        // Inside a box, the touches go to the box's own window and the activity never sees them - so
        // tapping away has to be heard where it happens. On the shelves this is the same tree the activity
        // owns, so one listener answers for both.
        final View whole=typing.getRootView();
        // Borrowed, and given back. A box listens on this same view for a tap beside its paper, and a
        // name typed inside one used to leave it with nobody listening at all.
        final View.OnTouchListener before=whole!=null&&whole.getTag() instanceof View.OnTouchListener
            ?(View.OnTouchListener)whole.getTag():null;
        if(whole!=null)whole.setOnTouchListener((v,event)->{
            if(done[0]){whole.setOnTouchListener(before);return false;}
            if(event.getActionMasked()!=MotionEvent.ACTION_DOWN)return false;
            int[] box=new int[2];typing.getLocationOnScreen(box);
            float x=event.getRawX(), y=event.getRawY();
            if(x>=box[0]&&x<=box[0]+typing.getWidth()&&y>=box[1]&&y<=box[1]+typing.getHeight())return false;
            settle.run();
            whole.setOnTouchListener(before);
            return false;
        });
    }

    /** A name being typed somewhere on the shelves, and what keeping it means. */
    private EditText naming;
    private Runnable namingKeep;

    /**
     * A tap anywhere but the name being typed keeps it, exactly as the keyboard's tick does. A field that
     * stays open because nothing else on a screen of tiles wanted focus is a field you have to know how to
     * leave, and nobody should have to know that.
     */
    private boolean keptNameOnTouchOutside(MotionEvent event) {
        if(naming==null||event.getActionMasked()!=MotionEvent.ACTION_DOWN)return false;
        if(!naming.isAttachedToWindow()){naming=null;namingKeep=null;return false;}
        int[] at=new int[2];naming.getLocationOnScreen(at);
        float x=event.getRawX(), y=event.getRawY();
        if(x>=at[0]&&x<=at[0]+naming.getWidth()&&y>=at[1]&&y<=at[1]+naming.getHeight())return false;
        Runnable keep=namingKeep;
        naming=null;namingKeep=null;
        if(keep!=null)keep.run();
        return false;
    }

    /** The thing a finger is holding, and where it first went down: a press that may yet become a drag. */
    private View heldTile;
    private NoteStore.Branch heldBranch;
    private final float[] heldFrom=new float[2];

    /** The row lifts off the list and leaves its gap behind, which then follows the finger. */
    private void lift(View row,NoteStore.Branch branch) {
        dragging=branch;lifted=row;
        row.startDragAndDrop(null,new View.DragShadowBuilder(row),null,0);
        row.setVisibility(View.INVISIBLE);
    }

    private boolean dragOver(View list,DragEvent event) {
        switch(event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED: return dragging!=null;
            case DragEvent.ACTION_DRAG_LOCATION:
                if(desktop)across(event.getX(),event.getY());else follow(event.getY());
                return true;
            case DragEvent.ACTION_DROP:
                if(onto!=null) {
                    Object held=onto.getTag();
                    markOnto(null);
                    if(held instanceof NoteStore.Branch&&dragging!=null)combine(dragging,(NoteStore.Branch)held);
                } else keepOrder();
                return true;
            case DragEvent.ACTION_DRAG_ENDED:
                markOnto(null);
                if(lifted!=null)lifted.setVisibility(View.VISIBLE);
                dragging=null;lifted=null;
                // Let go of it anywhere but the level itself and nothing was rearranged, so it is read back.
                if(!event.getResult())refresh();
                return true;
            default: return true;
        }
    }

    /**
     * On a desktop the finger means one of two things: over the middle of another thing, dropping puts both
     * in a new one; anywhere between them, the tiles part and dropping keeps that order.
     */
    private void across(float x,float y) {
        if(lifted==null||tiles==null)return;
        int first=-1,held=0;
        List<Float> middlesX=new ArrayList<>(),middlesY=new ArrayList<>();
        View over=null;
        for(int at=0;at<tiles.getChildCount();at++) {
            View child=tiles.getChildAt(at);
            if(!(child.getTag() instanceof NoteStore.Branch))continue;
            if(first<0)first=at;
            if(child==lifted)held=middlesX.size();
            float middleX=child.getX()+child.getWidth()/2f, middleY=child.getY()+child.getHeight()/2f;
            middlesX.add(middleX);middlesY.add(middleY);
            boolean inside=x>child.getX()+child.getWidth()*0.2f&&x<child.getX()+child.getWidth()*0.8f
                &&y>child.getY()+child.getHeight()*0.15f&&y<child.getY()+child.getHeight()*0.7f;
            if(inside&&child!=lifted&&sameKind(child))over=child;
        }
        if(first<0)return;
        markOnto(over);
        if(over!=null)return;
        float[] acrossX=new float[middlesX.size()],downY=new float[middlesY.size()];
        for(int at=0;at<acrossX.length;at++){acrossX[at]=middlesX.get(at);downY[at]=middlesY.get(at);}
        float halfRow=lifted.getHeight()/2f;
        int want=first+Reorder.slot(x,y,acrossX,downY,halfRow,held);
        if(want!=tiles.indexOfChild(lifted)){tiles.removeView(lifted);tiles.addView(lifted,want);}
    }

    /** Only two of a kind go together: a page with a page, a book with a book. */
    private boolean sameKind(View child) {
        Object held=child.getTag();
        return dragging!=null&&held instanceof NoteStore.Branch
            &&((NoteStore.Branch)held).kind==dragging.kind
            &&dragging.kind!=NoteStore.Branch.Kind.COLLECTION;
    }

    /** The gap moves to where the finger is, so the level always shows the order it would keep. */
    private void follow(float y) {
        if(lifted==null||rows==null)return;
        int first=-1,held=0;
        List<Float> middles=new ArrayList<>();
        for(int at=0;at<rows.getChildCount();at++) {
            View child=rows.getChildAt(at);
            if(!(child.getTag() instanceof NoteStore.Branch))continue;
            if(first<0)first=at;
            if(child==lifted)held=middles.size();
            middles.add(child.getY()+child.getHeight()/2f);
        }
        if(first<0)return;
        float[] line=new float[middles.size()];
        for(int at=0;at<line.length;at++)line[at]=middles.get(at);
        int want=first+Reorder.slot(y,line,held);
        if(want!=rows.indexOfChild(lifted)){rows.removeView(lifted);rows.addView(lifted,want);}
        edge(y);
    }

    /** Held against the top or bottom of the list, it scrolls, so a long book is reordered in one gesture. */
    private void edge(float y) {
        if(scroller==null)return;
        int reach=dp(72),step=dp(12),seen=scroller.getScrollY();
        if(y<seen+reach)scroller.scrollBy(0,-step);
        else if(y>seen+scroller.getHeight()-reach)scroller.scrollBy(0,step);
    }

    /** What the level looks like now is what it is: the places are written in one go, and silently. */
    private void keepOrder() {
        android.view.ViewGroup level=desktop?tiles:rows;
        if(dragging==null||level==null)return;
        final NoteStore.Branch.Kind kind=dragging.kind;
        final List<String> ids=new ArrayList<>();
        for(int at=0;at<level.getChildCount();at++) {
            Object held=level.getChildAt(at).getTag();
            if(held instanceof NoteStore.Branch)ids.add(((NoteStore.Branch)held).id);
        }
        background.submit(()->{store.order(kind,ids);return null;},
            done->{},e->{alert("Could not keep that order.");refresh();});
    }

    // ---- carrying something somewhere else ---------------------------------------------------------------

    /** Works out what the move would do to the audience before anything is written. */
    private void putDown(NoteStore.Branch place) {
        if(carrying==null)return;
        final NoteStore.Branch moved=carrying;final String destination=place.id,into=place.name;
        if(destination.equals(moved.parent)){carrying=null;toast("Already there");browse();return;}
        background.submit(()->{
            List<Sharing.Rule> rules=store.shares();
            Sharing.Change change=moved.kind==NoteStore.Branch.Kind.PAGE
                ? Sharing.moving(rules,store.collectionOf(moved.parent),moved.parent,store.collectionOf(destination),destination,moved.id)
                : Sharing.moving(rules,moved.parent,moved.id,destination,moved.id,"");
            Map<String,String> names=new HashMap<>();
            for(NoteStore.Contact contact:store.addresses())names.put(contact.address,contact.name);
            return new Object[]{change,names};
        },found->confirmMove(moved,destination,into,(Sharing.Change)found[0],castNames(found[1])),
           e->alert(READ_FAILED));
    }

    @SuppressWarnings("unchecked")
    private Map<String,String> castNames(Object names){return (Map<String,String>)names;}

    private void confirmMove(NoteStore.Branch moved,String destination,String into,Sharing.Change change,Map<String,String> names) {
        if(!change.any()){doMove(moved,destination,into);return;}
        StringBuilder said=new StringBuilder("Moving \"").append(moved.name).append("\" into ").append(into)
            .append(" changes who receives it.\n");
        if(!change.gained.isEmpty()) {
            said.append("\nStarts reaching:");
            for(Map.Entry<String,Boolean> who:change.gained.entrySet())said.append("\n  • ").append(who(who,names));
        }
        if(!change.lost.isEmpty()) {
            said.append("\nStops reaching:");
            for(Map.Entry<String,Boolean> who:change.lost.entrySet())said.append("\n  • ").append(who(who,names));
        }
        said.append("\n\nWhoever starts receiving it gets it now. What has already reached "
            +"somebody stays with them.");
        new Box().setTitle("This changes who can read it").setMessage(said.toString())
            .setPositiveButton("Move anyway",(d,w)->doMove(moved,destination,into))
            .setOnCancelListener(d->{carrying=null;browse();}).show();
    }
    private String who(Map.Entry<String,Boolean> reached,Map<String,String> names) {
        String name=names.get(reached.getKey());
        return (name!=null?name:reached.getKey())+(reached.getValue()?" (your device)":" (someone else)");
    }

    /**
     * Moving something ends where it landed, not where you happened to be standing. Being returned to a
     * level the thing is no longer in is what makes a move that worked look like one that did not.
     */
    private void doMove(NoteStore.Branch moved,String destination,String into) {
        final boolean page=moved.kind==NoteStore.Branch.Kind.PAGE;
        background.submit(()->{
            // Where it was, before it is anywhere else: an undo has to know the place to put it back into.
            final String from=page?store.bookOf(moved.id):store.collectionOfBook(moved.id);
            if(page)store.movePage(moved.id,destination);
            else store.moveBook(moved.id,destination);
            final String called=moved.name;
            handler.post(()->canUndo(called,()->{
                if(page)store.movePage(moved.id,from);else store.moveBook(moved.id,from);
            }));
            return null;},
            done->{carrying=null;toast("Moved into "+into);
                if(page)openBookOf(destination);else openCollection(destination,into);
                // Moving something into a book somebody else reads is a disclosure, and one that was
                // announced and then queued. It goes now, like sharing does.
                sendAfterSharing(page?Sharing.Scope.PAGE:Sharing.Scope.BOOK,moved.id);},
            e->{carrying=null;alert("Could not move that. Nothing was changed.");browse();});
    }

    /** The books of one collection, from anywhere. */
    private void openCollection(String collection,String name) {
        trail.clear();
        trail.add(new Step(NoteStore.Branch.Kind.LIBRARY,Sharing.EVERYTHING,"All collections"));
        trail.add(new Step(NoteStore.Branch.Kind.COLLECTION,collection,name));
        browse();
    }

    // ---- making and removing ------------------------------------------------------------------------------

    /** The + adds whatever this level holds: a collection, a book, or a page. */


    // ---- the addresses you share with --------------------------------------------------------------------

    /** Kept once, then picked. Retyping a long address is how a page reaches the wrong person. */
    private void addressBook() {
        background.submit(store::addresses,known->{
            LinearLayout body=inside();
            if(known.isEmpty())body.addView(label("No addresses saved yet.",READING,INK));
            for(final NoteStore.Contact contact:known) {
                LinearLayout entry=new LinearLayout(this);entry.setGravity(Gravity.CENTER_VERTICAL);
                entry.setPadding(0,dp(12),0,dp(10));entry.setBackgroundResource(touchFeedback());
                LinearLayout words=column();
                words.addView(line(contact.name,READING,INK));
                TextView address=line(contact.address,READING,MUTED);
                address.setEllipsize(TextUtils.TruncateAt.MIDDLE);words.addView(address);
                words.addView(label(contact.mine?"both ways":"receives my changes",QUIET,MUTED));
                entry.addView(words,new LinearLayout.LayoutParams(0,-2,1));
                // One thing to say about an address, so one thing to tap: is it a device of yours?
                TextView mine=label(contact.mine?"● mine":"○ mine",QUIET,contact.mine?ACCENT:MUTED);
                mine.setGravity(Gravity.CENTER);mine.setMinWidth(dp(70));
                entry.addView(mine);
                entry.setContentDescription(contact.name+", "+(contact.mine?"a device of mine":"somebody else")
                    +". Tap to change, hold to forget.");
                entry.setOnClickListener(v->background.submit(()->{store.setMine(contact.address,!contact.mine);return null;},
                    done->{refresh();addressBook();},e->alert("Could not change that. Nothing was changed.")));
                entry.setOnLongClickListener(v->{forgetAddress(contact);return true;});
                body.addView(entry);
            }
            ScrollView scroll=scrolling(body);
            new Box().setTitle("Addresses").setView(scroll)
                .setNeutralButton("Add a device",(d,w)->myAddress())
                .setPositiveButton("Share with someone",(d,w)->typeAddress(null,null,null)).show();
        },e->alert(READ_FAILED));
    }

    // ---- pairing one device with another -----------------------------------------------------------------

    /**
     * This device, as the other one has to see it: where to reach it, and the two keys — one to seal for it,
     * one to check what it signs. The address can be typed or pasted, because a node's address is the
     * person's to say: Core will tell us when it answers, and until then nobody should be stuck waiting.
     *
     * <p>The line beneath is not a secret — anybody who has it can write to you, and nobody who has it can
     * read what you send. What makes it trust is the six digits both screens show when the other end reads
     * it: they come from the keys themselves, so a line changed on its way will not agree.
     */
    private void myAddress() { withAddress(()->myCode("My address",null,null,"",false,null)); }

    /**
     * The code, and a way to copy it.
     *
     * @param titled  what the box is called where it is opened from
     * @param said    the line above the code, or null for the plain one
     * @param andThen a third button and where it leads, or null for none
     */
    private void myCode(final String titled,final String said2,final Runnable andThen,
                        final String offer,final boolean writes,final Runnable scanning) {
        background.submit(()->{
            String said=getSharedPreferences("settings",MODE_PRIVATE).getString("address","");
            return new Object[]{said,keys().line(deviceName(),said,offer,writes)};
        },ready->{
            final String said=(String)((Object[])ready)[0];
            final String line=(String)((Object[])ready)[1];
            // Nothing here but the address and its code: a box you hold up to another phone should not have
            // to be scrolled to find the thing you are holding up.
            LinearLayout body=inside();
            body.addView(label(said2!=null?said2
                :offer.isEmpty()?"Hold this up to their phone, or send it to them. It is how anybody "
                    +"reaches this pad - both to send you things and to be sent them."
                :"Let them scan this. Their phone will say what they are being given and what they can do "
                    +"with it, in the words below.",READING,MUTED));
            // What the code is offering, said on this screen in the same words the other phone will use,
            // so nobody has to hold one up and hope. Tapping it is how the level is changed, before it is
            // held up rather than afterwards - the code itself carries it.
            if(!offer.isEmpty()) {
                TextView level=tap((writes?"They can read and write ":"They can read ")+offer,
                    offer+", "+(writes?"read and write":"read only"),READING,ACCENT,null);
                level.setGravity(Gravity.CENTER);level.setPadding(dp(4),dp(10),dp(4),dp(2));
                level.setOnClickListener(v->{
                    offerWrites=!writes;
                    myCode(titled,said2,andThen,offer,offerWrites,scanning);
                });
                body.addView(level);
            }
            // The code is the thing to hold up; the address itself is sixty characters nobody reads unless
            // they are checking it against something, so it waits behind a word until it is asked for.
            final TextView address=label("",READING,INK);
            address.setGravity(Gravity.CENTER);address.setPadding(0,dp(8),0,0);
            address.setVisibility(View.GONE);
            // An address that carries no host reaches nobody, however right it looks, so it is said here
            // rather than found out when the first thing sent never arrives.
            if(!said.isEmpty()&&!Pairing.reachable(said))
                body.addView(label("This address has no host after the @, so nothing can reach it. "
                    +"Open Core and copy the whole Maxima contact address.",READING,WARN));
            final TextView show=tap(said.isEmpty()?"No address yet — tap Change it":"Show the address",
                "Show the address",READING,ACCENT,null);
            show.setGravity(Gravity.CENTER);show.setPadding(0,dp(8),0,0);
            show.setOnClickListener(v->{
                if(said.isEmpty())return;
                address.setText(said);address.setVisibility(View.VISIBLE);show.setVisibility(View.GONE);
            });
            body.addView(show);body.addView(address);
            // Only where the button below is doing something else: one way to change it, not two.
            if(scanning!=null) {
                TextView change=tap("Change it","Change this device's address",READING,MUTED,v->changeMyAddress());
                change.setGravity(Gravity.CENTER);change.setPadding(0,dp(2),0,0);
                body.addView(change);
            }
            // The code is the thing you hold up to another phone's camera, and a real Maxima address makes
            // a dense one: every pixel of it is a pixel the camera has to resolve across the room. So it is
            // drawn at the width the box turns out to have rather than at a width guessed beforehand — a
            // code given more room than the box has is not shrunk to fit, it is cut, and a cut code has no
            // corner to find it by.
            try {
                ImageView code=new ImageView(this);
                // Drawn from the code's own size, one pixel a square, and blown up to whatever room the box
                // turns out to have. A bitmap made at a guessed size and then shrunk loses whole squares in
                // the shrinking, and a code missing squares reads at arm's length and nowhere further.
                android.graphics.drawable.BitmapDrawable drawn=
                    new android.graphics.drawable.BitmapDrawable(getResources(),Qr.of(Pairing.link(line),1));
                // Squares, not a photograph: smoothing the edges is exactly what a camera trips over.
                drawn.setFilterBitmap(false);
                code.setImageDrawable(drawn);
                code.setAdjustViewBounds(true);
                code.setScaleType(ImageView.ScaleType.FIT_CENTER);
                LinearLayout.LayoutParams place=new LinearLayout.LayoutParams(-1,-2);
                place.gravity=Gravity.CENTER_HORIZONTAL;place.setMargins(0,dp(12),0,0);
                code.setContentDescription("This device's code");
                body.addView(code,place);
            } catch(Exception e){/* a line that cannot be drawn is still a line you can copy */}
            AlertDialog.Builder box=new Box().setTitle(titled).setView(scrolling(body));
            // Both directions, side by side: hold this up for them to scan, or scan the one they are
            // holding up. Which of you shows and which of you scans should not decide what can happen.
            if(andThen!=null)box.setNegativeButton("Shared with",(d,w)->andThen.run());
            if(scanning!=null)box.setNeutralButton("Add someone",(d,w)->scanning.run());
            else box.setNeutralButton("Change",(d,w)->changeMyAddress());
            // In the box that is about this device's address, copying means the address - that is the
            // thing somebody asked you for. Where the box is a code being held up, it means the code.
            final boolean addressBox=scanning==null&&offer.isEmpty();
            box.setPositiveButton(addressBox?"Copy address":"Copy",(d,w)->{
                    if(addressBox&&said.isEmpty()){alert("There is no address yet to copy.");return;}
                    ClipboardManager board=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                    if(board!=null)board.setPrimaryClip(ClipData.newPlainText("Mininotes",addressBox?said:line));
                    toast(addressBox?"Address copied":"Code copied");
                }).show();
        },e->alert("Could not prepare this device's keys. Nothing was changed."));
    }

    /** Changing it is the same two ways as adding anybody else's: pasted, or read off a screen. */
    private void changeMyAddress() {
        LinearLayout body=inside();
        body.addView(label("Paste this device's Maxima address, or scan it from Core. The whole of it: "
            +"the key, then @ and the host it can be reached through.",READING,MUTED));
        new Box().setTitle("Change my address").setView(body)
            .setNeutralButton("Scan a code",(d,w)->scanning(said->{keepMyAddress(said);myAddress();}))
            .setPositiveButton("Paste",(d,w)->{
                ClipboardManager board=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                ClipData clip=board==null?null:board.getPrimaryClip();
                CharSequence on=clip==null||clip.getItemCount()==0?null:clip.getItemAt(0).coerceToText(this);
                if(on==null||on.toString().trim().isEmpty()){alert("There is nothing on the clipboard to paste.");return;}
                keepMyAddress(on.toString().trim());myAddress();
            }).show();
    }

    private void keepMyAddress(String typed) {
        final String said=typed==null?"":typed.trim();
        // Kept either way — a half address is better mended than refused — but never kept quietly.
        if(!said.isEmpty()&&!Pairing.reachable(said))
            alert("That has no host after the @, so nothing will reach it. It is kept, but copy the whole "
                +"contact address from Core to be reachable.");
        myAddress=said;
        background.submit(()->{getSharedPreferences("settings",MODE_PRIVATE).edit().putString("address",said).apply();return null;},
            done->{toast(said.isEmpty()?"Address cleared":"Address kept");},
            e->alert("Could not keep that. Nothing was changed."));
    }


    /**
     * Another device's code, taken for what it is.
     *
     * <p>A code <em>scanned off the other screen</em> needs nothing further: you are looking at the device
     * you mean, and nothing came between the two of you. A line that arrived some other way — through a
     * message, a mail, somebody passing it along — could have been changed on the way, and for that there
     * are six digits taken from the keys themselves: if the two screens disagree, the line was altered.
     */
    private void readPairing(String said,boolean scanned){readPairing(said,scanned,false);}

    /**
     * @param outside opened from outside the app rather than read by the scanner in it. Scanning in here
     *                is somebody saying "this device"; a camera that opened the app on its own account is
     *                not, so nothing it brings is kept without being asked about first.
     */
    private void readPairing(String said,boolean scanned,boolean outside) {
        final Pairing.Said them;
        try{them=Pairing.read(said);}catch(IllegalArgumentException e){alert(e.getMessage());return;}
        if(scanned) {
            if(them.offer.isEmpty()&&outside) {
                new Box().setTitle(them.name).setMessage("Pair with this device?")
                    .setPositiveButton("Pair",(d,w)->keepPairing(them)).show();
                return;
            }
            if(them.offer.isEmpty()){keepPairing(them);return;}
            new Box().setTitle(them.name+" is sharing with you")
                .setMessage(them.offer+"\n"+"\n"+(them.writes
                    ?"You will be able to read it and write in it. What you write goes back to them."
                    :"You will be able to read it. Writing in it stays on this phone.")
                    +"\n"+"\n"+"It will appear on your shelves when it arrives.")
                .setPositiveButton("Accept",(d,w)->keepPairing(them))
                .show();
            return;
        }
        background.submit(()->Envelope.code(keys().signing().getPublic(),Keys.publicKey(them.signing)),digits->{
            new Box().setTitle(them.name)
                .setMessage("This line came from somewhere else, so it could have been changed on the way."
                    +" Send them your code as well: when they read it, their phone shows six digits too, and"
                    +" the two must be the same.\n\nThis phone says:\n\n        "+digits
                    +"\n\nScanning the code off their screen instead needs none of this.")
                .setPositiveButton("They match",(d,w)->keepPairing(them))
                .setNeutralButton("They differ",(d,w)->alert("Nothing was saved. Scan the code from the screen instead."))
                .show();
        },e->alert("That line's keys could not be read. Nothing was saved."));
    }

    /** The strip that is waiting for what somebody offered to arrive, put away by the arriving. */
    private int awaiting;
    private final Runnable notYet=()->{
        if(awaiting==0)return;
        busyDone(awaiting,"Not here yet. It comes when their phone is next open.");awaiting=0;
    };

    private void keepPairing(Pairing.Said said) {
        final int job=busy("Saving "+said.name+"\u2026");
        final String[] unreached={null};
        network.submit(()->{
            store.pairedWith(said.address,said.name,true,said.agreement,said.signing);
            // And introduce the two nodes, so what was scanned is the last address either of them has to
            // be told by hand. A failure here is not a failure to pair: the keys are saved either way, and
            // sending falls back to the address on the code until an introduction takes.
            busySay(job,"Finding "+said.name+" on the network\u2026");
            try {
                String key=Node.introduce(this,said.address);
                if(!key.isEmpty())store.knownAs(said.address,key);
            } catch(Exception notNow){/* the address on the code still works until it does not */}
            // And, where they offered something, tell them it was taken. A code is read in one direction:
            // without this the phone that made the offer never hears that anybody accepted, and the person
            // who accepted watches a shelf nothing arrives on.
            // A plain code too: without a hello the device that showed it never learns this phone, and
            // drops as a stranger's everything this phone then shares with it.
            {
                String mine=getSharedPreferences("settings",MODE_PRIVATE).getString("address","");
                // Kept before it is sent, because the sending may be to a phone nobody is holding.
                store.accepting(said.address,said.name,said.scope,said.target,said.writes);
                busySay(job,said.target.isEmpty()?"Telling "+said.name+" you paired\u2026":"Telling "+said.name+" you accepted\u2026");
                // Not reaching them is not a failure to pair, and is not reported as one: it used to land
                // in "Could not save that device. Nothing was changed.", after the device had been saved.
                // What was accepted is kept, and said again every time this opens until they answer.
                try{Post.accept(this,keys(),said,deviceName(),mine);}
                catch(Exception notNow){unreached[0]=notNow.getMessage()==null?"":notNow.getMessage();}
            }
            return null;
        },done->{
            // There is somebody to hear from now, so the pad goes on listening after it is closed.
            background.submit(()->{Listening.settle(this);return null;},settled->askToSaySo(),e->{});
            if(said.offer.isEmpty()){
                busyDone(job,unreached[0]!=null?"Paired. "+said.name+" is told when it is next reachable":"Paired. "+said.name+" is asked to pair back");
                addressBook();return;
            }
            if(unreached[0]!=null) {
                busyDone(job,null);
                alert(said.name+" could not be reached just now. This phone tells them again by itself, "
                    +"each time it is opened, until they answer.");
                return;
            }
            // Scanning a code says what is being offered; it does not fetch it. Nothing is pulled in this
            // app - the other end sends - so the strip stays up saying what is being waited for, until it
            // arrives or until long enough has gone by to say that it has not.
            busySay(job,"Waiting for "+said.name+" to send it\u2026");
            awaiting=job;
            handler.removeCallbacks(notYet);handler.postDelayed(notYet,120_000);
        },e->{busyDone(job,null);alert("Could not save that device. Nothing was changed.");});
    }

    /** What this phone calls itself when it introduces itself, and what the other end will list it as. */
    /**
     * What everybody else sees this pad called.
     *
     * <p>It starts as whatever the manufacturer wrote on the phone, because something has to be there
     * before anybody has thought about it — but "Pixel 7" is what a shop calls a device, not what a person
     * calls themselves, and it is the word the other end reads when something arrives. So it can be changed,
     * and what is kept is what is used: in the pairing line, and as the node's own name on the network.
     */
    private String deviceName() {
        String kept=getSharedPreferences("settings",MODE_PRIVATE).getString("me","");
        if(!kept.trim().isEmpty())return kept.trim();
        String made=android.os.Build.MODEL;
        return made==null||made.trim().isEmpty()?"A device":made.trim();
    }

    private void keepDeviceName(String said) {
        final String name=said==null?"":said.trim();
        final int job=busy("Telling the network\u2026");
        network.submit(()->{
            getSharedPreferences("settings",MODE_PRIVATE).edit().putString("me",name).apply();
            Node.called(this,name.isEmpty()?deviceName():name);
            return null;
        },done->{busyDone(job,name.isEmpty()?"Back to the phone's own name":"Now called "+name);profile();},
          e->{busyDone(job,null);alert("Could not keep that. Nothing was changed.");});
    }

    /**
     * Everything about this pad rather than about what is on it, in one place and in three parts: what you
     * are called, how anybody reaches you, and whether the node behind that is working.
     *
     * <p>They were three rows in a menu, which meant three places to look for one subject. A person setting
     * a phone up for the first time wants all of it at once.
     */
    private void profile() {
        withAddress(()->background.submit(()->{
            String said=getSharedPreferences("settings",MODE_PRIVATE).getString("address","");
            int paired=0,known=0;
            for(NoteStore.Contact contact:store.addresses()){known++;if(contact.paired())paired++;}
            int owed=store.owed(NoteStore.Branch.Kind.LIBRARY,Sharing.EVERYTHING).size();
            return new Object[]{said,known,paired,owed,keys().line(deviceName(),said)};
        },ready->{
            final Object[] got=(Object[])ready;
            final String address=(String)got[0];
            final int known=(Integer)got[1], paired=(Integer)got[2], owed=(Integer)got[3];
            final String line=(String)got[4];
            // Nothing in here is indented against anything else: every line, every heading and every tick
            // starts at the same edge, and the checklist's tick sits in the margin to the left of it rather
            // than pushing its words along. A page where each part chose its own edge is the untidiness.
            LinearLayout body=inside();

            body.addView(part("YOU"));
            final TextView called=label(deviceName(),READING,INK);
            called.setPadding(0,dp(2),0,dp(2));
            called.setMinimumHeight(dp(44));
            called.setGravity(Gravity.CENTER_VERTICAL);
            called.setBackgroundResource(touchFeedback());
            called.setContentDescription("Change what this pad is called");
            body.addView(called);
            body.addView(under("What the other end sees when something of yours arrives."));

            body.addView(part("YOUR ADDRESS"));
            if(address.isEmpty())
                body.addView(under(Node.allowedOnTheNetwork(this)
                    ?"No relay has answered yet, so nothing can reach this phone."
                    :"This app is not allowed on the network, so it has no address."));
            else {
                // The code sits in the middle of its own width, with the same air above and below it as
                // the headings have, so it reads as one thing on the page rather than a picture dropped in.
                LinearLayout.LayoutParams place=new LinearLayout.LayoutParams(-1,-2);
                place.setMargins(0,dp(6),0,dp(10));
                body.addView(codeView(line),place);
                TextView shown=label(address,HEADING,MUTED);
                shown.setLineSpacing(dp(2),1f);
                shown.setTextIsSelectable(true);
                body.addView(shown);
                body.addView(tapRow("Copy address",()->{
                    ClipboardManager board=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                    if(board!=null)board.setPrimaryClip(ClipData.newPlainText("Mininotes",address));
                    toast("Address copied");
                }));
            }

            // One line, which only says more when something is wrong. It was four ticked lines about the
            // app's own plumbing - that it is a Maxima node, that it has an address, that there is somebody
            // to send to, that their keys are known - which is what somebody building it wants to see and
            // nobody using it needs to. Connected, or the one thing to do about not being.
            body.addView(part("Connection"));
            boolean allowed=Node.allowedOnTheNetwork(this);
            step(body,!address.isEmpty(),
                !address.isEmpty()?"Connected":allowed?"Connecting\u2026":"Not connected",
                !address.isEmpty()?"":allowed?"It can take a few seconds to find the network."
                    :"This app is not allowed on the network. Allow it in the phone's settings.");
            if(owed>0)body.addView(under(owed==1?"1 note is waiting to go.":owed+" notes are waiting to go."));
            // Whether it goes on listening once the pad is closed: on or off, so a switch.
            final TextView means=under("");
            final Runnable says=()->means.setText(!Listening.switchedOn(this)
                ?"Notes arrive only while the pad is open."
                :paired==0?"Starts once a device is paired."
                :"Android shows a notification for as long as it listens.");
            says.run();
            body.addView(switchRow("Listen while the pad is closed",Listening.switchedOn(this),on->{
                Listening.switchOn(this,on);
                says.run();
                background.submit(()->{Listening.settle(this);return null;},
                    done->{if(on&&paired>0)askToSaySo();},e->{});
            }));
            body.addView(means);
            if(paired>0&&Listening.switchedOn(this)&&!maySaySo()) {
                body.addView(tapRow("Let it say when a note arrives",()->started(
                    new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,getPackageName()))));
                body.addView(under("This app is not allowed to show notifications. A note still arrives; nothing says so."));
            }
            // Android's battery saving stops a closed app from listening once the phone has slept a while.
            // Asked for here, by a tap, never on its own; Android puts the question.
            // On or off, so a switch - but Android holds the answer, not the pad: the switch opens Android's
            // own question (or, to turn it off, its battery list) and shows what Android says on coming back.
            sleepSwitch=null;sleepSays=null;
            if(paired>0&&Listening.switchedOn(this)&&getSystemService(android.os.PowerManager.class)!=null) {
                View row=switchRow("Keep listening while the phone sleeps",sleepAllowed(),on->{
                    if(quietSwitch)return;
                    started(on?new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            android.net.Uri.parse("package:"+getPackageName()))
                        :new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                });
                sleepSwitch=(android.widget.Switch)((LinearLayout)row).getChildAt(1);
                sleepSays=under("");
                body.addView(row);body.addView(sleepSays);
                sleepSaid();
            }

            // Security: where the lock stands, and the way to it.
            body.addView(part("Security"));
            body.addView(tapRow(PhoneLock.locked(this)?"🔒 Encrypted with a password":"Not encrypted — lock with a password",this::security));

            body.addView(part("BACKUP"));
            body.addView(under("One file holding every collection, book, note and attachment on this phone."));
            LinearLayout both=new LinearLayout(this);
            both.setPadding(0,dp(8),0,dp(2));
            both.addView(pill("Export",()->pick(EXPORT)),new LinearLayout.LayoutParams(0,-2,1f));
            both.addView(gap(0),new LinearLayout.LayoutParams(dp(10),dp(1)));
            both.addView(pill("Import",()->pick(IMPORT)),new LinearLayout.LayoutParams(0,-2,1f));
            body.addView(both);

            final AlertDialog box=new Box().setTitle("Profile")
                .setView(scrolling(body)).create();
            // A box is its own window, and a window that was not opened expecting a keyboard will not
            // raise one however politely the field asks. It is told before the field is tapped.
            called.setOnClickListener(v->{
                android.view.Window window=box.getWindow();
                if(window!=null) {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
                    window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                        |WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
                }
                editInPlace(called,name->keepDeviceName(name));
            });
            box.show();
        },e->alert(READ_FAILED)));
    }

    /** One of a pair of things you can do, the same size as the other and sitting beside it. */
    private TextView pill(String words,Runnable does) {
        TextView one=label(words,READING,INK);
        one.setGravity(Gravity.CENTER);
        one.setPadding(dp(12),dp(12),dp(12),dp(12));
        one.setMinimumHeight(dp(48));
        GradientDrawable edge=new GradientDrawable();
        edge.setColor(0);edge.setCornerRadius(dp(10));
        edge.setStroke(Math.max(1,dp(1)),LINE);
        one.setBackground(edge);
        one.setOnClickListener(v->does.run());
        return one;
    }

    /** The quiet line under something, saying what it is. One indent for all of them, so a page lines up. */
    private TextView selectable(TextView words){words.setTextIsSelectable(true);return words;}

    private TextView under(String said) {
        TextView t=label(said,QUIET,MUTED);
        // Explanations are read, and sometimes passed on: they can be held and copied.
        t.setTextIsSelectable(true);
        t.setPadding(0,dp(2),0,dp(2));
        return t;
    }

    /** A heading over one part of a box, so three subjects in one place still read as three. */
    private TextView part(String said) {
        // The quiet way of writing, and ordinary letters. It was smaller again, in capitals, spaced out:
        // a fourth way of writing a word, for the one kind of word nobody needs to read first.
        String plain=said==null||said.isEmpty()?"":said.substring(0,1).toUpperCase(java.util.Locale.ROOT)
            +said.substring(1).toLowerCase(java.util.Locale.ROOT);
        TextView head=label(plain,QUIET,MUTED);
        head.setPadding(0,dp(18),0,dp(2));
        return head;
    }

    private synchronized Keys keys() {
        if(deviceKeys==null)deviceKeys=Keys.of(this);
        return deviceKeys;
    }

    /**
     * The other device's code, read off its screen. The camera is open only while this is showing and only
     * to look for a code: nothing is recorded, no frame is kept, and it closes the moment one is found.
     */
    private void scanning(final Consumer<String> said) { scanning(said,null); }

    /**
     * The camera, looking for a code.
     *
     * @param orPaste what to do instead for somebody who was sent a line in a message rather than shown a
     *                screen, or null where pasting is not offered. It sits on this screen because being
     *                asked which of the two you meant, before you can do either, is the thing to avoid.
     */
    private void scanning(final Consumer<String> said,final Runnable orPaste) {
        if(!Lens.allowed(this)){Lens.ask(this);waitingToScan=true;waitingFor=said;waitingPaste=orPaste;return;}
        // Square, taken from whatever width it is actually given rather than a number decided in advance:
        // asking for a fixed size got a box the dialog then squeezed sideways, and a window that is not the
        // shape it looks is a window you aim slightly wrong every time.
        TextureView looking=new TextureView(this) {
            @Override protected void onMeasure(int wide,int high){super.onMeasure(wide,wide);}
        };
        LinearLayout body=inside();
        LinearLayout.LayoutParams place=new LinearLayout.LayoutParams(-1,-2);
        place.gravity=Gravity.CENTER_HORIZONTAL;place.setMargins(0,dp(12),0,dp(4));
        body.addView(looking,place);
        AlertDialog.Builder asking=new Box().setTitle("Scan their code").setView(body);
        if(orPaste!=null)asking.setNeutralButton("Paste instead",(d,w)->orPaste.run());
        final AlertDialog showing=asking.create();
        final Lens[] lens={null};
        showing.setOnDismissListener(d->{if(lens[0]!=null)lens[0].close();});
        lens[0]=new Lens(this,looking,read->{
            if(lens[0]!=null)lens[0].close();
            showing.dismiss();
            said.accept(read);
        });
        looking.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture t,int w,int h){lens[0].open();}
            @Override public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture t,int w,int h){}
            @Override public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture t){lens[0].close();return true;}
            @Override public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture t){}
        });
        showing.show();
        if(looking.isAvailable())lens[0].open();
    }

    @Override public void onWindowFocusChanged(boolean has) {
        super.onWindowFocusChanged(has);
        if(lockedOut)return;
        if(!has||!wantKeyboard||page==null)return;
        wantKeyboard=false;
        writeOn();
    }

    @Override public void onRequestPermissionsResult(int asked,String[] permissions,int[] answers) {
        super.onRequestPermissionsResult(asked,permissions,answers);
        if(asked!=Lens.ASKING||!waitingToScan)return;
        waitingToScan=false;
        final Consumer<String> then=waitingFor;waitingFor=null;
        final Runnable orPaste=waitingPaste;waitingPaste=null;
        if(answers.length>0&&answers[0]==android.content.pm.PackageManager.PERMISSION_GRANTED&&then!=null)
            scanning(then,orPaste);
        // Refusing the camera is an answer, not a dead end: the line can still be pasted.
        else if(orPaste!=null)orPaste.run();
        else if(then!=null)alert("Without the camera, paste the line instead. Nothing was changed.");
    }



    /**
     * What this is, where it came from, and where to send something if you want to. One box rather than a
     * row apiece, because none of it is a thing you do — it is a thing you read once.
     */
    private void about() {
        LinearLayout body=inside();
        body.addView(selectable(label("Mininotes v"+version(),READING,INK)));
        final String newer=newerKnown();
        if(!newer.isEmpty()) {
            TextView out=tap("v"+newer+" is out","Update",READING,ACCENT,v->announce(newer));
            out.setPadding(0,0,0,0);out.setGravity(Gravity.START);body.addView(out);
        }
        body.addView(gap(6));
        body.addView(selectable(label("Free to use, change and pass on. Not to be sold, or put inside anything sold."
            ,READING,MUTED)));
        body.addView(gap(8));
        // What it does and does not protect, said where somebody would look for it rather than only in a
        // file on a website. Both halves matter: what is sent is sealed, and what is sitting here is not.
        body.addView(selectable(label("What you share is sealed end to end and carried over Maxima, the communication "
            +"layer of Minima, by this phone's own node — nobody in between can read it. "
            +(PhoneLock.locked(this)?"Notes on this phone, their files and the backups you export are encrypted with your password."
                :"Notes on this phone are not encrypted, and neither are backups; Security, in the menu, can lock them with a password."),READING,MUTED)));
        body.addView(gap(14));
        body.addView(label("Source",READING,MUTED));
        if(SOURCE.isEmpty())body.addView(label("A public repository is coming; this build is not published yet.",QUIET,INK));
        else {
            TextView link=tap(SOURCE,"Open the source",READING,ACCENT,v->openAddress(SOURCE));
            link.setPadding(0,0,0,0);link.setGravity(Gravity.START);body.addView(link);
        }
        body.addView(gap(14));
        body.addView(label("Donate",READING,MUTED));
        if(DONATE.isEmpty())body.addView(label("An address will go here.",READING,INK));
        else {
            TextView address=tap(DONATE,"Copy the donation address",READING,INK,v->{
                ClipboardManager board=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                if(board!=null)board.setPrimaryClip(ClipData.newPlainText("Minima address",DONATE));
                toast("Address copied");
            });
            address.setGravity(Gravity.START);address.setPadding(0,0,0,0);body.addView(address);
        }
        // The daily look, and the switch that stops it. Said in full where the switch is, because a notes
        // app that goes to the network on its own owes an account of exactly what for: one request for one
        // line of text, with nothing sent along with it.
        if(!SOURCE.isEmpty()) {
            body.addView(gap(14));
            body.addView(switchRow("Look for a newer version once a day",
                getSharedPreferences("settings",MODE_PRIVATE).getBoolean("update_look",true),on->{
                    final boolean kept=on;
                    background.submit(()->{getSharedPreferences("settings",MODE_PRIVATE).edit()
                        .putBoolean("update_look",kept).apply();return null;},done->{},e->{});
                }));
            body.addView(selectable(label("One line of text is read from the repository when the pad is opened. Nothing "
                +"is sent with it, and nothing is fetched until you tap Update: then the build is fetched "
                +"from the repository's release, checked against its checksum and its signing key, and "
                +"handed to Android, which asks before installing. Off, it looks only when you tap the "
                +"button below.",QUIET,MUTED)));
        }
        AlertDialog.Builder box=new Box().setTitle("About").setView(scrolling(body));
        if(!SOURCE.isEmpty())box.setPositiveButton("Check for a newer version",(d,w)->checkForUpdate());
        box.show();
    }

    private View gap(int high){View gap=new View(this);gap.setMinimumHeight(dp(high));return gap;}

    /**
     * Hands an address to whatever on the phone deals in that kind of address.
     *
     * <p>An email address is asked for by the action that means <i>write to this person</i>. Mail apps
     * listen for that one; several do not listen for the action a web address is opened with, so asking
     * the browser's way got "nothing on this phone can open that" out of a phone with mail on it. Both are
     * tried, in that order, and only an address nothing at all answers is reported back.
     */
    private void openAddress(String address) {
        Uri where=Uri.parse(address);
        boolean mail="mailto".equalsIgnoreCase(where.getScheme());
        if(mail&&started(new Intent(Intent.ACTION_SENDTO,where)))return;
        if(started(new Intent(Intent.ACTION_VIEW,where)))return;
        alert(mail?"No mail app on this phone offered to write to that address."
                  :"Nothing on this phone can open that address.");
    }

    /** Starts something, saying whether anything was there to start. */
    private boolean started(Intent going) {
        try{startActivity(going);return true;}
        catch(Exception e){return false;}
    }

    /**
     * The newest version's name, as the repository gives it: one line of text, read and reduced to a version
     * or to nothing. Nothing is sent with the request and nothing else is fetched. Runs off the interface
     * thread, for the look somebody tapped for and for the daily one alike.
     */
    private String publishedVersion() throws Exception {
        java.net.HttpURLConnection call=(java.net.HttpURLConnection)new java.net.URL(LATEST).openConnection();
        call.setConnectTimeout(8000);call.setReadTimeout(8000);call.setRequestProperty("Accept","text/plain");
        try(InputStream in=call.getInputStream()) {
            ByteArrayOutputStream held=new ByteArrayOutputStream();
            byte[] part=new byte[256];int n;
            while((n=in.read(part))!=-1&&held.size()<4096)held.write(part,0,n);
            return Update.read(new String(held.toByteArray(),StandardCharsets.UTF_8));
        } finally {call.disconnect();}
    }

    /** Whether a newer build has been published, asked by somebody who tapped for the answer. */
    private void checkForUpdate() {
        if(LATEST.isEmpty())return;
        final int job=busy("Looking for a newer version\u2026");
        lookout.submit(this::publishedVersion,newest->{
            busyDone(job,null);
            if(newest.isEmpty()){alert("The repository did not say which version is newest.");return;}
            looked(newest);
            if(Update.newer(newest,version()))announce(newest);
            else alert("This is the newest build: v"+version()+".");
        },e->{busyDone(job,null);alert("Could not reach the repository. Nothing was changed.");});
    }

    /**
     * The look nobody tapped for: once a day at most, when the pad is opened or come back to.
     *
     * <p>A person who never opens About would otherwise never learn that a fault they are living with was
     * fixed a month ago. So the pad looks, and what it does about the answer is small on purpose: one line
     * on the screen the first time a version is heard of, and a row in the menu for as long as it is
     * newer than this one. No box over the page - the pad opens on the page with the cursor in it, and
     * nothing gets between a person and that. Failing is silent, and is tried again at the next opening:
     * a phone with no connection is not something to report to somebody writing a note.
     *
     * <p>About has the switch that turns it off.
     */
    private void lookQuietly() {
        if(LATEST.isEmpty())return;
        final android.content.SharedPreferences kept=getSharedPreferences("settings",MODE_PRIVATE);
        if(!kept.getBoolean("update_look",true))return;
        if(!Update.due(System.currentTimeMillis(),kept.getLong("update_looked",0)))return;
        lookout.submit(this::publishedVersion,newest->{
            if(newest.isEmpty())return;
            final boolean heardBefore=newest.equals(kept.getString("update_told",""));
            looked(newest);
            if(!Update.newer(newest,version())||heardBefore)return;
            background.submit(()->{kept.edit().putString("update_told",newest).apply();return null;},done->{},e->{});
            toast("Mininotes v"+newest+" is out. It is in the menu.");
        },e->{});
    }

    /** Writes down what the repository said and when, so the menu can say it without asking again. */
    private void looked(final String newest) {
        final long when=System.currentTimeMillis();
        background.submit(()->{getSharedPreferences("settings",MODE_PRIVATE).edit()
            .putString("update_latest",newest).putLong("update_looked",when).apply();return null;},done->versionShown(),e->{});
    }

    /** The line under the app's name on its first screen, while that screen is up. */
    private TextView versionLine;

    /**
     * The version under the app's name: quiet on its own; in the accent colour, and a tap from the update,
     * while a newer build is known. Tapped otherwise, it looks now.
     */
    private void versionShown() {
        if(versionLine==null)return;
        final String newer=newerKnown();
        if(newer.isEmpty()) {
            versionLine.setText("v"+version());versionLine.setTextColor(MUTED);
            versionLine.setContentDescription("Mininotes v"+version()+". Tap to look for a newer version.");
            versionLine.setOnClickListener(v->checkForUpdate());
        } else {
            versionLine.setText("v"+version()+"  ·  Update to v"+newer);versionLine.setTextColor(ACCENT);
            versionLine.setTypeface(null,android.graphics.Typeface.BOLD);
            versionLine.setContentDescription("Mininotes v"+newer+" is out. Tap to update.");
            versionLine.setOnClickListener(v->announce(newer));
        }
    }

    /** The newer build this phone has heard of, or nothing. Asked of what was written down, not of the network. */
    private String newerKnown() {
        String said=getSharedPreferences("settings",MODE_PRIVATE).getString("update_latest","");
        return Update.newer(said,version())?Update.read(said):"";
    }

    /**
     * Says there is a newer build, and offers to bring it here.
     *
     * <p>It used to send the person to the release page and stop: find the file, download it, open it,
     * answer Android. Now the pad does the fetching and the checking, and Android does the asking - the
     * one step that is rightly not the app's to skip. Before anything is handed over: the file matches the
     * checksum published beside it, it is this app and a later build of it, and it is signed with the key
     * this build was signed with - which Android insists on anyway, and which is better said in words than
     * as an error from the installer.
     */
    private void announce(final String newest) {
        new Box().setTitle("v"+newest+" is out")
            .setMessage("This phone has v"+version()+".\n\nUpdate fetches the build from the repository, "
                +"checks it, and hands it to Android, which asks before installing.")
            .setNegativeButton("Not now",(d,w)->{})
            .setPositiveButton("Update",(d,w)->fetchUpdate(newest)).show();
    }

    /** Where a fetched build waits to be handed over. Emptied at every opening: nothing is kept here. */
    private File updates(){return new File(getCacheDir(),"update");}

    /** More than any build of this will be. A file bigger than this is not the build, whatever it is. */
    private static final long BUILD_MOST=64L*1024*1024;

    private void fetchUpdate(final String newest) {
        if(SOURCE.isEmpty())return;
        final int job=busy("Fetching v"+newest+"…");
        lookout.submit(()->{
            File dir=updates();
            if(!dir.isDirectory()&&!dir.mkdirs())throw new IllegalStateException("Nowhere on this phone to put the file.");
            final File apk=new File(dir,"Mininotes-"+newest+".apk");
            String digest,got;
            try {
                // The checksum first: it is small, and a release without one is not one to fetch from.
                digest=Update.digest(fetchText(Update.asset(SOURCE,newest)+".sha256"));
                if(digest.isEmpty())throw new IllegalStateException("The release carries no readable checksum, so the file could not be checked. Nothing was installed.");
                got=fetchFile(Update.asset(SOURCE,newest),apk,job,newest);
            } catch(java.io.IOException notNow) {
                apk.delete();
                throw new IllegalStateException("Could not reach the repository. Nothing was changed.");
            }
            if(!got.equals(digest)){apk.delete();throw new IllegalStateException("The file did not match the checksum published beside it. Nothing was installed.");}
            busySay(job,"Checking v"+newest+"…");
            android.content.pm.PackageManager packages=getPackageManager();
            @SuppressWarnings("deprecation")
            android.content.pm.PackageInfo theirs=packages.getPackageArchiveInfo(apk.getPath(),
                android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES);
            if(theirs==null||!getPackageName().equals(theirs.packageName)){apk.delete();throw new IllegalStateException("The file is not this app. Nothing was installed.");}
            android.content.pm.PackageInfo mine=packages.getPackageInfo(getPackageName(),
                android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES);
            if(theirs.getLongVersionCode()<=mine.getLongVersionCode()){apk.delete();throw new IllegalStateException("The published build is not later than this one. Nothing was installed.");}
            // Android will refuse a build signed with another key, and rightly; said here in words instead.
            String theirKey=signerOf(theirs), myKey=signerOf(mine);
            if(!theirKey.isEmpty()&&!myKey.isEmpty()&&!theirKey.equals(myKey)) {
                apk.delete();
                throw new IllegalStateException("The published build is signed with a different key from this one, "
                    +"so Android will not install it over this build. This is a development build: the published "
                    +"one has to be installed on its own, after a backup.");
            }
            busySay(job,"Handing it to Android…");
            install(apk,newest);
            return null;
        },done->busyDone(job,"Android asks you to confirm"),e->{
            busyDone(job,null);
            new Box().setTitle("Could not update")
                .setMessage(e.getMessage()==null?"Something went wrong fetching it. Nothing was changed.":e.getMessage())
                .setNegativeButton("Not now",(d,w)->{})
                .setPositiveButton("Open the download page",(d,w)->openAddress(DOWNLOAD)).show();
        });
    }

    /** A small file of text from the repository, or an exception. Nothing is sent with the request. */
    private String fetchText(String address) throws java.io.IOException {
        java.net.HttpURLConnection call=(java.net.HttpURLConnection)new java.net.URL(address).openConnection();
        call.setConnectTimeout(8000);call.setReadTimeout(8000);call.setRequestProperty("Accept","text/plain");
        try(InputStream in=call.getInputStream()) {
            ByteArrayOutputStream held=new ByteArrayOutputStream();
            byte[] part=new byte[256];int n;
            while((n=in.read(part))!=-1&&held.size()<4096)held.write(part,0,n);
            return new String(held.toByteArray(),StandardCharsets.UTF_8);
        } finally {call.disconnect();}
    }

    /**
     * The build itself, to a file, saying how far it has got as it goes.
     *
     * @return the SHA-256 of what was written, as hex, worked out as the bytes went by
     */
    private String fetchFile(String address,File into,int job,String newest) throws java.io.IOException {
        java.net.HttpURLConnection call=(java.net.HttpURLConnection)new java.net.URL(address).openConnection();
        call.setConnectTimeout(15000);call.setReadTimeout(30000);
        try {
            java.security.MessageDigest sum=java.security.MessageDigest.getInstance("SHA-256");
            long whole=call.getContentLengthLong(), sofar=0, said=-1;
            try(InputStream in=new BufferedInputStream(call.getInputStream());OutputStream out=new FileOutputStream(into)) {
                byte[] part=new byte[65536];int n;
                while((n=in.read(part))!=-1) {
                    sofar+=n;
                    if(sofar>BUILD_MOST)throw new IllegalStateException("The file is far larger than a build of this. Nothing was installed.");
                    out.write(part,0,n);sum.update(part,0,n);
                    long pct=whole>0?sofar*100/whole:-1;
                    if(pct!=said&&pct%5==0){said=pct;busySay(job,"Fetching v"+newest+" · "+pct+"%");}
                }
            }
            return Update.hex(sum.digest());
        } catch(java.security.NoSuchAlgorithmException never) {
            throw new IllegalStateException("This phone cannot work out a checksum.");
        } finally {call.disconnect();}
    }

    /** The SHA-256 of the certificate a build is signed with, as hex, or empty where it cannot be read. */
    private static String signerOf(android.content.pm.PackageInfo info) {
        try {
            android.content.pm.SigningInfo signing=info==null?null:info.signingInfo;
            if(signing==null)return "";
            android.content.pm.Signature[] all=signing.getApkContentsSigners();
            if(all==null||all.length==0)return "";
            return Update.hex(java.security.MessageDigest.getInstance("SHA-256").digest(all[0].toByteArray()));
        } catch(Exception unreadable){return "";}
    }

    /**
     * Handed to Android's installer, which asks the person and answers to {@link Installing}. The version
     * being installed is written down first, so the new build can say it has arrived when it opens.
     */
    private void install(File apk,String newest) throws Exception {
        android.content.pm.PackageInstaller installer=getPackageManager().getPackageInstaller();
        android.content.pm.PackageInstaller.SessionParams params=new android.content.pm.PackageInstaller.SessionParams(
            android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(getPackageName());
        params.setSize(apk.length());
        int id=installer.createSession(params);
        try(android.content.pm.PackageInstaller.Session session=installer.openSession(id)) {
            try(OutputStream out=session.openWrite(apk.getName(),0,apk.length());
                InputStream in=new java.io.FileInputStream(apk)) {
                byte[] part=new byte[65536];int n;
                while((n=in.read(part))!=-1)out.write(part,0,n);
                session.fsync(out);
            }
            getSharedPreferences("settings",MODE_PRIVATE).edit().putString("update_installing",newest).apply();
            Intent told=new Intent(this,Installing.class).setAction(Installing.STATUS);
            // Mutable, because the installer writes its answer into it; explicit, so nobody else can.
            int flags=android.app.PendingIntent.FLAG_UPDATE_CURRENT
                |(android.os.Build.VERSION.SDK_INT>=31?android.app.PendingIntent.FLAG_MUTABLE:0);
            session.commit(android.app.PendingIntent.getBroadcast(this,0,told,flags).getIntentSender());
        }
    }

    /**
     * After an update: the build that was handed to Android is the one running now, so it is said once.
     * And whatever was fetched is cleared away, whether or not it was installed.
     */
    private void afterUpdate() {
        final android.content.SharedPreferences kept=getSharedPreferences("settings",MODE_PRIVATE);
        String asked=kept.getString("update_installing","");
        if(!asked.isEmpty()) {
            if(asked.equals(version()))toast("Updated to v"+asked);
            kept.edit().remove("update_installing").apply();
        }
        chores.submit(()->{File[] left=updates().listFiles();if(left!=null)for(File one:left)one.delete();return null;},
            done->{},e->{});
    }

    /**
     * Which build this is, read from the package rather than written in the source twice. It goes up with
     * every build that leaves here, so a screen can be matched to the thing that drew it.
     */
    private String version() {
        try{return getPackageManager().getPackageInfo(getPackageName(),0).versionName;}
        catch(Exception e){return "unknown";}
    }

    /** What the app can say about itself: the version, the phone, the Android on it. Nothing about you. */
    private String aboutThisPhone() {
        return "Mininotes "+version()+" \u00b7 Android "+android.os.Build.VERSION.RELEASE
            +" \u00b7 "+android.os.Build.MODEL;
    }

    /** What was picked last time the box was open, so a second thought does not start from the top. */
    private Feedback.Kind saidKind=Feedback.Kind.BROKEN;
    private Feedback.Area saidArea=Feedback.Area.ELSE;
    private String saidWords="", saidMinima="";

    /**
     * Somewhere to say what you think, and somewhere to read what everybody else thought.
     *
     * <p>It ends up in the repository's issues, where it is public, answerable and countable. The app
     * cannot post it: that would need a key, and a key inside an app anybody can download is a key anybody
     * has - so the form is filled in here and handed to the browser, and the person who wrote it is the
     * person who posts it, under their own name.
     *
     * <p>Which means it is public the moment it is sent, and that is said here in as many words before
     * anything is typed rather than in small print underneath it.
     */
    private void feedback() {
        final LinearLayout body=inside();

        body.addView(part("WHAT SORT"));
        final LinearLayout kinds=new LinearLayout(this);
        kinds.setOrientation(LinearLayout.VERTICAL);
        body.addView(kinds);

        body.addView(part("WHICH PART"));
        final LinearLayout areas=new LinearLayout(this);
        areas.setOrientation(LinearLayout.VERTICAL);
        body.addView(areas);

        body.addView(part("WHAT YOU WANT TO SAY"));
        final EditText words=field("What happened, or what it should do",Feedback.SAID_MOST);
        words.setSingleLine(false);
        words.setMinLines(4);
        words.setGravity(Gravity.TOP|Gravity.START);
        words.setInputType(android.text.InputType.TYPE_CLASS_TEXT
            |android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        words.setText(saidWords);
        body.addView(words);
        // Public, and what not to put in it. Three facts, said once. The long version of all this is in
        // the form the browser opens, where there is room for it and where it is read at leisure.
        body.addView(under("Public. No names, no addresses, nothing off a note."));

        body.addView(part("SENT WITH IT"));
        body.addView(label(aboutThisPhone(),QUIET,INK));

        body.addView(part("MINIMA ADDRESS"));
        final EditText paid=field("Mx\u2026",Feedback.MINIMA_MOST);
        paid.setText(saidMinima);
        body.addView(paid);
        body.addView(under("Optional. Nothing is promised \u2014 it is a list, in case there is ever a fund."));

        final TextView[] kindRows=new TextView[Feedback.Kind.values().length];
        final TextView[] areaRows=new TextView[Feedback.Area.values().length];
        final Runnable mark=()->{
            for(int i=0;i<kindRows.length;i++)chosen(kindRows[i],Feedback.Kind.values()[i]==saidKind);
            for(int i=0;i<areaRows.length;i++)chosen(areaRows[i],Feedback.Area.values()[i]==saidArea);
        };
        for(int i=0;i<kindRows.length;i++) {
            final Feedback.Kind kind=Feedback.Kind.values()[i];
            kindRows[i]=picked(kind.said,()->{saidKind=kind;mark.run();});
        }
        for(int i=0;i<areaRows.length;i++) {
            final Feedback.Area area=Feedback.Area.values()[i];
            areaRows[i]=picked(area.said,()->{saidArea=area;mark.run();});
        }
        twoAcross(kinds,kindRows);
        twoAcross(areas,areaRows);
        mark.run();

        body.addView(part("SEND IT"));
        final LinearLayout both=new LinearLayout(this);
        both.setOrientation(LinearLayout.HORIZONTAL);
        // Kept, either way: a box closed by accident with a paragraph in it has to give the paragraph back.
        final Runnable hold=()->{saidWords=words.getText().toString();saidMinima=paid.getText().toString().trim();};
        both.addView(pill("Post it",()->{
            hold.run();
            if(saidWords.trim().isEmpty()){alert("There is nothing written to send yet.");return;}
            String going=Feedback.url(SOURCE,saidKind,saidArea,saidWords,saidMinima,aboutThisPhone());
            if(going.isEmpty()) {
                copy(Feedback.plain(saidKind,saidArea,saidWords,saidMinima,aboutThisPhone()));
                alert("Not published yet. It is on the clipboard instead.");
                return;
            }
            // Handed over filled in. What the browser shows is the form, not the sending of it: it is read
            // over and posted by the person who wrote it, which is also what puts their name on it.
            openAddress(going);
        }),new LinearLayout.LayoutParams(0,-2,1f));
        both.addView(gap(0),new LinearLayout.LayoutParams(dp(10),1));
        both.addView(pill("Copy it",()->{
            hold.run();
            if(saidWords.trim().isEmpty()){alert("There is nothing written to copy yet.");return;}
            copy(Feedback.plain(saidKind,saidArea,saidWords,saidMinima,aboutThisPhone()));
            toast("Copied");
        }),new LinearLayout.LayoutParams(0,-2,1f));
        body.addView(both);

        final String log=Feedback.log(SOURCE);
        if(!log.isEmpty()) {
            body.addView(part("EVERYBODY ELSE"));
            body.addView(tapRow("Read the whole lot",()->openAddress(log)));
        }

        final AlertDialog box=new Box().setTitle("Feedback").setView(scrolling(body)).create();
        // A box will not raise a keyboard it was not opened expecting, and this one is mostly typing.
        //
        // It is moved out of the keyboard's way rather than made smaller to fit above it. A box told to
        // resize is made short when the keyboard arrives and is not reliably given the height back when it
        // leaves: it stays short, and the end of the form - which here is the part that sends it - cannot
        // be scrolled to at all. Nothing inside needs resizing anyway, because the whole box already
        // scrolls.
        android.view.Window window=box.getWindow();
        if(window!=null) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
                |WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
        }
        box.setOnDismissListener(d->hold.run());
        box.show();
    }

    /** One of a short list to pick from. Still a sentence, not a chip: the whole phrase, never an icon. */
    private TextView picked(String words,Runnable pick) {
        TextView one=label(words,READING,MUTED);
        one.setPadding(dp(12),dp(10),dp(12),dp(10));
        one.setMinimumHeight(dp(56));
        one.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);
        one.setMaxLines(2);
        one.setOnClickListener(v->pick.run());
        return one;
    }

    /**
     * A short list laid out two across.
     *
     * <p>Twelve things to pick from, one under another, is two screens of scrolling before you reach the
     * box you came here to type in - and a form whose first screen holds no way to write on it reads as a
     * form that is going to take a while. Two across halves it. They are still whole phrases, and every
     * cell is the same height whether its phrase took one line or two, because a row of boxes that jog up
     * and down is harder to read than a longer list would have been.
     */
    private void twoAcross(LinearLayout into,TextView[] all) {
        for(int at=0;at<all.length;at+=2) {
            LinearLayout row=new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for(int side=0;side<2;side++) {
                LinearLayout.LayoutParams place=new LinearLayout.LayoutParams(0,-1,1f);
                place.setMargins(side==0?0:dp(5),dp(3),side==0?dp(5):0,dp(3));
                if(at+side<all.length)row.addView(all[at+side],place);
                else row.addView(gap(0),place);
            }
            into.addView(row);
        }
    }

    /**
     * The one of them that is picked, said with an edge round it rather than with a word beside it.
     *
     * <p>Only the colours change here. Where the cell sits and how big it is was settled when it was laid
     * out, and setting that again from in here would undo the row it was put in.
     */
    private void chosen(TextView one,boolean on) {
        one.setTextColor(on?INK:MUTED);
        one.setTypeface(null,on?android.graphics.Typeface.BOLD:android.graphics.Typeface.NORMAL);
        GradientDrawable edge=new GradientDrawable();
        edge.setColor(on?CARD:0);
        edge.setCornerRadius(dp(10));
        edge.setStroke(Math.max(1,dp(1)),on?ACCENT:LINE);
        one.setBackground(edge);
    }

    /** Onto the clipboard, wherever it came from. */
    private void copy(String said) {
        ClipboardManager board=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        if(board!=null)board.setPrimaryClip(ClipData.newPlainText("Mininotes",said));
    }



    private void forgetAddress(NoteStore.Contact contact) {
        new Box().setTitle("Forget this address?").setMessage(contact.name+"\n\n"+contact.address
            +"\n\nAnything already shared with it stays shared; this only removes it from the list you pick from.")
            .setPositiveButton("Forget",(d,w)->background.submit(()->{store.removeAddress(contact.address);
                    // With nobody left to hear from there is nothing to stay up for.
                    Listening.settle(this);return null;},
                done->{toast("Forgotten");addressBook();},e->alert("Could not change that."))).show();
    }

    // ---- who each element is shared with -----------------------------------------------------------------

    /**
     * Who receives this thing: every address you know, each either reaching it or not, and tapping one is
     * what changes that. One list rather than a list of rules with a way in to another list — because the
     * question a person has is "does Ana get this?", and the answer is in front of them either way.
     *
     * <p>An address can also reach this thing through something above it: a rule on the collection reaches
     * every book in it. That is said where it happens, and it cannot be undone from in here — it is undone
     * where it was made, which is the only place it means anything.
     */
    /**
     * Sharing something is handing somebody the way to reach this pad, so that is what this box is: the
     * code, and a way to copy it. Who already has what is a list, and a list is not what you are holding a
     * phone up for - it lives on its own row in the menu.
     */
    private void shareSheet(final Sharing.Scope scope,final String target,final String name) {
        if(scope==null)return;
        final String offer=Sharing.travelling(scope,name);
        withAddress(()->drawShareCode(scope,target,name,offer));
    }

    private void drawShareCode(final Sharing.Scope scope,final String target,final String name,
                               final String offer) {
        background.submit(()->{
            String said=getSharedPreferences("settings",MODE_PRIVATE).getString("address","");
            // Remembered with the time it went up: whoever scans it in the next quarter of an hour is
            // given it without this phone asking again - showing the code was the asking.
            getSharedPreferences("offers",MODE_PRIVATE).edit().putString(scope.name()+":"+target,
                System.currentTimeMillis()+":"+offerWrites).apply();
            return keys().line(deviceName(),said,offer,offerWrites,scope.name(),target);
        },line->{
            LinearLayout body=inside();
            body.addView(codeView(line));
            // What this phone is doing while the code is up, which is waiting - and it says when it stops.
            TextView waiting=under("Waiting for them to scan it\u2026");
            waiting.setGravity(Gravity.CENTER);waiting.setPadding(0,dp(10),0,0);
            body.addView(waiting);
            // The one thing to decide, as the two things it can be. Choosing redraws the code, because the
            // code is what carries it.
            LinearLayout choice=new LinearLayout(this);
            choice.setGravity(Gravity.CENTER);choice.setPadding(0,dp(14),0,dp(2));
            choice.addView(levelPick("Read only",!offerWrites,()->{offerWrites=false;shareSheet(scope,target,name);}));
            choice.addView(levelPick("Read & write",offerWrites,()->{offerWrites=true;shareSheet(scope,target,name);}));
            body.addView(choice);
            new Box().setTitle("Share "+Sharing.shortly(scope,name)).setView(scrolling(body))
                .setNeutralButton("Add someone",(d,w)->typeAddress(scope,target,name))
                .setPositiveButton("Copy",(d,w)->{
                    ClipboardManager board=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                    if(board!=null)board.setPrimaryClip(ClipData.newPlainText("Mininotes",line));
                    toast("Code copied");
                }).show();
        },e->alert("Could not prepare this device's keys. Nothing was changed."));
    }

    /**
     * What the next code offered will let them do.
     *
     * <p>Read and write, until somebody says otherwise. Sharing something with another device of yours is
     * what this is mostly for, and a copy you cannot write in is not the same notebook in two places - it
     * is a photograph of one. The narrower of the two is still a tap away on the code itself.
     */
    private boolean offerWrites=true;

    /** One of the two things a code can offer, with the one it is offering marked. */
    private TextView levelPick(String words,boolean on,Runnable pick) {
        TextView one=label(words,QUIET,on?INK:MUTED);
        one.setGravity(Gravity.CENTER);
        one.setPadding(dp(18),dp(10),dp(18),dp(10));
        one.setMinimumHeight(dp(44));
        one.setTypeface(null,on?android.graphics.Typeface.BOLD:android.graphics.Typeface.NORMAL);
        GradientDrawable edge=new GradientDrawable();
        edge.setColor(on?CARD:0);edge.setCornerRadius(dp(10));
        edge.setStroke(Math.max(1,dp(on?2:1)),on?ACCENT:LINE);
        one.setBackground(edge);
        LinearLayout.LayoutParams place=new LinearLayout.LayoutParams(-2,-2);
        place.setMargins(dp(5),0,dp(5),0);
        one.setLayoutParams(place);
        one.setContentDescription(words+(on?", chosen":""));
        one.setOnClickListener(v->{if(!on)pick.run();});
        return one;
    }

    /** The code itself, drawn from its own size and blown up to the room the box turns out to have. */
    private View codeView(String line) {
        try {
            ImageView code=new ImageView(this);
            // Dressed as a link, so that a phone's own camera offers to open it here. See Pairing.LINK.
            android.graphics.drawable.BitmapDrawable drawn=
                new android.graphics.drawable.BitmapDrawable(getResources(),Qr.of(Pairing.link(line),1));
            drawn.setFilterBitmap(false);
            code.setImageDrawable(drawn);
            code.setAdjustViewBounds(true);
            code.setScaleType(ImageView.ScaleType.FIT_CENTER);
            code.setContentDescription("This device's code");
            return code;
        } catch(Exception e) {
            return label("The code could not be drawn. Copy the line instead.",READING,WARN);
        }
    }

    /**
     * The other direction: something another device shares with you, who shares it, and what you may do.
     *
     * <p>And a way out. Unsubscribing cannot stop them sending - only they can decide that, and no phone
     * gets a say over another - so what it stops is this phone taking it in. That distinction is said in
     * the box rather than left for somebody to work out from what does not happen.
     */
    private void sharedWithMe(final NoteStore.Branch branch) {
        // One box, whoever the thing belongs to. There were two, laid out differently and saying different
        // things, and which one opened depended on a fact about the thing that nobody looking at it knew.
        if(branch.scope()!=null)sharedWith(branch.scope(),branch.id,branch.name);
    }

    private void sharedWith(final Sharing.Scope scope,final String target,final String name) {
        if(scope==null)return;
        final NoteStore.Branch.Kind kind=kindOf(scope);
        background.submit(()->{
            List<NoteStore.Contact> known=store.addresses();
            List<Sharing.Rule> here=store.sharesOn(scope,target);
            Map<String,Boolean> reaching=store.reaches(scope,target);
            // How much each of them has not been given yet. The list is about what somebody has, and
            // "has it, and two notes of it have not left this phone" is a different thing from "has it".
            Map<String,Integer> waiting=new LinkedHashMap<>();
            for(Outbox.Wait wait:store.owed(kind,target))
                waiting.merge(wait.address,1,Integer::sum);
            Map<String,String> through=new LinkedHashMap<>();
            for(String address:reaching.keySet()) {
                String by=store.grantedBy(scope,target,address);
                if(!by.isEmpty())through.put(address,by);
            }
            // Whose it is. Everything else in the box is the same either way; only who the owner is, and
            // whether the way out is "remove them" or "leave", depends on it.
            String origin=store.cameFrom(kind,target);
            // Stopped on the thing itself, or on the book or the collection that holds it.
            boolean left=!origin.isEmpty()&&store.pausedHere(kind,target);
            Sharing.Level mayDo=origin.isEmpty()?null:store.myLevel(scope,target);
            // Where it was given, which is where it is left: this, or the book or collection it came in.
            Object[] given=origin.isEmpty()?null:store.givenOn(scope,target,name);
            return new Object[]{known,here,reaching,waiting,through,origin,left,mayDo,
                store.pauseFor(kind,target),given};
        },found->drawShare(scope,target,name,found),e->alert(READ_FAILED));
    }

    /** The same four levels, said the way the notebook says them. */
    private static NoteStore.Branch.Kind kindOf(Sharing.Scope scope) {
        switch(scope) {
            case COLLECTION: return NoteStore.Branch.Kind.COLLECTION;
            case BOOK: return NoteStore.Branch.Kind.BOOK;
            case PAGE: return NoteStore.Branch.Kind.PAGE;
            default: return NoteStore.Branch.Kind.LIBRARY;
        }
    }

    /** What this box is about, named on the box rather than left to whatever screen is behind it. */
    private static String subject(Sharing.Scope scope,String name) {
        switch(scope) {
            case COLLECTION: return name+" collection";
            case BOOK: return name+" book";
            case PAGE: return name;
            default: return "Everything on this phone";
        }
    }

    /**
     * Everything about who else has a thing, in one box, the same whatever the thing is.
     *
     * <p>Made to be used without being read. At the top, the same mark that opened it and two or three
     * words saying what it means. Under that, one button, and it is always the next thing to do: Share for
     * a thing that is only here, Resume for one this phone has stopped taking in, Sync now for anything
     * else. Then the people, a name and what they may do. Then the few things that can be set, each a line
     * with a switch or an arrow-head. Nothing is explained, because a box that needs explaining has
     * already failed; the first version of this one put the only thing that mattered - that this phone had
     * stopped receiving - in a sentence, and the way to put it right in green at the bottom, under a
     * paragraph, below a big button that did something else.
     */
    @SuppressWarnings("unchecked")
    private void drawShare(final Sharing.Scope scope,final String target,final String name,Object[] found) {
        final List<NoteStore.Contact> known=(List<NoteStore.Contact>)found[0];
        final List<Sharing.Rule> here=(List<Sharing.Rule>)found[1];
        final Map<String,Boolean> reaching=(Map<String,Boolean>)found[2];
        final Map<String,Sharing.Rule> mine=new LinkedHashMap<>();
        for(Sharing.Rule rule:here)mine.put(rule.address,rule);
        final Map<String,Integer> waiting=(Map<String,Integer>)found[3];
        final Map<String,String> through=(Map<String,String>)found[4];
        final String origin=(String)found[5];
        final boolean left=(Boolean)found[6];
        final Sharing.Level mayDo=(Sharing.Level)found[7];
        final Object[] given=(Object[])found[9];
        // An admin of somebody else's thing may do with its people what its owner may.
        final boolean admin=mayDo!=null&&mayDo.shares();
        final int pause=(Integer)found[8];
        final boolean theirs=!origin.isEmpty();
        final NoteStore.Branch.Kind kind=kindOf(scope);

        final Map<String,String> called=new HashMap<>();
        for(NoteStore.Contact contact:known)called.put(contact.address,contact.name);
        final List<NoteStore.Contact> has=new ArrayList<>(), hasNot=new ArrayList<>();
        for(NoteStore.Contact contact:known)
            (mine.containsKey(contact.address)||reaching.containsKey(contact.address)?has:hasNot).add(contact);
        int owed=0;
        for(int each:waiting.values())owed+=each;
        final boolean shared=theirs||!has.isEmpty();
        final String owner=theirs?(called.get(origin)==null?"Another device":called.get(origin)):"";

        LinearLayout body=inside();

        // The mark, and what it means.
        final Mark.What which=!shared?Mark.What.HERE:left?Mark.What.PAUSED
            :owed>0?Mark.What.WAITING:Mark.What.GONE;
        LinearLayout stands=new LinearLayout(this);
        stands.setGravity(Gravity.CENTER_VERTICAL);
        stands.setPadding(0,dp(4),0,dp(2));
        Mark.PAPER_HOLE=CARD;
        Mark drawn=new Mark(which,which==Mark.What.WAITING||which==Mark.What.PAUSED?ACCENT:INK);
        int side=Math.round(READING*reading()*1.5f*getResources().getDisplayMetrics().scaledDensity);
        drawn.sized(side);
        ImageView ring=new ImageView(this);ring.setImageDrawable(drawn);
        stands.addView(ring,new LinearLayout.LayoutParams(side,side));
        TextView means=label(which==Mark.What.HERE?"Only on this phone"
            :which==Mark.What.PAUSED?"Paused \u00b7 not receiving"
            :which==Mark.What.WAITING?"Waiting to send":"Up to date",READING,INK);
        means.setPadding(dp(12),0,0,0);
        stands.addView(means);
        body.addView(stands);

        final Runnable resume=()->background.submit(()->{
                store.resume(kind,target);return null;
            },done->{
                if(shareBox!=null&&shareBox.isShowing())shareBox.dismiss();
                refresh();refreshOwed();
                // Taken in again, and at once: everybody who has it is asked for what this phone missed.
                syncNow(kind,target,name);
            },e->alert("Could not change that."));

        // The one thing to do.
        if(which==Mark.What.HERE)body.addView(primary("Share",()->addSomeone(scope,target,name,hasNot)));
        else if(which==Mark.What.PAUSED)body.addView(primary("Resume",resume));
        else body.addView(primary("Sync now",()->{
                if(shareBox!=null&&shareBox.isShowing())shareBox.dismiss();
                syncNow(kind,target,name);
            }));

        if(shared) {
            // Who, and then how: two subjects, so two headings. It was one run of lines that all looked
            // alike, in which a person, "Add someone" and "Pause receiving" were the same kind of thing to
            // the eye - and only one of the three is a person. Somebody wears the round initial a phone
            // gives a person; something to do among them wears a plus in an empty ring; and what is on or
            // off is a switch, because an arrow-head promises a box and there is none behind it.
            final String me=store.myName==null||store.myName.trim().isEmpty()?"This phone"
                :store.myName.trim()+" (you)";
            body.addView(part("Who has access"));
            if(theirs) {
                body.addView(person(row(owner,"Owner",null),owner));
                body.addView(person(row(me,mayDo==null?"":mayDo.words(),null),me));
                for(final Sharing.Rule rule:here) {
                    if(rule.address.equals(origin)||rule.level==Sharing.Level.GONE)continue;
                    String who=called.get(rule.address)==null?"Another device":called.get(rule.address);
                    NoteStore.Contact them=null;
                    for(NoteStore.Contact one:known)if(one.address.equals(rule.address))them=one;
                    if(admin&&them!=null)body.addView(person(roleRow(scope,target,name,them,rule,0),who));
                    else body.addView(person(row(who,rule.level.words(),null),who));
                }
                if(admin)body.addView(toDo("Add someone","+",()->addSomeone(scope,target,name,hasNot)));
            } else {
                body.addView(person(row(me,"Owner",null),me));
                for(final NoteStore.Contact contact:has) {
                    final Sharing.Rule rule=mine.get(contact.address);
                    final int behind=waiting.containsKey(contact.address)?waiting.get(contact.address):0;
                    // Through something that holds this, it is changed where it was given.
                    if(rule==null)body.addView(person(row(contact.name,
                        Boolean.TRUE.equals(reaching.get(contact.address))?"Can write":"Can read",null),contact.name));
                    else body.addView(person(roleRow(scope,target,name,contact,rule,behind),contact.name));
                }
                body.addView(toDo("Add someone","+",()->addSomeone(scope,target,name,hasNot)));
            }

            body.addView(part("Syncing"));
            body.addView(switchRow("Sync automatically",pause>0,
                on->setPause(scope,target,name,on?NoteStore.USUALLY:NoteStore.WHEN_ASKED)));
            if(pause>0) {
                final List<String> waits=new ArrayList<>();
                final List<Integer> seconds=new ArrayList<>();
                for(int wait:WAITS)if(wait>0){waits.add(waitSaid(wait));seconds.add(wait);}
                body.addView(dropRow("Delay",waits,null,seconds.indexOf(pause),waitSaid(pause),
                    picked->setPause(scope,target,name,seconds.get(picked))));
            }
            if(theirs)body.addView(switchRow("Pause receiving",left,on->{
                if(!on){resume.run();return;}
                background.submit(()->{store.refuse(origin,target,kind);return null;},
                    done->sharedWith(scope,target,name),e->alert("Could not change that."));
            }));
            // Everybody but the owner can go, and the way out is the last thing in the box: under stopping
            // for now, which is the smaller version of the same wish. Where it was given, which may be the
            // book it came in. A plain line like the ones above it - it was among the people, wearing a
            // ring to line up with them, and the way to leave is not one of the people.
            if(theirs&&given!=null)body.addView(row("Unfollow","",
                ()->askUnfollow((Sharing.Scope)given[0],(String)given[1],(String)given[2])));
        }

        // Changing what somebody may do redraws this box, and redrawing it used to mean opening another
        // one on top of the last. Tap three times and there are three, each showing the state as it was
        // when it opened - so backing out revealed a stale one, and the screen said two devices had
        // something the notebook had given to one. The data was never wrong; the screen was lying.
        AlertDialog box=new Box().setTitle(subject(scope,name)).setView(scrolling(body))
            .setOnDismissListener(d->{refresh();refreshOwed();}).create();
        if(shareBox!=null&&shareBox.isShowing())shareBox.dismiss();
        shareBox=box;boxScope=scope;boxTarget=target;boxName=name;
        box.show();
    }

    /** Who to add: the devices this phone already knows, and the two ways of meeting one it does not. */
    private void addSomeone(final Sharing.Scope scope,final String target,final String name,
                            final List<NoteStore.Contact> hasNot) {
        LinearLayout body=inside();
        final AlertDialog[] box={null};
        for(final NoteStore.Contact contact:hasNot)
            body.addView(row(contact.name,"",()->{
                if(box[0]!=null)box[0].dismiss();
                setLevel(scope,target,name,contact,Sharing.Level.WRITE);
            }));
        body.addView(row("Show them my code","",()->{if(box[0]!=null)box[0].dismiss();shareSheet(scope,target,name);}));
        body.addView(row("Scan their code","",()->{if(box[0]!=null)box[0].dismiss();typeAddress(scope,target,name);}));
        box[0]=new Box().setTitle("Share with").setView(scrolling(body)).create();
        if(shareBox!=null&&shareBox.isShowing())shareBox.dismiss();
        box[0].show();
    }

    /** Somebody, rather than something to do: the line begins with the round initial a phone gives a person. */
    private View person(View line,String name) {
        String called=name==null?"":name.trim();
        String first=called.isEmpty()?"?":new String(Character.toChars(called.codePointAt(0)))
            .toUpperCase(java.util.Locale.ROOT);
        ((LinearLayout)line).addView(disc(first,false),0);
        return line;
    }

    /** Something to do among the people: where a person's initial would be, a sign in an empty ring. */
    private View toDo(String words,String sign,Runnable go) {
        LinearLayout line=(LinearLayout)row(words,"",go);
        line.addView(disc(sign,true),0);
        return line;
    }

    /**
     * Unfollow, asked once. It tells other people something and cannot be taken back from here - only
     * being given the thing again brings it back - so it is the one thing in this box that asks first.
     */
    private void askUnfollow(final Sharing.Scope scope,final String target,final String name) {
        new Box().setTitle("Unfollow "+subject(scope,name)+"?")
            .setMessage("You stop getting changes, and the others are told. Your copy stays on this phone.")
            .setPositiveButton("Unfollow",(d,w)->{
                if(shareBox!=null&&shareBox.isShowing())shareBox.dismiss();
                final NoteStore.Branch.Kind kind=kindOf(scope);
                final int job=busy("Telling the others\u2026");
                network.submit(()->Post.leave(this,store,keys(),kind,target),
                    told->{busyDone(job,"Unfollowed");refresh();refreshOwed();},
                    e->{busyDone(job,null);alert("Could not unfollow that. Nothing was changed.");});
            }).show();
    }

    /** The round thing a line begins with: filled behind somebody's initial, an empty ring round a plus. */
    private View disc(String face,boolean empty) {
        TextView round=label(face,QUIET,empty?MUTED:INK);
        round.setGravity(Gravity.CENTER);round.setIncludeFontPadding(false);
        GradientDrawable ring=new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        if(empty)ring.setStroke(dp(1),MUTED,dp(3),dp(2));else ring.setColor(mix(CARD,INK,0.12f));
        round.setBackground(ring);
        int side=Math.round(QUIET*reading()*2.2f*getResources().getDisplayMetrics().scaledDensity);
        LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(side,side);
        size.setMargins(0,0,dp(12),0);
        round.setLayoutParams(size);
        return round;
    }

    /** One person and what they may do, which drops down to the four things it can be. */
    private View roleRow(final Sharing.Scope scope,final String target,final String name,
                         final NoteStore.Contact contact,final Sharing.Rule rule,int behind) {
        final Sharing.Level[] levels={Sharing.Level.READ,Sharing.Level.WRITE,Sharing.Level.ADMIN};
        List<String> may=new ArrayList<>();
        int at=-1;
        for(int i=0;i<levels.length;i++){may.add(levels[i].words());if(rule.level==levels[i])at=i;}
        return dropRow(contact.name,may,"Remove",at,behind>0?rule.level.words()+" \u00b7 waiting":rule.level.words(),picked->{
            if(picked>=levels.length)stopSharing(scope,target,name,rule,contact.name);
            else if(rule.level!=levels[picked])setLevel(scope,target,name,contact,levels[picked]);
        });
    }

    /**
     * A line whose right-hand side drops down into the few things it can be, with the one it is ticked.
     *
     * <p>For anything chosen from a handful. It was a line that moved on to the next one each time it was
     * tapped, which shows one choice at a time and makes finding the others a matter of tapping until
     * they come round - and, for what somebody may do with your notes, of giving them each in turn on
     * the way. A list that drops down from the thing being set is what every phone already does here.
     *
     * @param also one thing to do that is not one of the things it can be, or null: under a line, with no
     *             ring beside it, because a ring says "this is how it is set" and taking somebody off is
     *             not a setting. It is picked as the number after the last choice.
     */
    private View dropRow(String left,final List<String> choices,final String also,final int chosen,String shown,
                         final java.util.function.IntConsumer picked) {
        LinearLayout entry=new LinearLayout(this);
        entry.setGravity(Gravity.CENTER_VERTICAL);
        entry.setMinimumHeight(dp(52));
        entry.setPadding(0,dp(6),0,dp(6));
        entry.addView(label(left,READING,INK),new LinearLayout.LayoutParams(0,-2,1));
        final TextView set=label(shown+"  \u25be",QUIET,MUTED);
        set.setPadding(dp(12),dp(8),0,dp(8));set.setGravity(Gravity.END);
        entry.addView(set);
        entry.setBackgroundResource(touchFeedback());
        entry.setContentDescription(left+", "+shown+". Tap to change.");
        entry.setOnClickListener(v->{
            android.widget.PopupMenu menu=new android.widget.PopupMenu(this,set,Gravity.END);
            for(int i=0;i<choices.size();i++)
                menu.getMenu().add(1,i,i,choices.get(i)).setCheckable(true).setChecked(i==chosen);
            menu.getMenu().setGroupCheckable(1,true,true);
            if(also!=null) {
                menu.getMenu().add(2,choices.size(),choices.size(),also);
                menu.getMenu().setGroupDividerEnabled(true);
            }
            menu.setOnMenuItemClickListener(item->{picked.accept(item.getItemId());return true;});
            menu.show();
        });
        return entry;
    }

    /**
     * Sync, in one tap: what is waiting goes, and everybody who has the thing is asked for what they have.
     *
     * <p>It used to be a row that opened a box that listed what was waiting and offered a button - three
     * steps to the only thing anybody opening it wanted. What happened is said by the mark: it turns to a
     * tick when they answer. Only a failure says anything in words.
     */
    private void syncNow(final NoteStore.Branch.Kind kind,final String id,final String name) {
        syncing=busy("Syncing\u2026");
        // What is on the page is written down first, and the sending waits behind that writing: the mark
        // can be pressed from the first keystroke, which is before the notebook has heard of it.
        if(active!=null&&!shelves)save();
        background.submit(()->null,written->syncWritten(kind,id,name),e->syncWritten(kind,id,name));
    }

    /** The strip the sync that is going on speaks through. */
    private int syncing;

    private void syncWritten(final NoteStore.Branch.Kind kind,final String id,final String name) {
        final int job=syncing;
        network.submit(()->{
            Post.Done done=Post.send(this,store,keys(),kind,id,null,kind==NoteStore.Branch.Kind.PAGE);
            int asked=Post.ask(this,store,keys(),kind,id);
            return new Object[]{done,asked};
        },got->{
            Post.Done done=(Post.Done)got[0];int asked=(Integer)got[1];
            saidState();askWhatIsOwed();refresh();refreshOwed();
            if(done.failed>0) {
                busyDone(job,null);
                alert((done.sent>0?"Some of it went, and some could not.":"It could not go just now.")
                    +(done.why.isEmpty()?"":"\n\n"+done.why)+"\n\nIt stays waiting and is tried again by itself.");
            }
            else if(done.sent==0&&asked==0)busyDone(job,name==null||name.isEmpty()?"Nothing to sync":"Nothing to sync in "+name);
            // Gone, and asked for. That they have it is theirs to say: the mark turns to a tick when they do.
            else busyDone(job,"Sent. The tick comes when they answer.");
        },e->{busyDone(job,null);saidState();alert("Could not sync. "+(e.getMessage()==null?"":e.getMessage()));});
    }

    /** The waits offered, in seconds. A negative one is the thing that waits to be asked. */
    private static final int[] WAITS={3,10,30,120,NoteStore.WHEN_ASKED};

    /** And in full, for the description and for the line saying what a thing takes after. */
    private static String waitSaid(int seconds) {
        switch(seconds) {
            case 3: return "3 seconds";
            case 10: return "10 seconds";
            case 30: return "30 seconds";
            case 120: return "2 minutes";
            default: return "when I ask";
        }
    }

    private void setPause(final Sharing.Scope scope,final String target,final String name,int seconds) {
        final NoteStore.Branch.Kind kind=kindOf(scope);
        background.submit(()->{store.setPause(kind,target,seconds);return null;},
            done->{
                if(active!=null&&!shelves)askPause(active.id);
                sharedWith(scope,target,name);
            },
            e->alert("Could not change that."));
    }

    /**
     * One device, what it may do with this, and why.
     *
     * <p>The level is a chip rather than a word at the far edge: it is the one thing on the line you can
     * change, and a thing you can change should look like one. What used to be there was a word in the
     * margin and a sentence at the top of the box explaining that names could be tapped - which is the
     * app teaching its own conventions, and a line nobody reads twice.
     */
    /** The one box showing who has something, so redrawing it replaces it rather than covering it. */
    private AlertDialog shareBox;
    /** What that box is about, so it can be drawn again when what it says changes from somewhere else. */
    private Sharing.Scope boxScope;
    private String boxTarget="",boxName="";

    /** Kept where it can be found without looking for it, or no longer. */
    private void keepToHand(NoteStore.Branch thing,boolean kept) {
        final NoteStore.Branch.Kind kind=thing.kind;final String id=thing.id;
        background.submit(()->{store.keepToHand(kind,id,kept);return null;},
            // Drawn again, so the star is on the thing and the place that gathers them is there, or gone.
            done->{toast(kept?"Added to favourites":"Removed from favourites");if(shelves)refresh();},
            e->alert("Could not change that."));
    }

    /**
     * Taking access away is one tap, and one tap puts it back: a box in between would be in the way.
     *
     * <p>Written down as a decision, and then said: to the person taken off, by the same road as leaving,
     * so that their copy becomes their own; and to everybody else who has the thing, in the list that
     * travels with it. It used to be a row deleted here and nothing more, and the person taken off went
     * on with a tick on their copy.
     */
    private void stopSharing(final Sharing.Scope scope,final String target,final String name,
                             final Sharing.Rule rule,final String who) {
        final long now=System.currentTimeMillis();
        background.submit(()->{store.removeShare(rule,now);return null;},
            done->{
                refresh();sharedWith(scope,target,name);
                final int job=busy("Telling "+who+"…");
                network.submit(()->{
                    boolean told=Post.removed(this,store,keys(),scope,target,rule.address,now);
                    busySay(job,"Telling the others…");
                    Post.changed(this,store,keys(),kindOf(scope),target);
                    return told;
                },told->{
                    saidState();refresh();refreshOwed();
                    busyDone(job,told?"Removed":"Removed. "+who+" is told when their phone is next open.");
                },e->{busyDone(job,null);alert("Removed here. "+who+" could not be told yet; this phone tells them again by itself.");});
            },
            e->alert("Could not change that. Nothing was changed."));
    }

    /** Pick a saved address, or type a new one — which is then saved for next time. */

    /** Typing an address once: it is saved under a name, and used here if this came from a share sheet. */
    /**
     * A new address, without a keyboard. Nobody types sixty characters of Maxima address correctly, and
     * nobody should have to: it is scanned off the other screen, or pasted from wherever it was sent. What
     * arrives is either a whole pairing line — an address and the keys to seal with — or a bare address,
     * and each is taken for what it is.
     */
    private void typeAddress(final Sharing.Scope scope,final String target,final String name) {
        // Straight to the camera, with pasting offered on it. There used to be a card in the way first:
        // two sentences saying that a code can be scanned or pasted, and then a button for each - a screen
        // asking which of two things you meant before letting you do either, when one of them is the
        // camera and the camera is the answer almost every time. The paste is on the scanner, for anybody
        // who was sent a line in a message rather than shown a screen.
        scanning(said->tookAddress(scope,target,name,said,true),()->pasted(scope,target,name));
    }

    /**
     * Somebody whose notes can come here. The camera opens on the word, because scanning their code is the
     * whole of it - there is nothing to choose first, and a box offering two ways to do one thing is a box
     * asking a question nobody came with.
     */
    private void addFromSomeone() {
        scanning(said->tookAddress(null,"","",said,true),()->pasted(null,"",""));
    }

    /** Whatever is on the clipboard, taken for what it is. */
    private void pasted(Sharing.Scope scope,String target,String name) {
        ClipboardManager board=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        ClipData clip=board==null?null:board.getPrimaryClip();
        CharSequence said=clip==null||clip.getItemCount()==0?null:clip.getItemAt(0).coerceToText(this);
        if(said==null||said.toString().trim().isEmpty()){alert("There is nothing on the clipboard to paste.");return;}
        tookAddress(scope,target,name,said.toString().trim(),false);
    }

    /**
     * What was scanned or pasted. A pairing line goes to pairing, where its keys are checked by six digits;
     * a bare address only needs to be told apart from a device of yours, which is two taps and no typing.
     */
    private void tookAddress(final Sharing.Scope scope,final String target,final String name,String said,boolean scanned) {
        // The line, whether it was read as itself or as the link the code is dressed as.
        final String taken=Pairing.line(said);
        if(taken.startsWith(Pairing.MARK)||taken.startsWith(Pairing.MARK_1)){readPairing(taken,scanned);return;}
        if(taken.length()<8||taken.contains(" ")||taken.contains("\n")) {
            alert("That does not look like a Minima address or a pairing code. Nothing was saved.");
            return;
        }
        // Saved as somebody else's, which is the careful way round; the list carries a toggle for the other.
        final String called=taken.length()>8?taken.substring(0,8)+"…":taken;
        keepAddress(scope,target,name,called,taken,false);
    }

    private void keepAddress(Sharing.Scope scope,String target,String name,String typedName,String typedAddress,boolean mine) {
        final String address=typedAddress.trim();
        final String called=typedName.trim().isEmpty()?(mine?"My device":"Contact"):typedName.trim();
        // A blank or obviously wrong address is refused here rather than stored and silently never used.
        if(address.length()<8||address.contains(" ")){alert("That does not look like a Minima address. Paste the whole address.");return;}
        background.submit(()->{store.addAddress(address,called,mine);return null;},
            done->{if(scope==null){toast("Saved");addressBook();}else saveShare(scope,target,name,address,mine);},
            e->alert("Could not save that. Nothing was changed."));
    }

    /** One person's standing in one thing, set and sent. */
    private void setLevel(Sharing.Scope scope,String target,String name,NoteStore.Contact who,
                          Sharing.Level level) {
        final String address=who.address, called=who.name;
        background.submit(()->{store.setLevel(scope,target,address,level,called);return null;},
            done->{
                toast(level.words());
                refresh();sharedWith(scope,target,name);
                sendAfterSharing(scope,target);
            },
            e->alert("Could not save that. Nothing was changed."));
    }

    private void saveShare(Sharing.Scope scope,String target,String name,String address,boolean mine) {
        final Sharing.Rule rule=new Sharing.Rule(scope,target,address,mine);
        background.submit(()->{store.addShare(rule);return null;},
            done->{
                toast(mine?"Reads and writes it":"Reads it");
                refresh();sharedWith(scope,target,name);
                sendAfterSharing(scope,target);
            },
            e->alert("Could not save that. Nothing was changed."));
    }

    /**
     * Giving somebody something is the giving of it.
     *
     * <p>Saying "shared" and then leaving a note sitting in a queue until somebody remembers a button is
     * the app doing the bookkeeping and calling it the job. So what the new rule owes goes now.
     *
     * <p>Quietly when it works, because the sentence before it already said what happened. Never quietly
     * when it does not: a share that says "shared" and sent nothing is the one thing this must not be.
     */
    private void sendAfterSharing(final Sharing.Scope scope,final String target) {
        sendAfterSharing(scope,target,0,null);
    }

    /** @param going the strip this carries on from, or 0 to begin one; {@code to} is who, where it is one person */
    private void sendAfterSharing(final Sharing.Scope scope,final String target,int going,final String to) {
        final NoteStore.Branch.Kind kind=kindOf(scope);
        final String sending=to==null?"Sending\u2026":"Sending it to "+to+"\u2026";
        final int job=going!=0?going:busy(sending);
        if(going!=0)busySay(job,sending);
        network.submit(()->Post.changed(this,store,keys(),kind,target),done->{
            saidState();refresh();refreshOwed();
            if(done.failed>0) {
                busyDone(job,null);
                alert((done.sent>0?"Sent "+done.sent+", but ":"")
                    +(done.failed==1?"one note is still waiting.":done.failed+" notes are still waiting.")
                    +(done.why.isEmpty()?"":System.lineSeparator()+System.lineSeparator()+done.why));
            }
            // Sent is what is known. Whether they have it is theirs to say, and the mark says it when they do.
            else busyDone(job,done.sent>1?"Sent "+done.sent+" notes":done.sent==1?"Sent":"Nothing to send");
        },e->{busyDone(job,null);alert("Shared, but nothing could be sent yet. It stays waiting.");});
    }

    /** Taking access away is said by name: who stops receiving what, and what they keep whatever you do. */
    private void confirmStop(Sharing.Scope scope,String target,String name,Sharing.Rule rule) {
        background.submit(()->store.address(rule.address),who->{
            String called=who==null?rule.address:who.name;
            new Box().setTitle("Stop sharing with "+called+"?")
                .setMessage(called+" stops receiving "+Sharing.describe(scope,name)+"."
                    +"\n\nWhatever has already reached them stays with them: this stops what comes next."
                    +"\n\n"+rule.address)
                .setPositiveButton("Stop",(d,w)->background.submit(()->{store.removeShare(rule);return null;},
                    done->{toast("Stopped");refresh();sharedWith(scope,target,name);},
                    e->alert("Could not change that. Nothing was changed."))).show();
        },e->alert(READ_FAILED));
    }


    /**
     * A line to write on, drawn like the rest of the app rather than like the platform's underline: a box
     * of the same paper with a rule round it, room for a finger, and the writing at the size everything else
     * is read at. A name typed into a box that looks like the app is a name typed into the app.
     */
    private EditText field(String hint,int max) {
        EditText input=new EditText(this);
        input.setHint(hint);input.setContentDescription(hint);input.setSingleLine(true);
        input.setTextSize(Math.max(12,Math.round(READING*reading())));
        input.setTextColor(INK);input.setHintTextColor(MUTED);
        GradientDrawable box=new GradientDrawable();
        box.setColor(PAPER);box.setCornerRadius(dp(12));box.setStroke(Math.max(1,dp(1)),LINE);
        input.setBackground(box);
        input.setPadding(dp(14),dp(12),dp(14),dp(12));
        LinearLayout.LayoutParams place=new LinearLayout.LayoutParams(-1,-2);
        place.setMargins(0,dp(10),0,dp(2));
        input.setLayoutParams(place);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(max)});
        return input;
    }
    private FrameLayout wrap(View view){FrameLayout pad=new FrameLayout(this);pad.setPadding(dp(24),dp(12),dp(24),dp(4));pad.addView(view);return pad;}

    /**
     * The inside of a box, to one measure. Words that touch the edge of the thing they are written in read
     * as if they were cut off, so every box keeps the same room around what it says — a hand's width at the
     * sides, and air above and below — whether it holds a line, a list, or a note from last Tuesday.
     */
    private LinearLayout inside() {
        LinearLayout body=column();
        body.setPadding(dp(24),dp(8),dp(24),dp(16));
        return body;
    }

    /**
     * Every box the app puts up, so that every one of them is put down the same way: tap anywhere that is
     * not the box.
     *
     * <p>That was the rule already and it was not true. The paper of a box is drawn inside a window that is
     * bigger than it - a finger's width of nothing at each side, a little more above and below - and the
     * phone only counts a tap as outside when it misses the <i>window</i>. So a tap beside the box, which
     * is where a thumb goes when the box is tall, landed on something invisible that belonged to the box
     * and did nothing. About was where it was noticed, because About is tall; it was true of all of them.
     *
     * <p>Now a tap that misses the paper is a tap outside, whatever it happened to land on. It closes the
     * box the same way the phone's own outside-tap does, so whatever a box does on being closed - keeping
     * a name, redrawing the shelf behind it - still happens.
     */
    private final class Box extends AlertDialog.Builder {
        Box(){super(MainActivity.this);}

        /** Its words can be held and copied, like any text worth passing on. */
        @Override public AlertDialog show() {
            AlertDialog shown=super.show();
            TextView said=shown.findViewById(android.R.id.message);
            if(said!=null)said.setTextIsSelectable(true);
            return shown;
        }

        @Override public AlertDialog create() {
            final AlertDialog made=super.create();
            // A box is a window of its own, and what is done in it is use as much as anything on the page:
            // its touches and keys keep the notebook from locking again under somebody who is using it.
            final android.view.Window own=made.getWindow();
            if(own!=null) {
                final android.view.Window.Callback inner=own.getCallback();
                own.setCallback((android.view.Window.Callback)java.lang.reflect.Proxy.newProxyInstance(getClassLoader(),
                    new Class<?>[]{android.view.Window.Callback.class},(proxy,method,args)->{
                        String called=method.getName();
                        if(called.equals("dispatchTouchEvent")||called.equals("dispatchKeyEvent"))lastTouch=System.currentTimeMillis();
                        try{return method.invoke(inner,args);}
                        catch(java.lang.reflect.InvocationTargetException thrown){throw thrown.getCause();}
                    }));
            }
            made.setCanceledOnTouchOutside(true);
            final View whole=made.getWindow()==null?null:made.getWindow().getDecorView();
            if(whole==null)return made;
            final boolean[] began={false};
            final View.OnTouchListener away=(v,event)->{
                android.graphics.Rect edge=new android.graphics.Rect();
                android.graphics.drawable.Drawable paper=v.getBackground();
                if(paper==null||!paper.getPadding(edge))return false;
                float x=event.getX(), y=event.getY();
                boolean off=x<edge.left||y<edge.top||x>v.getWidth()-edge.right||y>v.getHeight()-edge.bottom;
                switch(event.getActionMasked()) {
                    // Taken on the way down so that the way up comes here too, and acted on only on the
                    // way up: a finger that lands beside the box and slides onto it has changed its mind.
                    case MotionEvent.ACTION_DOWN: began[0]=off;return off;
                    case MotionEvent.ACTION_UP:
                        boolean leave=began[0]&&off;began[0]=false;
                        // Said as a click as well as acted on, so whatever drives the screen without a
                        // finger hears that something was pressed.
                        if(leave){v.performClick();made.cancel();}
                        return leave;
                    case MotionEvent.ACTION_CANCEL: began[0]=false;return false;
                    default: return began[0];
                }
            };
            // Kept on the view as well as set on it, so that anything which borrows the listener for a
            // while - a name being typed in place does - can hand this one back.
            whole.setTag(away);
            whole.setOnTouchListener(away);
            return made;
        }
    }

    /** The same room, for a box whose content has to scroll. */
    private ScrollView scrolling(View body) {
        // Never the whole screen. A box that reaches the bottom edge has nowhere outside it left to tap,
        // and tapping outside is how every box here is closed - so one that fills the screen is one you
        // cannot put down. It keeps a strip below it and scrolls inside whatever is left.
        ScrollView scroll=new ScrollView(this) {
            @Override protected void onMeasure(int wide,int high) {
                // What is left after the things a box may also carry - a title, a row of buttons, the
                // margin round its paper, the phone's own bars - and a strip to tap. It was 72% of the
                // screen, which was measured on a box with no buttons and left almost nothing under
                // one that had them.
                int high_=getResources().getDisplayMetrics().heightPixels;
                int most=Math.max(Math.round(high_*0.4f),high_-dp(330));
                super.onMeasure(wide,MeasureSpec.makeMeasureSpec(most,MeasureSpec.AT_MOST));
            }
        };
        scroll.setClipToPadding(false);
        scroll.addView(body);
        return scroll;
    }

    /**
     * A box with one line to write on, and as little else as it can have. The title already says what is
     * being made and the field already says what goes in it, so asking the same thing again in a sentence
     * was the box talking to itself. The one line kept is the one nothing else says: there is no button,
     * and closing the box is what keeps the name.
     */
    private View naming(EditText input,String said) {
        LinearLayout body=inside();
        body.addView(input);
        return body;
    }

    // ---- Core, backups, lifecycle ------------------------------------------------------------------------

    /**
     * What has to be true before a note can reach another device, said as a list with each step either done
     * or not, and the first one that is not done saying what to do about it. Sharing a thing and then being
     * left to wonder is the state this replaces: a person should be able to see where the chain stops.
     */
    private void sending() {
        final LinearLayout body=inside();
        final TextView head=label("Checking…",READING,MUTED);
        body.addView(head);
        final AlertDialog box=new Box().setTitle("Node settings").setView(scrolling(body))
            .setNegativeButton("Open Core",(d,w)->openCore())
            .setNeutralButton("My address",(d,w)->myAddress())
            .setPositiveButton("Check again",(d,w)->sending()).create();
        box.show();
        background.submit(()->{
            String mine=getSharedPreferences("settings",MODE_PRIVATE).getString("address","");
            int paired=0,known=0;
            for(NoteStore.Contact contact:store.addresses()){known++;if(contact.paired())paired++;}
            int owed=store.owed(NoteStore.Branch.Kind.LIBRARY,Sharing.EVERYTHING).size();
            if(core==null)core=new CoreConnection(this);
            return new Object[]{mine,known,paired,owed,core.installed()};
        },found->{
            final Object[] said=(Object[])found;
            final String mine=(String)said[0];
            final int known=(Integer)said[1], paired=(Integer)said[2], owed=(Integer)said[3];
            final boolean core=(Boolean)said[4];
            body.removeAllViews();
            // Nothing here needs another app any more. Core is still named, because a phone that has one
            // is a phone whose owner will look for it in this list and wonder where it went.
            step(body,true,"Nothing else has to be installed",
                core?"":"");
            // The Core answers or it does not, and only it can say: asked here rather than guessed at.
            final View[] answering=step(body,true,"This phone is its own Maxima node","");
            final View[] address=step(body,!mine.isEmpty(),"This device has a Maxima address",
                mine.isEmpty()?"Starting the node…":"");
            step(body,known>0,"Somewhere to send to",
                known>0?"":"Add an address, or scan another device's code.");
            // Who this phone can reach is the same subject as whether it can reach anybody, so the list of
            // them opens from the step that is about it rather than from a row of its own in the menu.
            body.addView(tapRow(known==0?"Add an address":known==1?"1 address":known+" addresses",
                this::addressBook));
            step(body,paired>0,"That device's keys are known",
                paired>0?"":"Scan its code from My address on the other phone, so notes can be sealed for it.");
            body.addView(gap(10));
            body.addView(label(owed==0?"Nothing is waiting to go."
                :owed==1?"1 note is waiting to go.":owed+" notes are waiting to go.",13,owed>0?ACCENT:MUTED));
            // Not conditional on another app any more: the node is this one, so it is always asked.
            askCoreAnswers(answering,address,mine.isEmpty());
        },e->{body.removeAllViews();body.addView(label("Could not read that. Nothing was changed.",READING,WARN));});
    }

    /** Minima Core itself, opened where it stands: the switch that enables this app lives inside it. */
    private void openCore() {
        Intent open=getPackageManager().getLaunchIntentForPackage(CoreConnection.CORE);
        if(open==null){alert("Minima Core is not installed on this phone.");return;}
        try{startActivity(open);}catch(Exception e){alert("Could not open Minima Core.");}
    }

    /** One line of the list: done or not, and what to do about it if not. */
    private View[] step(LinearLayout body,boolean done,String said,String todo) {
        LinearLayout row=new LinearLayout(this);row.setPadding(0,dp(8),0,dp(2));
        TextView mark=label(done?"✓":"·",READING,done?ACCENT:MUTED);
        mark.setMinWidth(dp(26));mark.setGravity(Gravity.START);
        row.addView(mark);
        LinearLayout words=column();
        words.addView(label(said,READING,INK));
        TextView how=null;
        if(!done&&!todo.isEmpty()){how=label(todo,READING,MUTED);words.addView(how);}
        row.addView(words,new LinearLayout.LayoutParams(0,-2,1));
        body.addView(row);
        return new View[]{mark,how};
    }

    /** A step that turned out to be done after all: ticked, and told to stop explaining itself. */
    private void ticked(View[] step) {
        ((TextView)step[0]).setText("✓");
        ((TextView)step[0]).setTextColor(ACCENT);
        if(step[1]!=null)step[1].setVisibility(View.GONE);
    }

    /**
     * Whether Core will answer this app at all, which only Core can say — and, the moment it does, what
     * this device's own address is. Nobody should have to copy that across by hand from the app that knows it.
     */
    private void askCoreAnswers(final View[] answering,final View[] address,final boolean ask) {
        ticked(answering);
        if(ask)ownAddress(address);
    }

    /**
     * The address this phone hands out, made sure of before anything is drawn that contains it.
     *
     * <p>Two things went wrong without this, and both of them look to a person like the app not working.
     * A phone whose owner never opened Node settings had no address at all, so the code it held up carried
     * nothing and whoever scanned it saved a contact there was nowhere to send to. And a phone that got its
     * address before this app preferred the routable form kept the six-hundred-character permanent one for
     * ever, which makes a code too dense to read across a table.
     *
     * <p>So the node is asked whenever an address is about to be shown. It starts in a moment where it is
     * already running, and the box waits where it is not — better a box that takes a breath than a code
     * that cannot work.
     */
    private void withAddress(final Runnable then) {
        // The box this is for cannot open until the node has answered, and a tap that opens nothing is a
        // tap somebody makes again.
        final int job=busy("Getting this phone's address\u2026");
        network.submit(()->{
            String kept=getSharedPreferences("settings",MODE_PRIVATE).getString("address","");
            // The node, asked for what it has now. A failure leaves whatever was kept: an old address is
            // worth more than none, and this must never be the thing that stops a box opening.
            try {
                for(String one:Node.addresses(this))
                    if(Pairing.reachable(one)&&!one.equals(kept)) {
                        getSharedPreferences("settings",MODE_PRIVATE).edit().putString("address",one).apply();
                        return one;
                    }
                // No fallback to the permanent address. It reads like the better one - it survives the
                // host moving, where an ordinary address does not - but it is not an address you can send
                // to: it is a key and a directory to ask, and the answer only exists once this node has
                // published itself there and the asker is allowed to read it. A code handed out in a
                // node's first seconds, before any relay has answered, carried one of these and looked
                // exactly like a good one. It failed days later, on somebody else's phone, with "directory
                // replied UNKNOWN" - the app's plumbing turning up in the middle of their afternoon.
                // Better to have no code yet and say so.
            } catch(Exception e){/* whatever was kept stands */}
            return kept;
        },said->{busyDone(job,null);myAddress=said;then.run();},e->{busyDone(job,null);then.run();});
    }

    /**
     * The address, from the node this app now is.
     *
     * <p>It used to be asked of other apps, and the answer was always somebody else's to give — a Core
     * with no Maxima built into it, a transport that answers only apps signed with its own key. Neither
     * was a thing this app could fix from here. It carries the transport itself now, so the address is
     * not fetched from anywhere: it is simply what this phone is called on the network.
     *
     * <p>Starting means reaching a relay, which takes as long as the network takes, so the line says what
     * is happening while it happens rather than going blank and hoping nobody minds.
     */
    private void ownAddress(final View[] address) {
        if(address[1]!=null)((TextView)address[1]).setText("Reaching the network\u2026");
        network.submit(()->{
            // The routable one, in preference to the permanent one. A permanent address is six hundred
            // characters where a routable one is four hundred, and it is a code somebody has to point a
            // camera at - the difference is a code that reads across a table and one that does not. What
            // the permanent form buys is surviving a host move, and the network already heals that for a
            // contact it knows: a failed send looks the address up again. The permanent form is kept as
            // the fallback for a phone that has no routable address at all.
            for(String one:Node.addresses(this))if(Pairing.reachable(one))return one;
            String permanent=Node.permanent(this);
            return Pairing.reachable(permanent)?permanent:"";
        },said->{
            if(said.isEmpty()) {
                if(address[1]!=null)((TextView)address[1]).setText(Node.allowedOnTheNetwork(this)
                    ?"No relay answered yet, so nothing can reach this phone. Check the network and open "
                        +"this again."
                    // Said in the words of the thing to go and change, because nothing here can change it.
                    :"This app is not allowed on the network, so it can reach no relay and has no address. "
                        +"Allow it in Settings \u2192 Apps \u2192 Mininotes \u2192 Permissions.");
                return;
            }
            keepMyAddress(said);
            ticked(address);
        },e->{android.util.Log.w("Mininotes/Node","could not start",e);
              if(address[1]!=null)((TextView)address[1])
                .setText("The node could not start. Nothing else was changed.");});
    }

    /**
     * The Maxima transport app, asked for the address Core has no code to give. It is a second node on the
     * same phone rather than a fallback: Core carries the notebook's business and this carries the post.
     */
    private void askTransport(final View[] address,final String coreSaid) {
        if(transport==null)transport=new MaximaConnection(this);
        if(!transport.installed()) {
            if(address[1]!=null&&coreSaid!=null)((TextView)address[1]).setText(coreSaid);
            return;
        }
        transport.address(said->{keepMyAddress(said);ticked(address);toast("The Maxima app gave this device's address");},
            why->{if(address[1]!=null)((TextView)address[1]).setText(why);});
    }

    private void checkCore(){background.submit(()->{if(core==null)core=new CoreConnection(this);return null;},ready->core.check(this::alert),e->alert("Could not prepare the Core connection. Your notes are unaffected."));}

    private void pick(int request) {
        Intent i=new Intent(request==EXPORT?Intent.ACTION_CREATE_DOCUMENT:Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE);
        if(request==EXPORT) {
            i.setType("application/zip");i.putExtra(Intent.EXTRA_TITLE,"mininotes-backup.zip");
        } else {
            // A backup is a zip now; the ones written before files existed are plain text, and still read.
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/zip","application/json","text/plain"});
        }
        startActivityForResult(i,request);
    }

    /**
     * A file from anywhere on the phone, kept with this note. It is copied rather than pointed at: what
     * another app lends you can be moved, renamed or withdrawn, and a note that loses what it holds because
     * something else tidied up is not keeping anything.
     */
    private void attach() {
        if(holdingId().isEmpty()){toast("There is nothing here to keep a file with.");return;}
        // Which thing it is kept with is settled now, not when the picker comes back: by then the reader
        // may have walked somewhere else, and a file landing on the wrong thing is worse than none.
        attachingTo=holding();attachingToId=holdingId();
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
            .addCategory(Intent.CATEGORY_OPENABLE),ATTACH);
    }

    /** Copies what was picked into the pad's own folder, then writes the row that says whose it is. */
    private void keepFile(final Uri from) {
        final NoteStore.Branch.Kind kind=attachingTo;
        final String what=attachingToId;
        if(what==null||what.isEmpty())return;
        status("Keeping the file…");
        background.submit(()->{
            String name=null,type=getContentResolver().getType(from);long said=-1;
            try(Cursor about=getContentResolver().query(from,null,null,null,null)) {
                if(about!=null&&about.moveToFirst()) {
                    int atName=about.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    int atSize=about.getColumnIndex(android.provider.OpenableColumns.SIZE);
                    if(atName>=0&&!about.isNull(atName))name=about.getString(atName);
                    if(atSize>=0&&!about.isNull(atSize))said=about.getLong(atSize);
                }
            }
            if(said>0&&Attachment.tooBig(said))throw new IllegalArgumentException(TOO_BIG);
            if(store.weight()>=Attachment.PLENTY)throw new IllegalArgumentException(TOO_MUCH);
            NoteStore.Held held=store.opening(kind,what,name,type,Math.max(0,said));
            File landing=store.fileFor(held.id);
            final long[] written={0};
            try(InputStream in=getContentResolver().openInputStream(from);
                OutputStream out=new FileOutputStream(landing)) {
                if(in==null)throw new IllegalStateException("Nothing to read");
                // Some apps say nothing about the size beforehand, so it is also counted on the way in.
                InputStream counted=new java.io.FilterInputStream(in){
                    @Override public int read(byte[] b,int off,int len) throws java.io.IOException {
                        int n=super.read(b,off,len);if(n>0){written[0]+=n;if(Attachment.tooBig(written[0]))throw new IllegalArgumentException(TOO_BIG);}return n;
                    }
                };
                // Sealed on the way in when the notebook is locked, so its bytes are never on the phone plain.
                byte[] key=NoteStore.key();
                if(key!=null)Sealed.seal(key,counted,out);
                else{byte[] part=new byte[16384];int n;while((n=counted.read(part))!=-1)out.write(part,0,n);}
            } catch(Exception e){store.sweep();throw e;}
            // The row is written last: until it exists the bytes are nobody's, and get swept up.
            store.keep(new NoteStore.Held(held.id,held.note,held.name,held.kind,written[0],held.added,held.held));
            return held.name;
        },name->{saidState();showFiles();toast(name+" kept here");},
          e->{saidState();alert(e instanceof IllegalArgumentException&&e.getMessage()!=null
                ?e.getMessage():"Could not keep that file. Nothing was changed.");});
    }

    /**
     * Everything this thing can reach, drawn along the strip: what is kept here first, then what the book
     * and the collection around it keep. The clip stays whether or not anything is on it, so there is always
     * somewhere to put one.
     */
    private void showFiles() {
        if(attached==null)return;
        final NoteStore.Branch.Kind kind=holding();
        final String what=holdingId();
        if(what.isEmpty())return;
        background.submit(()->store.filesReaching(kind,what),held->{
            if(attached==null||!what.equals(holdingId())||kind!=holding())return;
            attached.removeAllViews();
            for(NoteStore.Held file:held)attached.addView(chip(file,file.held!=kind));
        },e->{});
    }

    /**
     * One file it can reach: what it is called and how big it is. Tap to open it, hold to take it out.
     *
     * <p>A file that came from the book or the collection around this thing is drawn dashed and says where
     * it is kept, so it reads as borrowed rather than as one of this thing's own - and so that taking one
     * out is plainly a thing that happens somewhere else as well.
     */
    private View chip(final NoteStore.Held file,final boolean fromAbove) {
        LinearLayout chip=column();
        chip.setPadding(dp(12),dp(7),dp(12),dp(7));
        GradientDrawable edge=new GradientDrawable();
        edge.setColor(Color.TRANSPARENT);edge.setCornerRadius(dp(12));edge.setStroke(Math.max(1,dp(1)),LINE);
        if(fromAbove)edge.setStroke(Math.max(1,dp(1)),LINE,dp(4),dp(3));
        chip.setBackground(edge);
        chip.addView(line(file.name,QUIET,fromAbove?MUTED:INK));
        chip.addView(label(fromAbove
            ?Attachment.size(file.bytes)+" \u00b7 "+(file.held==NoteStore.Branch.Kind.COLLECTION?"collection":"book")
            :Attachment.size(file.bytes),HEADING,MUTED));
        LinearLayout.LayoutParams place=new LinearLayout.LayoutParams(-2,-2);
        // The words grew, so the box they sit in grows with them rather than cutting them off.
        place.setMargins(dp(4),dp(2),dp(4),dp(8));place.width=dp(200);
        chip.setLayoutParams(place);
        chip.setContentDescription("Open "+file.name+", "+Attachment.size(file.bytes)
            +(fromAbove?", kept with the "+(file.held==NoteStore.Branch.Kind.COLLECTION?"collection":"book"):""));
        chip.setOnClickListener(v->openFile(file));
        chip.setOnLongClickListener(v->{askDropFile(file);return true;});
        return chip;
    }

    /** Hands one kept file to whatever app can open it, for as long as that app is open. */
    private void openFile(NoteStore.Held file) {
        Uri lent=Lending.of(file.id);
        Intent look=new Intent(Intent.ACTION_VIEW).setDataAndType(lent,file.kind)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_ACTIVITY_NEW_TASK);
        try{startActivity(look);}
        catch(Exception e){alert("Nothing on this phone can open "+file.name+".");}
    }

    /** Taking a file out of a note deletes the copy the note was keeping, so it says so first. */
    private void askDropFile(final NoteStore.Held file) {
        new Box().setTitle("Remove "+file.name+"?")
            .setMessage(Attachment.size(file.bytes)+"\n\nThe copy this note is keeping is deleted. Whatever you attached it from is untouched.")
            .setPositiveButton("Remove",(d,w)->background.submit(()->{store.drop(file.id);return null;},
                done->{showFiles();toast("Removed");},e->alert("Could not remove that file. Nothing was changed."))).show();
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(lockedOut)return;if(result!=RESULT_OK||data==null||data.getData()==null)return;final Uri file=data.getData();
        if(request==EXPORT)background.submit(()->{export(file);return null;},done->toast("Backup exported"),e->alert(BACKUP_FAILED));
        else if(request==IMPORT)askHowToImport(file);
        else if(request==ATTACH)keepFile(file);}
    /**
     * A backup is a zip: the notes as one piece of text, and beside them the files they keep. It has to be,
     * once a note can keep a file — a backup that restores the writing but not what it held is not a backup
     * of the note. Written straight to the file the picker gave, so nothing is held in memory twice.
     */
    private void export(Uri file) throws Exception {
        try(OutputStream out=getContentResolver().openOutputStream(file,"wt")) {
            if(out==null)throw new IllegalStateException("No output stream");
            final String text=store.backup();final List<NoteStore.Held> files=store.everyFile();
            byte[] key=NoteStore.key();
            if(key==null){zipBackup(text,files,out);return;}
            // Locked: the backup is sealed whole and carries the lock, so it opens with the same password or
            // words anywhere - on the PC too. The zip inside is sealed as it is made, never written plain.
            PhoneLock.writeBackupHead(out,PhoneLock.kept(this));
            java.io.PipedInputStream plain=new java.io.PipedInputStream(1<<16);java.io.PipedOutputStream into=new java.io.PipedOutputStream(plain);
            final Exception[] failed={null};
            Thread making=new Thread(()->{try(into){zipBackup(text,files,into);}catch(Exception e){failed[0]=e;}},"mininotes-backup");
            making.start();
            try{Sealed.seal(key,plain,out);}finally{making.join();}
            if(failed[0]!=null)throw failed[0];
        }
    }

    /** The backup's zip: the notes as text, and every file, plain inside it. */
    private void zipBackup(String text,List<NoteStore.Held> files,OutputStream out) throws Exception {
        ZipOutputStream zip=new ZipOutputStream(out);
        zip.putNextEntry(new ZipEntry(BACKUP_TEXT));zip.write(text.getBytes(StandardCharsets.UTF_8));zip.closeEntry();
        for(NoteStore.Held held:files) {
            File kept=store.fileFor(held.id);
            if(!kept.isFile())continue;
            zip.putNextEntry(new ZipEntry(Attachment.entry(held.id)));
            PhoneLock.copyOut(kept,zip);
            zip.closeEntry();
        }
        zip.finish();zip.flush();
    }

    /**
     * Reads a backup either way round. A zip is unpacked first — its files into the pad's own folder, its
     * text kept aside — and the text is then read as it always was; a backup written before files existed
     * is that text on its own, and still restores. Bytes nobody claims are swept up by the store.
     */
    /**
     * Two ways to mean "import", and only the reader knows which. Adding brings the backup in beside what is
     * here; replacing is a restore — the pad becomes what the backup was, and what is on the phone now goes.
     * Replacing is the one that cannot be undone, so it says so and is asked twice.
     */
    /** A locked backup is opened first - its password or words - and then asked about like any other. */
    private void askHowToImport(final Uri file) {
        background.submit(()->{try(InputStream in=new BufferedInputStream(getContentResolver().openInputStream(file))){return PhoneLock.backupLock(in);}},
            lock->{if(lock==null)askHowToImport(file,null);else backupKey(lock,key->askHowToImport(file,key));},e->alert(READ_FAILED));
    }
    private void askHowToImport(final Uri file,final byte[] key) {
        new Box().setTitle("Import this backup")
            .setMessage("Add it to what is here, or replace everything with it?"
                +"\n\nAdding keeps your notes and brings the backup's in beside them, as copies."
                +"\n\nReplacing is a restore: this pad becomes what the backup was.")
            .setPositiveButton("Add to this pad",(d,w)->importing(file,false,key))
            .setNeutralButton("Replace everything",(d,w)->confirmReplace(file,key))
            .show();
    }

    private void confirmReplace(final Uri file,final byte[] key) {
        background.submit(()->store.inside(NoteStore.Branch.Kind.LIBRARY,Sharing.EVERYTHING).holds.size(),here->
            new Box().setTitle("Replace everything?")
                .setMessage((here==0?"This pad":here==1?"The one collection on this pad":"All "+here+" collections on this pad")
                    +" and every book, note and file in them are deleted, and the backup is put in their place."
                    +"\n\nThis cannot be undone. Export what is here first if you are not sure.")
                .setPositiveButton("Replace everything",(d,w)->importing(file,true,key)).show(),
            e->alert(READ_FAILED));
    }

    private void importing(final Uri file,final boolean replacing,final byte[] key) {
        background.submit(()->{
            int count=restore(file,replacing,key);
            // Into a locked notebook, what came in plain is sealed like everything else in it.
            byte[] mine=NoteStore.key();if(mine!=null)PhoneLock.every(this,mine,true);
            return count;
        },count->{
            trail.clear();trail.add(new Step(NoteStore.Branch.Kind.LIBRARY,Sharing.EVERYTHING,"All collections"));
            refresh();showFiles();
            toast(replacing?(count+(count==1?" note restored":" notes restored"))
                           :(count+(count==1?" note added":" notes added")));
        },e->alert(BACKUP_FAILED));
    }

    private int restore(Uri file,boolean replacing,byte[] key) throws Exception {
        Thread[] opening={null};final Exception[] failed={null};
        try(InputStream raw=getContentResolver().openInputStream(file)) {
            if(raw==null)throw new IllegalStateException("No input stream");
            BufferedInputStream in=new BufferedInputStream(raw);
            if(key!=null) {
                // A locked backup: past its lock, then opened as it is read, and read to its end so its last check runs.
                PhoneLock.backupLock(in);
                java.io.PipedInputStream plain=new java.io.PipedInputStream(1<<16);java.io.PipedOutputStream into=new java.io.PipedOutputStream(plain);
                final InputStream sealed=in;
                opening[0]=new Thread(()->{try(into){Sealed.open(key,sealed,into);}catch(Exception e){failed[0]=e;}},"mininotes-backup-open");
                opening[0].start();in=new BufferedInputStream(plain);
            }
            in.mark(2);
            boolean zipped=in.read()=='P'&&in.read()=='K';
            in.reset();
            if(!zipped)return store.importBackup(readAll(in,IMPORT_BYTES),replacing);
            String text=null;
            try(ZipInputStream zip=new ZipInputStream(in)) {
                ZipEntry entry;
                while((entry=zip.getNextEntry())!=null) {
                    if(entry.isDirectory())continue;
                    // Only two kinds of entry are ours, and a file entry is named by a plain id: a zip that
                    // names a path is a zip trying to write somewhere it was not unpacked.
                    if(BACKUP_TEXT.equals(entry.getName())){text=readAll(zip,IMPORT_BYTES);continue;}
                    String id=Attachment.idOf(entry.getName());
                    if(id==null)continue;
                    long written=0;
                    try(OutputStream out=new FileOutputStream(store.landingFor(id))) {
                        byte[] part=new byte[16384];int n;
                        while((n=zip.read(part))!=-1) {
                            written+=n;
                            if(Attachment.tooBig(written))throw new IllegalArgumentException(TOO_BIG);
                            out.write(part,0,n);
                        }
                    }
                }
                // Read to the very end: a locked backup's last piece carries the check that it is whole.
                byte[] rest=new byte[8192];while(in.read(rest)!=-1){/* drained */}
            }
            if(opening[0]!=null){opening[0].join();if(failed[0]!=null)throw new IllegalArgumentException("That backup could not be opened: "+failed[0].getMessage());}
            if(text==null)throw new IllegalArgumentException("That zip is not a Mininotes backup.");
            return store.importBackup(text,replacing);
        } finally {
            store.sweep();
        }
    }

    private String readAll(InputStream in,int most) throws Exception {
        ByteArrayOutputStream buffer=new ByteArrayOutputStream();byte[] part=new byte[8192];int n;
        while((n=in.read(part))!=-1){if(buffer.size()+n>most)throw new IllegalArgumentException("Backup exceeds 10 MB of text");buffer.write(part,0,n);}
        return new String(buffer.toByteArray(),StandardCharsets.UTF_8);
    }

    // The process can be killed after these callbacks, so wait a bounded time for queued writes to land.
    @Override protected void onPause(){if(lockedOut){super.onPause();return;}save();keepVersion();rememberWhere();background.flush(FLUSH_TIMEOUT);super.onPause();}

    /**
     * Where the app was when it was left: a note being written, or a level of the shelves and the way in to
     * it. Coming back to a different place than you left is the app deciding it knows better.
     */
    private void rememberWhere() {
        StringBuilder where=new StringBuilder();
        if(!shelves&&active!=null)where.append("note\n").append(active.id);
        else {
            where.append("shelves");
            for(Step step:trail)
                where.append('\n').append(step.kind.name()).append('\t').append(step.id).append('\t')
                     .append(step.name==null?"":step.name.replace('\t',' ').replace('\n',' '));
        }
        final String said=where.toString();
        background.submit(()->{getSharedPreferences("settings",MODE_PRIVATE).edit().putString("where",said).apply();return null;},
            done->{},e->{});
    }

    /** The trail as it was left, or empty if the app was last on a note or has never been opened. */
    private List<Step> whereTrail(String where) {
        List<Step> back=new ArrayList<>();
        if(where==null||!where.startsWith("shelves"))return back;
        String[] lines=where.split("\n");
        for(int at=1;at<lines.length;at++) {
            String[] parts=lines[at].split("\t",-1);
            if(parts.length<2)continue;
            try{back.add(new Step(NoteStore.Branch.Kind.valueOf(parts[0]),parts[1],parts.length>2?parts[2]:""));}
            catch(IllegalArgumentException unknown){/* a kind from another build is simply not a step */}
        }
        return back;
    }
    @Override protected void onSaveInstanceState(Bundle state){if(lockedOut){super.onSaveInstanceState(state);return;}save();background.flush(FLUSH_TIMEOUT);if(active!=null)state.putString("note",active.id);super.onSaveInstanceState(state);}
    @Override public void onBackPressed() {
        if(lockedOut){super.onBackPressed();return;}
        if(carrying!=null){carrying=null;browse();return;}
        if(shelves){if(trail.size()>1)climb(trail.size()-2);else back();return;}
        save();super.onBackPressed();
    }
    // The notebook is not closed here any more. It belongs to the process, and the process can outlive this
    // screen: a note arriving a minute after the pad was put away is written into the same notebook.
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);if(lockedOut){super.onDestroy();return;}
        background.submit(()->{if(core!=null)core.close();return null;},done->{},e->{});
        network.abandon();chores.abandon();lookout.abandon();background.close();super.onDestroy();}

    /**
     * On screen: what lands is said here, as it lands, rather than by the phone.
     *
     * <p>And whatever happened while nobody was looking is caught up with — an offer somebody took up is
     * put to the person it was waiting for, and if the note left open was written in from the other end,
     * the page is brought up to it before a word typed on the old one can go back over it.
     */
    /** Back from Android's battery question: the switch says what Android decided, not what was tapped. */
    @Override protected void onResume() {
        super.onResume();
        if(lockedOut)return;
        if(sleepSwitch!=null)sleepSaid();
    }

    private boolean sleepAllowed() {
        android.os.PowerManager power=getSystemService(android.os.PowerManager.class);
        return power!=null&&power.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void sleepSaid() {
        boolean on=sleepAllowed();
        if(sleepSwitch.isChecked()!=on){quietSwitch=true;sleepSwitch.setChecked(on);quietSwitch=false;}
        if(sleepSays!=null)sleepSays.setText(on
            ?"Notes arrive while the phone sleeps. It wakes the phone for a moment every five to nine minutes. To turn this off, choose Mininotes in Android's list and pick Optimise."
            :"Android stops the pad listening after the phone has slept a while. Notes sent then can be missed.");
    }

    @Override protected void onStart() {
        super.onStart();
        if(lockedOut)return;
        // Locked again while away, or away longer than was chosen: the unlock page, and nothing of the notebook.
        if(!PhoneLock.open(this)){recreate();return;}
        if(idleTooLong()){relockNow();return;}
        handler.removeCallbacks(awayRelock);handler.removeCallbacks(idleCheck);handler.postDelayed(idleCheck,30_000);
        Listening.watch(watching);
        for(Hello.Said them:Listening.unanswered())somebodyAccepted(them);
        // Only on coming back. The first time, the screen was drawn from the notebook a moment ago.
        if(!beenAway)return;
        beenAway=false;
        // A pad that is never closed, only left, would otherwise look once in its life.
        lookQuietly();
        if(active!=null&&!shelves)changedUnderneath(active.id);
        else refresh();
    }

    @Override protected void onStop(){if(lockedOut){super.onStop();return;}
        handler.removeCallbacks(idleCheck);
        int minutes=PhoneLock.locked(this)?PhoneLock.minutes(this):0;
        if(minutes>0)handler.postDelayed(awayRelock,minutes*60_000L);
        Listening.unwatch(watching);beenAway=true;super.onStop();}

    // ---- locking again when not used ---------------------------------------------------------------------

    /** When the screen was last touched; the notebook locks again after the time chosen in Security. */
    private long lastTouch=System.currentTimeMillis();
    @Override public void onUserInteraction(){super.onUserInteraction();lastTouch=System.currentTimeMillis();}

    private boolean idleTooLong() {
        int minutes=PhoneLock.minutes(this);
        return PhoneLock.locked(this)&&minutes>0&&System.currentTimeMillis()-lastTouch>=minutes*60_000L;
    }

    /** On screen: looked at every half minute. */
    private final Runnable idleCheck=()->{if(idleTooLong())relockNow();else handler.postDelayed(this.idleCheck,30_000);};

    /** Away: once the time is up the notebook is closed there and then, and the page it left waits for the password. */
    private final Runnable awayRelock=()->{if(idleTooLong()&&PhoneLock.open(this))PhoneLock.relock(this);};

    /** Writing saved, the notebook closed and its key let go, and the unlock page in its place. */
    private void relockNow() {
        handler.removeCallbacks(idleCheck);handler.removeCallbacks(awayRelock);
        save();keepVersion();background.flush(FLUSH_TIMEOUT);
        PhoneLock.relock(this);recreate();
    }

    /** This screen, as the thing that is told when something lands. One object, so it can be taken down. */
    private final Consumer<Post.Landed> watching=landed->runOnUiThread(()->heard(landed));

    /**
     * A code opened from outside the app: the phone's own camera pointed at another phone, or a link
     * somebody tapped.
     *
     * <p>Which of the two decides what is asked. A code read off the other screen by a camera came from
     * the device in front of you and through nothing else. A link that arrived any other way could have
     * been changed on the way, and for that there are the six digits - so only a camera is taken at its
     * word, and anything else is treated as a line that was pasted. Who sent it here is something another
     * app on this phone could lie about; an app that can do that has no need to, and either way nothing is
     * kept until somebody has read who it is and pressed Accept.
     */
    private boolean opened(Intent intent) {
        if(intent==null||!Intent.ACTION_VIEW.equals(intent.getAction()))return false;
        String said=intent.getDataString();
        if(!Pairing.isLink(said))return false;
        android.net.Uri by=getReferrer();
        String from=by==null||by.getHost()==null?"":by.getHost();
        // Used, so that turning the phone round does not open it a second time.
        setIntent(new Intent(this,MainActivity.class));
        readPairing(Pairing.line(said),CAMERAS.contains(from),true);
        return true;
    }

    /** The cameras and code readers a phone comes with. Anything else is a link from somewhere. */
    private static final java.util.Set<String> CAMERAS=new java.util.HashSet<>(java.util.Arrays.asList(
        "com.google.android.GoogleCamera","app.grapheneos.camera","com.android.camera2","com.android.camera",
        "com.google.android.googlequicksearchbox","com.google.ar.lens","com.google.android.gms",
        "com.android.systemui","com.sec.android.app.camera","com.samsung.android.bixby.vision",
        "com.motorola.camera3","com.oneplus.camera","com.oplus.camera","com.huawei.camera",
        "com.android.camera.miui","com.xiaomi.scanner","org.lineageos.aperture"));

    /** Tapped from a notification about one note, with the pad already open behind it: that note. */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if(lockedOut)return;
        if(opened(intent))return;
        String note=intent==null?null:intent.getStringExtra(Listening.NOTE);
        if(note==null||note.isEmpty())return;
        if(active!=null&&!shelves&&active.id.equals(note)){changedUnderneath(note);return;}
        closeNote();open(note);
    }

    /** Something landed while the pad was on screen. */
    private void heard(Post.Landed landed) {
        if(landed.accepted!=null){somebodyAccepted(landed.accepted);return;}
        // Somebody said they have something. Nothing to say about it: the marks say it, once they are
        // asked again what is still waiting.
        if(landed.answered) {
            askWhatIsOwed();refresh();refreshOwed();
            // Somebody changed what this phone may do while the box saying so was open - or the page.
            if(landed.people&&boxScope!=null&&shareBox!=null&&shareBox.isShowing())
                sharedWith(boxScope,boxTarget,boxName);
            if(landed.people)askWritable();
            return;
        }
        if(landed.said==null)return;
        if(awaiting!=0){handler.removeCallbacks(notYet);busyDone(awaiting,null);awaiting=0;}
        toast(landed.said);refresh();refreshOwed();
        // Somebody left while the list of who has it was open.
        if(landed.people&&boxScope!=null&&shareBox!=null&&shareBox.isShowing())
            sharedWith(boxScope,boxTarget,boxName);
        changedUnderneath(landed.note);
        // Taken off the thing that is open, say: the copy is this phone's own now, and the page writes.
        if(landed.people)askWritable();
    }

    /**
     * The note that is open was written in from somewhere else. See {@link Arriving#onThePage}.
     *
     * <p>Asked of the notebook on the worker, behind any writing of this page that was already on its way,
     * so what comes back is what the notebook says after both.
     */
    private void changedUnderneath(final String id) {
        if(id==null||active==null||shelves||page==null||!active.id.equals(id))return;
        background.submit(()->store.get(id),stored->{
            if(stored==null||active==null||shelves||page==null||!active.id.equals(id))return;
            if(stored.revision<=keptRevision&&stored.body.equals(kept))return;
            Arriving.Page said=Arriving.onThePage(kept,page.getText().toString(),stored.body);
            handler.removeCallbacks(autoSave);
            // A title changed here and not written down yet is kept, the same as words on the page are.
            final String wanted=active.title==null?"":active.title;
            final boolean renamedHere=!wanted.equals(keptTitle);
            active=stored;kept=stored.body;keptRevision=stored.revision;
            if(active.title==null)active.title="";
            keptTitle=active.title;
            final boolean titleUnsaved=renamedHere&&!wanted.equals(active.title);
            if(titleUnsaved)active.title=wanted;
            showTitle();
            if(!page.getText().toString().equals(said.text)) {
                int at=Math.max(0,page.getSelectionStart());
                loading=true;page.setText(said.text);loading=false;
                linkify();
                page.setSelection(Math.min(at,page.getText().length()));
            }
            // Words typed since the last writing are in the page and nowhere else, so it is written again.
            if(said.unsaved||titleUnsaved){edits++;handler.postDelayed(autoSave,SAVE_DELAY);}
            else saved=edits;
            failed=false;saidState();askWhatIsOwed();refreshOwed();
        },e->{});
    }
}
