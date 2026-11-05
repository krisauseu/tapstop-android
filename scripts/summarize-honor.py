#!/usr/bin/env python3
"""Extract phase/resource metadata without keys, transcripts or audio content."""
import csv, json, math, re, statistics
from pathlib import Path
from datetime import datetime
from zoneinfo import ZoneInfo
root=Path('diagnostics/honor')
allrows=[]
summaries={}
for name in ['series','restart-groq','restart-openai-manual']:
 path=root/name/'logcat.txt'
 if not path.exists(): continue
 requests={}; snapshots=[]
 resources=[json.loads(s) for s in (root/name/'resources.jsonl').read_text().splitlines() if s.strip()]
 for line in path.read_text().splitlines():
  m=re.search(r'^(\d\d-\d\d \d\d:\d\d:\d\d\.\d+)\s+(\d+)\s+\d+ I TapStopDiag: (TS-\d+) t=(\d+) dt=(\d+) event=(\S+) thread=(\S+)(.*)',line)
  if not m: continue
  stamp,pid,rid,t,dt,event,thread,meta=m.groups()
  wall=datetime.strptime('2026-'+stamp,'%Y-%m-%d %H:%M:%S.%f').replace(tzinfo=ZoneInfo('Europe/Berlin'))
  r=requests.setdefault(rid,dict(session=name,pid=pid,id=rid,timestamp=wall.isoformat(),epoch=wall.timestamp(),events={},frames=[]))
  md=dict(re.findall(r'(\w+)=([^\s]+)',meta))
  r['events'][event]=dict(t=int(t),epoch=wall.timestamp(),metadata=md)
  if event=='slow_worker_frame': r['frames'].append(meta.split('frame=',1)[-1].strip())
  if event=='configuration': r.update(md)
  if event=='audio_finalized': r.update(md)
  if event=='cleanup_complete': r.update(md)
  if event=='processing_error': r['error_class']=md.get('type','NA')
  if event=='stt_headers_received': r['http']=md.get('http','NA')
  if event in ['replay_baseline','settled']:
   snapshots.append(dict(event=event,epoch=wall.timestamp(),pid=pid,**md))
 counts={}
 rows=[]
 for r in requests.values():
  if 'provider' not in r: continue
  key=(r['provider'],r['mode']);counts[key]=counts.get(key,0)+1;r['run']=counts[key]
  r['kind']='fixture' if name=='series' or r['run']<=5 and r['mode']=='BLITZTEXT' else 'microphone'
  def delta(end,start):
   es=r['events'];return es[end]['t']-es[start]['t'] if end in es and start in es else None
  for k,end,start in [('queue_ms','worker_started','worker_submitted'),('stream_ready_ms','stt_request_stream_ready','stt_http_execute'),('upload_ms','stt_upload_complete','stt_request_stream_ready'),('headers_wait_ms','stt_headers_received','stt_upload_complete'),('body_ms','stt_body_complete','stt_headers_received'),('parsing_ms','stt_parsing_complete','stt_body_complete'),('stt_ms','stt_parsing_complete','stt_begin'),('rewrite_ms','rewrite_parsing_complete','rewrite_begin'),('total_ms','result_ready','recording_stop_requested'),('cleanup_ms','cleanup_complete','recording_stop_requested')]:r[k]=delta(end,start)
  r.update(connect_ms='NA',tls_ms='NA',dns_ms='NA',target_ip='NA',ip_family='NA')
  end=r['events'].get('cleanup_complete',r['events'].get('processing_error',r['events']['configuration']))['epoch']
  matches=[x for x in resources if x['pid']==r['pid']]
  if matches:
   sample=min(matches,key=lambda x:abs(x['epoch']-end))
   r['sample_offset_ms']=round((sample['epoch']-end)*1000)
   r['socket_fds_sample']=sample['sockets']
   pss=re.search(r'TOTAL PSS:\s*(\d+)', '\n'.join(sample['memory']))
   r['pss_kib_sample']=int(pss.group(1)) if pss else None
  r['result']='HTTP '+r.get('http','NA') if r.get('http') else r.get('error_class','incomplete')
  rows.append(r)
 allrows+=rows
 summaries[name]=dict(snapshots=snapshots,resources=resources)
(root/'requests.json').write_text(json.dumps(allrows,indent=2)+'\n')
columns=['session','pid','id','run','kind','timestamp','provider','model','mode','audio_ms','audio_bytes','queue_ms','connect_ms','tls_ms','stream_ready_ms','upload_ms','headers_wait_ms','body_ms','parsing_ms','stt_ms','rewrite_ms','total_ms','cleanup_ms','http','error_class','workers','submitted','threads','fds','socket_fds_sample','watchdogs','heap_bytes','native_bytes','pss_kib_sample','sample_offset_ms','target_ip','ip_family','result']
with (root/'requests.csv').open('w') as f:
 writer=csv.DictWriter(f,fieldnames=columns,extrasaction='ignore');writer.writeheader();writer.writerows(allrows)
def stats(values):
 values=sorted(values)
 return dict(n=len(values),minimum=min(values),median=statistics.median(values),p90=values[math.ceil(.9*len(values))-1],maximum=max(values))
st={}
for device,rows in [('HONOR',allrows),('Samsung',list(csv.DictReader(open('diagnostics/series/logcat.csv'))))]:
 for provider in ['GROQ','OPENAI']:
  vals=[int(r['stt_ms']) for r in rows if r['provider']==provider and r['mode']=='BLITZTEXT' and r.get('stt_ms') is not None and (device=='Samsung' or r['session']=='series')]
  st[device+'/'+provider]=stats(vals)
(root/'distribution.json').write_text(json.dumps(st,indent=2)+'\n')
(root/'resource-metadata.json').write_text(json.dumps(summaries,indent=2)+'\n')
lines=['| Gerät | Provider | Session / Lauf | Request-ID | Connect | Stream bereit¹ | Upload → Headers | STT gesamt | Ergebnis |','|---|---|---|---|---:|---:|---:|---:|---|']
for r in allrows:
 lines.append(f"| HONOR | {r['provider']} | {r['session']} / {r['mode']} {r['run']} ({r['kind']}) | {r['id']} | NA | {r['stream_ready_ms']} | {r['headers_wait_ms']} | {r['stt_ms']} | {r['result']} |")
for session in ['series','restart']:
 for r in csv.DictReader(open('diagnostics/'+session+'/logcat.csv')):
  if r['stt_ms'] and int(r['stt_ms'])>5000:
   lines.append(f"| Samsung | {r['provider']} | {session} / {r['mode']} | {r['id']} | NA | {r['stream_ready_ms']} | {r['headers_wait_ms']} | {r['stt_ms']} | HTTP 200 |")
(root/'measurement-table.md').write_text('\n'.join(lines)+'\n')
for r in allrows:
 if r.get('stt_ms') and r['stt_ms']>5000:
  (root/(r['session']+'-'+r['id']+'-stack.txt')).write_text('\n'.join(r['frames'])+'\n')
print(json.dumps(st,indent=2))
print('requests',len(allrows),'outliers',[(r['session'],r['id'],r['stt_ms'],r['stream_ready_ms'],r['headers_wait_ms']) for r in allrows if r.get('stt_ms') and r['stt_ms']>5000])
