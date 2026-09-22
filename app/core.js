(function (root) {
  'use strict';
  var C = {};
  C.APP = 'mininotes.v1';
  C.quote = function (s) { return "'" + String(s).replace(/'/g, "''") + "'"; };
  C.validNote = function (n) {
    return !!n && typeof n.id === 'string' && /^[a-zA-Z0-9_-]{1,100}$/.test(n.id) &&
      typeof n.title === 'string' && n.title.length <= 160 && typeof n.body === 'string' && n.body.length <= 24000 &&
      typeof n.tag === 'string' && n.tag.length <= 40 && typeof n.pinned === 'boolean' && typeof n.deleted === 'boolean' &&
      typeof n.updated === 'number' && isFinite(n.updated) && n.updated >= 0 && n.updated <= 8640000000000000;
  };
  C.clean = function (n) {
    if (!C.validNote(n)) throw new Error('Invalid note. Titles allow 160 characters and notes allow 24,000.');
    return {id:n.id,title:n.title,body:n.body,tag:n.tag,pinned:n.pinned,deleted:n.deleted,updated:n.updated};
  };
  C.hex = function (s) {
    var bytes = unescape(encodeURIComponent(s)), out = '0x', i;
    for (i=0;i<bytes.length;i++) out += ('0'+bytes.charCodeAt(i).toString(16)).slice(-2);
    return out;
  };
  C.unhex = function (s) {
    if (typeof s !== 'string' || s.length > 200000 || !/^0x([a-fA-F0-9]{2})*$/.test(s)) throw new Error('Invalid message encoding');
    var out='', i;
    for(i=2;i<s.length;i+=2) out += '%'+s.substr(i,2);
    return decodeURIComponent(out);
  };
  C.envelope = function (data, now) {
    var m = JSON.parse(C.unhex(data));
    if(m.app !== C.APP || m.version !== 1 || m.type !== 'note' || !/^[a-zA-Z0-9_-]{1,100}$/.test(m.id) || typeof m.id !== 'string' ||
       typeof m.created !== 'number' || !isFinite(m.created) || m.created > now+300000 || m.created < now-604800000 || !C.validNote(m.note)) throw new Error('Invalid or expired message');
    return m;
  };
  C.schema = 'CREATE TABLE IF NOT EXISTS notes (id VARCHAR(100) PRIMARY KEY, payload CLOB NOT NULL)';
  C.inboxSchema = 'CREATE TABLE IF NOT EXISTS inbox (id VARCHAR(100) NOT NULL, sender VARCHAR(2048) NOT NULL, payload CLOB NOT NULL, PRIMARY KEY (id,sender))';
  root.NotesCore = C;
  if (typeof module !== 'undefined') module.exports = C;
}(typeof globalThis !== 'undefined' ? globalThis : this));
