#!/usr/bin/env python3
"""Exercise packaged CLI commands against disposable local JVMs. Build with mvn install first."""
from pathlib import Path
import argparse, json, select, signal, socket, subprocess, tempfile, threading, time, zipfile

parser=argparse.ArgumentParser()
parser.add_argument('--project',type=Path,default=Path(__file__).resolve().parents[1])
parser.add_argument('--plugin',type=Path)
args=parser.parse_args()
root=args.project.resolve(); jar=root/'target/java-trail-1.6.0-cli.jar'; checks=[]

def check(condition,message):
    assert condition,message
    checks.append(message); print('PASS:',message,flush=True)

def run(*options,timeout=25):
    return subprocess.run(['java','-jar',str(jar),*map(str,options)],text=True,capture_output=True,timeout=timeout)

def port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1',0)); return sock.getsockname()[1]

def stop(process):
    if process.poll() is None:
        process.terminate()
        try: process.wait(timeout=3)
        except subprocess.TimeoutExpired: process.kill();process.wait(timeout=3)

class Target:
    def __init__(self,classname,*,suspended=False,continuous=False):
        self.port=port(); self.classname=classname
        self.process=subprocess.Popen(['java',f'-agentlib:jdwp=transport=dt_socket,server=y,suspend={"y" if suspended else "n"},address=127.0.0.1:{self.port}',
            '-cp',str(root/'target/test-classes'),classname,*(['--continuous'] if continuous else [])],
            stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
        ready,_,_=select.select([self.process.stdout],[],[],5)
        if not ready or 'Listening for transport' not in self.process.stdout.readline():
            stop(self.process); raise AssertionError('JDWP target did not start')
        if classname.endswith('LifecycleApplication'):
            assert self.process.stdout.readline().strip()=='ready'
    def command(self,text):
        self.process.stdin.write(text+'\n'); self.process.stdin.flush()
    def close(self): stop(self.process)

sample='org.onestonesoup.javatrail.sample.SampleApplication'
lifecycle='org.onestonesoup.javatrail.sample.LifecycleApplication'
check(run('--help').returncode==0 and 'report <trace.json>' in run('--help').stdout,'Packaged help exposes offline reports')
check(run('--version').stdout.strip()=='Java Trail 1.6.0','Packaged version is 1.6.0')
with zipfile.ZipFile(jar) as archive:
    names=archive.namelist()
    check(not any(n.startswith(('org/onestonesoup/openforum/','org/mozilla/')) for n in names),'Standalone JAR has no OpenForum or Rhino classes')
if args.plugin:
    with zipfile.ZipFile(args.plugin) as archive:
        names=archive.namelist()
        check('org/onestonesoup/javatrail/TraceReport.class' in names,'Plugin bundles updated standalone engine/report library')
        check('org/onestonesoup/openforum/javatrail/JavaTrailSystemHelper.class' in names,'Plugin retains original helper API')
        check(not any(n.startswith(('org/onestonesoup/openforum/controller/','org/mozilla/')) for n in names),'Plugin excludes the provided server runtime')

with tempfile.TemporaryDirectory(prefix='java-trail-package-') as folder:
    temp=Path(folder); config=temp/'config.json'
    def configure(target,method='entryPoint',cls=sample):
        data={'host':'localhost','port':'5005','startClass':cls,'startMethod':method,'includePackages':['org.onestonesoup.javatrail.sample'],
            'maxDurationMillis':10000,'awaitCompletionMillis':12000,'outputFile':'unused.json'}
        config.write_text(json.dumps(data))
    target=Target(sample,suspended=True)
    try:
        configure(target)
        output=temp/'nested/trace.json'; report=temp/'report.html'
        result=run('trace',config,'--port',target.port,'--host','127.0.0.1','--output',output,'--max-events','1000',
            '--report','html','--report-output',report,'--metadata',root/'examples/report-metadata.json')
        check(result.returncode==0,'Capture with named overrides succeeds: '+result.stderr.strip())
        trace=json.loads(output.read_text())
        check(trace['timedOut']=='false' and int(trace['eventCount'])>4,'Packaged trace completes and records domain calls')
        check(trace['truncated']=='false' and trace['eventsRetained']=='true','Capture records explicit retention/limit state')
        html=report.read_text()
        check('Narrative Flow' in html and 'Mocked/Overridden' not in html and 'href="https://github.com/nikcross/java-trail"' in html and 'href="https://tools.wet-wired.com"' in html,'Capture produces walkthrough-style HTML')
        examples=root/'examples';examples.mkdir(exist_ok=True)
        (examples/'sample-trace.json').write_text(output.read_text())
        (examples/'sample-report.html').write_text(html)
        text_result=run('report',output,'--format','text','--metadata',root/'examples/report-metadata.json','--output',examples/'sample-report.txt')
        check(text_result.returncode==0 and 'Narrative Flow / Call Tree' in (examples/'sample-report.txt').read_text(),'Offline report creates text tree and counts')
        check(run('report',output,'--output',output).returncode==2,'CLI refuses to overwrite trace input with a report')
        check(run('trace',config,'--output',config).returncode==2,'CLI refuses to overwrite configuration with trace output')
        check(run('trace',config,'--no-retain').returncode==2,'Stream-only mode requires a JSONL sink')
        hostile=temp/'hostile.json';hostile.write_text(json.dumps({'title':'<img src=x onerror="window.pwned=1">','summary':'</p><script>window.pwned=1</script>','sourceRoot':'javascript:alert(1)'}))
        malicious=root/'target/hostile-report.html'
        check(run('report',output,'--metadata',hostile,'--output',malicious).returncode==0,'Report accepts arbitrary metadata as escaped text')
        check('<script>' not in malicious.read_text() and 'href="javascript:' not in malicious.read_text(),'Report escapes HTML and rejects unsafe link paths')
    finally: target.close()

    target=Target(lifecycle)
    try:
        configure(target,'neverCalled',lifecycle)
        summary=temp/'timeout.json'
        result=run('trace',config,'--port',target.port,'--max-duration','100','--await-completion','1000','--fail-on-timeout','--output',summary)
        check(result.returncode==3 and json.loads(summary.read_text())['timedOut']=='true','Strict timeout returns 3 and writes the JSON result')
        check(target.process.poll() is None,'Timed-out tracing leaves target alive')
        late='org.onestonesoup.javatrail.sample.LateLoadedCounter'
        watcher=subprocess.Popen(['java','-jar',str(jar),'watch','--class',late,'--field','counter','--host','127.0.0.1','--port',str(target.port),
            '--wait-class','5000','--duration','300','--poll-interval','50'],text=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        timer=threading.Timer(1.0,lambda:target.command('late'));timer.start()
        try:
            stdout,stderr=watcher.communicate(timeout=10)
            check(watcher.returncode==0 and '  To:1' in stdout and '  To:2' in stdout,'Named watcher captures late-class initializer and subsequent field changes')
        finally: timer.cancel();stop(watcher)
    finally: target.close()

    target=Target(sample,suspended=True,continuous=True)
    try:
        configure(target)
        stream=temp/'events.jsonl';summary=temp/'bounded.json'
        result=run('trace',config,'--port',target.port,'--max-events','3','--stream-jsonl',stream,'--no-retain','--output',summary)
        data=json.loads(summary.read_text());records=[json.loads(line) for line in stream.read_text().splitlines()]
        check(result.returncode==0 and len(records)==3 and data['eventCount']=='3','JSONL records are valid and respect maxEvents')
        check(data['events']==[] and data['eventsRetained']=='false' and data['truncated']=='true','Stream-only capture retains no events and marks truncation')
        check(target.process.poll() is None,'Event-limit completion detaches without killing the target')
        check(run('report',summary,'--format','text').returncode==2,'Report refuses a summary whose events were not retained')
    finally: target.close()

    target=Target('org.onestonesoup.javatrail.sample.FieldWatchApplication')
    try:
        watcher=subprocess.Popen(['java','-jar',str(jar),'watch','--class',target.classname,'--field','counter','--host','127.0.0.1','--port',str(target.port),
            '--poll-interval','50'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        time.sleep(.6);watcher.send_signal(signal.SIGINT);watcher.wait(timeout=5)
        retry=run('watch','--class',target.classname,'--field','counter','--port',target.port,'--duration','300','--poll-interval','50')
        check(retry.returncode==0 and 'Class:'+target.classname in retry.stdout,'Ctrl+C releases debugger so a second watcher can attach')
        check(target.process.poll() is None,'Interrupted watcher leaves target JVM alive')
    finally: target.close()

    unused=port();configure(None)
    check(run('trace',config,'--port',unused,'--output',temp/'connection.json').returncode==1,'Connection failure returns exit 1')

(root/'target/package-verification.json').write_text(json.dumps(checks,indent=2)+'\n')
print('All',len(checks),'packaged checks passed.',flush=True)
