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

/* SPDX-License-Identifier: EPL-1.0 */
/*global Ext, NX */
Ext.define('NX.clusterweb.view.NodesDashboard', {
  extend: 'Ext.panel.Panel',
  alias: 'widget.nx-clusterweb-nodes-dashboard',
  requires: ['Ext.data.Store', 'Ext.grid.Panel', 'Ext.util.TaskManager', 'NX.util.Url', 'NX.clusterweb.util.NodesPresentation'],
  border: false,
  layout: {type: 'vbox', align: 'stretch'},
  minHeight: 480,
  refreshInterval: 10000,

  initComponent: function() {
    var me = this, presentation = NX.clusterweb.util.NodesPresentation, encode = function(value) { return presentation.encode(value); };
    me.nodeStore = Ext.create('Ext.data.Store', {
      fields: ['node', 'dc', 'endpoint', 'role', 'state', 'core', 'caughtUp', 'copies', 'epoch', 'observedAt', 'detail'],
      data: [], sorters: [{property: 'node', direction: 'ASC'}]
    });
    me.tbar = [{
      xtype: 'button', itemId: 'cluster-refresh', text: 'Refresh', iconCls: 'x-fa fa-refresh',
      handler: function() { me.refreshStatus(); }
    }, {xtype: 'tbtext', itemId: 'cluster-refresh-status', text: 'Not sampled yet'}];
    me.items = [{
      xtype: 'component', itemId: 'cluster-message', hidden: true, margin: '10 10 0 10'
    }, {
      xtype: 'panel', itemId: 'cluster-summary', title: 'Cluster status', ui: 'nx-subsection',
      border: false, margin: 10, html: '<div style="padding:14px">Unknown — awaiting a fresh snapshot</div>'
    }, {
      xtype: 'grid', itemId: 'cluster-nodes-grid', title: 'Nexus nodes', ui: 'nx-subsection',
      store: me.nodeStore, flex: 1, minHeight: 190, margin: '0 10 10 10',
      viewConfig: {emptyText: 'No node status available', deferEmptyText: false},
      columns: [
        {text: 'Node', dataIndex: 'node', flex: 2, minWidth: 180, renderer: encode},
        {text: 'DC', dataIndex: 'dc', width: 80, renderer: encode},
        {text: 'Role', dataIndex: 'role', width: 115, renderer: encode},
        {text: 'State', dataIndex: 'state', width: 110, renderer: function(value) { return presentation.badge(value); }},
        {text: 'Core', dataIndex: 'core', width: 85, renderer: encode},
        {text: 'Caught up', dataIndex: 'caughtUp', width: 90, renderer: encode},
        {text: 'Copies / required', dataIndex: 'copies', width: 135, renderer: encode},
        {text: 'Core epoch', dataIndex: 'epoch', width: 90, renderer: encode},
        {text: 'Observed', dataIndex: 'observedAt', width: 165, renderer: encode},
        {text: 'Detail', dataIndex: 'detail', flex: 2, minWidth: 240, renderer: encode},
        {text: 'Member endpoint', dataIndex: 'endpoint', width: 260, hidden: true, renderer: encode}
      ]
    }, {
      xtype: 'panel', itemId: 'cluster-postgres', title: 'PostgreSQL', ui: 'nx-subsection',
      border: false, margin: '0 10 10 10', html: '<div style="padding:12px">Unknown</div>'
    }];
    me.callParent(arguments);
    me.on('afterrender', me.startPolling, me, {single: true});
  },

  startPolling: function() {
    var me = this;
    me.pollingStopped = false;
    me.pollTask = Ext.TaskManager.start({run: me.poll, scope: me, interval: me.refreshInterval});
    me.refreshStatus();
  },
  poll: function() {
    if (this.pollingStopped || this.destroyed || this.isDestroyed) { return false; }
    if (this.snapshot) { this.renderSnapshot(); }
    this.refreshStatus();
  },
  refreshStatus: function() {
    var me = this;
    if (me.pollingStopped || me.destroyed || me.isDestroyed || me.pendingRequest) { return; }
    me.down('#cluster-refresh').setDisabled(true);
    me.down('#cluster-refresh-status').setText('Refreshing…');
    if (!me.snapshot) { me.setLoading('Loading cluster status…'); }
    me.pendingRequest = Ext.Ajax.request({
      url: NX.util.Url.urlOf('/service/rest/internal/ui/cluster/nodes'),
      method: 'GET', timeout: 15000, disableCaching: true,
      callback: function(options, success, response) {
        if (me.pollingStopped || me.destroyed || me.isDestroyed) { return; }
        me.pendingRequest = null;
        me.setLoading(false);
        me.down('#cluster-refresh').setDisabled(false);
        if (success) {
          try {
            var data = Ext.decode(response.responseText);
            if (!NX.clusterweb.util.NodesPresentation.validSnapshot(data)) { throw new Error('Unsupported status response'); }
            me.snapshot = data;
            me.refreshError = null;
            me.renderSnapshot();
          } catch (invalid) { me.showRefreshError('The cluster status response could not be read.'); }
        } else {
          if (response.status === 401 || response.status === 403) {
            me.snapshot = null;
            me.nodeStore.removeAll();
            me.down('#cluster-summary').update('<div style="padding:14px">Unknown — access to cluster status is unavailable</div>');
            me.down('#cluster-postgres').update('<div style="padding:12px">Unknown</div>');
          }
          me.showRefreshError(response.status === 403 ? 'Permission nexus:nodes:read is required.' :
            response.status === 401 ? 'Sign in again to view cluster nodes.' : 'Cluster status could not be refreshed. Last known values may be stale.');
        }
      }
    });
  },
  renderSnapshot: function() {
    var me = this, data = me.snapshot, presentation = NX.clusterweb.util.NodesPresentation, now = Date.now();
    if (!data) { return; }
    me.down('#cluster-summary').update(presentation.summaryHtml(data, now, !!me.refreshError));
    me.down('#cluster-postgres').update(presentation.postgresHtml(data.summary));
    me.nodeStore.loadData(Ext.Array.map(data.nodes, function(node) {
      var row = presentation.row(node, now);
      if (me.refreshError) { row.state = 'unknown'; }
      return row;
    }));
    me.down('#cluster-refresh-status').setText(me.refreshError ? 'Refresh failed — last known snapshot' : 'Sampled ' + presentation.time(data.sampledAt));
    var message = me.refreshError || (!presentation.fresh(data.sampledAt, now) ? 'The status snapshot is stale; current cluster health is unknown.' : null), banner = me.down('#cluster-message');
    banner.setHidden(!message);
    if (message) { banner.update('<div style="padding:10px;border:1px solid #d9a441;background:#fff8e8" role="status">' + presentation.encode(message) + '</div>'); }
  },
  showRefreshError: function(message) {
    this.refreshError = message;
    this.down('#cluster-refresh-status').setText('Refresh failed');
    var banner = this.down('#cluster-message');
    banner.setHidden(false);
    banner.update('<div style="padding:10px;border:1px solid #d9a441;background:#fff8e8" role="alert">' + NX.clusterweb.util.NodesPresentation.encode(message) + '</div>');
    if (this.snapshot) { this.renderSnapshot(); }
  },
  beforeDestroy: function() {
    this.pollingStopped = true;
    if (this.pollTask) { Ext.TaskManager.stop(this.pollTask); this.pollTask = null; }
    if (this.pendingRequest) { Ext.Ajax.abort(this.pendingRequest); this.pendingRequest = null; }
    this.callParent(arguments);
  },
  onDestroy: function() {
    this.callParent(arguments);
    if (this.nodeStore && !this.nodeStore.destroyed && !this.nodeStore.isDestroyed) { this.nodeStore.destroy(); }
    this.snapshot = null;
  }
});

/* SPDX-License-Identifier: EPL-1.0 */
/*global Ext, NX */
Ext.define('NX.clusterweb.override.Nodes', {
  override: 'NX.coreui.view.system.Nodes',
  requires: ['NX.State', 'NX.clusterweb.view.NodesDashboard'],
  initComponent: function() {
    if (NX.State.getValue('nxClusterEnabled') !== true) { this.callParent(arguments); return; }
    this.iconName = 'node-default';
    this.masters = [{xtype: 'nx-clusterweb-nodes-dashboard'}];
    this.skipDetail = true;
    this.tabs = null;
    this.html = null;
    // Skip the original licensed Nodes init; retain the standard Drilldown frame.
    this.callSuper(arguments);
  }
});

/* SPDX-License-Identifier: EPL-1.0 */
/*global Ext, NX */
Ext.define('NX.clusterweb.override.NodesController', {
  override: 'NX.coreui.controller.Nodes',
  requires: ['NX.State', 'NX.Permissions', 'NX.clusterweb.view.NodesDashboard'],
  init: function() {
    if (NX.State.getValue('nxClusterEnabled') === true) {
      this.features = Ext.apply({}, this.features);
      this.features.description = 'Cluster members, replication and PostgreSQL';
      this.features.visible = function() {
        return NX.State.getValue('nxClusterEnabled') === true && NX.Permissions.check('nexus:nodes:read');
      };
    }
    this.callParent(arguments);
  },
  loadStores: function() {
    if (NX.State.getValue('nxClusterEnabled') !== true) { this.callParent(arguments); return; }
    var feature = this.getFeature(), dashboard = feature && feature.down('nx-clusterweb-nodes-dashboard');
    if (dashboard) { dashboard.refreshStatus(); }
  },
  reselect: function() {
    if (NX.State.getValue('nxClusterEnabled') !== true) { this.callParent(arguments); }
  }
});

/* SPDX-License-Identifier: EPL-1.0 */
/*global Ext */
Ext.define('NX.clusterweb.app.PluginConfig', {
  extend: 'NX.app.PluginConfig',
  requires: ['NX.clusterweb.util.NodesPresentation', 'NX.clusterweb.view.NodesDashboard',
    'NX.clusterweb.override.Nodes', 'NX.clusterweb.override.NodesController']
});
