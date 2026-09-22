# Live-node validation (not yet executed)

1. Record Minima version and read-only `help command:maxima`, `help command:maxcontacts` responses. Do not record session IDs or credentials.
2. Install the exact hashed package on two authorized nodes and open it from MiniHub. Record the hash and displayed version.
3. In READ mode, create a Unicode note including apostrophes, reload, edit, pin, trash, restore, and export/import. Verify SQL responses and 24,000-character round trips.
4. With both nodes already added as contacts, explicitly send a harmless note. Check pending permission handling and `response.delivered`, then confirm the receiver's actual MAXIMA envelope and inbox row.
5. Retry the same snapshot in the same dialog: there should be one inbox entry. Restart the recipient app/service and repeat. Deliver snapshots in reverse order: both distinct snapshots should remain independently available.
6. Test offline recipient, command failure, delayed response, unknown contact, malformed payload, expired payload, and forged payload sender fields. Incoming contacts must never overwrite personal notes.
7. Stop the sender tab during a send. The app must not claim recipient persistence. It does not automatically resend on restart.
8. Verify the exact installed package on desktop and Android MiniHub, including icon rendering, dialog scrolling, mobile backup export/import and background receipt while its page is closed.
