/* Validate generated reports in Chrome through its built-in DevTools protocol. Node 22+, no npm dependencies. */
const {spawn}=require('node:child_process');
const fs=require('node:fs'); const path=require('node:path');
const root=path.resolve(process.argv[2]||path.join(__dirname,'..'));
const report=path.join(root,'examples/sample-report.html');
const checks=[]; let browser,ws;
function check(value,message){if(!value)throw Error(message);checks.push(message);process.stdout.write('PASS: '+message+'\n');}
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
(async()=>{
  browser=spawn('google-chrome',['--headless','--no-sandbox','--disable-gpu','--no-first-run','--allow-file-access-from-files',
    '--remote-debugging-port=0','--user-data-dir='+path.join(root,'target/report-chrome-profile'),'about:blank'],{stdio:['ignore','ignore','pipe']});
  let log='';
  const endpoint=await new Promise((resolve,reject)=>{const timer=setTimeout(()=>reject(Error('Chrome start timed out')),10000);
    browser.stderr.on('data',data=>{log+=data;const match=log.match(/DevTools listening on (ws:\/\/[^\s]+)/);if(match){clearTimeout(timer);resolve(match[1]);}});
    browser.on('error',reject);
  });
  const origin=new URL(endpoint); const pages=await (await fetch('http://'+origin.host+'/json/list')).json();
  const target=pages.find(p=>p.type==='page');ws=new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((resolve,reject)=>{ws.onopen=resolve;ws.onerror=reject;});
  let serial=0; const pending=new Map(); const runtimeErrors=[],remoteRequests=[];
  ws.onmessage=message=>{const value=JSON.parse(message.data);if(value.id){const waiter=pending.get(value.id);if(waiter){pending.delete(value.id);value.error?waiter.reject(Error(JSON.stringify(value.error))):waiter.resolve(value.result);}}
    if(value.method==='Runtime.exceptionThrown')runtimeErrors.push(value.params);
    if(value.method==='Network.requestWillBeSent' && /^https?:/.test(value.params.request.url))remoteRequests.push(value.params.request.url);
  };
  const send=(method,params={})=>new Promise((resolve,reject)=>{const id=++serial;pending.set(id,{resolve,reject});ws.send(JSON.stringify({id,method,params}));});
  const evaluate=async expression=>{const r=await send('Runtime.evaluate',{expression,returnByValue:true});if(r.exceptionDetails)throw Error(JSON.stringify(r.exceptionDetails));return r.result.value;};
  await send('Page.enable');await send('Runtime.enable');await send('Network.enable');
  async function navigate(file){await send('Page.navigate',{url:'file://'+file});for(let i=0;i<100;i++){if(await evaluate('document.readyState==="complete" && location.href === '+JSON.stringify('file://'+file)))return;await delay(30);}throw Error('Page did not load');}
  const count=JSON.parse(fs.readFileSync(path.join(root,'examples/sample-trace.json'))).events.length;
  for(const [label,width,height,mobile] of [['desktop',1280,900,false],['mobile',390,844,true]]){
    await send('Emulation.setDeviceMetricsOverride',{width,height,deviceScaleFactor:1,mobile});await navigate(report);
    const state=await evaluate(`(()=>({title:document.title,bg:getComputedStyle(document.body).backgroundColor,
      overflow:document.documentElement.scrollWidth>innerWidth+1,steps:document.querySelectorAll('section[id^="step-"]').length,
      headers:[...document.querySelectorAll('th')].map(n=>n.textContent),mockSectionAbsent:!document.body.textContent.includes('Mocked/Overridden') && !document.body.textContent.includes('mock/override status') && !!document.querySelector('.columns.single'),
      source:[...document.querySelectorAll('a')].filter(a=>(a.getAttribute('href')||'').startsWith('jetbrains:')).map(a=>a.getAttribute('href')),scripts:document.scripts.length,
      footer:document.querySelector('footer')?.textContent,summary:!!document.getElementById('summary'),diagram:!!document.querySelector('#interaction-diagram svg'),messages:document.querySelectorAll('.interaction-message').length,scrollRegion:!!document.querySelector('.interaction-scroll[tabindex="0"]')}))()`);
    check(state.title.endsWith('Test Flow Walkthrough'),label+': walkthrough title');
    check(state.bg==='rgb(11, 13, 16)',label+': requested dark palette');
    check(!state.overflow,label+': no horizontal overflow');
    check(state.steps===count,label+': one narrative step per observed event');
    check(state.headers.join('|')==='Narrative Flow|Implementation References',label+': narrative/reference table headings');
    check(state.mockSectionAbsent,label+': mock section and labels omitted without declarations');
    check(state.source.length>0 && state.source.every(h=>new URL(h).searchParams.get('project')==='java-trail' && new URL(h).searchParams.get('path').startsWith('src/test/java/')),label+': encoded IDE project/source links');
    check(state.scripts===0 && state.summary,label+': script-free report and closing summary');
    check(state.diagram && state.messages===Math.min(count,80),label+': SVG diagram shows observed event sequence');
    check(state.scrollRegion,label+': diagram scroll region is keyboard accessible');
    check(state.footer.includes('Agent: Codex')&&state.footer.includes('software-developer/testing/flow-test-walkthrough.prompt.txt'),label+': prompt attribution');
    await evaluate('document.querySelector(".interaction-scroll").focus()');
    check(await evaluate('document.activeElement.matches(".interaction-scroll")'),label+': diagram region accepts keyboard focus');
    await send('Input.dispatchKeyEvent',{type:'keyDown',key:'Tab',code:'Tab',windowsVirtualKeyCode:9});await send('Input.dispatchKeyEvent',{type:'keyUp',key:'Tab',code:'Tab',windowsVirtualKeyCode:9});
    check(await evaluate('document.activeElement.matches("a")'),label+': references accessible by keyboard');
    await send('Input.dispatchKeyEvent',{type:'keyDown',key:'Enter',code:'Enter',windowsVirtualKeyCode:13});
    await send('Input.dispatchKeyEvent',{type:'keyUp',key:'Enter',code:'Enter',windowsVirtualKeyCode:13});
    check(await evaluate('location.hash === "#step-1"'),label+': sequence arrow navigates to narrative step');
    await evaluate('window.scrollTo(0,0)');
    const shot=await send('Page.captureScreenshot',{format:'png',captureBeyondViewport:false});
    fs.writeFileSync(path.join(root,'target/report-'+label+'.png'),Buffer.from(shot.data,'base64'));
    await evaluate('document.getElementById("interaction-diagram").scrollIntoView()');
    const diagram=await send('Page.captureScreenshot',{format:'png',captureBeyondViewport:false});
    fs.writeFileSync(path.join(root,'target/report-diagram-'+label+'.png'),Buffer.from(diagram.data,'base64'));
    await evaluate('document.getElementById("step-1").scrollIntoView()');
    const flow=await send('Page.captureScreenshot',{format:'png',captureBeyondViewport:false});
    fs.writeFileSync(path.join(root,'target/report-flow-'+label+'.png'),Buffer.from(flow.data,'base64'));
  }
  await navigate(path.join(root,'target/hostile-report.html'));
  check(await evaluate('window.pwned === undefined && document.images.length===0 && document.scripts.length===0'),'Hostile metadata cannot execute HTML or scripts');
  check(runtimeErrors.length===0,'No browser runtime errors');check(remoteRequests.length===0,'Reports make no network requests');
  fs.writeFileSync(path.join(root,'target/browser-verification.json'),JSON.stringify(checks,null,2)+'\n');
  process.stdout.write('All '+checks.length+' browser checks passed.\n');
})().catch(e=>{process.stderr.write(e.stack+'\n');process.exitCode=1;}).finally(()=>{if(ws)ws.close();if(browser)browser.kill();});
