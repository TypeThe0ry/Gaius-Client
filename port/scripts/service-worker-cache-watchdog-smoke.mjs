import {readFile} from 'node:fs/promises';
const s=await readFile(new URL('../web/sw/gaius-sw.js',import.meta.url),'utf8');
for(const m of ['CACHE_OP_TIMEOUT_MS = 3000','const bounded =','const openCache =','network-cache-timeout']) if(!s.includes(m)) throw new Error(`missing ${m}`);
console.log(JSON.stringify({ok:true,cacheOperationsBounded:true,timeoutMs:3000}));
