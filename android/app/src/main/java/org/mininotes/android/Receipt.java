// SPDX-License-Identifier: LicenseRef-Mininotes-NoPaidProducts
// Apache-2.0 with the Commons Clause and a paid-product condition. See LICENSE.
package org.mininotes.android;

/**
 * "I have it." What a phone says back when a note has arrived and been written down.
 *
 * <p>The network can say that it took a message. It cannot say that anybody received one, and it will take
 * a message for a phone that is asleep, switched off, or no longer there. For a long time this app wrote
 * "delivered" on the network's word, and everything that leaned on that was leaning on nothing: the mark
 * that says a note is up to date, and — worse — the text two phones are taken to have agreed on, which is
 * what every merge is made against. A phone that had been handing notes to the network on behalf of
 * somebody who never got them took that person's next words for old news and set them aside in silence.
 *
 * <p>So the far end answers, and only its answer counts. Which note and which revision ride in the
 * envelope's own header, where they are sealed and signed like everything else; this is the few bytes
 * inside that say what kind of message it is.
 *
 * <p>Said after the note is written down, never before: an answer that went out ahead of the writing
 * would be a promise, and this is a receipt.
 *
 * <p>Holds no Android types, so the format is unit tested.
 */
final class Receipt {
    /** The format, and the first four bytes. */
    static final byte[] MAGIC={'M','N','R','1'};

    /**
     * It arrived, and is written down - but this phone's note does not now say what was sent: the two were
     * put together, or what arrived was older than what was here. A number, so a later build can say more.
     */
    static final int HAVE=1;
    /**
     * It arrived, and this phone's note now says exactly what was sent.
     *
     * <p>The difference matters more than it looks. Every merge is made against the text two phones last
     * both had, and "they received my revision 20" was being taken for "we both have revision 20". A
     * phone that received it, put it together with its own and ended somewhere else had agreed to
     * nothing - and the next thing it wrote was weighed against a text it never had.
     */
    static final int TOOK=3;
    /**
     * Not an answer but a question, in the same five bytes: "send me whatever you have for me."
     *
     * <p>Nothing in Maxima can be fetched. A thing arrives because somebody sent it, so a phone that has
     * been asleep all night has no way to go and get what it missed: it can only wait for the other phones
     * to try again, which they do less and less often. This is how it says it is back. It is what makes
     * Sync a button that does something on the phone that is behind, and not only on the one that is ahead.
     */
    static final int ASK=2;

    /**
     * "I have left this." Said by a phone that has stopped following something somebody shares with it,
     * to everybody who has it - one number for each kind of thing that can be left.
     *
     * <p>A phone could always stop taking a thing in, and nobody was ever told: the owner's mark said
     * "waiting" for ever, and their phone tried again every quarter of an hour for somebody who had gone.
     * Which note rides in the envelope, as it does for every answer; for a book or a collection it is any
     * note out of it, and the phone that hears works out which shelf that is from its own shelves - so
     * nothing here depends on the two phones calling a shelf by the same name, which they do not.
     */
    static final int LEFT_PAGE=4, LEFT_BOOK=5, LEFT_COLLECTION=6;

    /** The number that says a thing shared at this level has been left, or 0 where nothing can be. */
    static int left(Sharing.Scope scope) {
        if(scope==null)return 0;
        switch(scope) {
            case PAGE: return LEFT_PAGE;
            case BOOK: return LEFT_BOOK;
            case COLLECTION: return LEFT_COLLECTION;
            default: return 0;
        }
    }

    /** Which kind of thing was left, or null where the number says something else. */
    static Sharing.Scope leftScope(int about) {
        switch(about) {
            case LEFT_PAGE: return Sharing.Scope.PAGE;
            case LEFT_BOOK: return Sharing.Scope.BOOK;
            case LEFT_COLLECTION: return Sharing.Scope.COLLECTION;
            default: return null;
        }
    }

    private Receipt(){}

    /** The inside of an answer. */
    static byte[] wrap(int what) {
        return new byte[]{MAGIC[0],MAGIC[1],MAGIC[2],MAGIC[3],(byte)what};
    }

    /** What an answer says, or 0 when these bytes are not one. */
    static int open(byte[] said) {
        if(said==null||said.length!=MAGIC.length+1)return 0;
        for(int at=0;at<MAGIC.length;at++)if(said[at]!=MAGIC[at])return 0;
        return said[MAGIC.length]&0xff;
    }
}
