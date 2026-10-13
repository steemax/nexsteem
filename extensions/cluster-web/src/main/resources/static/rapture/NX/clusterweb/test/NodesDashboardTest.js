/* SPDX-License-Identifier: EPL-1.0 */
/* Run with Node.js; no browser, dependencies, server or credentials required. */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const base = path.resolve(__dirname, '..');
const definitions = {};
const htmlEncode = value => String(value).replace(/[&<>"']/g, x => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[x]));
const events = {requests: [], stopped: [], aborted: []};
const sandbox = {
  Ext: {
    define: (name, value) => { definitions[name] = value; }, htmlEncode, isArray: Array.isArray,
    Date: {format: date => date.toISOString()}, Array: {map: (a, f) => a.map(f)},
    decode: JSON.parse, apply: Object.assign,
    TaskManager: {start: task => task, stop: task => events.stopped.push(task)},
    Ajax: {request: options => { const request = {options}; events.requests.push(request); return request; }, abort: r => events.aborted.push(r)}
  },
  NX: {clusterweb: {util: {}}, util: {Url: {urlOf: p => '/nexus' + p}},
    State: {getValue: () => true}, Permissions: {check: () => true}}, Date, isFinite
};
vm.createContext(sandbox);
for (const file of ['util/NodesPresentation.js','view/NodesDashboard.js','override/Nodes.js','override/NodesController.js','app/PluginConfig.js']) {
  vm.runInContext(fs.readFileSync(path.join(base,file),'utf8'),sandbox,{filename:file});
}
const presentation = definitions['NX.clusterweb.util.NodesPresentation'];
sandbox.NX.clusterweb.util.NodesPresentation = presentation;
const now = Date.now(), observedAt = new Date(now).toISOString();
const passive = {node:'reserve',dc:'dc2',reachable:true,observedAt,status:{authority:true,writerReady:false,coreRunning:false,epoch:0,
  degraded:false,replica:{memberReady:true,caughtUp:true,quorumReady:true,degraded:false,requiredCopies:2,availableCopies:2}}};
assert.equal(presentation.role(passive),'Passive');
assert.equal(presentation.nodeState(passive,now),'healthy');
assert.equal(presentation.nodeState({...passive,observedAt:new Date(now-40000).toISOString()},now),'unknown');
assert.equal(presentation.nodeState({...passive,reachable:false},now),'unavailable');
assert.equal(presentation.role({...passive,status:{...passive.status,coreRunning:true,epoch:2}}),'Starting');
assert.equal(presentation.state('__proto__').key,'unknown');
assert.equal(presentation.state('toString').key,'unknown');
assert.equal(presentation.validSnapshot({schemaVersion:1,nodes:[null],summary:{}}),false);
const snapshot = {schemaVersion:1,cluster:'<img src=x onerror=alert(1)>',sampledAt:observedAt,nodes:[passive],
  summary:{state:'degraded',detail:'<script>bad()</script>',degraded:false,writerNode:'writer',writerReady:true}};
const markup = presentation.summaryHtml(snapshot,now,false);
assert(!markup.includes('<script>'));
assert(!markup.includes('<img'));
assert(markup.includes('&lt;script&gt;'));
assert(markup.includes('Normal write policy (writer report)'));
assert(!markup.includes('Degraded writes:'));
assert(presentation.summaryHtml(snapshot,now,true).includes('>Unknown</span>'));

const dashboard = definitions['NX.clusterweb.view.NodesDashboard'];
let updates = 0;
const child = {setDisabled(){},setText(){},setHidden(){},update(){updates++;}};
const panel = Object.assign({},dashboard,{down:()=>child,setLoading(){},callParent(){},nodeStore:{loadData(){},removeAll(){}}});
panel.startPolling();
assert.equal(events.requests.length,1);
assert.equal(panel.pollTask.interval,10000);
assert.equal(events.requests[0].options.url,'/nexus/service/rest/internal/ui/cluster/nodes');
panel.poll();
assert.equal(events.requests.length,1,'A poll must not overlap an outstanding request');
const pending = panel.pendingRequest;
panel.beforeDestroy();
assert.equal(events.stopped.length,1);
assert.equal(events.aborted[0],pending);
const previousUpdates = updates;
pending.options.callback({},true,{status:200,responseText:JSON.stringify(snapshot)});
assert.equal(updates,previousUpdates,'A late callback must not touch destroyed UI');
panel.poll();
assert.equal(events.requests.length,1);

const controller = definitions['NX.clusterweb.override.NodesController'];
let premiumCalls = 0, refreshes = 0;
controller.loadStores.call({getFeature:()=>({down:()=>({refreshStatus(){refreshes++;}})}),callParent(){premiumCalls++;}});
controller.reselect.call({callParent(){premiumCalls++;}});
assert.equal(refreshes,1);
assert.equal(premiumCalls,0,'Cluster dashboard must not call the original commercial Node store');
console.log('PASS passive role/health, stale/unknown, monitoring vs write policy, escaping, single poll, destroy abort, custom controller route');
