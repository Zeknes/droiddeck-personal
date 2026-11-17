#!/usr/bin/env python3
import argparse
import json
from pathlib import Path
import re
import shlex
import statistics
import subprocess
import time

def latency_summary(text):
    rows = text.splitlines()
    period = int(rows[0]) if rows and rows[0].isdigit() else 0
    stamps = sorted(set(int(row.split()[1]) for row in rows[1:] if len(row.split()) == 3
                        and row.split()[1].isdigit() and 0 < int(row.split()[1]) < 2**63-1))
    intervals = [(b-a)/1e6 for a,b in zip(stamps,stamps[1:])]
    result = {'frames':len(stamps),'refresh_period_ns':period, 'source':'SurfaceFlinger actualPresentTime'}
    if intervals:
        ordered=sorted(intervals)
        result.update(fps=1000/statistics.mean(intervals),p50_ms=statistics.median(intervals),
                      p95_ms=ordered[min(len(ordered)-1,int(len(ordered)*0.95))],
                      intervals_over_1_5x=sum(x > period/1e6*1.5 for x in intervals) if period else None)
    else:
        result['unavailable']='No actual presentation timestamps exposed for this layer; configured refresh is not measured FPS.'
    return result


def main():
    from adb_device import resolve_serial
    parser=argparse.ArgumentParser(description='Read Android display modes and sample DD SurfaceFlinger presentation timestamps without changing display settings.')
    parser.add_argument('--serial')
    parser.add_argument('--seconds',type=int,choices=range(1,16),default=6)
    parser.add_argument('--package',default='com.zeknes.droiddeck.pure')
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    serial=resolve_serial('adb',args.serial)
    def shell(command):
        return subprocess.check_output(['adb','-s',serial,'shell',command],text=True,timeout=20)
    report={'schema':1,'serial':serial,'seconds':args.seconds,'battery_before':shell('dumpsys battery'),
            'display_before':shell('dumpsys display'),'scope':'Surface presentation timing, not guest render-loop FPS. No global settings changed.'}
    listing=shell('dumpsys SurfaceFlinger --list')
    names=[]
    for line in listing.splitlines():
        if args.package in line and 'SurfaceView[' in line and '(BLAST)' in line:
            name=line
            if name.startswith('RequestedLayerState{'):
                name=name.removeprefix('RequestedLayerState{').split(' parentId=',1)[0].split(' z=',1)[0].rstrip('}')
            names.append(name)
    report['layers']=names
    before={name:shell('dumpsys SurfaceFlinger --latency '+shlex.quote(name)) for name in names}
    old={name:set(text.splitlines()[1:]) for name,text in before.items()}
    rows={name:set() for name in names}
    periods={name:[] for name in names}
    start=time.monotonic()
    while time.monotonic()-start < args.seconds:
        for name in names:
            sample=shell('dumpsys SurfaceFlinger --latency '+shlex.quote(name)).splitlines()
            if sample:
                periods[name].append(sample[0])
                rows[name].update(row for row in sample[1:] if row not in old[name])
        time.sleep(0.2)
    report['sample_seconds']=time.monotonic()-start
    report['presentation']={}
    for name in names:
        sample='\n'.join(periods[name][-1:]+sorted(rows[name]))
        result=latency_summary(sample)
        result['refresh_periods_seen_ns']=sorted(set(periods[name]))
        result['sample_coverage']='Polling at 200 ms; only exposed actualPresentTime values are counted, not synthetic vsyncs.'
        report['presentation'][name]=result
    report['display_after']=shell('dumpsys display')
    report['battery_after']=shell('dumpsys battery')
    report['android_ui_frame_stats']=shell('dumpsys gfxinfo '+shlex.quote(args.package)+' framestats')
    report['ui_stats_scope']='Android UI/render-thread frames are not the guest SurfaceView frames.'
    if not names:
        report['unavailable']='No DD SurfaceView layer found; keep the desktop foreground and retry.'
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'output':str(args.output),'presentation':report['presentation']},indent=2))
    return 0


if __name__=='__main__':
    raise SystemExit(main())
