/* The node supplies MDS. Keep this file and core.js compatible with ES5. */
MDS.load('core.js');
var ready = false;
var pending = [];
function receive(event) {
  if (!event.data || event.data.application !== NotesCore.APP) return;
  var sender = event.data.from, msg;
  if (typeof sender !== 'string' || !/^0x[a-fA-F0-9]{2,2046}$/.test(sender)) return;
  try { msg = NotesCore.envelope(event.data.data, Date.now()); } catch(e) { return; }
  // Only existing contacts may add snapshots to the inbox. They cannot edit notes.
  MDS.cmd('maxcontacts action:list', function (r) {
    if (!r || r.status !== true || !r.response || !r.response.contacts) return;
    var contacts=r.response.contacts, allowed=false, i;
    for(i=0;i<contacts.length;i++) if(contacts[i].publickey===sender) allowed=true;
    if(!allowed) return;
    var id=NotesCore.quote(msg.id), from=NotesCore.quote(sender), payload=NotesCore.quote(JSON.stringify(NotesCore.clean(msg.note)));
    // Atomic insert-if-absent, with a composite primary key as a second guard.
    MDS.sql('INSERT INTO inbox (id,sender,payload) SELECT '+id+','+from+','+payload+' WHERE NOT EXISTS (SELECT 1 FROM inbox WHERE id='+id+' AND sender='+from+')', function (result) {
      if(result && result.status === true) MDS.comms.solo('inbox-updated');
    });
  });
}
MDS.init(function(event) {
  if(event.event==='inited') {
    MDS.sql(NotesCore.inboxSchema,function(r) {
      if(!r || r.status!==true) {MDS.log('Mininotes inbox initialization failed'); return;}
      ready=true;
      while(pending.length) receive(pending.shift());
    });
  } else if(event.event==='MAXIMA') {
    if(ready) receive(event); else if(pending.length<30) pending.push(event);
  }
});
