(function(root) {
  'use strict';
  var MAX = 256 * 1024 * 1024;
  var DB = 'gaius-shader-packs-v1';
  var state = root.__gaiusShaderPacks || (root.__gaiusShaderPacks = {});
  state.records = state.records || [];
  state.toast = toast;
  state.selected = state.selected || 'OFF';
  state.revision = state.revision || 0;
  state.available = !!state.available;
  state.screenOpen = false;
  state.listeners = state.listeners || [];
  function toast(message) {
    try {
      var events = root.__gaiusMinecraftEvents || (root.__gaiusMinecraftEvents = []);
      events.push({event:'shader-pack-toast', detail:String(message), at:Date.now()});
      if (events.length > 500) events.splice(0, events.length - 500);
      // Diagnostics stay in the event log; the native screen translates status codes.
    } catch (_) {}
  }
  function status(code, name, message) {
    state.lastMessage = code;
    state.lastMessageName = name || '';
    toast(message);
    notify();
  }
  function db(done) {
    if (!root.indexedDB) { done(null); return; }
    var req = indexedDB.open(DB,1);
    req.onupgradeneeded=function(){ req.result.createObjectStore('packs',{keyPath:'id'}); req.result.createObjectStore('meta',{keyPath:'key'}); };
    req.onsuccess=function(){ done(req.result); }; req.onerror=function(){ done(null); };
  }
  function notify() { state.revision++; for (var i=0;i<state.listeners.length;i++) try { state.listeners[i](state); } catch (_) {} }
  function parseZip(buffer) {
    var bytes=new Uint8Array(buffer), end=Math.max(0,bytes.length-65557), e=-1;
    for (var i=bytes.length-22;i>=end;i--) if(bytes[i]===80&&bytes[i+1]===75&&bytes[i+2]===5&&bytes[i+3]===6){e=i;break;}
    if(e<0) throw new Error('ZIP end record missing');
    var count=bytes[e+10]|(bytes[e+11]<<8), cd=bytes[e+12]|(bytes[e+13]<<8)|(bytes[e+14]<<16)|(bytes[e+15]<<24), off=bytes[e+16]|(bytes[e+17]<<8)|(bytes[e+18]<<16)|(bytes[e+19]<<24);
    var names=[], p=off, hasShaders=false;
    for(i=0;i<count && p+46<=bytes.length;i++){
      if(bytes[p]!==80||bytes[p+1]!==75||bytes[p+2]!==1||bytes[p+3]!==2) break;
      var nl=bytes[p+28]|(bytes[p+29]<<8), xl=bytes[p+30]|(bytes[p+31]<<8), cl=bytes[p+32]|(bytes[p+33]<<8), name='';
      for(var j=0;j<nl;j++) name+=String.fromCharCode(bytes[p+46+j]);
      name=name.replace(/\\/g,'/').replace(/^\/+/, '');
      if(/(^|\/)shaders\//.test(name)) hasShaders=true;
      names.push(name); p += 46+nl+xl+cl;
    }
    if(!hasShaders) throw new Error('shader pack requires a shaders/ directory');
    return names;
  }

  function textDecode(bytes) { try { return new TextDecoder('utf-8').decode(bytes); } catch (_) { var out=''; for(var i=0;i<bytes.length;i++) out+=String.fromCharCode(bytes[i]); return out; } }
  function zipEntries(buffer) {
    var bytes=new Uint8Array(buffer), end=Math.max(0,bytes.length-65557), e=-1, out=[];
    for(var i=bytes.length-22;i>=end;i--) if(bytes[i]===80&&bytes[i+1]===75&&bytes[i+2]===5&&bytes[i+3]===6){e=i;break;}
    if(e<0) throw new Error('ZIP end record missing');
    var count=bytes[e+10]|(bytes[e+11]<<8), off=bytes[e+16]|(bytes[e+17]<<8)|(bytes[e+18]<<16)|(bytes[e+19]<<24), p=off;
    for(i=0;i<count && p+46<=bytes.length;i++) {
      if(bytes[p]!==80||bytes[p+1]!==75||bytes[p+2]!==1||bytes[p+3]!==2) break;
      var method=bytes[p+10]|(bytes[p+11]<<8), cs=bytes[p+20]|(bytes[p+21]<<8)|(bytes[p+22]<<16)|(bytes[p+23]<<24), us=bytes[p+24]|(bytes[p+25]<<8)|(bytes[p+26]<<16)|(bytes[p+27]<<24), nl=bytes[p+28]|(bytes[p+29]<<8), xl=bytes[p+30]|(bytes[p+31]<<8), cl=bytes[p+32]|(bytes[p+33]<<8), lo=bytes[p+42]|(bytes[p+43]<<8)|(bytes[p+44]<<16)|(bytes[p+45]<<24), name='';
      for(var j=0;j<nl;j++) name+=String.fromCharCode(bytes[p+46+j]);
      name=name.replace(/\\/g,'/').replace(/^\/+/, ''); out.push({name:name,method:method,compressed:cs,size:us,local:lo}); p+=46+nl+xl+cl;
    }
    return {bytes:bytes, entries:out};
  }
  function readEntry(zip, entry) {
    var b=zip.bytes, p=entry.local;
    if(p+30>b.length) return Promise.reject(new Error('ZIP local header outside file'));
    var nl=b[p+26]|(b[p+27]<<8), xl=b[p+28]|(b[p+29]<<8), start=p+30+nl+xl, raw=b.slice(start,start+entry.compressed);
    if(entry.method===0) return Promise.resolve(raw);
    if(entry.method!==8 || typeof DecompressionStream==='undefined') return Promise.reject(new Error('deflate ZIP requires DecompressionStream'));
    return new Response(new Blob([raw]).stream().pipeThrough(new DecompressionStream('deflate-raw'))).arrayBuffer().then(function(x){return new Uint8Array(x);});
  }
  function extractRecord(record) {
    if(record.files) return Promise.resolve(record.files);
    var zip; try { zip=zipEntries(record.bytes); } catch(e) { return Promise.reject(e); }
    var wanted=zip.entries.filter(function(e){ return /(^|\/)shaders\//.test(e.name) && /\.(vsh|fsh|gsh|csh|glsl|properties|txt)$/i.test(e.name); });
    var files={};
    var jobs=wanted.map(function(e){ return readEntry(zip,e).then(function(bytes){ var n=e.name.replace(/^.*?(?=shaders\/)/,''); files[n]=textDecode(bytes); }); });
    return Promise.all(jobs).then(function(){ record.files=files; return files; });
  }
  function idFor(name, buffer) { var b=new Uint8Array(buffer), h=2166136261; for(var i=0;i<b.length;i++){h^=b[i];h=Math.imul(h,16777619);} return name.replace(/[^A-Za-z0-9._-]/g,'_')+'-'+(h>>>0).toString(16); }
  function save(record, done) {
    db(function(database){ if(!database){done(false);return;} var tx=database.transaction(['packs','meta'],'readwrite'); tx.objectStore('packs').put(record); tx.objectStore('meta').put({key:'selected',value:record.id}); tx.oncomplete=function(){done(true);}; tx.onerror=function(){done(false);}; });
  }
  function importFile(file) {
    if(!file || file.size<=0 || file.size>MAX){status('rejected', '', 'Shader pack rejected: size');return;}
    file.arrayBuffer().then(function(buffer){
      var names=parseZip(buffer), id=idFor(file.name,buffer), rec={id:id,name:file.name,bytes:buffer,names:names,created:Date.now()};
      save(rec,function(ok){ if(!ok){status('storage_unavailable', '', 'Shader pack storage unavailable');return;} var replaced=false; for(var ri=0;ri<state.records.length;ri++){ if(state.records[ri].id===id){state.records[ri]=rec;replaced=true;break;} } if(!replaced) state.records.push(rec); state.selected=id; notify(); status('imported', file.name, 'Shader pack imported: '+file.name); });
    }).catch(function(err){status('rejected', '', 'Shader pack rejected: '+(err.message||err));});
  }
  function install() {
    if(state.installed) return; state.installed=true;
    var input=document.createElement('input'); input.type='file'; input.accept='.zip,application/zip,application/x-zip-compressed'; input.multiple=true; input.hidden=true;
    input.addEventListener('change',function(){var fs=input.files||[]; for(var i=0;i<fs.length;i++) importFile(fs[i]); if(input.parentNode) input.parentNode.removeChild(input);});
    state.openPicker=function(){document.body.appendChild(input);input.click();};
    document.addEventListener('dragover',function(e){e.preventDefault();},true);
    document.addEventListener('drop',function(e){e.preventDefault();var fs=e.dataTransfer&&e.dataTransfer.files||[];for(var i=0;i<fs.length;i++) importFile(fs[i]);},true);
  }
  state.list=function(){return state.records.slice();};
  state.select=function(id){state.selected=id||'OFF'; db(function(database){if(database){var tx=database.transaction('meta','readwrite');tx.objectStore('meta').put({key:'selected',value:state.selected});}}); notify();};
  state.onChange=function(fn){if(typeof fn==='function') state.listeners.push(fn);};
  state.getSelectedFiles=function(){ if(state.selected==='OFF') return Promise.resolve(null); for(var i=0;i<state.records.length;i++) if(state.records[i].id===state.selected) return extractRecord(state.records[i]); return Promise.resolve(null); };
  // Native UI: Options > Video Settings > Shader Packs... (BrowserShaderPacksScreen) reads this.
  state.setAvailable=function(value){state.available=!!value; notify();};
  state.setScreenOpen=function(open){state.screenOpen=!!open; if(!open) { state.lastMessage=''; state.lastMessageName=''; }};
  state.snapshot=function(){
    var packs=[]; for(var i=0;i<state.records.length;i++) packs.push({id:state.records[i].id,name:state.records[i].name});
    return JSON.stringify({available:state.available,selected:state.selected,revision:state.revision,message:state.lastMessage||'',messageName:state.lastMessageName||'',packs:packs});
  };
  install();
  db(function(database){if(!database)return;var tx=database.transaction(['packs','meta'],'readonly'), req=tx.objectStore('packs').getAll(), sel=tx.objectStore('meta').get('selected');req.onsuccess=function(){state.records=req.result||[]; for(var ri=0;ri<state.records.length;ri++) extractRecord(state.records[ri]).catch(function(){}); notify();};sel.onsuccess=function(){if(sel.result)state.selected=sel.result.value;};});
})(typeof globalThis !== 'undefined' ? globalThis : window);
