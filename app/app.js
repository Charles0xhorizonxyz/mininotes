'use strict';
const $=id=>document.getElementById(id);
const nodeMode=new URLSearchParams(location.search).has('uid');
const store=new NotesStorage(nodeMode);
let notes=[], inbox=[], view='all', query='', active=null, revision=0, savedRevision=0, timer, busy=false, snapshot=null;
const uid=()=>crypto.randomUUID().replace(/-/g,'');
const notify=message=>{$('notice').textContent=message;};
function fresh(title='',body='',tag='Personal') {return {id:uid(),title,body,tag,pinned:false,deleted:false,updated:Date.now()};}
function guard(fn) {return (...args)=>Promise.resolve().then(()=>fn(...args)).catch(e=>notify(e.message));}
function render() {
  $('all-count').textContent=notes.filter(n=>!n.deleted).length;
  $('inbox-count').textContent=inbox.length;
  const titles={all:'All notes',pinned:'Pinned notes',inbox:'Maxima inbox',trash:'Trash'};
  $('view-title').textContent=(titles[view]||view.slice(4))+'.';
  $('view-description').textContent=view==='inbox'?'Thoughts shared with you. Open one to save your own copy.':view==='trash'?'A second chance for thoughts you set aside.':'Ideas, everyday moments, and everything in between.';
  document.querySelectorAll('[data-view]').forEach(b=>b.classList.toggle('active',b.dataset.view===view));
  $('tags').replaceChildren();
  [...new Set(notes.filter(n=>!n.deleted).map(n=>n.tag||'Personal'))].sort().forEach(tag=>{const b=document.createElement('button');b.className='tag-button';b.textContent=tag;b.onclick=()=>{view='tag:'+tag;render();};$('tags').append(b);});
  let list=view==='inbox'?inbox.map(x=>({...x.note,receipt:x})):notes.filter(n=>view==='trash'?n.deleted:!n.deleted&&(view==='pinned'?n.pinned:view.startsWith('tag:')?(n.tag||'Personal')===view.slice(4):true));
  list=list.filter(n=>(n.title+' '+n.body+' '+n.tag).toLowerCase().includes(query)).sort((a,b)=>$('sort').value==='title'?a.title.localeCompare(b.title):Number(b.pinned)-Number(a.pinned)||b.updated-a.updated);
  $('result-count').textContent=list.length+' notes';$('notes').replaceChildren();
  for(const n of list) {
    const card=document.createElement('button');card.className='note-card';
    const top=document.createElement('div');top.className='note-top';const tag=document.createElement('span');tag.textContent=n.tag||'Personal';const pin=document.createElement('span');pin.textContent=n.pinned?'✧':'↗';top.append(tag,pin);
    const h=document.createElement('h2');h.textContent=n.title||'Untitled thought';const p=document.createElement('p');p.textContent=n.body||'A thought waiting to happen…';const time=document.createElement('time');time.dateTime=new Date(n.updated).toISOString();time.textContent=new Date(n.updated).toLocaleDateString(undefined,{month:'short',day:'numeric',year:'numeric'});card.append(top,h,p,time);
    card.onclick=guard(async()=>{if(n.receipt) {const copy={...NotesCore.clean(n),id:uid(),deleted:false,pinned:false,updated:Date.now()};await store.save(copy);notes.push(copy);notify('Saved a copy from '+n.receipt.sender.slice(0,18)+'… to your notebook.');open(copy);}else open(n);});$('notes').append(card);
  }
  $('empty').hidden=list.length>0;
  if(!list.length) {$('empty').querySelector('h2').textContent=query?'No matching thoughts.':view==='inbox'?'A quiet inbox.':view==='trash'?'Nothing in the trash.':'A blank page. A fresh possibility.';$('empty').querySelector('p').textContent=query?'Try a different word or notebook.':view==='inbox'?(nodeMode?'Notes from your Maxima contacts will appear here.':'Sharing is available when installed in MiniHub.'):'Give that thought somewhere to live.';$('empty-new').hidden=!!query||view==='inbox'||view==='trash';}
  if(list.length && view==='all'&&!query){const b=document.createElement('button');b.className='note-card add-card';const s=document.createElement('span');s.textContent='＋';b.append(s,document.createTextNode('A new thought'));b.onclick=guard(create);$('notes').append(b);}
}
function open(note) {active={...note};revision=0;savedRevision=0;$('note-title').value=active.title;$('note-body').value=active.body;$('note-tag').value=active.tag;$('save-status').textContent='Saved';updateEditor();$('editor').showModal();$('note-title').focus();}
function updateEditor() {$('word-count').textContent=(active.body.trim()?active.body.trim().split(/\s+/).length:0)+' words';$('pin').textContent=active.pinned?'★ Unpin':'☆ Pin';$('trash').textContent=active.deleted?'Restore note':'Move to trash';$('share').disabled=!nodeMode||active.deleted;$('share').title=nodeMode?'Share a copy':'Install in MiniHub to share';}
async function create() {const n=fresh();await store.save(n);notes.push(n);render();open(n);}
function edited(){active.title=$('note-title').value;active.body=$('note-body').value;active.tag=$('note-tag').value;active.updated=Date.now();revision++;$('save-status').textContent='Unsaved changes';updateEditor();clearTimeout(timer);timer=setTimeout(()=>save().catch(()=>{}),450);}
async function save(){if(!active||savedRevision===revision)return;const n={...active},r=revision;$('save-status').textContent='Saving…';try{await store.save(n);const i=notes.findIndex(x=>x.id===n.id);if(i>=0)notes[i]=n;if(active?.id===n.id){savedRevision=Math.max(savedRevision,r);$('save-status').textContent=savedRevision===revision?'Saved':'Unsaved changes';}render();}catch(e){$('save-status').textContent='Save failed — retry';notify(e.message);throw e;}}
async function close(){clearTimeout(timer);await save();$('editor').close();active=null;}
async function loadInbox(){inbox=await store.list('inbox');render();}
async function start(){try{await store.init();notes=await store.list();await loadInbox();$('connection').textContent=nodeMode?'Stored on your Minima node':'Browser notebook';if(!nodeMode)notify('Browser preview · Notes are saved in this browser. Install in MiniHub for node storage and Maxima sharing.');render();}catch(e){notify(e.message);$('connection').textContent='Storage unavailable';document.querySelectorAll('button').forEach(b=>b.disabled=true);}}
$('new-note').onclick=guard(create);$('empty-new').onclick=guard(create);$('search').oninput=e=>{query=e.target.value.toLowerCase();render();};$('sort').onchange=render;
document.querySelectorAll('[data-view]').forEach(b=>b.onclick=guard(async()=>{view=b.dataset.view;if(view==='inbox')await loadInbox();else render();}));
['note-title','note-body','note-tag'].forEach(id=>$(id).oninput=edited);
$('close-editor').onclick=guard(close);$('editor').addEventListener('cancel',e=>{e.preventDefault();guard(close)();});$('retry-save').onclick=guard(save);
$('pin').onclick=guard(async()=>{active.pinned=!active.pinned;edited();await save();});$('trash').onclick=guard(async()=>{active.deleted=!active.deleted;edited();await save();await close();});
$('export').onclick=guard(async()=>{await save();const blob=new Blob([JSON.stringify({app:NotesCore.APP,version:1,exported:new Date().toISOString(),notes:await store.list()},null,2)],{type:'application/json'});const url=URL.createObjectURL(blob),a=document.createElement('a');a.href=url;a.download='mininotes-'+new Date().toISOString().slice(0,10)+'.json';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);notify('Backup exported. It contains your notes as plain text.');});
$('import').onclick=()=>$('import-file').click();$('import-file').onchange=guard(async e=>{const file=e.target.files[0];e.target.value='';if(!file)return;if(file.size>10000000)throw new Error('Backup is too large (10 MB limit).');const b=JSON.parse(await file.text());if(b.app!==NotesCore.APP||b.version!==1||!Array.isArray(b.notes)||b.notes.length>1000||!b.notes.every(NotesCore.validNote))throw new Error('Invalid backup. No notes imported.');let count=0;try{for(const n of b.notes){const copy={...NotesCore.clean(n),id:uid()};await store.save(copy);notes.push(copy);count++;}}finally{render();notify(count+' notes imported as separate copies.');}});
$('share').onclick=guard(async()=>{await save();snapshot={app:NotesCore.APP,version:1,type:'note',id:uid(),created:Date.now(),note:NotesCore.clean(active)};$('share-status').textContent='Loading contacts…';$('contacts').replaceChildren();$('send').disabled=true;$('share-dialog').showModal();const r=await store.call('cmd','maxcontacts action:list');if(!Array.isArray(r.response?.contacts))throw new Error('Unexpected contact response');for(const c of r.response.contacts){if(!/^0x[a-fA-F0-9]{2,2046}$/.test(c.publickey))continue;const o=document.createElement('option');o.value=c.publickey;o.textContent=(c.extradata?.name||'Contact')+' · '+c.publickey.slice(-12);$('contacts').append(o);}$('send').disabled=!$('contacts').options.length;$('share-status').textContent=$('contacts').options.length?'':'No contacts yet. Add a contact in your Minima app.';});
$('cancel-share').onclick=()=>{$('share-dialog').close();};
$('send').onclick=async()=>{if(busy)return;busy=true;$('send').disabled=true;$('share-status').textContent='Sending…';const recipient=$('contacts').value;$('contacts').disabled=true;try{if(!/^0x[a-fA-F0-9]{2,2046}$/.test(recipient))throw new Error('Choose a valid contact');const r=await store.call('cmd','maxima action:send publickey:'+recipient+' application:'+NotesCore.APP+' data:'+NotesCore.hex(JSON.stringify(snapshot)));if(r.response?.delivered!==true)throw new Error('Transport did not confirm acceptance. You can retry this same copy.');$('share-status').textContent='Accepted by Maxima transport. Recipient storage and reading are not confirmed.';}catch(e){$('share-status').textContent=e.message;}finally{busy=false;$('send').disabled=false;$('contacts').disabled=false;}};
window.addEventListener('beforeunload',e=>{if(active&&savedRevision!==revision){e.preventDefault();e.returnValue='';}});
document.addEventListener('keydown',e=>{if(['INPUT','TEXTAREA','SELECT'].includes(document.activeElement.tagName)||document.querySelector('dialog[open]'))return;if(e.key==='n'){e.preventDefault();guard(create)();}if(e.key==='/'){e.preventDefault();$('search').focus();}});
if(nodeMode){$('connection').textContent='Connecting to Minima…';MDS.init(event=>{if(event.event==='inited')start();if(event.event==='MDSCOMMS'&&event.data?.message==='inbox-updated')guard(loadInbox)();});}else start();
