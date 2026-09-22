/* Page-side storage. A failed node connection never falls back to browser storage. */
window.NotesStorage = class {
  constructor(node) { this.node = node; this.queue = Promise.resolve(); }
  call(method, value) {
    return new Promise((resolve,reject) => {
      const timer=setTimeout(()=>reject(new Error('Node timed out. Your changes are still in this tab; retry saving.')),20000);
      MDS[method](value, r => {clearTimeout(timer); if(!r || r.status !== true || r.pending) reject(new Error(r?.pending ? 'Approve the request in MiniHub, then reopen Mininotes.' : 'Node request failed. Reopen from MiniHub and retry.')); else resolve(r);});
    });
  }
  async init() { if(this.node) {await this.call('sql',NotesCore.schema); await this.call('sql',NotesCore.inboxSchema);} else this.local(); }
  local() {const raw=localStorage.getItem('mininotes.v1'); const data=raw ? JSON.parse(raw) : []; if(!Array.isArray(data)||!data.every(NotesCore.validNote)) throw new Error('Browser notebook is unreadable. Existing data has been preserved.'); return data;}
  async list(table='notes') {
    if(!this.node) return table==='notes' ? this.local() : [];
    if(!['notes','inbox'].includes(table)) throw new Error('Invalid table');
    const all=[];
    // One bounded payload per response avoids large MDS response truncation.
    for(let offset=0;;offset++) {
      const r=await this.call('sql',`SELECT * FROM ${table} ORDER BY id${table==='inbox'?', sender':''} LIMIT 1 OFFSET ${offset}`);
      if(!Array.isArray(r.rows)) throw new Error('Invalid database response');
      if(!r.rows.length) break;
      const row=r.rows[0], note=JSON.parse(row.PAYLOAD);
      if(table==='notes') all.push(NotesCore.clean(note)); else all.push({id:row.ID,sender:row.SENDER,note:NotesCore.clean(note)});
    }
    return all;
  }
  save(note) {
    const clean=NotesCore.clean(note);
    const op=this.queue.catch(()=>{}).then(async()=>{
      if(this.node) await this.call('sql',`MERGE INTO notes (id,payload) KEY(id) VALUES (${NotesCore.quote(clean.id)},${NotesCore.quote(JSON.stringify(clean))})`);
      else {const all=this.local(), i=all.findIndex(n=>n.id===clean.id); if(i<0) all.push(clean); else all[i]=clean; localStorage.setItem('mininotes.v1',JSON.stringify(all));}
    });
    this.queue=op; return op;
  }
};
