/* SPDX-License-Identifier: EPL-1.0 */
/*global Ext */
Ext.define('NX.clusterweb.util.NodesPresentation', {
  singleton: true,
  staleAfterMs: 35000,

  scalar: function(value) {
    return value === null || value === undefined || typeof value === 'object' ? 'Unknown' : String(value);
  },
  encode: function(value) { return Ext.htmlEncode(this.scalar(value)); },
  booleanText: function(value) { return value === true ? 'Yes' : value === false ? 'No' : 'Unknown'; },
  count: function(value) { return typeof value === 'number' && isFinite(value) && value >= 0 ? String(value) : 'Unknown'; },
  instant: function(value) {
    var millis = typeof value === 'string' ? Date.parse(value) : NaN;
    return isFinite(millis) ? millis : null;
  },
  fresh: function(value, now) {
    var millis = this.instant(value);
    return millis !== null && millis <= now + 10000 && now - millis <= this.staleAfterMs;
  },
  time: function(value) {
    var millis = this.instant(value);
    return millis === null ? 'Unknown' : Ext.Date.format(new Date(millis), 'Y-m-d H:i:s');
  },
  state: function(value) {
    var known = {
      healthy: {key: 'healthy', text: 'Healthy', color: '#16804a'},
      degraded: {key: 'degraded', text: 'Degraded', color: '#925a00'},
      unavailable: {key: 'unavailable', text: 'Unavailable', color: '#b42318'},
      unknown: {key: 'unknown', text: 'Unknown', color: '#59636b'}
    };
    return typeof value === 'string' && Object.prototype.hasOwnProperty.call(known, value) ? known[value] : known.unknown;
  },
  badge: function(value) {
    var state = this.state(value);
    return '<span style="font-weight:600;color:' + state.color + '">' + state.text + '</span>';
  },
  role: function(node) {
    var status = node.status || {};
    if (node.reachable === false) { return 'Unreachable'; }
    if (node.reachable !== true || !node.status) { return 'Unknown'; }
    if (status.writerReady === true) { return 'Active writer'; }
    if (status.coreRunning === true && typeof status.epoch === 'number' && status.epoch > 0) { return 'Starting'; }
    return 'Passive';
  },
  nodeState: function(node, now) {
    var status = node.status || {}, replica = status.replica || {};
    if (node.reachable === false) { return 'unavailable'; }
    if (node.reachable !== true || !node.status || !this.fresh(node.observedAt, now)) { return 'unknown'; }
    if (replica.memberReady === false) { return 'unavailable'; }
    if (status.authority !== true || replica.memberReady !== true) { return 'unknown'; }
    if (status.degraded === true || replica.degraded === true || replica.caughtUp === false || replica.quorumReady === false) { return 'degraded'; }
    if (this.role(node) === 'Starting') { return 'degraded'; }
    // A caught-up passive member has no running Core and is healthy by design.
    return replica.caughtUp === true && replica.quorumReady === true ? 'healthy' : 'unknown';
  },
  row: function(node, now) {
    var status = node.status || {}, replica = status.replica || {}, role = this.role(node);
    return {
      node: this.scalar(node.node), dc: this.scalar(node.dc), endpoint: this.scalar(node.endpoint),
      role: role, state: this.nodeState(node, now),
      core: status.coreRunning === true ? 'Running' : status.coreRunning === false ? 'Stopped' : 'Unknown',
      caughtUp: this.booleanText(replica.caughtUp),
      copies: this.count(replica.availableCopies) + ' / ' + this.count(replica.requiredCopies),
      epoch: typeof status.epoch === 'number' && status.epoch > 0 ? String(status.epoch) : '—',
      observedAt: this.time(node.observedAt),
      detail: this.scalar(node.error || status.detail || (role === 'Passive' ? 'Passive member; Core stopped' : null))
    };
  },
  pair: function(label, value) {
    return '<div style="margin:4px 18px 4px 0;max-width:100%;overflow-wrap:anywhere"><strong>' + label + ':</strong> ' + this.encode(value) + '</div>';
  },
  validSnapshot: function(value) {
    if (!value || value.schemaVersion !== 1 || !Ext.isArray(value.nodes) ||
        !value.summary || typeof value.summary !== 'object' || Ext.isArray(value.summary)) { return false; }
    return value.nodes.every(function(node) {
      return node && typeof node === 'object' && !Ext.isArray(node) &&
        (!node.status || (typeof node.status === 'object' && !Ext.isArray(node.status)));
    });
  },
  summaryHtml: function(snapshot, now, failed) {
    var summary = snapshot.summary || {}, stale = !this.fresh(snapshot.sampledAt, now),
        state = failed || stale ? 'unknown' : summary.state,
        policy = summary.degraded === true ? 'Degraded writes: reduced remote durability' :
          summary.degraded === false ? 'Normal write policy (writer report)' : 'Write policy unknown';
    return '<div style="padding:14px" aria-live="polite">' + this.badge(state) +
      '<div style="margin-top:6px;overflow-wrap:anywhere">' + this.encode(summary.detail) + '</div>' +
      '<div style="display:flex;flex-wrap:wrap;margin-top:8px">' +
      this.pair('Cluster', snapshot.cluster) + this.pair('Writer', summary.writerNode) +
      this.pair('Writer ready', this.booleanText(summary.writerReady)) +
      this.pair('Blob copies available / required', this.count(summary.availableCopies) + ' / ' + this.count(summary.requiredCopies)) +
      this.pair('Caught up', this.booleanText(summary.caughtUp)) +
      this.pair('Reachable nodes', this.count(summary.reachableNodes) + ' / ' + this.count(summary.totalNodes)) + '</div>' +
      '<div style="margin-top:8px">' + this.encode(policy) + '</div>' +
      '<div style="margin-top:6px;color:#59636b">Snapshot: ' + this.encode(this.time(snapshot.sampledAt)) +
      (failed || stale ? ' — stale or unavailable; last known values are shown' : ' — auto refresh every 10 seconds') + '</div></div>';
  },
  postgresHtml: function(summary) {
    return '<div style="padding:12px;display:flex;flex-wrap:wrap">' +
      this.pair('Primary', summary.pgNode) + this.pair('Primary DC', summary.pgDC) +
      this.pair('Synchronous standby', summary.pgSyncStandby) +
      this.pair('Remote synchronous replication', this.booleanText(summary.pgRemoteSync)) +
      this.pair('Primary term', summary.pgTerm) + '</div>';
  }
});
