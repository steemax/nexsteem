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
